package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.DomainException
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.Operation
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.ChatMessage
import com.doan.studyhub.core.data.model.ConversationSummary
import com.doan.studyhub.core.data.model.DirectChatRecipient
import com.doan.studyhub.core.data.remote.ChatRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ChatRepository(
    private val remoteDataSource: ChatRemoteDataSource = ChatRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall()
) {
    fun getCurrentUserId(): String? {
        return SupabaseClient.client.auth.currentUserOrNull()?.id
    }

    suspend fun getConversations(): ApiResponse<List<ConversationSummary>> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_LIST) {
            remoteDataSource.listMyConversations()
        }
    }

    suspend fun getOrCreateDirectChat(targetUserId: String): ApiResponse<DirectChatRecipient> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_CREATE) {
            remoteDataSource.getOrCreateDirectConversation(targetUserId)
                ?: throw DomainException(ErrorType.PROFILE_NOT_FOUND)
        }
    }

    suspend fun getMessages(conversationId: String): ApiResponse<List<ChatMessage>> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_MESSAGES) {
            remoteDataSource.getMessages(conversationId)
        }
    }

    suspend fun sendTextMessage(conversationId: String, content: String): ApiResponse<ChatMessage> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_SEND) {
            getCurrentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
            remoteDataSource.sendMessage(
                conversationId = conversationId,
                content = content.trim(),
                type = "text",
                fileUrl = null
            )
        }
    }

    suspend fun sendImageMessage(conversationId: String, fileName: String, bytes: ByteArray): ApiResponse<ChatMessage> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_UPLOAD) {
            getCurrentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
            val publicUrl = remoteDataSource.uploadChatImage(conversationId, fileName, bytes)
            remoteDataSource.sendMessage(
                conversationId = conversationId,
                content = null,
                type = "image",
                fileUrl = publicUrl
            )
        }
    }

    suspend fun markRead(conversationId: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_MARK_READ) {
            remoteDataSource.markConversationRead(conversationId)
        }
    }

    fun observeMessages(conversationId: String): Flow<ChatMessage> {
        return remoteDataSource.observeMessages(conversationId)
    }
}

