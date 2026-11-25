package com.doan.studyhub.user.ui.chat

import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.ChatMessage
import com.doan.studyhub.core.data.model.GroupConversation
import com.doan.studyhub.core.data.model.MessageSender
import com.doan.studyhub.core.data.repository.ChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {
    private fun ok(message: ChatMessage) = ApiResponse.Success(message, "t")
    private fun fail(type: ErrorType) = ApiResponse.Failure(ApiError(type), "t")

    private fun msg(id: String, sender: String, text: String, at: String = "2026-11-25T10:00:00Z", status: String = "sent") =
        ChatMessage(id = id, conversationId = "conv", senderId = sender, type = "text", content = text, status = status, createdAt = at)

    private class Fake : ChatRepository() {
        var me = "me"
        var history = emptyList<ChatMessage>()
        val realtime = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 16)
        var sendText: suspend (String) -> ApiResponse<ChatMessage> = { error("not set") }
        var group: ApiResponse<GroupConversation> = ApiResponse.Success(GroupConversation("conv", "group", "Nhóm", 3), "t")
        var profiles: suspend (Collection<String>) -> ApiResponse<Map<String, MessageSender>> = { ids ->
            ApiResponse.Success(ids.associateWith { MessageSender(it, "Name $it") }, "t")
        }
        val profileCalls = mutableListOf<List<String>>()
        var reads = 0
        var groupCalls = 0

        override fun getCurrentUserId() = me
        override suspend fun getMessages(conversationId: String) = ApiResponse.Success(history, "t")
        override fun observeMessages(conversationId: String): Flow<ChatMessage> = realtime
        override suspend fun markRead(conversationId: String): ApiResponse<Unit> { reads++; return ApiResponse.Success(Unit, "t") }
        override suspend fun sendTextMessage(conversationId: String, content: String) = sendText(content)
        override suspend fun getOrCreateStudyGroupConversation(groupId: String): ApiResponse<GroupConversation> { groupCalls++; return group }
        override suspend fun getSenderProfiles(userIds: Collection<String>): ApiResponse<Map<String, MessageSender>> {
            profileCalls += userIds.toList(); return profiles(userIds)
        }
    }

    private suspend fun settle() = repeat(20) { yield() }

    private fun runChat(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try { block(scope) } finally { scope.cancel() }
    }

    private fun direct(repo: Fake, scope: CoroutineScope) = DirectChatViewModel(repo, scope).also { it.initChat("conv", null) }
    private fun group(repo: Fake, scope: CoroutineScope) = GroupChatViewModel(repo, scope).also { it.initGroup("group") }

    @Test fun sendTextReplacesTemporaryMessageWithServerMessage() = runChat { scope ->
        val repo = Fake().apply { sendText = { ok(msg("srv1", "me", it)) } }
        val vm = direct(repo, scope); settle()
        vm.sendText("  xin chào  "); settle()
        val messages = vm.uiState.value.messages
        assertEquals(listOf("srv1"), messages.map { it.id })
        assertEquals("xin chào", messages.single().content)
    }

    @Test fun blankTextIsIgnoredAndMissingConversationReportsError() = runChat { scope ->
        val repo = Fake()
        val vm = DirectChatViewModel(repo, scope)
        vm.sendText("hi")
        assertNotNull(vm.uiState.value.errorMessage)
        vm.clearErrorMessage(); vm.initChat("conv", null); settle()
        vm.sendText("   ")
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun failedSendCanBeRetriedWithoutDuplicates() = runChat { scope ->
        var attempts = 0
        val repo = Fake().apply { sendText = { attempts++; if (attempts == 1) fail(ErrorType.NETWORK_UNAVAILABLE) else ok(msg("srv1", "me", it)) } }
        val vm = direct(repo, scope); settle()
        vm.sendText("hello"); settle()
        val failed = vm.uiState.value.messages.single()
        assertEquals("failed", failed.status)
        vm.retrySendMessage(failed); settle()
        assertEquals(listOf("srv1"), vm.uiState.value.messages.map { it.id })
        assertEquals(2, attempts)
    }

    @Test fun realtimeEchoOfOwnMessageReplacesTemporaryCopyOnce() = runChat { scope ->
        val gate = CompletableDeferred<ApiResponse<ChatMessage>>()
        val repo = Fake().apply { sendText = { gate.await() } }
        val vm = direct(repo, scope); settle()
        vm.sendText("hello"); settle()
        repo.realtime.emit(msg("srv1", "me", "hello")); settle()
        assertEquals(listOf("srv1"), vm.uiState.value.messages.map { it.id })
        gate.complete(ok(msg("srv1", "me", "hello"))); settle()
        assertEquals(listOf("srv1"), vm.uiState.value.messages.map { it.id })
    }

    @Test fun otherMembersIdenticalMessageDoesNotReplaceMyPendingMessage() = runChat { scope ->
        val gate = CompletableDeferred<ApiResponse<ChatMessage>>()
        val repo = Fake().apply { sendText = { gate.await() } }
        val vm = group(repo, scope); settle()
        vm.sendText("ok"); settle()
        repo.realtime.emit(msg("srv9", "alice", "ok")); settle()
        val ids = vm.uiState.value.messages.map { it.id }
        assertEquals(2, ids.size)
        assertTrue(ids.any { it?.startsWith("temp_") == true })
        assertTrue("srv9" in ids)
    }

    @Test fun groupLoadsHeaderAndSenderProfilesInOneBatchExcludingMe() = runChat { scope ->
        val repo = Fake().apply {
            history = listOf(msg("1", "alice", "a"), msg("2", "bob", "b"), msg("3", "alice", "c"), msg("4", "me", "d"))
        }
        val vm = group(repo, scope); settle()
        val state = vm.uiState.value
        assertEquals(ChatHeader("Nhóm", 3), state.header)
        assertEquals(1, repo.profileCalls.size)
        assertEquals(setOf("alice", "bob"), repo.profileCalls.single().toSet())
        assertEquals("Name alice", state.senders.getValue("alice").displayName)
        assertEquals(4, state.messages.size)
    }

    @Test fun senderLookupFailureKeepsMessagesAndRetriesOnNextMessage() = runChat { scope ->
        var failing = true
        val repo = Fake().apply {
            history = listOf(msg("1", "alice", "a"))
            profiles = { ids ->
                if (failing) fail(ErrorType.NETWORK_UNAVAILABLE)
                else ApiResponse.Success(ids.associateWith { MessageSender(it, "Name $it") }, "t")
            }
        }
        val vm = group(repo, scope); settle()
        assertEquals(1, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.senders.isEmpty())
        failing = false
        repo.realtime.emit(msg("2", "alice", "b")); settle()
        assertEquals("Name alice", vm.uiState.value.senders.getValue("alice").displayName)
        assertEquals(2, repo.profileCalls.size)
    }

    @Test fun senderKnownOnceIsNotRequestedAgain() = runChat { scope ->
        val repo = Fake().apply { history = listOf(msg("1", "alice", "a")) }
        val vm = group(repo, scope); settle()
        repo.realtime.emit(msg("2", "alice", "b")); settle()
        assertEquals(1, repo.profileCalls.size)
        assertEquals(2, vm.uiState.value.messages.size)
    }

    @Test fun groupMessagesNeverShowReadReceiptButDirectMessagesDo() = runChat { scope ->
        val history = listOf(
            msg("1", "me", "mine", "2026-11-25T10:00:00Z"),
            msg("2", "alice", "reply", "2026-11-25T10:05:00Z")
        )
        val directRepo = Fake().apply { this.history = history }
        val directVm = direct(directRepo, scope); settle()
        assertEquals("read", directVm.uiState.value.messages.first { it.id == "1" }.status)

        val groupRepo = Fake().apply { this.history = history }
        val groupVm = group(groupRepo, scope); settle()
        assertEquals("sent", groupVm.uiState.value.messages.first { it.id == "1" }.status)
    }

    @Test fun losingAccessClosesGroupChat() = runChat { scope ->
        val denied = Fake().apply { group = fail(ErrorType.GROUP_UNAVAILABLE) }
        val vm = group(denied, scope); settle()
        assertTrue(vm.uiState.value.inaccessible)
        assertNull(vm.uiState.value.conversationId)
        assertFalse(vm.uiState.value.isLoading)

        val repo = Fake().apply { sendText = { fail(ErrorType.PERMISSION_DENIED) } }
        val member = group(repo, scope); settle()
        assertFalse(member.uiState.value.inaccessible)
        member.sendText("hi"); settle()
        assertTrue(member.uiState.value.inaccessible)
    }

    @Test fun directPermissionDeniedDoesNotCloseTheScreen() = runChat { scope ->
        val repo = Fake().apply { sendText = { fail(ErrorType.PERMISSION_DENIED) } }
        val vm = direct(repo, scope); settle()
        vm.sendText("hi"); settle()
        assertEquals("failed", vm.uiState.value.messages.single().status)
        assertFalse(vm.uiState.value.inaccessible)
    }

    @Test fun resumeRefreshesHeaderAndDetectsRemoval() = runChat { scope ->
        val repo = Fake()
        val vm = group(repo, scope); settle()
        repo.group = ApiResponse.Success(GroupConversation("conv", "group", "Tên mới", 5), "t")
        vm.refreshReadStatus(); settle()
        assertEquals(ChatHeader("Tên mới", 5), vm.uiState.value.header)
        repo.group = fail(ErrorType.GROUP_UNAVAILABLE)
        vm.refreshReadStatus(); settle()
        assertTrue(vm.uiState.value.inaccessible)
        assertTrue(repo.reads >= 1)
    }

    @Test fun initGroupTwiceDoesNotReopenAnOpenConversation() = runChat { scope ->
        val repo = Fake()
        val vm = group(repo, scope); settle()
        vm.initGroup("group"); settle()
        assertEquals(1, repo.groupCalls)
    }
}
