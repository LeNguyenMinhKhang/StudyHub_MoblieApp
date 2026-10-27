package com.doan.studyhub.user.ui.notification

import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.AppNotification
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.model.NotificationPage
import com.doan.studyhub.core.data.repository.NotificationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NotificationViewModelTest {
    private fun notice(id: String, read: Boolean = false) =
        AppNotification(id, "friend_request", read, "2026-10-27T12:00:00+00:00")

    private class FakeRepository : NotificationRepository() {
        var owner: String? = "actor"
        var page = NotificationPage(emptyList())
        var lists = 0
        var pendingList: CompletableDeferred<Unit>? = null
        var markResult: ApiResponse<Int> = ApiResponse.Success(1, "mark")
        val marked = mutableListOf<List<String>?>()
        override fun currentUserId() = owner
        override fun sessionChanges() = emptyFlow<String?>()
        override suspend fun list(cursor: NotificationCursor?, limit: Int): ApiResponse<NotificationPage> {
            lists++
            pendingList?.await()
            return ApiResponse.Success(page, "list")
        }
        override suspend fun markRead(ids: List<String>?): ApiResponse<Int> {
            marked += ids
            return markResult
        }
    }

    @Test fun markReadUpdatesUnreadCountLocally() = runBlocking {
        val repo = FakeRepository().apply { page = NotificationPage(listOf(notice("a"), notice("b")), unreadCount = 2) }
        val vm = NotificationViewModel(repo, this)
        vm.refresh()
        yield()
        vm.markRead(vm.uiState.value.items.first())
        yield()
        assertEquals(1, vm.uiState.value.unreadCount)
        assertTrue(vm.uiState.value.items.first().isRead)
        assertEquals(listOf(listOf("a")), repo.marked)
    }

    @Test fun failedMarkAllReadsServerStateAgain() = runBlocking {
        val repo = FakeRepository().apply {
            page = NotificationPage(listOf(notice("a")), unreadCount = 1)
            markResult = ApiResponse.Failure(ApiError(type = ErrorType.UNKNOWN_ERROR), "mark")
        }
        val vm = NotificationViewModel(repo, this)
        vm.refresh()
        yield()
        vm.markAllRead()
        yield()
        yield()
        assertEquals(2, repo.lists)
        assertEquals(1, vm.uiState.value.unreadCount)
    }

    @Test fun accountSwitchDiscardsPendingList() = runBlocking {
        val repo = FakeRepository().apply {
            page = NotificationPage(listOf(notice("a")), unreadCount = 1)
            pendingList = CompletableDeferred()
        }
        val vm = NotificationViewModel(repo, this)
        vm.refresh()
        yield()
        repo.owner = "another-actor"
        repo.pendingList?.complete(Unit)
        yield()
        assertTrue(vm.uiState.value.items.isEmpty())
        assertEquals(0, vm.uiState.value.unreadCount)
    }
}
