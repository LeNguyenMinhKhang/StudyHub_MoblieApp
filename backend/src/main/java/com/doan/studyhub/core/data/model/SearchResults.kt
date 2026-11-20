package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Search tabs in screen order, each backed by one authenticated-only RPC. */
enum class SearchTab(val rpc: String) {
    USERS("search_profiles"),
    POSTS("search_posts"),
    SUBJECTS("search_subjects"),
    DOCUMENTS("search_documents")
}

/** One ranked page as returned by the search RPCs: `{items, has_more}`. */
@Serializable
data class SearchPage<T>(
    val items: List<T> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false
) {
    companion object {
        fun <T> empty(): SearchPage<T> = SearchPage(emptyList(), false)
    }
}

@Serializable
data class ProfileSearchItem(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class DocumentSearchItem(
    val id: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_type") val fileType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("post_id") val postId: String,
    @SerialName("post_title") val postTitle: String,
    @SerialName("author_display_name") val authorDisplayName: String? = null
)

/** A displayable search result; [key] is unique within its tab for de-duplication. */
sealed interface SearchResult {
    val key: String

    data class User(val profile: ProfileSearchItem) : SearchResult {
        override val key get() = profile.id
    }

    data class PostResult(val detail: PostDetail) : SearchResult {
        override val key get() = detail.post.id.orEmpty()
    }

    data class SubjectResult(val subject: Subject, val followed: Boolean) : SearchResult {
        override val key get() = subject.id
    }

    data class Document(val document: DocumentSearchItem) : SearchResult {
        override val key get() = document.id
    }
}
