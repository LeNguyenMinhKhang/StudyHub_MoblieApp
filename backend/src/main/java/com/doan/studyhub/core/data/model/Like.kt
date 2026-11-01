package com.doan.studyhub.core.data.model


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Like(
    @SerialName("id")
    val id: String? = null,

    @SerialName("post_id")
    val postId: String,

    @SerialName("user_id")
    val userId: String,

    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class CreateLikeRequest(
    @SerialName("post_id")
    val postId: String,

    @SerialName("user_id")
    val userId: String
)