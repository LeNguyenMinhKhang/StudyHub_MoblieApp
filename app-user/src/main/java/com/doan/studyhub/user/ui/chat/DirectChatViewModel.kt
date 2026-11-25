package com.doan.studyhub.user.ui.chat

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-to-one conversation; everything else lives in [ConversationChatViewModel]. */
class DirectChatViewModel(
    repository: ChatRepository = ChatRepository(),
    externalScope: CoroutineScope? = null
) : ConversationChatViewModel(repository, externalScope, showsReadReceipts = true, tracksSenders = false) {

    fun initChat(conversationId: String?, targetUserId: String?) {
        scope.launch {
            mutableState.update { it.copy(isLoading = true, errorMessage = null) }

            val resolvedConvId = if (!conversationId.isNullOrBlank()) {
                conversationId
            } else if (!targetUserId.isNullOrBlank()) {
                when (val res = repository.getOrCreateDirectChat(targetUserId)) {
                    is ApiResponse.Success -> res.data.conversationId
                    is ApiResponse.Failure -> {
                        mutableState.update { it.copy(isLoading = false, errorMessage = res.error.message) }
                        return@launch
                    }
                }
            } else {
                mutableState.update { it.copy(isLoading = false, errorMessage = "Thiếu thông tin cuộc trò chuyện") }
                return@launch
            }

            openConversation(resolvedConvId)
        }
    }
}
