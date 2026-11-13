package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorLogger
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.model.DocumentSearchItem
import com.doan.studyhub.core.data.model.Post
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.ProfileSearchItem
import com.doan.studyhub.core.data.model.SearchPage
import com.doan.studyhub.core.data.model.SearchResult
import com.doan.studyhub.core.data.model.SearchTab
import com.doan.studyhub.core.data.model.Subject
import com.doan.studyhub.core.data.remote.SearchRemoteDataSource
import com.doan.studyhub.core.data.remote.decodeSearchPage
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {
    private class FakeRemote : SearchRemoteDataSource() {
        val calls = mutableListOf<String>()
        var failure: Exception? = null
        var posts = SearchPage(listOf(post("p2"), post("p1"), post("p3")), hasMore = true)
        var subjects = SearchPage(listOf(Subject("s1", "Giải tích"), Subject("s2", "Đại số")), hasMore = false)
        var onCall: () -> Unit = {}

        private fun record(tab: String, query: String, limit: Int, offset: Int) {
            calls += "$tab:$query:$limit:$offset"
            onCall()
            failure?.let { throw it }
        }

        override suspend fun profiles(query: String, limit: Int, offset: Int): SearchPage<ProfileSearchItem> {
            record("users", query, limit, offset)
            return SearchPage(listOf(ProfileSearchItem("u1", "nguyenvana", "Nguyễn Văn An")), hasMore = false)
        }

        override suspend fun posts(query: String, limit: Int, offset: Int): SearchPage<Post> {
            record("posts", query, limit, offset)
            return posts
        }

        override suspend fun subjects(query: String, limit: Int, offset: Int): SearchPage<Subject> {
            record("subjects", query, limit, offset)
            return subjects
        }

        override suspend fun documents(query: String, limit: Int, offset: Int): SearchPage<DocumentSearchItem> {
            record("documents", query, limit, offset)
            return SearchPage(listOf(DocumentSearchItem("f1", "giai-tich.pdf", postId = "p1", postTitle = "Ôn thi")), hasMore = true)
        }
    }

    private val quietApi = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> })

    private fun repository(
        remote: FakeRemote = FakeRemote(),
        owner: () -> String? = { "viewer" },
        followed: Set<String> = emptySet(),
        followLookups: MutableList<String> = mutableListOf(),
        followWrites: MutableList<String> = mutableListOf()
    ) = SearchRepository(
        remote = remote,
        enrichPosts = { posts -> posts.map { PostDetail(post = it, likeCount = it.title.length) } },
        followedSubjectIds = { user -> followLookups += user; followed },
        setSubjectFollow = { user, subject, follow -> followWrites += "$user:$subject:$follow" },
        api = quietApi,
        session = owner
    )

    @Test fun `short query returns an empty page without a request`() = runBlocking {
        val remote = FakeRemote()
        val result = repository(remote).search(SearchTab.POSTS, "  a ")
        assertEquals(SearchPage.empty<SearchResult>(), (result as ApiResponse.Success).data)
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun `oversized query and invalid paging are rejected locally`() = runBlocking {
        val remote = FakeRemote()
        val repo = repository(remote)
        val tooLong = repo.search(SearchTab.USERS, "a".repeat(101))
        val negative = repo.search(SearchTab.USERS, "toan", offset = -1)
        assertEquals(ErrorType.VALIDATION_FAILED, (tooLong as ApiResponse.Failure).error.type)
        assertEquals(ErrorType.VALIDATION_FAILED, (negative as ApiResponse.Failure).error.type)
        assertTrue(remote.calls.isEmpty())
        assertTrue(repo.search(SearchTab.USERS, "a".repeat(100)) is ApiResponse.Success)
    }

    @Test fun `missing session fails before any request`() = runBlocking {
        val remote = FakeRemote()
        val result = repository(remote, owner = { null }).search(SearchTab.USERS, "nguyen")
        assertEquals(ErrorType.UNAUTHENTICATED, (result as ApiResponse.Failure).error.type)
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun `account switch during a request discards the result`() = runBlocking {
        var owner: String? = "viewer"
        val remote = FakeRemote().apply { onCall = { owner = "other" } }
        val result = repository(remote, owner = { owner }).search(SearchTab.USERS, "nguyen")
        assertEquals(ErrorType.UNAUTHENTICATED, (result as ApiResponse.Failure).error.type)
    }

    @Test fun `network failure maps to a safe retryable error`() = runBlocking {
        val remote = FakeRemote().apply { failure = UnknownHostException("db.internal secret") }
        val result = repository(remote).search(SearchTab.DOCUMENTS, "giai tich")
        val error = (result as ApiResponse.Failure).error
        assertEquals(ErrorType.NETWORK_UNAVAILABLE, error.type)
        assertTrue(error.retryable)
        assertFalse(error.message.contains("secret"))
    }

    @Test fun `query is trimmed and page size capped`() = runBlocking {
        val remote = FakeRemote()
        repository(remote).search(SearchTab.USERS, "  nguyen van  ", offset = 40, limit = 500)
        assertEquals(listOf("users:nguyen van:50:40"), remote.calls)
    }

    @Test fun `posts keep server rank order after enrichment`() = runBlocking {
        val result = repository().search(SearchTab.POSTS, "giai tich")
        val page = (result as ApiResponse.Success).data
        assertEquals(listOf("p2", "p1", "p3"), page.items.map { it.key })
        assertTrue(page.hasMore)
        assertTrue(page.items.all { it is SearchResult.PostResult })
    }

    @Test fun `subjects carry the viewer follow state`() = runBlocking {
        val lookups = mutableListOf<String>()
        val result = repository(followed = setOf("s2"), followLookups = lookups).search(SearchTab.SUBJECTS, "toan")
        val items = (result as ApiResponse.Success).data.items.map { it as SearchResult.SubjectResult }
        assertEquals(listOf(false, true), items.map { it.followed })
        assertEquals(listOf("viewer"), lookups)
    }

    @Test fun `empty subject page skips the follow lookup`() = runBlocking {
        val lookups = mutableListOf<String>()
        val remote = FakeRemote().apply { subjects = SearchPage.empty() }
        val result = repository(remote, followLookups = lookups).search(SearchTab.SUBJECTS, "khong co")
        assertTrue((result as ApiResponse.Success).data.items.isEmpty())
        assertTrue(lookups.isEmpty())
    }

    @Test fun `follow toggle uses the session owner`() = runBlocking {
        val writes = mutableListOf<String>()
        val repo = repository(followWrites = writes)
        assertTrue(repo.setFollowed("s1", follow = true) is ApiResponse.Success)
        assertTrue(repo.setFollowed("s1", follow = false) is ApiResponse.Success)
        assertEquals(listOf("viewer:s1:true", "viewer:s1:false"), writes)
        val anonymous = repository(owner = { null }).setFollowed("s1", follow = true)
        assertEquals(ErrorType.UNAUTHENTICATED, (anonymous as ApiResponse.Failure).error.type)
    }

    @Test fun `rpc payloads decode with has_more and unknown columns`() {
        val posts = decodeSearchPage(
            """{"items":[{"id":"p1","author_id":"a1","title":"Giải tích","type":"question:public","extra_column":1}],"has_more":true}""",
            Post.serializer()
        )
        assertEquals("p1", posts.items.single().id)
        assertTrue(posts.hasMore)

        val documents = decodeSearchPage(
            """{"items":[{"id":"f1","file_name":"a.pdf","file_type":null,"file_size":null,"created_at":"2026-11-06T10:00:00+00:00","post_id":"p1","post_title":"Ôn thi","author_display_name":"A"}],"has_more":false}""",
            DocumentSearchItem.serializer()
        )
        assertNull(documents.items.single().fileSize)
        assertFalse(documents.hasMore)

        val empty = decodeSearchPage("""{"items":[],"has_more":false}""", ProfileSearchItem.serializer())
        assertTrue(empty.items.isEmpty())
    }

    private companion object {
        fun post(id: String) = Post(id = id, authorId = "a1", title = "Bài $id")
    }
}
