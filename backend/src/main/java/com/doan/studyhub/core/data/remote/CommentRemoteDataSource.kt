package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Comment
import com.doan.studyhub.core.data.model.CommentReactionSummary
import com.doan.studyhub.core.data.model.CommentReactor
import com.doan.studyhub.core.data.model.CreateCommentRequest
import com.doan.studyhub.core.data.model.UpdateCommentRequest
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class CommentRemoteDataSource {

    private val postgrest = SupabaseClient.client.postgrest

    private val validReactionTypes = setOf(
        "like",
        "love",
        "haha",
        "sad",
        "angry"
    )

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
                UpdateCommentRequest(content = cleanedContent)
            ) {
                filter {
                    eq("id", commentId)
                }
            }
    }

    suspend fun deleteComment(commentId: String) {
        postgrest["comments"]
            .delete {
                filter {
                    eq("id", commentId)
                }
            }
    }

    suspend fun markBestAnswer(
        postId: String,
        commentId: String?
    ) {
        require(
            !commentId.isNullOrBlank() ||
                    commentId == null
        )

        SupabaseClient.client.auth.currentUserOrNull()
            ?: throw IllegalStateException(
                "Bạn cần đăng nhập để chọn câu trả lời hay nhất."
            )

        postgrest.rpc(
            "set_best_answer",
            buildJsonObject {
                put("p_post_id", postId)
                put("p_comment_id", commentId)
            }
        )
    }

    suspend fun setCommentReaction(
        commentId: String,
        reactionType: String?
    ): String? {
        SupabaseClient.client.auth.currentUserOrNull()
            ?: throw IllegalStateException(
                "Bạn cần đăng nhập để bày tỏ cảm xúc."
            )

        require(
            reactionType == null ||
                    reactionType in validReactionTypes
        ) {
            "Loại cảm xúc không hợp lệ."
        }

        val response = postgrest.rpc(
            function = "set_comment_reaction",
            parameters = buildJsonObject {
                put("p_comment_id", commentId)
                put("p_reaction_type", reactionType)
            }
        )

        return response.data
            .trim()
            .removeSurrounding("\"")
            .takeUnless {
                it == "null" || it.isBlank()
            }
    }

    suspend fun getCommentReactionSummaries(
        commentIds: List<String>
    ): Map<String, CommentReactionSummary> {
        if (commentIds.isEmpty()) return emptyMap()

        SupabaseClient.client.auth.currentUserOrNull()
            ?: throw IllegalStateException(
                "Bạn cần đăng nhập để xem cảm xúc."
            )

        val result = mutableMapOf<String, CommentReactionSummary>()

        commentIds.distinct().chunked(500).forEach { batch ->
            val response = postgrest.rpc(
                function = "get_comment_reaction_summaries",
                parameters = buildJsonObject {
                    putJsonArray("p_comment_ids") {
                        batch.forEach { add(it) }
                    }
                }
            )

            val rows = Json.parseToJsonElement(response.data).jsonArray

            rows.forEach { element ->
                val row = element.jsonObject

                val commentId = row["comment_id"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()

                if (commentId.isBlank()) return@forEach

                val totalCount = row["total_count"]
                    ?.jsonPrimitive
                    ?.longOrNull
                    ?.toInt() ?: 0

                val counts = row["counts_by_type"]
                    ?.jsonObject
                    ?.mapValues { (_, value) ->
                        value.jsonPrimitive.longOrNull?.toInt() ?: 0
                    }
                    ?: emptyMap()

                val currentReaction = row["current_user_reaction"]
                    ?.jsonPrimitive
                    ?.contentOrNull

                result[commentId] = CommentReactionSummary(
                    commentId = commentId,
                    totalCount = totalCount,
                    countsByType = counts,
                    currentUserReaction = currentReaction
                )
            }
        }

        return result
    }

    suspend fun getCommentReactors(
        commentId: String,
        reactionType: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): List<CommentReactor> {
        SupabaseClient.client.auth.currentUserOrNull()
            ?: throw IllegalStateException(
                "Bạn cần đăng nhập để xem danh sách cảm xúc."
            )

        require(
            reactionType == null ||
                    reactionType in validReactionTypes
        ) {
            "Bộ lọc cảm xúc không hợp lệ."
        }

        val response = postgrest.rpc(
            function = "get_comment_reactors",
            parameters = buildJsonObject {
                put("p_comment_id", commentId)
                put("p_reaction_type", reactionType)
                put("p_limit", limit.coerceIn(1, 100))
                put("p_offset", offset.coerceAtLeast(0))
            }
        )

        return Json.decodeFromString(response.data)
    }
}