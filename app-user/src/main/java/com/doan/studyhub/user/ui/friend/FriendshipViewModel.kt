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

class FriendshipViewModel @JvmOverloads constructor(
    private val repository: FriendshipRepository = FriendshipRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(FriendshipUiState())
    val uiState = mutable.asStateFlow()
    private var owner = repository.currentUserId()
    private var target: String? = null
    private var generation = 0

    init { scope.launch { repository.sessionChanges().collect { syncSession() } } }

    private fun syncSession(): Boolean {
        val current = repository.currentUserId()
        if (owner != current) {
            owner = current
            generation++
            mutable.value = FriendshipUiState()
        }
        return current != null
    }

    fun show(id: String) {
        syncSession()
        if (target != id) { target = id; generation++; mutable.value = FriendshipUiState(); refresh() }
        else if (mutable.value.snapshot == null && !mutable.value.loading && !mutable.value.busy) refresh()
    }

    fun refresh() {
        if (!syncSession() || mutable.value.busy) return
        val id = target ?: return
        val request = ++generation
        if (id.equals(owner, ignoreCase = true)) {
            mutable.value = FriendshipUiState(FriendshipSnapshot(id, FriendshipState.SELF), reconciled = true)
            return
        }
        mutable.value = mutable.value.copy(loading = true, reconciled = false, error = null)
        scope.launch {
            val result = repository.state(id)
            if (!syncSession() || generation != request) return@launch
            renderRead(result)
        }
    }

    fun act(action: FriendshipAction) {
        if (!syncSession() || action !in mutable.value.actions) return
        val snapshot = mutable.value.snapshot ?: return
        val id = if (action == FriendshipAction.SEND) snapshot.targetId else snapshot.friendshipId ?: return
        val request = ++generation
        mutable.value = mutable.value.copy(busy = true, error = null)
        scope.launch {
            val result = repository.mutate(action, id)
            if (!syncSession() || generation != request) return@launch
            when (result) {
                is ApiResponse.Success -> mutable.value = FriendshipUiState(result.data, reconciled = true)
                is ApiResponse.Failure -> {
                    mutable.value = mutable.value.copy(reconciled = false, error = result.error.message)
                    val read = repository.state(snapshot.targetId)
                    if (!syncSession() || generation != request) return@launch
                    renderRead(read, result.error.message)
                }
            }
        }
    }

    private fun renderRead(result: ApiResponse<FriendshipSnapshot>, mutationError: String? = null) {
        mutable.value = when (result) {
            is ApiResponse.Success -> FriendshipUiState(result.data, reconciled = true, error = mutationError)
            is ApiResponse.Failure -> mutable.value.copy(loading = false, busy = false, reconciled = false, error = result.error.message)
        }
    }
}
