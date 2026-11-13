package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppNotification(
    val id: String,
    val type: String,
    @SerialName("is_read") val isRead: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("post_id") val postId: String? = null,
    val actor: FriendshipPeer? = null
)

@Serializable
data class NotificationCursor(@SerialName("created_at") val createdAt: String, val id: String)

@Serializable
data class NotificationPage(
    val items: List<AppNotification>,
    @SerialName("next_cursor") val nextCursor: NotificationCursor? = null,
    @SerialName("unread_count") val unreadCount: Int = 0
)
