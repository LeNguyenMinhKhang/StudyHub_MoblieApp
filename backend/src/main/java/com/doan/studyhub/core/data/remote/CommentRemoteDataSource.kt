
package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Comment
import com.doan.studyhub.core.data.model.CreateCommentRequest
import com.doan.studyhub.core.data.model.UpdateCommentRequest
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

class CommentRemoteDataSource {

    private val postgrest = SupabaseClient.client.postgrest

    // Lấy danh sách bình luận của bài viết
    suspend fun getComments(postId: String): List<Comment> {
        return postgrest["comments"]
            .select {
                filter {
                    eq("post_id", postId)
                }

                order(
                    column = "created_at",
                    order = Order.ASCENDING
                )
            }
            .decodeList<Comment>()
    }

    // Thêm bình luận hoặc trả lời bình luận
    suspend fun createComment(
        postId: String,
        content: String,
        parentId: String? = null
    ): Comment {

        val currentUserId = SupabaseClient.client.auth
            .currentUserOrNull()?.id
            ?: throw IllegalStateException(
                "Bạn cần đăng nhập để bình luận."
            )

        val cleanedContent = content.trim()

        require(cleanedContent.isNotBlank()) {
            "Nội dung bình luận không được để trống."
        }

        val request = CreateCommentRequest(
            postId = postId,
            userId = currentUserId,
            parentId = parentId,
            content = cleanedContent
        )

        return postgrest["comments"]
            .insert(request) {
                select()
            }
            .decodeList<Comment>()
            .first()
    }

    // Chỉnh sửa nội dung bình luận
    suspend fun updateComment(
        commentId: String,
        content: String
    ) {
        val cleanedContent = content.trim()

        require(cleanedContent.isNotBlank()) {
            "Nội dung bình luận không được để trống."
        }

        postgrest["comments"]
            .update(
                UpdateCommentRequest(
                    content = cleanedContent
                )
            ) {
                filter {
                    eq("id", commentId)
                }
            }
    }

    // Xóa bình luận
    suspend fun deleteComment(commentId: String) {
        postgrest["comments"]
            .delete {
                filter {
                    eq("id", commentId)
                }
            }
    }

    // Đánh dấu câu trả lời tốt nhất
    suspend fun markBestAnswer(
        postId: String,
        commentId: String
    ) {
        // Bỏ đánh dấu câu trả lời tốt nhất cũ
        postgrest["comments"]
            .update(
                mapOf("is_best_answer" to false)
            ) {
                filter {
                    eq("post_id", postId)
                    eq("is_best_answer", true)
                }
            }

        // Đánh dấu câu trả lời mới
        postgrest["comments"]
            .update(
                mapOf("is_best_answer" to true)
            ) {
                filter {
                    eq("id", commentId)
                    eq("post_id", postId)
                }
            }
    }
}
