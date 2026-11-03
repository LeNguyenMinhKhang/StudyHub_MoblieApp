package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String? = null,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val type: String = "text",
    val content: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    val status: String = "sent",
    @SerialName("created_at") val createdAt: String? = null
)

