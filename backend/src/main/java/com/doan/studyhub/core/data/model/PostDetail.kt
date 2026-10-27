package com.doan.studyhub.core.data.model

import kotlinx.serialization.Serializable

// Model tổng hợp hiển thị chi tiết bài viết kèm tác giả, môn học và danh sách file
@Serializable
data class PostDetail(
    val post: Post,
    val author: Profile? = null,
    val subject: Subject? = null,
    val files: List<PostFile> = emptyList()
)
