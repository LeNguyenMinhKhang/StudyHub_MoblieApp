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
import com.doan.studyhub.core.data.model.GroupConversation
import com.doan.studyhub.core.data.model.MessageSender
import com.doan.studyhub.core.data.remote.ChatRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

open class ChatRepository(
    private val remoteDataSource: ChatRemoteDataSource = ChatRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall()
) {
    open fun getCurrentUserId(): String? {
        return SupabaseClient.client.auth.currentUserOrNull()?.id
    }

    open suspend fun getConversations(): ApiResponse<List<ConversationSummary>> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_LIST) {
            remoteDataSource.listMyConversations()
        }
    }

    open suspend fun getOrCreateDirectChat(targetUserId: String): ApiResponse<DirectChatRecipient> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_CREATE) {
            remoteDataSource.getOrCreateDirectConversation(targetUserId)
                ?: throw DomainException(ErrorType.PROFILE_NOT_FOUND)
        }
    }

    open suspend fun getOrCreateStudyGroupConversation(groupId: String): ApiResponse<GroupConversation> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_CREATE) {
            getCurrentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
            remoteDataSource.getOrCreateStudyGroupConversation(groupId)
        }
    }

    /** Resolves display names/avatars in batches of [SENDER_BATCH]; ids are de-duplicated. */
    open suspend fun getSenderProfiles(userIds: Collection<String>): ApiResponse<Map<String, MessageSender>> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_MESSAGES) {
            userIds.filter { it.isNotBlank() }.distinct().chunked(SENDER_BATCH)
                .flatMap { remoteDataSource.getSenderProfiles(it) }
                .associateBy { it.id }
        }
    }

    open suspend fun getMessages(conversationId: String): ApiResponse<List<ChatMessage>> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_MESSAGES) {
            remoteDataSource.getMessages(conversationId)
        }
    }

    open suspend fun sendTextMessage(conversationId: String, content: String): ApiResponse<ChatMessage> = withContext(Dispatchers.IO) {
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

    open suspend fun sendImageMessage(conversationId: String, fileName: String, bytes: ByteArray): ApiResponse<ChatMessage> = withContext(Dispatchers.IO) {
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

    open suspend fun sendDocumentMessage(
        conversationId: String,
        fileName: String,
        bytes: ByteArray,
        fileMetadata: String,
        mimeType: String? = null
    ): ApiResponse<ChatMessage> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_UPLOAD) {
            getCurrentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
            val publicUrl = remoteDataSource.uploadChatDocument(conversationId, fileName, bytes, mimeType)
            remoteDataSource.sendMessage(
                conversationId = conversationId,
                content = fileMetadata,
                type = "document",
                fileUrl = publicUrl
            )
        }
    }

    open suspend fun markRead(conversationId: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        api.execute(Operation.CHAT_MARK_READ) {
            remoteDataSource.markConversationRead(conversationId)
        }
    }

    open fun observeMessages(conversationId: String): Flow<ChatMessage> {
        return remoteDataSource.observeMessages(conversationId)
    }

    companion object {
        const val SENDER_BATCH = 100
    }
}

