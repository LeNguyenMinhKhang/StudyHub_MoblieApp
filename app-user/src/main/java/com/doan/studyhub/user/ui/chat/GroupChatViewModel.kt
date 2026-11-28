package com.doan.studyhub.user.ui.chat

import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Conversation of a study group: membership is checked by the server on every call. */
class GroupChatViewModel(
    repository: ChatRepository = ChatRepository(),
    externalScope: CoroutineScope? = null
) : ConversationChatViewModel(repository, externalScope, showsReadReceipts = false, tracksSenders = true) {

    private var groupId: String? = null

    fun initGroup(groupId: String) {
        if (this.groupId == groupId && mutableState.value.conversationId != null) return
        this.groupId = groupId
        scope.launch {
            mutableState.update { it.copy(isLoading = true, errorMessage = null, inaccessible = false) }
            when (val res = repository.getOrCreateStudyGroupConversation(groupId)) {
                is ApiResponse.Success -> {
                    mutableState.update { it.copy(header = ChatHeader(res.data.name, res.data.memberCount)) }
                    openConversation(res.data.conversationId)
                }
                is ApiResponse.Failure -> {
                    mutableState.update { it.copy(isLoading = false, errorMessage = res.error.message) }
                    onFailure(res.error)
                }
            }
        }
    }

    /** Re-checks access and refreshes the header (name, member count) when the screen resumes. */
    override fun refreshReadStatus() {
        val id = groupId ?: return
        if (mutableState.value.conversationId == null) return
        scope.launch {
            when (val res = repository.getOrCreateStudyGroupConversation(id)) {
                is ApiResponse.Success ->
                    mutableState.update { it.copy(header = ChatHeader(res.data.name, res.data.memberCount)) }
                is ApiResponse.Failure -> onFailure(res.error)
            }
        }
        super.refreshReadStatus()
    }

    override fun onFailure(error: ApiError) {
        if (error.type == ErrorType.GROUP_UNAVAILABLE || error.type == ErrorType.PERMISSION_DENIED) {
            mutableState.update { it.copy(inaccessible = true) }
        }
    }
}
