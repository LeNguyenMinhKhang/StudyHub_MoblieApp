package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Model đại diện cho 1 dòng trong bảng "post_files" trên Supabase
@Serializable
data class PostFile(
    @SerialName("id") val id: String? = null,
    @SerialName("post_id") val postId: String,
    @SerialName("file_url") val fileUrl: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_type") val fileType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class CreatePostFileRequest(
    @SerialName("post_id") val postId: String,
    @SerialName("file_url") val fileUrl: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_type") val fileType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null
)
