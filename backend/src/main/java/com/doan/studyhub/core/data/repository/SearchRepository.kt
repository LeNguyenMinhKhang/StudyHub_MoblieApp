package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.DomainException
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.Operation
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Post
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.SearchPage
import com.doan.studyhub.core.data.model.SearchResult
import com.doan.studyhub.core.data.model.SearchTab
import com.doan.studyhub.core.data.remote.PostRemoteDataSource
import com.doan.studyhub.core.data.remote.SearchRemoteDataSource
import com.doan.studyhub.core.data.remote.SubjectRemoteDataSource
import io.github.jan.supabase.auth.auth

open class SearchRepository(
    private val remote: SearchRemoteDataSource = SearchRemoteDataSource(),
    private val enrichPosts: suspend (List<Post>) -> List<PostDetail> = { PostRemoteDataSource().enrichPosts(it) },
    private val followedSubjectIds: suspend (userId: String) -> Set<String> = {
        SubjectRemoteDataSource().getFollowedSubjectIds(it).toSet()
    },
    private val setSubjectFollow: suspend (userId: String, subjectId: String, follow: Boolean) -> Unit = { userId, subjectId, follow ->
        val subjects = SubjectRemoteDataSource()
        if (follow) subjects.followSubject(userId, subjectId) else subjects.unfollowSubject(userId, subjectId)
    },
    private val api: SafeApiCall = SafeApiCall(),
    private val session: () -> String? = { SupabaseClient.client.auth.currentUserOrNull()?.id }
) {
    open fun currentUserId(): String? = session()

    /** Returns one ranked page; queries shorter than [MIN_QUERY_LENGTH] yield an empty page without a request. */
    open suspend fun search(
        tab: SearchTab,
        query: String,
        offset: Int = 0,
        limit: Int = PAGE_SIZE
    ): ApiResponse<SearchPage<SearchResult>> = api.execute(Operation.SEARCH) {
        val owner = currentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
        val trimmed = query.trim()
        if (trimmed.length > MAX_QUERY_LENGTH || offset < 0 || limit <= 0) throw DomainException(ErrorType.VALIDATION_FAILED)
        if (trimmed.length < MIN_QUERY_LENGTH) return@execute SearchPage.empty()
        val size = limit.coerceAtMost(MAX_PAGE_SIZE)
        val page: SearchPage<SearchResult> = when (tab) {
            SearchTab.USERS -> remote.profiles(trimmed, size, offset).let { raw ->
                SearchPage<SearchResult>(raw.items.map { SearchResult.User(it) }, raw.hasMore)
            }
            SearchTab.POSTS -> remote.posts(trimmed, size, offset).let { raw ->
                SearchPage<SearchResult>(enrichPosts(raw.items).map { SearchResult.PostResult(it) }, raw.hasMore)
            }
            SearchTab.SUBJECTS -> remote.subjects(trimmed, size, offset).let { raw ->
                val followed = if (raw.items.isEmpty()) emptySet() else followedSubjectIds(owner)
                SearchPage<SearchResult>(raw.items.map { SearchResult.SubjectResult(it, it.id in followed) }, raw.hasMore)
            }
            SearchTab.DOCUMENTS -> remote.documents(trimmed, size, offset).let { raw ->
                SearchPage<SearchResult>(raw.items.map { SearchResult.Document(it) }, raw.hasMore)
            }
        }
        if (currentUserId() != owner) throw DomainException(ErrorType.UNAUTHENTICATED)
        page
    }

    /** Follows or unfollows a subject with the existing subject_follows behavior. */
    open suspend fun setFollowed(subjectId: String, follow: Boolean): ApiResponse<Unit> = api.execute(Operation.SUBJECT_FOLLOW) {
        val owner = currentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
        if (subjectId.isBlank()) throw DomainException(ErrorType.VALIDATION_FAILED)
        setSubjectFollow(owner, subjectId, follow)
    }

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 100
    }
}
