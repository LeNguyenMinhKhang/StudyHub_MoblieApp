
package com.doan.studyhub.core.data.repository

import com.doan.studyhub.core.data.model.Comment
import com.doan.studyhub.core.data.remote.CommentRemoteDataSource

class CommentRepository(
    private val remoteDataSource: CommentRemoteDataSource =
        CommentRemoteDataSource()
) {

    // Lấy danh sách bình luận
    suspend fun getComments(postId: String): List<Comment> {
        return remoteDataSource.getComments(postId)
    }

    // Thêm bình luận hoặc trả lời
    suspend fun createComment(
        postId: String,
        content: String,
        parentId: String? = null
    ): Comment {
        return remoteDataSource.createComment(
            postId = postId,
            content = content,
            parentId = parentId
        )
    }

    // Chỉnh sửa bình luận
    suspend fun updateComment(
        commentId: String,
        content: String
    ) {
        remoteDataSource.updateComment(
            commentId = commentId,
            content = content
        )
    }

    // Xóa bình luận
    suspend fun deleteComment(commentId: String) {
        remoteDataSource.deleteComment(commentId)
    }

    // Đánh dấu câu trả lời tốt nhất
    suspend fun markBestAnswer(
        postId: String,
        commentId: String
    ) {
        remoteDataSource.markBestAnswer(
            postId = postId,
            commentId = commentId
        )
    }
}
