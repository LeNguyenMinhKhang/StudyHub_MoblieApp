package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One inbox row. New fields are nullable/defaulted so legacy RPC responses
 * (no source or template data) still decode.
 */
@Serializable
data class AppNotification(
    val id: String,
    val type: String,
    @SerialName("is_read") val isRead: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("post_id") val postId: String? = null,
    val actor: FriendshipPeer? = null,
    @SerialName("source_kind") val sourceKind: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("template_version") val templateVersion: Int = 1,
    @SerialName("opened_at") val openedAt: String? = null
)

@Serializable
data class NotificationCursor(@SerialName("created_at") val createdAt: String, val id: String)

@Serializable
data class NotificationPage(
    val items: List<AppNotification>,
    @SerialName("next_cursor") val nextCursor: NotificationCursor? = null,
    @SerialName("unread_count") val unreadCount: Int = 0
)

enum class NotificationFilter(val wire: String) { ALL("all"), UNREAD("unread") }

enum class NotificationCategory(val wire: String) { FRIENDSHIP("friendship"), LIKE("like"), COMMENT("comment") }

/** Server-confirmed in-app preferences; every category defaults to enabled. */
@Serializable
data class NotificationPreferences(
    val friendship: Boolean = true,
    val like: Boolean = true,
    val comment: Boolean = true
) {
    fun isEnabled(category: NotificationCategory) = when (category) {
        NotificationCategory.FRIENDSHIP -> friendship
        NotificationCategory.LIKE -> like
        NotificationCategory.COMMENT -> comment
    }
}

/** Result of opening a notification: what the client may navigate to. */
@Serializable
data class NotificationTarget(
    val id: String,
    val type: String,
    @SerialName("post_id") val postId: String? = null,
    @SerialName("actor_id") val actorId: String? = null
)

/**
 * Delivery signals from the private recipient channel. They only say "re-read";
 * content and counts always come from authorized RPCs.
 */
sealed interface NotificationSignal {
    /** Channel (re)joined: take an authoritative snapshot now. */
    data object Subscribed : NotificationSignal
    /** Something in the inbox or its visibility changed. */
    data object Changed : NotificationSignal
    /** Channel or socket dropped; the next [Subscribed] triggers reconciliation. */
    data object Disconnected : NotificationSignal
}

/** Templates this client renders; anything else uses the generic fallback. */
object NotificationTemplates {
    const val SUPPORTED_VERSION = 1
    val supportedTypes = setOf("friend_request", "friend_accept", "like", "comment")
    fun isSupported(item: AppNotification) =
        item.type in supportedTypes && item.templateVersion == SUPPORTED_VERSION
}
