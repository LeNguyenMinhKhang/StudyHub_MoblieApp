package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DirectChatRecipient(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("target_user_id") val targetUserId: String,
    @SerialName("target_display_name") val targetDisplayName: String,
    @SerialName("target_avatar_url") val targetAvatarUrl: String? = null
)

