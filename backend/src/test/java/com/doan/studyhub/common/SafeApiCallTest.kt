package com.doan.studyhub.common

import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeApiCallTest {
    @Test fun `a network failure is mapped and logged once without provider text`() = runBlocking {
        val records = mutableListOf<String>()
        val call = SafeApiCall(
            logger = ErrorLogger { operation, error, traceId, elapsed ->
                records += "${operation.name}:${error.code}:$traceId:$elapsed:${error.logMessage}"
            },
            traceId = { "trace-1" },
            clockMs = { 10L }
        )

        val result = call.execute<Unit>(Operation.PROFILE_READ) {
            throw UnknownHostException("secret-token-is-here")
        }

        assertTrue(result is ApiResponse.Failure)
        assertEquals(ErrorType.NETWORK_UNAVAILABLE, (result as ApiResponse.Failure).error.type)
        assertEquals(1, records.size)
        assertFalse(records.single().contains("secret-token-is-here"))
        assertEquals("trace-1", result.traceId)
    }

    @Test fun `cancellation is not converted into a failure`() {
        var logs = 0
        val call = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> logs++ })
        try {
            runBlocking {
                call.execute<Unit>(Operation.PROFILE_UPDATE) { throw CancellationException("cancelled") }
            }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, logs)
        }
    }

    @Test fun `unknown failures remain safe and separate per invocation`() = runBlocking {
        var next = 0
        val call = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> }, traceId = { "trace-${++next}" })
        val first = call.execute<Unit>(Operation.AUTH_SIGN_IN) { throw IllegalStateException("private data") }
        val second = call.execute<Unit>(Operation.AUTH_SIGN_IN) { throw IllegalStateException("other private data") }
        assertEquals(ErrorType.UNKNOWN_ERROR, (first as ApiResponse.Failure).error.type)
        assertEquals(ErrorType.UNKNOWN_ERROR, (second as ApiResponse.Failure).error.type)
        assertFalse(first.error.message.contains("private"))
        assertFalse(first.traceId == second.traceId)
    }

    @Test fun `explicit field errors remain scoped to one failure`() {
        val call = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> })
        val first = call.failure(Operation.PROFILE_UPDATE, ErrorType.USERNAME_TAKEN, mapOf("username" to "Trùng tên"))
        val second = call.failure(Operation.PROFILE_UPDATE, ErrorType.USERNAME_TAKEN)
        assertEquals("Trùng tên", first.error.fieldErrors["username"])
        assertTrue(second.error.fieldErrors.isEmpty())
    }

    @Test fun `concurrent failures have separate traces and one safe log each`() = runBlocking {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val call = SafeApiCall(logger = ErrorLogger { _, type, trace, _ ->
            logs += "${type.code}:$trace"
        })
        val results = (1..20).map { index ->
            async(Dispatchers.Default) {
                call.execute<Unit>(Operation.AUTH_SIGN_IN) {
                    throw IllegalStateException("private-$index")
                }
            }
        }.awaitAll()
        assertEquals(20, logs.size)
        assertEquals(20, results.map { it.traceId }.toSet().size)
        assertTrue(logs.all { !it.contains("private-") })
    }
}
