package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.ChatMessage
import com.doan.studyhub.core.data.model.ConversationSummary
import com.doan.studyhub.core.data.model.DirectChatRecipient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperation
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

open class ChatRemoteDataSource(
    private val clientProvider: () -> SupabaseClient = { SupabaseClient }
) {
    private val postgrest get() = clientProvider().client.postgrest
    private val storage get() = clientProvider().client.storage
    private val realtime get() = clientProvider().client.realtime

    open suspend fun listMyConversations(): List<ConversationSummary> {
        return postgrest.rpc("list_my_conversations").decodeList<ConversationSummary>()
    }

    open suspend fun getOrCreateDirectConversation(targetUserId: String): DirectChatRecipient? {
        return postgrest.rpc(
            "get_or_create_direct_conversation",
            buildJsonObject { put("p_target_id", targetUserId) }
        ).decodeAs<DirectChatRecipient>()
    }

    open suspend fun getMessages(conversationId: String): List<ChatMessage> {
        return postgrest.from("messages").select {
            filter {
                eq("conversation_id", conversationId)
            }
            order("created_at", Order.ASCENDING)
        }.decodeList<ChatMessage>()
    }

    open suspend fun sendMessage(conversationId: String, content: String?, type: String, fileUrl: String?): ChatMessage {
        return postgrest.rpc(
            "send_chat_message",
            buildJsonObject {
                put("p_conversation_id", conversationId)
                content?.let { put("p_content", it) }
                put("p_type", type)
                fileUrl?.let { put("p_file_url", it) }
            }
        ).decodeAs<ChatMessage>()
    }

    open suspend fun uploadChatImage(conversationId: String, fileName: String, bytes: ByteArray): String {
        val path = "$conversationId/${System.currentTimeMillis()}_$fileName"
        storage.from("chat").upload(path = path, data = bytes) {
            upsert = false
            contentType = io.ktor.http.ContentType.Image.JPEG
        }
        return storage.from("chat").publicUrl(path)
    }

    open suspend fun markConversationRead(conversationId: String) {
        postgrest.rpc(
            "mark_conversation_read",
            buildJsonObject { put("p_conversation_id", conversationId) }
        )
    }

    open fun observeMessages(conversationId: String): Flow<ChatMessage> = flow {
        val channel = realtime.channel("chat:$conversationId")
        val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter(FilterOperation("conversation_id", FilterOperator.EQ, conversationId))
        }
        channel.subscribe()
        try {
            changeFlow.collect { action ->
                val message = action.decodeRecord<ChatMessage>()
                if (message.conversationId == conversationId) {
                    emit(message)
                }
            }
        } finally {
            channel.unsubscribe()
        }
    }
}
