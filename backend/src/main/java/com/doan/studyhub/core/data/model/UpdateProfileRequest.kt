package com.doan.studyhub.core.data.model

/** The only fields exposed to the owner-edit RPC. */
data class UpdateProfileRequest(
    val username: String,
    val displayName: String,
    val bio: String?
)
