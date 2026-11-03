
package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Comment(
    @SerialName("id")
    val id: String? = null,

    @SerialName("post_id")
    val postId: String,

    @SerialName("user_id")
    val userId: String,

    @SerialName("parent_id")
    val parentId: String? = null,

    @SerialName("content")
    val content: String,

    @SerialName("is_best_answer")
    val isBestAnswer: Boolean = false,

    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class CreateCommentRequest(
    @SerialName("post_id")
    val postId: String,

    @SerialName("user_id")
    val userId: String,

    @SerialName("parent_id")
    val parentId: String? = null,

    @SerialName("content")
    val content: String
)

@Serializable
data class UpdateCommentRequest(
    @SerialName("content")
    val content: String
)
