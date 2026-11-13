
package com.doan.studyhub.core.data.model

import kotlinx.serialization.Serializable

// Model tổng hợp hiển thị chi tiết bài viết
// kèm tác giả, môn học, danh sách file, Like và bình luận.
@Serializable
data class PostDetail(
    val post: Post,
    val author: Profile? = null,
    val subject: Subject? = null,
    val files: List<PostFile> = emptyList(),

    // Thông tin Like
    val likeCount: Int = 0,
    val isLikedByCurrentUser: Boolean = false,

    // Tổng số bình luận và câu trả lời
    val commentCount: Int = 0,

    // Trạng thái lưu bài viết của người dùng hiện tại
    val isSavedByCurrentUser: Boolean = false,

    // Danh sách hashtag gắn kèm
    val hashtags: List<String> = emptyList()
)
