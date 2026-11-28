package com.doan.studyhub.core.data.repository

import com.doan.studyhub.core.data.remote.AdminPostRemoteDataSource
import kotlinx.serialization.json.JsonObject

class AdminPostRepository(
    private val remoteDataSource: AdminPostRemoteDataSource =
        AdminPostRemoteDataSource()
) {

    // Lấy danh sách bài viết (tìm kiếm + phân trang + lọc tác giả)
    suspend fun getPosts(
        search: String? = null,
        limit: Int = 20,
        offset: Int = 0,
        authorId: String? = null
    ) = remoteDataSource.getPosts(
        search = search,
        limit = limit,
        offset = offset,
        authorId = authorId
    )

    // Lấy chi tiết bài viết
    suspend fun getPostDetail(postId: String): JsonObject? {
        return remoteDataSource.getPostDetail(postId)
    }

    // Xóa bài viết
    suspend fun deletePost(postId: String): Boolean {
        return remoteDataSource.deletePost(postId)
    }
}