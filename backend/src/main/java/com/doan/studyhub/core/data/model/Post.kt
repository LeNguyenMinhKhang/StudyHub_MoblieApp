package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Model đại diện cho 1 dòng trong bảng "posts" trên Supabase
@Serializable
data class Post(
    @SerialName("id") val id: String? = null,
    @SerialName("author_id") val authorId: String,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String? = null,
    @SerialName("best_comment_id") val bestCommentId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class CreatePostRequest(
    @SerialName("author_id") val authorId: String,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String? = null
)

object PostTypeUtils {
    const val VISIBILITY_PUBLIC = "public"
    const val VISIBILITY_FRIENDS = "friends"
    const val VISIBILITY_PRIVATE = "private"

    const val CATEGORY_NONE = "none"
    const val CATEGORY_KNOWLEDGE = "knowledge"
    const val CATEGORY_QUESTION = "question"
    const val CATEGORY_DOCUMENT = "document"

    fun parse(rawType: String?): Pair<String, String> {
        if (rawType.isNullOrBlank()) return Pair(CATEGORY_NONE, VISIBILITY_PUBLIC)
        val parts = rawType.split(":")
        val categoryPart = parts[0].trim().lowercase()
        val visibilityPart = if (parts.size > 1) {
            parts[1].trim().lowercase()
        } else {
            if (categoryPart in listOf(VISIBILITY_PUBLIC, VISIBILITY_FRIENDS, VISIBILITY_PRIVATE)) {
                categoryPart
            } else {
                VISIBILITY_PUBLIC
            }
        }
        val cleanCategory = if (categoryPart in listOf(CATEGORY_KNOWLEDGE, CATEGORY_QUESTION, CATEGORY_DOCUMENT)) {
            categoryPart
        } else {
            CATEGORY_NONE
        }
        val cleanVisibility = if (visibilityPart in listOf(VISIBILITY_FRIENDS, VISIBILITY_PRIVATE)) {
            visibilityPart
        } else {
            VISIBILITY_PUBLIC
        }
        return Pair(cleanCategory, cleanVisibility)
    }

    fun format(category: String?, visibility: String?): String {
        val cat = if (category.isNullOrBlank() || category == CATEGORY_NONE) CATEGORY_NONE else category.trim().lowercase()
        val vis = if (visibility.isNullOrBlank() || visibility == VISIBILITY_PUBLIC) VISIBILITY_PUBLIC else visibility.trim().lowercase()
        return "$cat:$vis"
    }
}
