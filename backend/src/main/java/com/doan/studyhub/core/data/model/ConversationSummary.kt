
package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConversationSummary(
    @SerialName("conversation_id")
    val conversationId: String,

    val type: String,

    val title: String,

    @SerialName("avatar_url")
    val avatarUrl: String? = null,

    @SerialName("last_message_content")
    val lastMessageContent: String? = null,

    @SerialName("last_message_type")
    val lastMessageType: String? = null,

    @SerialName("last_message_at")
    val lastMessageAt: String? = null,

    @SerialName("unread_count")
    val unreadCount: Long = 0L
)
