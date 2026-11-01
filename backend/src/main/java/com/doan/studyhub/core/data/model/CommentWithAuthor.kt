package com.doan.studyhub.core.data.model

data class CommentWithAuthor(
    val comment: Comment,
    val author: PublicProfile?
)
