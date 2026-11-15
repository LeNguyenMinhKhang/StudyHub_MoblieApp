package com.doan.studyhub.core.data.model

data class CommentWithAuthor(
    val comment: Comment,
    val author: PublicProfile?,
    val reactionCount: Int = 0,
    val reactionCounts: Map<String, Int> = emptyMap(),
    val currentUserReaction: String? = null
)