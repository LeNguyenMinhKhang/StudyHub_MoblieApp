package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Result of get_or_create_study_group_conversation. */
@Serializable
data class GroupConversation(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("group_id") val groupId: String,
    val name: String,
    @SerialName("member_count") val memberCount: Int = 0
)

/** Public profile fields shown next to a message in a group conversation. */
@Serializable
data class MessageSender(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null
)
