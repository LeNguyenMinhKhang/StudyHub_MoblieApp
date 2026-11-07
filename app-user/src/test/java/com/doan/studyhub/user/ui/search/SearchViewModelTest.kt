package com.doan.studyhub.user.ui.search

import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.ProfileSearchItem
import com.doan.studyhub.core.data.model.SearchPage
import com.doan.studyhub.core.data.model.SearchResult
import com.doan.studyhub.core.data.model.SearchTab
import com.doan.studyhub.core.data.model.Subject
import com.doan.studyhub.core.data.repository.SearchRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchViewModelTest {
    private class FakeRepository : SearchRepository(session = { "viewer" }) {
        val calls = mutableListOf<String>()
        val pending = ArrayDeque<CompletableDeferred<ApiResponse<SearchPage<SearchResult>>>>()
        var hold = false
        var followResult: ApiResponse<Unit> = ApiResponse.Success(Unit, "follow")
        var responder: (SearchTab, String, Int) -> ApiResponse<SearchPage<SearchResult>> = { _, query, offset ->
            ApiResponse.Success(SearchPage(users(query, offset, 20), hasMore = true), "ok")
        }

        override fun currentUserId() = "viewer"

        override suspend fun search(tab: SearchTab, query: String, offset: Int, limit: Int): ApiResponse<SearchPage<SearchResult>> {
            calls += "${tab.name}:$query:$offset"
            if (!hold) return responder(tab, query, offset)
            val deferred = CompletableDeferred<ApiResponse<SearchPage<SearchResult>>>()
            pending.addLast(deferred)
            return deferred.await()
        }

        override suspend fun setFollowed(subjectId: String, follow: Boolean): ApiResponse<Unit> {
            calls += "follow:$subjectId:$follow"
            return followResult
        }
    }

    private fun failure(type: ErrorType = ErrorType.NETWORK_UNAVAILABLE) =
        ApiResponse.Failure(ApiError(type, detailMessage = "raw provider text"), "fail")

    @Test fun typingQuicklyRequestsOnlyTheFinalQuery() = runBlocking {
        val repo = FakeRepository()
        val vm = SearchViewModel(repo, this, debounceMs = 40)
        vm.onQueryChanged("g")
        vm.onQueryChanged("gi")
        vm.onQueryChanged("gia")
        delay(120)
        assertEquals(listOf("USERS:gia:0"), repo.calls)
        assertEquals("gia", vm.uiState.value.query)
        assertEquals(SearchPhase.CONTENT, vm.uiState.value.phase)
    }

    @Test fun staleResponseForAnOldQueryIsDiscarded() = runBlocking {
        val repo = FakeRepository().apply { hold = true }
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.submit("toan")
        yield()
        vm.submit("toan cao")
        yield()
        val (old, latest) = repo.pending.toList()
        latest.complete(ApiResponse.Success(SearchPage(users("latest", 0, 1), false), "new"))
        old.complete(ApiResponse.Success(SearchPage(users("old", 0, 1), false), "old"))
        yield()
        assertEquals(listOf("latest-0"), vm.uiState.value.tab.items.map { it.key })
        coroutineContext.cancelChildren()
    }

    @Test fun tabsLoadLazilyAndKeepTheirResults() = runBlocking {
        val repo = FakeRepository()
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.submit("toan")
        yield()
        vm.selectTab(SearchTab.POSTS)
        yield()
        vm.selectTab(SearchTab.USERS)
        yield()
        assertEquals(listOf("USERS:toan:0", "POSTS:toan:0"), repo.calls)
        assertEquals(20, vm.uiState.value.tab.items.size)
    }

    @Test fun newQueryResetsEveryTab() = runBlocking {
        val repo = FakeRepository()
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.submit("toan")
        yield()
        vm.selectTab(SearchTab.POSTS)
        yield()
        vm.submit("ly")
        yield()
        assertFalse(vm.uiState.value.tabs.getValue(SearchTab.USERS).loaded)
        assertEquals(SearchTab.POSTS, vm.uiState.value.selected)
        assertEquals("POSTS:ly:0", repo.calls.last())
    }

    @Test fun shortQueryIsIdleWithoutRequests() = runBlocking {
        val repo = FakeRepository()
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.submit(" a ")
        yield()
        assertTrue(repo.calls.isEmpty())
        assertEquals(SearchPhase.IDLE, vm.uiState.value.phase)
    }

    @Test fun emptyResultShowsEmptyPhase() = runBlocking {
        val repo = FakeRepository().apply { responder = { _, _, _ -> ApiResponse.Success(SearchPage.empty(), "e") } }
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.submit("khong co")
        yield()
        assertEquals(SearchPhase.EMPTY, vm.uiState.value.phase)
    }

    @Test fun firstPageErrorShowsSafeMessageAndRetryReloads() = runBlocking {
        val repo = FakeRepository()
        repo.responder = { _, _, _ -> failure() }
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.submit("toan")
        yield()
        assertEquals(SearchPhase.ERROR, vm.uiState.value.phase)
        assertEquals(ErrorType.NETWORK_UNAVAILABLE.userMessage, vm.uiState.value.tab.error)
        repo.responder = { _, query, offset -> ApiResponse.Success(SearchPage(users(query, offset, 3), false), "ok") }
        vm.retry()
        yield()
        assertEquals(SearchPhase.CONTENT, vm.uiState.value.phase)
        assertEquals(listOf("USERS:toan:0", "USERS:toan:0"), repo.calls)
    }

    @Test fun loadMoreFailureKeepsResultsAndRetriesTheSamePage() = runBlocking {
        val repo = FakeRepository()
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.submit("toan")
        yield()
        repo.responder = { _, _, _ -> failure() }
        vm.loadMore()
        yield()
        val tab = vm.uiState.value.tab
        assertEquals(20, tab.items.size)
        assertEquals(ErrorType.NETWORK_UNAVAILABLE.userMessage, tab.loadMoreError)
        assertEquals(SearchPhase.CONTENT, vm.uiState.value.phase)
        repo.responder = { _, query, offset -> ApiResponse.Success(SearchPage(users(query, offset, 5), false), "ok") }
        vm.retry()
        yield()
        assertEquals(listOf("USERS:toan:0", "USERS:toan:20", "USERS:toan:20"), repo.calls)
        assertEquals(25, vm.uiState.value.tab.items.size)
        assertNull(vm.uiState.value.tab.loadMoreError)
        assertFalse(vm.uiState.value.tab.hasMore)
    }

    @Test fun loadMoreIsSingleFlightAndDeduplicates() = runBlocking {
        val repo = FakeRepository()
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.submit("toan")
        yield()
        repo.responder = { _, query, _ ->
            // Data shifted: the second page repeats the last item of the first page.
            ApiResponse.Success(SearchPage(users(query, 19, 3), false), "ok")
        }
        vm.loadMore()
        vm.loadMore()
        yield()
        assertEquals(2, repo.calls.size)
        val keys = vm.uiState.value.tab.items.map { it.key }
        assertEquals(keys.distinct(), keys)
        assertEquals(22, keys.size)
    }

    @Test fun followToggleUpdatesSubjectState() = runBlocking {
        val repo = FakeRepository().apply {
            responder = { _, _, _ ->
                ApiResponse.Success(SearchPage<SearchResult>(listOf(SearchResult.SubjectResult(Subject("s1", "Giải tích"), false)), false), "ok")
            }
        }
        val vm = SearchViewModel(repo, this, debounceMs = 0)
        vm.selectTab(SearchTab.SUBJECTS)
        vm.submit("giai tich")
        yield()
        vm.toggleFollow("s1")
        vm.toggleFollow("s1")
        yield()
        assertEquals(listOf("SUBJECTS:giai tich:0", "follow:s1:true"), repo.calls)
        val subject = vm.uiState.value.tab.items.single() as SearchResult.SubjectResult
        assertTrue(subject.followed)
        assertTrue(vm.uiState.value.followBusy.isEmpty())

        repo.followResult = failure(ErrorType.PERMISSION_DENIED)
        vm.toggleFollow("s1")
        yield()
        assertTrue((vm.uiState.value.tab.items.single() as SearchResult.SubjectResult).followed)
        assertEquals(ErrorType.PERMISSION_DENIED.userMessage, vm.uiState.value.message)
    }

    private companion object {
        fun users(query: String, offset: Int, count: Int): List<SearchResult> = (0 until count).map { index ->
            val position = offset + index
            SearchResult.User(ProfileSearchItem("$query-$position", "u$position", "User $position"))
        }
    }
}
