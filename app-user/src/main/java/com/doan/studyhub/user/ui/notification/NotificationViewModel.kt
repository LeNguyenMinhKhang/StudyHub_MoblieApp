package com.doan.studyhub.user.ui.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.AppNotification
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.model.NotificationFilter
import com.doan.studyhub.core.data.model.NotificationSignal
import com.doan.studyhub.core.data.repository.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotificationUiState(
    val filter: NotificationFilter = NotificationFilter.ALL,
    val items: List<AppNotification> = emptyList(),
    val cursor: NotificationCursor? = null,
    /** Global visible unread count from the server, adjusted only by pending local reads. */
    val unreadCount: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    /** Blocking error shown when nothing is loaded. */
    val error: String? = null,
    /** Non-blocking error; existing rows stay visible. */
    val message: String? = null,
    /** Loaded window exceeds the reconciliation limit; offer a reset instead of claiming it is current. */
    val windowTruncated: Boolean = false
)

sealed interface NotificationEvent {
    data class OpenPost(val postId: String) : NotificationEvent
    data class OpenProfile(val userId: String) : NotificationEvent
}

/**
 * Single Home/session-scoped owner of notification state: the Home badges and the
 * notification center read the same [uiState]. Realtime signals only request a
 * re-read; the server snapshot is always authoritative.
 */
