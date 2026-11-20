package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommentReactor(
    val id: String,
    val username: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("reaction_type")
    val reactionType: String,
    @SerialName("reacted_at")
    val reactedAt: String? = null
)

data class CommentReactionSummary(
    val commentId: String,
    val totalCount: Int,
    val countsByType: Map<String, Int>,
    val currentUserReaction: String?
)