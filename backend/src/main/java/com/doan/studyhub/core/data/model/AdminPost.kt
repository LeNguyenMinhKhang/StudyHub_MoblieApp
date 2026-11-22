package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AdminPost(
    val id: String,

    @SerialName("author_id")
    val authorId: String,

    @SerialName("subject_id")
    val subjectId: String? = null,

    @SerialName("post_type")
    val postType: String? = null,

    val title: String,
    val content: String? = null,

    @SerialName("created_at")
    val createdAt: String,

    @SerialName("updated_at")
    val updatedAt: String? = null,

    @SerialName("author_username")
    val authorUsername: String? = null,

    @SerialName("author_display_name")
    val authorDisplayName: String? = null,

    @SerialName("author_email")
    val authorEmail: String? = null,

    @SerialName("author_avatar_url")
    val authorAvatarUrl: String? = null,

    @SerialName("subject_name")
    val subjectName: String? = null,

    @SerialName("comment_count")
    val commentCount: Long = 0,

    @SerialName("like_count")
    val likeCount: Long = 0,

    @SerialName("file_count")
    val fileCount: Long = 0
)