class NotificationViewModel @JvmOverloads constructor(
    private val repository: NotificationRepository = NotificationRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(NotificationUiState())
    val uiState = mutable.asStateFlow()
    private val eventChannel = Channel<NotificationEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var owner = repository.currentUserId()
    private var session = 0
    private var listEpoch = 0
    private val listLock = Mutex()
    private var refreshRequested = false
    private var refreshJob: Job? = null
    private var loadMoreJob: Job? = null

    /** Local reads not yet reflected by a list response: id -> mutation clock at confirmation (null = in flight). */
    private val readOverrides = mutableMapOf<String, Long?>()
    private var mutationClock = 0L

    init { scope.launch { repository.sessionChanges().collect { syncSession() } } }

    /** Runs while Home is in the foreground; cancelling it closes the channel. */
    suspend fun observeSignals() {
        refresh() // foreground re-entry always re-reads, even if the channel never joins
        repository.signals().collect { signal ->
            if (signal != NotificationSignal.Disconnected) refresh()
        }
    }

    /** Coalesced: at most one reconciliation runs and at most one more is queued. */
    fun refresh() {
        if (!syncSession()) return
        refreshRequested = true
        if (refreshJob?.isActive == true) return
        val mySession = session
        refreshJob = scope.launch {
            while (refreshRequested && mySession == session) {
                refreshRequested = false
                listLock.withLock { reconcile(mySession) }
            }
        }
    }

    fun loadMore() {
        if (!syncSession() || mutable.value.cursor == null || loadMoreJob?.isActive == true) return
        val mySession = session
        mutable.update { it.copy(loadingMore = true, message = null) }
        loadMoreJob = scope.launch {
            listLock.withLock {
                // Re-read under the lock: a reconciliation may have replaced the window and cursor.
                val current = mutable.value
                val cursor = current.cursor
                val epoch = listEpoch
                if (cursor == null || !isCurrent(mySession, epoch)) {
                    if (isCurrent(mySession, epoch)) mutable.update { it.copy(loadingMore = false) }
                    return@withLock
                }
                val clockAtRequest = mutationClock
                val result = repository.list(cursor, PAGE_SIZE, current.filter)
                if (!isCurrent(mySession, epoch)) return@withLock
                mutable.value = when (result) {
                    is ApiResponse.Success -> {
                        pruneOverrides(clockAtRequest)
                        val page = withOverrides(result.data.items)
                        val merged = (mutable.value.items + page).distinctBy { it.id }
                        mutable.value.copy(
                            items = merged, cursor = result.data.nextCursor,
                            unreadCount = adjustedUnread(result.data.unreadCount, result.data.items),
                            loadingMore = false, windowTruncated = merged.size > WINDOW_LIMIT
                        )
                    }
                    is ApiResponse.Failure -> mutable.value.copy(loadingMore = false, message = result.error.message)
                }
            }
        }
    }

    fun setFilter(filter: NotificationFilter) {
        if (filter == mutable.value.filter || !syncSession()) return
        resetWindow(filter)
    }

    /** Drops the loaded window and its cursor, then loads the first page again. */
    fun resetWindow(filter: NotificationFilter = mutable.value.filter) {
        if (!syncSession()) return
        listEpoch++
        loadMoreJob?.cancel()
        mutable.value = NotificationUiState(filter = filter, unreadCount = mutable.value.unreadCount, loading = true)
        refresh()
    }

    fun open(item: AppNotification) {
        if (!syncSession()) return
        val mySession = session
        val optimistic = !item.isRead && applyLocalRead(setOf(item.id)) > 0
        scope.launch {
            val result = repository.open(item.id)
            if (mySession != session) return@launch
            when (result) {
                is ApiResponse.Success -> {
                    if (optimistic) confirmOverrides(setOf(item.id))
                    // Navigate with the server-authorized target, not cached row data.
                    val event = result.data.postId?.let(NotificationEvent::OpenPost)
                        ?: result.data.actorId?.let(NotificationEvent::OpenProfile)
                    event?.let { eventChannel.send(it) }
                }
                is ApiResponse.Failure -> {
                    // Never keep a read state the server did not confirm; re-read authoritative data.
                    if (optimistic) revertLocalRead(setOf(item.id))
                    mutable.update { it.copy(message = result.error.message) }
                    refresh()
                }
            }
        }
    }

    fun markAllRead() {
        if (mutable.value.unreadCount == 0 || !syncSession()) return
        val mySession = session
        val ids = mutable.value.items.filter { !it.isRead }.map { it.id }.toSet()
        applyLocalRead(ids, all = true)
        scope.launch {
            val result = repository.markRead(null)
            if (mySession != session) return@launch
            when (result) {
                is ApiResponse.Success -> confirmOverrides(ids)
                is ApiResponse.Failure -> {
                    revertLocalRead(ids)
                    mutable.update { it.copy(message = result.error.message) }
                }
            }
            // Arrivals after the server's snapshot stay unread; only a fresh read shows them.
            refresh()
        }
    }

    fun clearMessage() = mutable.update { it.copy(message = null) }

    private suspend fun reconcile(mySession: Int) {
        val before = mutable.value
        val epoch = listEpoch
        val wanted = before.items.size.coerceAtLeast(PAGE_SIZE)
        val limit = wanted.coerceAtMost(WINDOW_LIMIT)
        val clockAtRequest = mutationClock
        mutable.update { it.copy(loading = true) }
        val collected = LinkedHashMap<String, AppNotification>()
        var cursor: NotificationCursor? = null
        var unread = before.unreadCount
        do {
            val result = repository.list(cursor, (limit - collected.size).coerceIn(1, MAX_PAGE), before.filter)
            if (!isCurrent(mySession, epoch)) return
            when (result) {
                is ApiResponse.Failure -> {
                    mutable.update {
                        if (it.items.isEmpty()) it.copy(loading = false, error = result.error.message)
                        else it.copy(loading = false, message = result.error.message)
                    }
                    return
                }
                is ApiResponse.Success -> {
                    result.data.items.forEach { collected.putIfAbsent(it.id, it) }
                    cursor = result.data.nextCursor
                    unread = result.data.unreadCount
                }
            }
        } while (cursor != null && collected.size < limit)
        pruneOverrides(clockAtRequest)
        val items = withOverrides(collected.values.toList())
        mutable.value = mutable.value.copy(
            items = items, cursor = cursor, unreadCount = adjustedUnread(unread, collected.values),
            loading = false, loaded = true, error = null, windowTruncated = wanted > WINDOW_LIMIT
        )
    }

    private fun applyLocalRead(ids: Set<String>, all: Boolean = false): Int {
        ids.forEach { readOverrides[it] = null }
        val state = mutable.value
        val changed = state.items.count { !it.isRead && it.id in ids }
        mutable.value = state.copy(
            items = state.items.map { if (it.id in ids) it.copy(isRead = true) else it },
            unreadCount = if (all) 0 else (state.unreadCount - changed).coerceAtLeast(0)
        )
        return changed
    }

    private fun revertLocalRead(ids: Set<String>) {
        ids.forEach { readOverrides.remove(it) }
        val state = mutable.value
        val reverted = state.items.count { it.isRead && it.id in ids }
        mutable.value = state.copy(
            items = state.items.map { if (it.id in ids) it.copy(isRead = false) else it },
            unreadCount = state.unreadCount + reverted
        )
    }

    private fun confirmOverrides(ids: Set<String>) {
        val clock = ++mutationClock
        ids.forEach { if (readOverrides.containsKey(it)) readOverrides[it] = clock }
    }

    /** A response requested after a mutation was confirmed already reflects it. */
    private fun pruneOverrides(clockAtRequest: Long) {
        readOverrides.entries.removeAll { (_, confirmed) -> confirmed != null && confirmed <= clockAtRequest }
    }

    private fun withOverrides(items: List<AppNotification>) =
        items.map { if (!it.isRead && readOverrides.containsKey(it.id)) it.copy(isRead = true) else it }

    private fun adjustedUnread(serverUnread: Int, serverItems: Collection<AppNotification>) =
        (serverUnread - serverItems.count { !it.isRead && readOverrides.containsKey(it.id) }).coerceAtLeast(0)

    private fun isCurrent(mySession: Int, epoch: Int) = syncSession() && mySession == session && epoch == listEpoch

    private fun syncSession(): Boolean {
        val current = repository.currentUserId()
        if (current != owner) {
            owner = current
            session++
            listEpoch++
            refreshRequested = false
            refreshJob?.cancel()
            loadMoreJob?.cancel()
            readOverrides.clear()
            mutable.value = NotificationUiState()
        }
        return current != null
    }

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_PAGE = 50
        const val WINDOW_LIMIT = 200
    }
}
