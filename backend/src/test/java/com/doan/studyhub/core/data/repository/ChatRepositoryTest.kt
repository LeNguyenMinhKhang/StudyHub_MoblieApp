package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.ChatRemoteDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatRepositoryTest {
    private val api = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> })
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decodesActualPostgresPayloads() {
        val payloads = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/group_chat/rpc_payloads.json")).readText()).jsonObject
        val conversation = json.decodeFromJsonElement<GroupConversation>(payloads.getValue("conversation"))
        assertEquals("Nhóm chat 💬", conversation.name)
        assertEquals(1, conversation.memberCount)
        val message = json.decodeFromJsonElement<ChatMessage>(payloads.getValue("message"))
        assertEquals(conversation.conversationId, message.conversationId)
        assertEquals("sent", message.status)
        val summary = json.decodeFromJsonElement<List<ConversationSummary>>(payloads.getValue("conversations")).single()
        assertEquals("group", summary.type)
        assertEquals(conversation.groupId, summary.studyGroupId)
    }

    @Test fun directSummaryWithoutStudyGroupStillDecodes() {
        val summary = json.decodeFromString<ConversationSummary>(
            """{"conversation_id":"c","type":"direct","title":"A","unread_count":2}"""
        )
        assertNull(summary.studyGroupId)
        assertEquals(2L, summary.unreadCount)
    }

    @Test fun groupSummaryAndInvitationAcceptMissingSubject() {
        val payloads = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/study_groups/rpc_payloads.json")).readText()).jsonObject
        val group = json.decodeFromJsonElement<GroupDetail>(payloads.getValue("detail_no_subject"))
        assertNull(group.subject)
        assertEquals("Nhóm không môn", group.name)
    }

    @Test fun groupConversationMapsGroupUnavailableAndPermissionErrors() {
        val mapper = ExceptionMapper()
        assertEquals(ErrorType.GROUP_UNAVAILABLE, mapper.postgrestType("G0004", Operation.CHAT_CREATE))
        assertEquals(ErrorType.PERMISSION_DENIED, mapper.postgrestType("42501", Operation.CHAT_SEND))
    }

    @Test fun senderProfilesAreDeduplicatedAndBatched() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val remote = object : ChatRemoteDataSource() {
            override suspend fun getSenderProfiles(userIds: List<String>): List<MessageSender> {
                batches += userIds
                return userIds.map { MessageSender(it, "Name $it") }
            }
        }
        val repo = ChatRepository(remote, api)
        val ids = (1..250).map { "u$it" } + listOf("u1", "u2", "", "  ")
        val result = repo.getSenderProfiles(ids) as ApiResponse.Success
        assertEquals(listOf(100, 100, 50), batches.map { it.size })
        assertEquals(250, result.data.size)
        assertEquals("Name u7", result.data.getValue("u7").displayName)
    }

    @Test fun senderProfilesWithNoIdsNeverCallRemote() = runBlocking {
        var calls = 0
        val remote = object : ChatRemoteDataSource() {
            override suspend fun getSenderProfiles(userIds: List<String>): List<MessageSender> { calls++; return emptyList() }
        }
        val result = ChatRepository(remote, api).getSenderProfiles(emptyList()) as ApiResponse.Success
        assertTrue(result.data.isEmpty())
        assertEquals(0, calls)
    }

    @Test fun senderProfileFailureIsReportedNotThrown() = runBlocking {
        val remote = object : ChatRemoteDataSource() {
            override suspend fun getSenderProfiles(userIds: List<String>): List<MessageSender> = throw java.io.IOException("offline")
        }
        assertTrue(ChatRepository(remote, api).getSenderProfiles(listOf("u1")) is ApiResponse.Failure)
    }

    @Test fun cancellationIsRethrown() {
        val remote = object : ChatRemoteDataSource() {
            override suspend fun getSenderProfiles(userIds: List<String>): List<MessageSender> = throw CancellationException("cancel")
        }
        try {
            runBlocking { ChatRepository(remote, api).getSenderProfiles(listOf("u1")) }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
        }
    }
}
