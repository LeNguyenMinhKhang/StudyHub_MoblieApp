package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.AdminPost
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AdminPostRemoteDataSource {

    private val client = SupabaseClient.client

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // Lấy danh sách bài viết (tìm kiếm + phân trang)
    suspend fun getPosts(
        search: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): List<AdminPost> {

        val parameters = buildJsonObject {
            put("p_search", search?.let { JsonPrimitive(it) } ?: JsonNull)
            put("p_limit", JsonPrimitive(limit))
            put("p_offset", JsonPrimitive(offset))
        }

        val result = client.postgrest.rpc(
            function = "admin_list_posts",
            parameters = parameters
        )

        return json.decodeFromString<List<AdminPost>>(result.data)
    }

    // Lấy chi tiết bài viết
    suspend fun getPostDetail(postId: String): JsonObject? {

        val parameters = buildJsonObject {
            put("p_post_id", JsonPrimitive(postId))
        }

        val result = client.postgrest.rpc(
            function = "admin_get_post_detail",
            parameters = parameters
        )

        val jsonElement = json.parseToJsonElement(result.data)

        if (jsonElement == JsonNull) {
            return null
        }

        return jsonElement as? JsonObject
    }

    // Xóa bài viết
    suspend fun deletePost(postId: String): Boolean {

        val parameters = buildJsonObject {
            put("p_post_id", JsonPrimitive(postId))
        }

        val result = client.postgrest.rpc(
            function = "admin_delete_post",
            parameters = parameters
        )

        return json.decodeFromString<Boolean>(result.data)
    }
}