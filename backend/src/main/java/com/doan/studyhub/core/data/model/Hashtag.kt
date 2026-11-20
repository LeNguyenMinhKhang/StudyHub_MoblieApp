package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Hashtag(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String
)

@Serializable
data class PostHashtag(
    @SerialName("post_id") val postId: String,
    @SerialName("hashtag_id") val hashtagId: String
)

@Serializable
data class SavedPost(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("post_id") val postId: String,
    @SerialName("created_at") val createdAt: String? = null
)

object HashtagUtils {
    /**
     * Chuẩn hóa hashtag:
     * - Cắt khoảng trắng đầu và cuối
     * - Loại bỏ dấu '#' ở đầu
     * - Chuyển sang chữ thường
     * - Giữ lại chữ cái (bao gồm tiếng Việt), chữ số và dấu gạch dưới
     * - Giới hạn tối đa 50 ký tự
     */
    fun normalize(rawTag: String): String {
        val trimmed = rawTag.trim().removePrefix("#").lowercase()
        val cleaned = trimmed.replace(Regex("[^\\p{L}\\p{Nd}_]"), "")
        return cleaned.take(50)
    }

    /**
     * Chuẩn hóa danh sách tags, loại bỏ tag rỗng và trùng lặp, tối đa 10 tags.
     */
    fun normalizeList(rawTags: List<String>): List<String> {
        return rawTags.map { normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
    }
}

