package com.doan.studyhub.core.data.repository

import com.doan.studyhub.core.data.model.Comment
import com.doan.studyhub.core.data.model.CommentReactionSummary
import com.doan.studyhub.core.data.model.CommentReactor
import com.doan.studyhub.core.data.remote.CommentRemoteDataSource

class CommentRepository(
    private val remoteDataSource: CommentRemoteDataSource =
        CommentRemoteDataSource()
) {

    suspend fun getComments(postId: String): List<Comment> {
        return remoteDataSource.getComments(postId)
    }

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

    suspend fun updateComment(
        commentId: String,
        content: String
    ) {
        remoteDataSource.updateComment(
            commentId = commentId,
            content = content
        )
    }

    suspend fun deleteComment(commentId: String) {
        remoteDataSource.deleteComment(commentId)
    }

    suspend fun markBestAnswer(
        postId: String,
        commentId: String?
    ) {
        remoteDataSource.markBestAnswer(
            postId = postId,
            commentId = commentId
        )
    }

    suspend fun setCommentReaction(
        commentId: String,
        reactionType: String?
    ): String? {
        return remoteDataSource.setCommentReaction(
            commentId = commentId,
            reactionType = reactionType
        )
    }

    suspend fun getCommentReactionSummaries(
        commentIds: List<String>
    ): Map<String, CommentReactionSummary> {
        return remoteDataSource.getCommentReactionSummaries(commentIds)
    }

    suspend fun getCommentReactors(
        commentId: String,
        reactionType: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): List<CommentReactor> {
        return remoteDataSource.getCommentReactors(
            commentId = commentId,
            reactionType = reactionType,
            limit = limit,
            offset = offset
        )
    }
}