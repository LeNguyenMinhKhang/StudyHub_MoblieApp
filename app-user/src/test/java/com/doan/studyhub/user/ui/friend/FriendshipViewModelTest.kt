package com.doan.studyhub.user.ui.friend

import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.FriendshipAction
import com.doan.studyhub.core.data.model.FriendshipSnapshot
import com.doan.studyhub.core.data.model.FriendshipState
import com.doan.studyhub.core.data.repository.FriendshipRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class FriendshipViewModelTest {
    private class FakeRepository : FriendshipRepository() {
        var owner: String? = "actor"
        var snapshot = FriendshipSnapshot("peer", FriendshipState.NONE)
        var calls = 0
        val result = CompletableDeferred<ApiResponse<FriendshipSnapshot>>()
        override fun currentUserId() = owner
        override fun sessionChanges() = emptyFlow<String?>()
        override suspend fun state(target: String) = ApiResponse.Success(snapshot, "read")
        override suspend fun mutate(action: FriendshipAction, id: String): ApiResponse<FriendshipSnapshot> {
            calls++
            return result.await()
        }
    }

    @Test fun duplicateTapSendsOnceAndShowsCancelAfterSuccess() = runBlocking {
        val repo = FakeRepository()
        val vm = FriendshipViewModel(repo, this)
        vm.show("peer")
        yield()
        assertEquals(listOf(FriendshipAction.SEND), vm.uiState.value.actions)
        vm.act(FriendshipAction.SEND)
        vm.act(FriendshipAction.SEND)
        yield()
        assertEquals(1, repo.calls)
        assertTrue(vm.uiState.value.actions.isEmpty())
        repo.result.complete(ApiResponse.Success(FriendshipSnapshot("peer", FriendshipState.OUTGOING_PENDING, "relation"), "send"))
        yield()
        assertEquals(listOf(FriendshipAction.CANCEL), vm.uiState.value.actions)
    }

    @Test fun failedSendReadsServerStateInsteadOfSendingAgain() = runBlocking {
        val repo = FakeRepository()
        val vm = FriendshipViewModel(repo, this)
        vm.show("peer")
        yield()
        vm.act(FriendshipAction.SEND)
        yield()
        repo.snapshot = FriendshipSnapshot("peer", FriendshipState.OUTGOING_PENDING, "relation")
        repo.result.complete(ApiResponse.Failure(ApiError(type = ErrorType.UNKNOWN_ERROR), "failure"))
        yield()
        assertEquals(1, repo.calls)
        assertEquals(listOf(FriendshipAction.CANCEL), vm.uiState.value.actions)
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun accountSwitchDiscardsPendingMutationResponse() = runBlocking {
        val repo = FakeRepository()
        val vm = FriendshipViewModel(repo, this)
        vm.show("peer")
        yield()
        vm.act(FriendshipAction.SEND)
        yield()
        repo.owner = "another-actor"
        repo.result.complete(ApiResponse.Success(FriendshipSnapshot("peer", FriendshipState.OUTGOING_PENDING, "relation"), "send"))
        yield()
        assertNull(vm.uiState.value.snapshot)
        assertTrue(vm.uiState.value.actions.isEmpty())
    }

    @Test fun explicitOwnProfileHasNoActions() = runBlocking {
        val vm = FriendshipViewModel(FakeRepository(), this)
        vm.show("actor")
        assertEquals(FriendshipState.SELF, vm.uiState.value.snapshot?.state)
        assertTrue(vm.uiState.value.actions.isEmpty())
    }
}
