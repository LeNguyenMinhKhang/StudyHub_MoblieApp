package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Model đại diện cho 1 dòng trong bảng "posts" trên Supabase
@Serializable
data class Post(
    @SerialName("id") val id: String? = null,
    @SerialName("author_id") val authorId: String,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String? = null,
    @SerialName("best_comment_id") val bestCommentId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class CreatePostRequest(
    @SerialName("author_id") val authorId: String,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String? = null
)
