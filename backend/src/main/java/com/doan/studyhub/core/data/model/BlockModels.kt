package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BlockSnapshot(
    @SerialName("target_id")
    val targetId: String,

    @SerialName("blocked_by_me")
    val blockedByMe: Boolean,

    @SerialName("blocked_me")
    val blockedMe: Boolean,

    @SerialName("is_blocked")
    val isBlocked: Boolean
)

@Serializable
data class BlockedPeer(
    val id: String,
    val username: String,

    @SerialName("display_name")
    val displayName: String,

    @SerialName("avatar_url")
    val avatarUrl: String? = null
)

@Serializable
data class BlockedUser(
    val id: String,

    @SerialName("created_at")
    val createdAt: String,

    val peer: BlockedPeer
)

@Serializable
data class BlockCursor(
    @SerialName("created_at")
    val createdAt: String,

    val id: String
)

@Serializable
data class BlockedUsersPage(
    val items: List<BlockedUser> = emptyList(),

    @SerialName("next_cursor")
    val nextCursor: BlockCursor? = null
)