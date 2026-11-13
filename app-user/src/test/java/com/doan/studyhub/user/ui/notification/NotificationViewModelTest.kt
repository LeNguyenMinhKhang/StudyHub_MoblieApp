package com.doan.studyhub.user.ui.notification

import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.AppNotification
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.model.NotificationFilter
import com.doan.studyhub.core.data.model.NotificationPage
import com.doan.studyhub.core.data.model.NotificationSignal
import com.doan.studyhub.core.data.model.NotificationTarget
import com.doan.studyhub.core.data.repository.NotificationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationViewModelTest {
    private fun notice(n: Int, read: Boolean = false, post: String? = "post-$n") =
        AppNotification(id = "n%04d".format(n), type = "like", isRead = read,
            createdAt = "2026-11-06T00:00:00+00:00", postId = post)

    /** Keyset-paginating fake server; ids sort like (created_at, id) with equal timestamps. */
    private class FakeRepository : NotificationRepository() {
        var owner: String? = "actor"
        val server = mutableListOf<AppNotification>()
        val listCalls = mutableListOf<Pair<NotificationCursor?, NotificationFilter>>()
        var listGate: CompletableDeferred<Unit>? = null
        var listFailure: ApiResponse.Failure? = null
        var markResult: ApiResponse<Int>? = null
        var afterMark: () -> Unit = {}
        var openResult: ApiResponse<NotificationTarget>? = null
        var openGate: CompletableDeferred<Unit>? = null
        val opened = mutableListOf<String>()
        val signalChannel = Channel<NotificationSignal>(Channel.UNLIMITED)

        override fun currentUserId() = owner
        override fun sessionChanges(): Flow<String?> = emptyFlow()
        override fun signals(): Flow<NotificationSignal> = signalChannel.receiveAsFlow()

        override suspend fun list(cursor: NotificationCursor?, limit: Int, filter: NotificationFilter): ApiResponse<NotificationPage> {
            listCalls += cursor to filter
            val gate = listGate
            // Snapshot is taken when the request reaches the "server", before the response is delivered.
            val visible = server.filter { filter == NotificationFilter.ALL || !it.isRead }.sortedByDescending { it.id }
            val unread = server.count { !it.isRead }
            gate?.await()
            listFailure?.let { return it }
            val after = visible.filter { cursor == null || it.id < cursor.id }
            val page = after.take(limit)
            val next = if (after.size > limit) NotificationCursor(page.last().createdAt, page.last().id) else null
            return ApiResponse.Success(NotificationPage(page, next, unread), "list")
        }

        override suspend fun markRead(ids: List<String>?): ApiResponse<Int> {
            markResult?.let { return it }
            val changed = server.count { !it.isRead && (ids == null || it.id in ids) }
            server.replaceAll { if (ids == null || it.id in ids) it.copy(isRead = true) else it }
            afterMark()
            return ApiResponse.Success(changed, "mark")
        }

        override suspend fun open(id: String): ApiResponse<NotificationTarget> {
            opened += id
            openGate?.await()
            openResult?.let { return it }
            val item = server.first { it.id == id }
            server.replaceAll { if (it.id == id) it.copy(isRead = true) else it }
            return ApiResponse.Success(NotificationTarget(id, item.type, item.postId, item.actor?.id), "open")
        }
    }

    private val failure = ApiResponse.Failure(ApiError(type = ErrorType.NETWORK_UNAVAILABLE), "f")

    private suspend fun settle(times: Int = 20) = repeat(times) { yield() }

    private fun CoroutineScope.vm(repo: FakeRepository) = NotificationViewModel(repo, this)

    @Test fun refreshLoadsFirstPageAndGlobalUnreadCount() = runBlocking {
        val repo = FakeRepository().apply { (1..25).forEach { server += notice(it, read = it % 5 == 0) } }
        val vm = vm(repo)
        vm.refresh(); settle()
        val state = vm.uiState.value
        assertEquals(20, state.items.size)
        assertEquals(20, state.unreadCount)
        assertTrue(state.cursor != null && state.loaded && !state.loading)
        coroutineContext.cancelChildren()
    }

    @Test fun paginationNeverDuplicatesAndReconcileKeepsWindow() = runBlocking {
        val repo = FakeRepository().apply { (1..45).forEach { server += notice(it) } }
        val vm = vm(repo)
        vm.refresh(); settle()
        vm.loadMore(); settle()
        vm.loadMore(); settle()
        assertEquals(45, vm.uiState.value.items.map { it.id }.distinct().size)
        assertNull(vm.uiState.value.cursor)

        // Deletion on a later page and a new arrival: reconcile reflects both without resurrecting rows.
        repo.server.removeAll { it.id == "n0003" }
        repo.server += notice(46)
        vm.refresh(); settle()
        val ids = vm.uiState.value.items.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertFalse("n0003" in ids)
        assertTrue("n0046" in ids)
        assertEquals(45, ids.size)
        coroutineContext.cancelChildren()
    }

    @Test fun windowBeyondLimitIsMarkedStaleAndCanReset() = runBlocking {
        val repo = FakeRepository().apply { (1..230).forEach { server += notice(it) } }
        val vm = vm(repo)
        vm.refresh(); settle()
        repeat(11) { vm.loadMore(); settle() }
        assertEquals(230, vm.uiState.value.items.size)
        assertTrue(vm.uiState.value.windowTruncated)
        vm.refresh(); settle(60)
        assertEquals(NotificationViewModel.WINDOW_LIMIT, vm.uiState.value.items.size)
        assertTrue("reconciliation must not claim the oversized window is current", vm.uiState.value.windowTruncated)
        vm.resetWindow(); settle()
        assertEquals(20, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.windowTruncated)
        assertNull("reset never reuses an old cursor", repo.listCalls.last().first)
        coroutineContext.cancelChildren()
    }

    @Test fun signalsDuringInFlightLoadCoalesceIntoOneFollowUp() = runBlocking {
        val repo = FakeRepository().apply { server += notice(1); listGate = CompletableDeferred() }
        val vm = vm(repo)
        launch { vm.observeSignals() }
        settle()
        assertEquals(1, repo.listCalls.size)
        repo.signalChannel.send(NotificationSignal.Subscribed)
        repo.server += notice(2) // arrives after the in-flight snapshot was taken
        repeat(3) { repo.signalChannel.send(NotificationSignal.Changed) }
        repo.signalChannel.send(NotificationSignal.Disconnected)
        settle()
        repo.listGate!!.complete(Unit); repo.listGate = null
        settle(40)
        assertEquals("one initial load + one coalesced follow-up", 2, repo.listCalls.size)
        assertEquals(listOf("n0002", "n0001"), vm.uiState.value.items.map { it.id })
        assertEquals(2, vm.uiState.value.unreadCount)

        repeat(2) { repo.signalChannel.send(NotificationSignal.Changed) } // duplicates: no count drift
        settle(40)
        assertEquals(2, vm.uiState.value.unreadCount)
        coroutineContext.cancelChildren()
    }

    @Test fun markAllKeepsLaterArrivalsUnread() = runBlocking {
        val repo = FakeRepository().apply { (1..3).forEach { server += notice(it) } }
        val vm = vm(repo)
        vm.refresh(); settle()
        repo.afterMark = { repo.server += notice(4) } // commits after the server's mark-all snapshot
        vm.markAllRead()
        assertEquals(0, vm.uiState.value.unreadCount)
        settle(40)
        val state = vm.uiState.value
        assertEquals(1, state.unreadCount)
        assertFalse(state.items.first { it.id == "n0004" }.isRead)
        assertTrue(state.items.filter { it.id != "n0004" }.all { it.isRead })
        coroutineContext.cancelChildren()
    }

    @Test fun failedMarkAllRevertsAndReconciles() = runBlocking {
        val repo = FakeRepository().apply { server += notice(1); markResult = failure }
        val vm = vm(repo)
        vm.refresh(); settle()
        vm.markAllRead(); settle(40)
        assertEquals(1, vm.uiState.value.unreadCount)
        assertFalse(vm.uiState.value.items.single().isRead)
        assertEquals(ErrorType.NETWORK_UNAVAILABLE.userMessage, vm.uiState.value.message)
        assertEquals(2, repo.listCalls.size)
        coroutineContext.cancelChildren()
    }

    @Test fun openNavigatesWithServerTargetAndRecordsOpen() = runBlocking {
        val repo = FakeRepository().apply { server += notice(1, post = "post-x") }
        val vm = vm(repo)
        vm.refresh(); settle()
        vm.open(vm.uiState.value.items.single())
        assertEquals("optimistic read", 0, vm.uiState.value.unreadCount)
        assertEquals(NotificationEvent.OpenPost("post-x"), vm.events.first())
        assertEquals(listOf("n0001"), repo.opened)
        coroutineContext.cancelChildren()
    }

    @Test fun failedOpenRestoresUnreadAndDoesNotNavigate() = runBlocking {
        val repo = FakeRepository().apply {
            server += notice(1)
            openResult = ApiResponse.Failure(ApiError(type = ErrorType.NOTIFICATION_UNAVAILABLE), "o")
        }
        val vm = vm(repo)
        vm.refresh(); settle()
        vm.open(vm.uiState.value.items.single()); settle(40)
        assertFalse(vm.uiState.value.items.single().isRead)
        assertEquals(1, vm.uiState.value.unreadCount)
        assertEquals(ErrorType.NOTIFICATION_UNAVAILABLE.userMessage, vm.uiState.value.message)
        assertEquals("reconciles with the server", 2, repo.listCalls.size)
        coroutineContext.cancelChildren()
    }

    @Test fun olderListResponseCannotUndoConfirmedRead() = runBlocking {
        val repo = FakeRepository().apply { server += notice(1); server += notice(2) }
        val vm = vm(repo)
        vm.refresh(); settle()
        repo.listGate = CompletableDeferred()
        vm.refresh(); settle()                       // snapshot taken: both unread
        vm.open(vm.uiState.value.items.first { it.id == "n0002" }); settle()
        repo.listGate!!.complete(Unit); repo.listGate = null
        settle(40)
        val state = vm.uiState.value
        assertTrue("stale response must not resurrect unread", state.items.first { it.id == "n0002" }.isRead)
        assertEquals(1, state.unreadCount)
        vm.refresh(); settle(40)                      // fresh server truth after confirmation
        assertEquals(1, vm.uiState.value.unreadCount)
        coroutineContext.cancelChildren()
    }

    @Test fun recoverableFailureKeepsExistingRows() = runBlocking {
        val repo = FakeRepository().apply { server += notice(1) }
        val vm = vm(repo)
        vm.refresh(); settle()
        repo.listFailure = failure
        vm.refresh(); settle()
        assertEquals(1, vm.uiState.value.items.size)
        assertNull(vm.uiState.value.error)
        assertEquals(ErrorType.NETWORK_UNAVAILABLE.userMessage, vm.uiState.value.message)
        coroutineContext.cancelChildren()
    }

    @Test fun filterChangeResetsWindowAndUsesFilter() = runBlocking {
        val repo = FakeRepository().apply { server += notice(1, read = true); server += notice(2) }
        val vm = vm(repo)
        vm.refresh(); settle()
        vm.setFilter(NotificationFilter.UNREAD); settle()
        assertEquals(NotificationFilter.UNREAD, repo.listCalls.last().second)
        assertNull(repo.listCalls.last().first)
        assertEquals(listOf("n0002"), vm.uiState.value.items.map { it.id })
        assertEquals(1, vm.uiState.value.unreadCount)
        coroutineContext.cancelChildren()
    }

    @Test fun accountSwitchDiscardsPendingListAndState() = runBlocking {
        val repo = FakeRepository().apply { server += notice(1); listGate = CompletableDeferred() }
        val vm = vm(repo)
        vm.refresh(); settle()
        repo.owner = "another-actor"
        repo.listGate!!.complete(Unit)
        settle()
        assertTrue(vm.uiState.value.items.isEmpty())
        assertEquals(0, vm.uiState.value.unreadCount)
        coroutineContext.cancelChildren()
    }

    @Test fun lateOpenFromPreviousAccountIsIgnored() = runBlocking {
        val repo = FakeRepository().apply { server += notice(1); openGate = CompletableDeferred() }
        val vm = vm(repo)
        vm.refresh(); settle()
        vm.open(vm.uiState.value.items.single()); settle()
        repo.owner = null
        vm.refresh() // observes the logout
        repo.openGate!!.complete(Unit); settle()
        assertTrue(vm.uiState.value.items.isEmpty())
        assertNull(vm.uiState.value.message)
        coroutineContext.cancelChildren()
    }
}
