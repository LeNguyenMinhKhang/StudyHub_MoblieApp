package com.doan.studyhub.core.data.repository

import com.doan.studyhub.core.data.model.Post
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.PostFile
import com.doan.studyhub.core.data.remote.PostRemoteDataSource

class PostRepository(
    private val remoteDataSource: PostRemoteDataSource = PostRemoteDataSource()
) {
    suspend fun getPosts(
        subjectId: String? = null,
        authorId: String? = null,
        type: String? = null,
        limit: Long? = null,
        offset: Long? = null
    ): List<PostDetail> {
        return remoteDataSource.getPosts(subjectId, authorId, type, limit, offset)
    }

    suspend fun getPostDetail(postId: String): PostDetail? {
        return remoteDataSource.getPostDetail(postId)
    }

    suspend fun createPost(
        authorId: String,
        subjectId: String?,
        type: String?,
        title: String,
        content: String?
    ): Post {
        val request = com.doan.studyhub.core.data.model.CreatePostRequest(
            authorId = authorId,
            subjectId = subjectId,
            type = type,
            title = title,
            content = content
        )
        return remoteDataSource.createPost(request)
    }

    suspend fun uploadPostFile(
        postId: String,
        fileName: String,
        fileBytes: ByteArray,
        fileType: String?
    ): PostFile {
        return remoteDataSource.uploadPostFile(postId, fileName, fileBytes, fileType)
    }

    suspend fun updatePost(
        postId: String,
        title: String,
        content: String?,
        type: String?,
        subjectId: String?
    ) {
        remoteDataSource.updatePost(postId, title, content, type, subjectId)
    }

    suspend fun deletePost(postId: String) {
        remoteDataSource.deletePost(postId)
    }

    suspend fun deletePostFile(fileId: String) {
        remoteDataSource.deletePostFile(fileId)
    }
}
