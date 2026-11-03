package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConversationSummary(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("target_user_id") val targetUserId: String,
    @SerialName("target_display_name") val targetDisplayName: String,
    @SerialName("target_avatar_url") val targetAvatarUrl: String? = null,
    @SerialName("last_message_content") val lastMessageContent: String? = null,
    @SerialName("last_message_type") val lastMessageType: String? = null,
    @SerialName("last_message_created_at") val lastMessageCreatedAt: String? = null,
    @SerialName("last_message_sender_id") val lastMessageSenderId: String? = null,
    @SerialName("unread_count") val unreadCount: Long = 0L
)

