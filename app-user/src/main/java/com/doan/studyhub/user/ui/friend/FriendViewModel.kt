package com.doan.studyhub.user.ui.friend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.repository.FriendshipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FriendViewModel @JvmOverloads constructor(
    private val repository: FriendshipRepository = FriendshipRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(FriendUiState())
    val uiState = mutable.asStateFlow()
    private var owner = repository.currentUserId()
    private var sessionGeneration = 0
    private val generations = mutableMapOf<FriendshipListKind, Int>()

    init { scope.launch { repository.sessionChanges().collect { syncSession() } } }

    private fun syncSession(): Boolean {
        val current = repository.currentUserId()
        if (current != owner) {
            owner = current
            sessionGeneration++
            generations.clear()
            mutable.value = FriendUiState()
        }
        return current != null
    }

    fun select(kind: FriendshipListKind) {
        syncSession()
        mutable.value = mutable.value.copy(selected = kind)
        if (!mutable.value.tab.loaded) refresh()
    }

    fun refresh() = load(mutable.value.selected, false)
    fun loadMore() = load(mutable.value.selected, true)

    private fun load(kind: FriendshipListKind, more: Boolean) {
        if (!syncSession()) return
        val previous = mutable.value.tabs.getValue(kind)
        if (more && (previous.loading || previous.cursor == null)) return
        val generation = (generations[kind] ?: 0) + 1
        generations[kind] = generation
        val session = sessionGeneration
        val cursor = if (more) previous.cursor else null
        updateTab(kind, previous.copy(loading = true, error = null))
        scope.launch {
            val result = repository.list(kind, cursor)
            if (!syncSession() || session != sessionGeneration || generations[kind] != generation) return@launch
            when (result) {
                is ApiResponse.Success -> {
                    val items = ((if (more) previous.items else emptyList()) + result.data.items).distinctBy { it.id }
                    updateTab(kind, FriendTabState(items, result.data.nextCursor, loaded = true))
                    if (!more) mutable.value = mutable.value.copy(unresolved = mutable.value.unresolved - previous.items.map { it.id }.toSet())
                }
                is ApiResponse.Failure -> updateTab(kind, previous.copy(loading = false, error = result.error.message))
            }
        }
    }

    fun act(item: FriendshipListItem, action: FriendshipAction) {
        if (!syncSession()) return
        val state = mutable.value
        if (item.id in state.busy || item.id in state.unresolved || state.tab.items.none { it.id == item.id }) return
        val allowed = when {
            item.status == "accepted" -> listOf(FriendshipAction.REMOVE)
            item.direction == "incoming" -> listOf(FriendshipAction.ACCEPT, FriendshipAction.DECLINE)
            else -> listOf(FriendshipAction.CANCEL)
        }
        if (action !in allowed) return
        val session = sessionGeneration
        mutable.value = state.copy(busy = state.busy + item.id, rowErrors = state.rowErrors - item.id)
        scope.launch {
            val result = repository.mutate(action, item.id)
            if (!syncSession() || session != sessionGeneration) return@launch
            val current = mutable.value
            if (result is ApiResponse.Success) {
                FriendshipListKind.entries.forEach { generations[it] = (generations[it] ?: 0) + 1 }
                mutable.value = current.copy(
                    busy = current.busy - item.id,
                    tabs = FriendshipListKind.entries.associateWith { kind ->
                        if (kind == current.selected) current.tabs.getValue(kind).copy(loaded = false, loading = false)
                        else FriendTabState()
                    }
                )
            } else if (result is ApiResponse.Failure) {
                mutable.value = current.copy(busy = current.busy - item.id,
                    unresolved = current.unresolved + item.id, rowErrors = current.rowErrors + (item.id to result.error.message))
            }
            // Reconcile by read, never replay a mutation after an ambiguous failure.
            refresh()
        }
    }

    private fun updateTab(kind: FriendshipListKind, tab: FriendTabState) {
        mutable.value = mutable.value.copy(tabs = mutable.value.tabs + (kind to tab))
    }
}
