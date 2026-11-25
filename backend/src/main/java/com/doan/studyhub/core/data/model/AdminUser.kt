package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AdminUser(
    @SerialName("id")
    val id: String,

    @SerialName("email")
    val email: String? = null,

    @SerialName("username")
    val username: String = "",

    @SerialName("display_name")
    val displayName: String = "",

    @SerialName("avatar_url")
    val avatarUrl: String? = null,

    @SerialName("role")
    val role: String = "user",

    @SerialName("is_banned")
    val isBanned: Boolean = false,

    @SerialName("created_at")
    val createdAt: String? = null
)