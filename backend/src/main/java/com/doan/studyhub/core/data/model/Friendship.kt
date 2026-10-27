package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FriendshipState {
    @SerialName("self") SELF,
    @SerialName("none") NONE,
    @SerialName("outgoing_pending") OUTGOING_PENDING,
    @SerialName("incoming_pending") INCOMING_PENDING,
    @SerialName("friends") FRIENDS,
    @SerialName("unavailable") UNAVAILABLE
}

enum class FriendshipListKind(val wire: String) { FRIENDS("friends"), INCOMING("incoming"), OUTGOING("outgoing") }
enum class FriendshipAction(val rpc: String) {
    SEND("send_friend_request"), ACCEPT("accept_friend_request"), DECLINE("decline_friend_request"),
    CANCEL("cancel_friend_request"), REMOVE("remove_friendship")
}

@Serializable
data class FriendshipSnapshot(
    @SerialName("target_id") val targetId: String,
    val state: FriendshipState,
    @SerialName("friendship_id") val friendshipId: String? = null
)

@Serializable
data class FriendshipPeer(
    val id: String, val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class FriendshipListItem(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val status: String, val direction: String, val peer: FriendshipPeer
)

@Serializable
data class FriendshipCursor(@SerialName("created_at") val createdAt: String, val id: String)

@Serializable
data class FriendshipPage(val items: List<FriendshipListItem>, @SerialName("next_cursor") val nextCursor: FriendshipCursor? = null)
