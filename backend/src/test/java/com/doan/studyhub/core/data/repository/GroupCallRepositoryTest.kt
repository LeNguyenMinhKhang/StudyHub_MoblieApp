package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.CallFunctionException
import com.doan.studyhub.core.data.remote.GroupCallRemoteDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupCallRepositoryTest {
    private val actor = "00000000-0000-0000-0000-000000000001"
    private val group = "00000000-0000-0000-0000-000000000201"
    private val logged = mutableListOf<ErrorType>()
    private val api = SafeApiCall(logger = ErrorLogger { _, type, _, _ -> logged += type })
    private val payloads = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/group_calls/rpc_payloads.json")).readText()).jsonObject
    private val json = Json { ignoreUnknownKeys = true }
    private inline fun <reified T> fixture(key: String): T = json.decodeFromJsonElement(payloads.getValue(key))
    private val confirmed get() = fixture<GroupCallResult>("confirm").call
    private val connection = CallConnection("wss://example.livekit.cloud", "header.payload.signature",
        "00000000-0000-4000-8000-0000000000e1", "2026-11-18T09:05:00Z")

    private fun repo(remote: GroupCallRemoteDataSource, session: () -> String? = { "s" }) =
        GroupCallRepository(remote, api, { actor }, session)
    private fun ApiResponse<*>.type() = (this as ApiResponse.Failure).error.type

    @Test fun decodesActualPostgresPayloads() {
        val active = requireNotNull(fixture<GroupCallState>("get_active").call)
        assertEquals(CallStatus.ACTIVE, active.status)
        assertEquals(CallMode.AUDIO, active.initialMode)
        assertEquals(6, active.capacity)
        assertFalse(active.canEnd)
        assertNull(active.myStatus)
        assertTrue(active.joinable)
        assertEquals("reserved", confirmed.myStatus)
        assertTrue(confirmed.canEnd)
        val ending = fixture<GroupCallResult>("end").call
        assertEquals(CallStatus.ENDING, ending.status)
        assertTrue(ending.cleanupPending)
        assertFalse(ending.joinable)
        assertEquals("left", fixture<CallLeaveResult>("leave").status)
        assertNull(fixture<GroupCallState>("get_none").call)
        // Edge response shape: the actual projection plus connection fields.
        val join = json.decodeFromString<CallJoin>(buildJsonObject {
            put("call", payloads.getValue("confirm").jsonObject.getValue("call"))
            put("connection", buildJsonObject {
                put("url", connection.url); put("token", connection.token)
                put("identity", connection.identity); put("expires_at", connection.expiresAt)
            })
        }.toString())
        assertEquals(connection, join.connection)
    }

    @Test fun tokenNeverAppearsInStringFormsOrLogs() = runBlocking {
        assertFalse(connection.toString().contains(connection.token))
        assertFalse(CallJoin(confirmed, connection).toString().contains(connection.token))
        val remote = object : GroupCallRemoteDataSource() {
            override suspend fun startOrJoin(groupId: String, requestId: String, clientSessionId: String, mode: CallMode) =
                throw CallFunctionException(410, "G0004")
        }
        val result = repo(remote).startOrJoin(group, UUID.randomUUID().toString(), UUID.randomUUID().toString(), CallMode.VIDEO)
        assertEquals(ErrorType.GROUP_UNAVAILABLE, result.type())
        assertEquals(listOf(ErrorType.GROUP_UNAVAILABLE), logged)
    }

    @Test fun inputIsValidatedBeforeTheNetwork() = runBlocking {
        var calls = 0
        val remote = object : GroupCallRemoteDataSource() {
            override suspend fun startOrJoin(groupId: String, requestId: String, clientSessionId: String, mode: CallMode): CallJoin {
                calls++; return CallJoin(confirmed, connection)
            }
            override suspend fun end(callId: String): GroupCallResult { calls++; return fixture("end") }
        }
        val r = repo(remote)
        val id = UUID.randomUUID().toString()
        assertEquals(ErrorType.VALIDATION_FAILED, r.startOrJoin("not-a-uuid", id, id, CallMode.AUDIO).type())
        assertEquals(ErrorType.VALIDATION_FAILED, r.startOrJoin(group, id.uppercase() + "x", id, CallMode.AUDIO).type())
        assertEquals(ErrorType.VALIDATION_FAILED, r.end("").type())
        assertEquals(0, calls)
        assertEquals(ErrorType.UNAUTHENTICATED,
            GroupCallRepository(remote, api, { null }, { null }).startOrJoin(group, id, id, CallMode.AUDIO).type())
        assertEquals(CallStatus.ENDING, (r.end(confirmed.id) as ApiResponse.Success).data.status)
    }

    @Test fun functionAndSqlCodesMapToStableCallErrors() = runBlocking {
        val cases = mapOf(
            "C0001" to ErrorType.CALL_FULL, "C0002" to ErrorType.CALL_IN_USE_ELSEWHERE,
            "C0003" to ErrorType.CALL_UNAVAILABLE, "C0004" to ErrorType.CALL_ENDING,
            "G0002" to ErrorType.UNAUTHENTICATED, "G0003" to ErrorType.GROUP_ACCOUNT_UNAVAILABLE,
            "G0004" to ErrorType.GROUP_UNAVAILABLE, "G0005" to ErrorType.PERMISSION_DENIED,
            "provider_quota" to ErrorType.CALL_QUOTA_EXCEEDED, "provider_unavailable" to ErrorType.CALL_PROVIDER_UNAVAILABLE,
            "server_error" to ErrorType.CALL_PROVIDER_UNAVAILABLE, "unexpected" to ErrorType.CALL_PROVIDER_UNAVAILABLE)
        for ((code, type) in cases) {
            val remote = object : GroupCallRemoteDataSource() {
                override suspend fun leave(callId: String): CallLeaveResult = throw CallFunctionException(400, code)
            }
            assertEquals(code, type, repo(remote).leave(confirmed.id).type())
        }
        assertTrue(ErrorType.CALL_PROVIDER_UNAVAILABLE.retryable && ErrorType.CALL_ENDING.retryable)
        assertFalse(ErrorType.CALL_FULL.retryable)
    }

    @Test fun joinCredentialsForAnEarlierSessionAreDiscarded() = runBlocking {
        val pending = CompletableDeferred<CallJoin>()
        var session = "first"
        val remote = object : GroupCallRemoteDataSource() {
            override suspend fun startOrJoin(groupId: String, requestId: String, clientSessionId: String, mode: CallMode) = pending.await()
        }
        val r = repo(remote) { session }
        val result = async { r.startOrJoin(group, UUID.randomUUID().toString(), UUID.randomUUID().toString(), CallMode.AUDIO) }
        yield()
        session = "second" // logout/account switch while the request is in flight
        pending.complete(CallJoin(confirmed, connection))
        val response = result.await()
        assertEquals(ErrorType.UNAUTHENTICATED, response.type())
        assertFalse(response.toString().contains(connection.token))
    }
}
