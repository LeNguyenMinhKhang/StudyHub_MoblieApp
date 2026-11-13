package com.doan.studyhub.user.ui.settings

import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.NotificationCategory
import com.doan.studyhub.core.data.model.NotificationPreferences
import com.doan.studyhub.core.data.repository.NotificationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsViewModelTest {
    private class FakeRepository : NotificationRepository() {
        var owner: String? = "b"
        var server = NotificationPreferences()
        var applyThenFail = false
        var failSave = false
        var loadGate: CompletableDeferred<Unit>? = null
        var reads = 0
        override fun currentUserId() = owner
        override fun sessionChanges(): Flow<String?> = emptyFlow()
        override suspend fun preferences(): ApiResponse<NotificationPreferences> {
            reads++
            loadGate?.await()
            return ApiResponse.Success(server, "get")
        }
        override suspend fun setPreference(category: NotificationCategory, enabled: Boolean): ApiResponse<NotificationPreferences> {
            if (failSave) return ApiResponse.Failure(ApiError(type = ErrorType.NETWORK_UNAVAILABLE), "set")
            server = when (category) {
                NotificationCategory.FRIENDSHIP -> server.copy(friendship = enabled)
                NotificationCategory.LIKE -> server.copy(like = enabled)
                NotificationCategory.COMMENT -> server.copy(comment = enabled)
            }
            // Ambiguous timeout: the server committed but the client never saw the response.
            if (applyThenFail) return ApiResponse.Failure(ApiError(type = ErrorType.REQUEST_TIMEOUT), "set")
            return ApiResponse.Success(server, "set")
        }
    }

    private suspend fun settle() = repeat(20) { yield() }

    @Test fun loadsServerDefaults() = runBlocking {
        val repo = FakeRepository()
        val vm = NotificationSettingsViewModel(repo, this)
        vm.load(); settle()
        assertEquals(NotificationPreferences(), vm.uiState.value.confirmed)
        coroutineContext.cancelChildren()
    }

    @Test fun successfulSaveUpdatesConfirmedState() = runBlocking {
        val repo = FakeRepository()
        val vm = NotificationSettingsViewModel(repo, this)
        vm.load(); settle()
        vm.setEnabled(NotificationCategory.LIKE, false)
        assertEquals(false, vm.uiState.value.saving[NotificationCategory.LIKE])
        assertEquals("unconfirmed value is not shown as saved", true, vm.uiState.value.confirmed?.like)
        settle()
        assertEquals(false, vm.uiState.value.confirmed?.like)
        assertTrue(vm.uiState.value.saving.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test fun failedSaveKeepsLastConfirmedAndReportsError() = runBlocking {
        val repo = FakeRepository().apply { failSave = true }
        val vm = NotificationSettingsViewModel(repo, this)
        vm.load(); settle()
        vm.setEnabled(NotificationCategory.COMMENT, false); settle()
        assertEquals(true, vm.uiState.value.confirmed?.comment)
        assertEquals(ErrorType.NETWORK_UNAVAILABLE.userMessage, vm.uiState.value.error)
        assertTrue(vm.uiState.value.saving.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test fun ambiguousTimeoutReconcilesWithServer() = runBlocking {
        val repo = FakeRepository().apply { applyThenFail = true }
        val vm = NotificationSettingsViewModel(repo, this)
        vm.load(); settle()
        vm.setEnabled(NotificationCategory.FRIENDSHIP, false); settle()
        assertEquals(2, repo.reads)
        assertEquals("re-read shows what the server actually stored", false, vm.uiState.value.confirmed?.friendship)
        coroutineContext.cancelChildren()
    }

    @Test fun sessionChangeClearsStateAndDropsLateResponse() = runBlocking {
        val repo = FakeRepository().apply { loadGate = CompletableDeferred() }
        val vm = NotificationSettingsViewModel(repo, this)
        vm.load(); settle()
        repo.owner = "c"
        repo.server = NotificationPreferences(like = false)
        repo.loadGate!!.complete(Unit); settle()
        assertNull(vm.uiState.value.confirmed)
        coroutineContext.cancelChildren()
    }
}
