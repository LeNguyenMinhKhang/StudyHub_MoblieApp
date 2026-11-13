package com.doan.studyhub.user.ui.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.AppNotification
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.repository.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NotificationUiState(
    val items: List<AppNotification> = emptyList(),
    val cursor: NotificationCursor? = null,
    val unreadCount: Int = 0,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null
)

class NotificationViewModel @JvmOverloads constructor(
    private val repository: NotificationRepository = NotificationRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(NotificationUiState())
    val uiState = mutable.asStateFlow()
    private var owner = repository.currentUserId()
    private var generation = 0

    init { scope.launch { repository.sessionChanges().collect { syncSession() } } }

    private fun syncSession(): Boolean {
        val current = repository.currentUserId()
        if (current != owner) {
            owner = current
            generation++
            mutable.value = NotificationUiState()
        }
        return current != null
    }

    fun refresh() = load(false)
    fun loadMore() = load(true)

    private fun load(more: Boolean) {
        if (!syncSession()) return
        val previous = mutable.value
        if (more && (previous.loading || previous.cursor == null)) return
        val request = ++generation
        mutable.value = previous.copy(loading = true, error = null)
        scope.launch {
            val result = repository.list(if (more) previous.cursor else null)
            if (!syncSession() || generation != request) return@launch
            mutable.value = when (result) {
                is ApiResponse.Success -> NotificationUiState(
                    items = ((if (more) previous.items else emptyList()) + result.data.items).distinctBy { it.id },
                    cursor = result.data.nextCursor,
                    unreadCount = result.data.unreadCount,
                    loaded = true
                )
                is ApiResponse.Failure -> previous.copy(loading = false, error = result.error.message)
            }
        }
    }

    fun markRead(item: AppNotification) {
        if (item.isRead || !syncSession()) return
        applyRead(setOf(item.id))
        scope.launch { repository.markRead(listOf(item.id)) }
    }

    fun markAllRead() {
        if (mutable.value.unreadCount == 0 || !syncSession()) return
        applyRead(null)
        val session = generation
        scope.launch {
            // Failure means the server may still hold unread rows; re-read instead of trusting local state.
            if (repository.markRead(null) is ApiResponse.Failure && session == generation) refresh()
        }
    }

    private fun applyRead(ids: Set<String>?) {
        val state = mutable.value
        val changed = state.items.count { !it.isRead && (ids == null || it.id in ids) }
        mutable.value = state.copy(
            items = state.items.map { if (ids == null || it.id in ids) it.copy(isRead = true) else it },
            unreadCount = if (ids == null) 0 else (state.unreadCount - changed).coerceAtLeast(0)
        )
    }
}
