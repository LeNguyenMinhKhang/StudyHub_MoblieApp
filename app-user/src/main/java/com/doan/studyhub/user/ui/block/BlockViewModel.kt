
package com.doan.studyhub.user.ui.block

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.BlockCursor
import com.doan.studyhub.core.data.model.BlockSnapshot
import com.doan.studyhub.core.data.model.BlockedUser
import com.doan.studyhub.core.data.repository.BlockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BlockUiState(
    val snapshot: BlockSnapshot? = null,
    val blockedUsers: List<BlockedUser> = emptyList(),
    val nextCursor: BlockCursor? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val loadingList: Boolean = false,
    val loadingMore: Boolean = false,
    val reconciled: Boolean = false,
    val error: String? = null,
    val listError: String? = null,
    // Trạng thái riêng cho từng người trong danh sách
    val unblockingIds: Set<String> = emptySet(),
    val blockedRowErrors: Map<String, String> = emptyMap()
)

class BlockViewModel @JvmOverloads constructor(
    private val repository: BlockRepository = BlockRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {

    private val scope get() = externalScope ?: viewModelScope

    private val mutable = MutableStateFlow(BlockUiState())
    val uiState = mutable.asStateFlow()

    private var owner = repository.currentUserId()
    private var target: String? = null

    private var generation = 0
    private var listGeneration = 0

    init {
        scope.launch {
            repository.sessionChanges().collect {
                syncSession()
            }
        }
    }

    private fun syncSession(): Boolean {
        val current = repository.currentUserId()

        if (owner != current) {
            owner = current
            generation++
            listGeneration++
            target = null
            mutable.value = BlockUiState()
        }

        return current != null
    }

    // MARK: - Block state

    fun show(id: String) {
        if (!syncSession()) return

        if (target != id) {
            target = id
            generation++
            mutable.value = BlockUiState()
            refreshState()
        } else if (
            mutable.value.snapshot == null &&
            !mutable.value.loading &&
            !mutable.value.busy
        ) {
            refreshState()
        }
    }

    fun refreshState() {
        if (!syncSession() || mutable.value.busy) return

        val id = target ?: return
        val request = ++generation

        mutable.value = mutable.value.copy(
            loading = true,
            reconciled = false,
            error = null
        )

        scope.launch {
            val result = repository.state(id)

            if (!syncSession() || generation != request) return@launch

            renderState(result)
        }
    }

    fun blockUser() {
        mutateBlock(block = true)
    }

    fun unblockUser() {
        mutateBlock(block = false)
    }

    private fun mutateBlock(block: Boolean) {
        if (!syncSession() || mutable.value.busy) return

        val id = target ?: return
        val request = ++generation

        mutable.value = mutable.value.copy(
            busy = true,
            error = null
        )

        scope.launch {
            val result = if (block) {
                repository.block(id)
            } else {
                repository.unblock(id)
            }

            if (!syncSession() || generation != request) return@launch

            when (result) {
                is ApiResponse.Success -> {
                    mutable.value = mutable.value.copy(
                        snapshot = result.data,
                        loading = false,
                        busy = false,
                        reconciled = true,
                        error = null
                    )

                    if (!block) {
                        removeFromBlockedList(id)
                    }
                }

                is ApiResponse.Failure -> {
                    mutable.value = mutable.value.copy(
                        busy = false,
                        reconciled = false,
                        error = result.error.message
                    )

                    // Đọc lại trạng thái thật từ Supabase
                    val read = repository.state(id)

                    if (!syncSession() || generation != request) return@launch

                    renderState(read, result.error.message)
                }
            }
        }
    }

    private fun renderState(
        result: ApiResponse<BlockSnapshot>,
        mutationError: String? = null
    ) {
        mutable.value = when (result) {
            is ApiResponse.Success -> mutable.value.copy(
                snapshot = result.data,
                loading = false,
                busy = false,
                reconciled = true,
                error = mutationError
            )

            is ApiResponse.Failure -> mutable.value.copy(
                loading = false,
                busy = false,
                reconciled = false,
                error = mutationError ?: result.error.message
            )
        }
    }

    // MARK: - Blocked users list

    fun refreshBlockedUsers() {
        if (!syncSession() || mutable.value.loadingList) return

        val request = ++listGeneration

        mutable.value = mutable.value.copy(
            loadingList = true,
            loadingMore = false,
            listError = null,
            blockedUsers = emptyList(),
            nextCursor = null,
            unblockingIds = emptySet(),
            blockedRowErrors = emptyMap()
        )

        scope.launch {
            val result = repository.list()

            if (!syncSession() || listGeneration != request) return@launch

            when (result) {
                is ApiResponse.Success -> {
                    mutable.value = mutable.value.copy(
                        blockedUsers = result.data.items,
                        nextCursor = result.data.nextCursor,
                        loadingList = false,
                        listError = null
                    )
                }

                is ApiResponse.Failure -> {
                    mutable.value = mutable.value.copy(
                        loadingList = false,
                        listError = result.error.message
                    )
                }
            }
        }
    }

    fun loadMoreBlockedUsers() {
        if (!syncSession()) return

        val cursor = mutable.value.nextCursor ?: return

        if (mutable.value.loadingList || mutable.value.loadingMore) return

        val request = listGeneration

        mutable.value = mutable.value.copy(
            loadingMore = true,
            listError = null
        )

        scope.launch {
            val result = repository.list(cursor = cursor)

            if (!syncSession() || listGeneration != request) return@launch

            when (result) {
                is ApiResponse.Success -> {
                    val current = mutable.value.blockedUsers
                    val newItems = result.data.items.filter { newItem ->
                        current.none { it.id == newItem.id }
                    }

                    mutable.value = mutable.value.copy(
                        blockedUsers = current + newItems,
                        nextCursor = result.data.nextCursor,
                        loadingMore = false,
                        listError = null
                    )
                }

                is ApiResponse.Failure -> {
                    mutable.value = mutable.value.copy(
                        loadingMore = false,
                        listError = result.error.message
                    )
                }
            }
        }
    }

    private fun removeFromBlockedList(userId: String) {
        mutable.value = mutable.value.copy(
            blockedUsers = mutable.value.blockedUsers.filter {
                !it.peer.id.equals(userId, ignoreCase = true)
            }
        )
    }

    fun clearError() {
        mutable.value = mutable.value.copy(
            error = null,
            listError = null
        )
    }

    fun unblockFromList(userId: String) {
        if (!syncSession()) return

        val id = userId.trim()
        if (id.isBlank() || id in mutable.value.unblockingIds) return

        val requestOwner = owner
        val requestGeneration = listGeneration

        mutable.value = mutable.value.copy(
            unblockingIds = mutable.value.unblockingIds + id,
            blockedRowErrors = mutable.value.blockedRowErrors - id
        )

        scope.launch {
            val result = repository.unblock(id)

            if (
                !syncSession() ||
                owner != requestOwner ||
                listGeneration != requestGeneration
            ) return@launch

            when (result) {
                is ApiResponse.Success -> {
                    mutable.value = mutable.value.copy(
                        blockedUsers = mutable.value.blockedUsers.filter {
                            !it.peer.id.equals(id, ignoreCase = true)
                        },
                        unblockingIds = mutable.value.unblockingIds - id,
                        blockedRowErrors = mutable.value.blockedRowErrors - id
                    )
                }

                is ApiResponse.Failure -> {
                    mutable.value = mutable.value.copy(
                        unblockingIds = mutable.value.unblockingIds - id,
                        blockedRowErrors = mutable.value.blockedRowErrors +
                                (id to result.error.message)
                    )
                }
            }
        }
    }
}