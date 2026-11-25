package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.GroupRemoteDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroupRepositoryTest {
    private val actor = "00000000-0000-0000-0000-000000000001"
    private val key = "00000000-0000-0000-0000-000000000102"
    private val subject = "00000000-0000-0000-0000-000000000101"
    private val detail = GroupSummary(key, "Group", null, GroupSubject(subject, "Subject", true), GroupRole.OWNER, 1, "2026-11-13T00:00:00Z", "2026-11-13T00:00:00Z")
    private val api = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> })

    @Test fun decodesActualPostgresPayloads() {
        val payloads = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/study_groups/rpc_payloads.json")).readText()).jsonObject
        val group = Json.decodeFromJsonElement<GroupDetail>(payloads.getValue("detail"))
        assertEquals("Nhóm 📚", group.name)
        assertEquals(GroupRole.OWNER, group.role)
        assertEquals(1, group.memberCount)
        assertNull(group.description)
        assertEquals(group, Json.decodeFromJsonElement<GroupPage<GroupSummary>>(payloads.getValue("groups")).items.single())
        val members = Json.decodeFromJsonElement<GroupPage<GroupMember>>(payloads.getValue("members"))
        assertEquals(actor, members.items.single().userId)
        assertNull(members.nextCursor)
        val incoming = Json.decodeFromJsonElement<GroupPage<GroupInvitation>>(payloads.getValue("incoming")).items.single()
        assertNull(incoming.invitee)
        assertEquals(actor, incoming.inviter.id)
        assertNotNull(Json.decodeFromJsonElement<GroupPage<GroupInvitation>>(payloads.getValue("outgoing")).items.single().invitee)
        assertEquals(incoming.id, Json.decodeFromJsonElement<GroupInvitationResult>(payloads.getValue("invitation_result")).id)
        assertEquals("archived", Json.decodeFromJsonElement<GroupMutationResult>(payloads.getValue("mutation_result")).status)
    }

    @Test fun unicodeValidationAndRetryKeepRequestKey() = runBlocking {
        val requests = mutableListOf<CreateGroupRequest>()
        val remote = object : GroupRemoteDataSource() {
            override suspend fun create(request: CreateGroupRequest): GroupDetail { requests += request; return detail }
        }
        val repo = GroupRepository(remote, api, { actor }, { "session" })
        val request = CreateGroupRequest(key, subject, "  " + "📚".repeat(100) + " ", "  ")
        assertTrue(repo.create(request) is ApiResponse.Success)
        assertTrue(repo.create(request) is ApiResponse.Success)
        assertEquals(key, requests[0].requestId)
        assertEquals(requests[0], requests[1])
        assertNull(requests[0].description)
        assertEquals("📚".repeat(100), requests[0].name)
        assertEquals(ErrorType.VALIDATION_FAILED, (repo.create(request.copy(name = "📚".repeat(101))) as ApiResponse.Failure).error.type)
        assertEquals(ErrorType.VALIDATION_FAILED, (repo.create(request.copy(subjectId = "1-1-1-1-1")) as ApiResponse.Failure).error.type)
        assertEquals(2, requests.size)
    }

    @Test fun groupCanBeCreatedWithoutSubject() = runBlocking {
        val requests = mutableListOf<CreateGroupRequest>()
        val remote = object : GroupRemoteDataSource() {
            override suspend fun create(request: CreateGroupRequest): GroupDetail { requests += request; return detail.copy(subject = null) }
        }
        val repo = GroupRepository(remote, api, { actor }, { "session" })
        val result = repo.create(CreateGroupRequest(key, null, "Nhóm không môn")) as ApiResponse.Success
        assertNull(result.data.subject)
        assertNull(requests.single().subjectId)
    }

    @Test fun decodesGroupAndInvitationWithoutSubject() {
        val payloads = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/study_groups/rpc_payloads.json")).readText()).jsonObject
        val group = Json.decodeFromJsonElement<GroupDetail>(payloads.getValue("detail_no_subject"))
        assertNull(group.subject)
        val invitation = Json.decodeFromString<GroupInvitation>(
            """{"id":"i","status":"pending","created_at":"2026-11-25T00:00:00Z","group_id":"g","group_name":"G","description":null,"subject":null,"inviter":{"id":"u","display_name":"U","avatar_url":null}}"""
        )
        assertNull(invitation.subject)
    }

    @Test fun staleSessionResultIsDiscardedEvenForSameAccount() = runBlocking {
        val pending = CompletableDeferred<GroupDetail>()
        var sessionKey = "first"
        val remote = object : GroupRemoteDataSource() { override suspend fun detail(id: String) = pending.await() }
        val repo = GroupRepository(remote, api, { actor }, { sessionKey })
        val result = async { repo.detail(key) }
        yield()
        sessionKey = "second"
        pending.complete(detail)
        assertEquals(ErrorType.UNAUTHENTICATED, (result.await() as ApiResponse.Failure).error.type)
    }

    @Test fun cancellationPropagatesAndInvalidPaginationNeverCallsRemote() = runBlocking {
        var calls = 0
        val remote = object : GroupRemoteDataSource() {
            override suspend fun groups(cursor: GroupCursor?, limit: Int): GroupPage<GroupSummary> {
                calls++; throw CancellationException("cancel")
            }
        }
        val repo = GroupRepository(remote, api, { actor }, { "session" })
        assertTrue(repo.groups(limit = 51) is ApiResponse.Failure)
        assertTrue(repo.groups(GroupCursor("infinity", key)) is ApiResponse.Failure)
        assertEquals(0, calls)
        try { repo.groups(); fail("cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun refreshKeepsSessionButReauthenticationChangesIt() {
        fun token(session: String, expiry: Int): String {
            val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"session_id\":\"$session\",\"exp\":$expiry}".toByteArray())
            return "header.$payload.signature"
        }
        assertEquals(groupSessionId(token("session-a", 1)), groupSessionId(token("session-a", 2)))
        assertNotEquals(groupSessionId(token("session-a", 1)), groupSessionId(token("session-b", 2)))
        assertNull(groupSessionId("malformed"))
    }

    @Test fun staleSessionExceptionIsDiscarded() = runBlocking {
        val pending = CompletableDeferred<GroupDetail>()
        var owner = actor
        val remote = object : GroupRemoteDataSource() { override suspend fun detail(id: String) = pending.await() }
        val repo = GroupRepository(remote, api, { owner }, { "session" })
        val result = async { repo.detail(key) }
        yield()
        owner = subject
        pending.completeExceptionally(DomainException(ErrorType.GROUP_UNAVAILABLE))
        assertEquals(ErrorType.UNAUTHENTICATED, (result.await() as ApiResponse.Failure).error.type)
    }

    @Test fun groupSqlStatesHaveSafeStableMappings() {
        val expected = listOf(ErrorType.VALIDATION_FAILED, ErrorType.UNAUTHENTICATED, ErrorType.GROUP_ACCOUNT_UNAVAILABLE,
            ErrorType.GROUP_UNAVAILABLE, ErrorType.PERMISSION_DENIED, ErrorType.GROUP_SUBJECT_UNAVAILABLE,
            ErrorType.GROUP_NOT_FRIENDS, ErrorType.GROUP_ALREADY_MEMBER, ErrorType.GROUP_INVITATION_UNAVAILABLE,
            ErrorType.GROUP_CONFLICT, ErrorType.GROUP_OWNER_REQUIRED)
        expected.forEachIndexed { index, type ->
            assertEquals(type, ExceptionMapper().postgrestType("G" + (index + 1).toString().padStart(4, '0'), Operation.GROUP_MUTATE))
        }
        assertEquals(ErrorType.VALIDATION_FAILED, ExceptionMapper().postgrestType("22P02", Operation.GROUP_READ))
    }
}
