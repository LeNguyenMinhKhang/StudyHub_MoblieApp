package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.CallFunctionException
import com.doan.studyhub.core.data.remote.GroupCallRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Group call state and admission. Join credentials are returned only to the login session that
 * requested them: a token arriving after logout/account switch is dropped, never cached or logged.
 */
open class GroupCallRepository(
    private val remote: GroupCallRemoteDataSource = GroupCallRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall(),
    private val session: () -> String? = { SupabaseClient.client.auth.currentUserOrNull()?.id },
    private val sessionKey: () -> String? = { groupSessionId(SupabaseClient.client.auth.currentSessionOrNull()?.accessToken) },
    private val mapper: ExceptionMapper = ExceptionMapper()
) {
    open fun currentUserId(): String? = session()
    open fun currentSessionKey(): String? = sessionKey()

    /** The group's open call, or null. Polled at most every 10 s while group details are visible. */
    open suspend fun current(groupId: String): ApiResponse<GroupCall?> = call(Operation.CALL_READ) {
        remote.current(uuid(groupId)).call
    }

    /**
     * Starts the group's call or joins the open one. Reuse [requestId] when retrying after a lost
     * response; [clientSessionId] identifies this app instance so another device is not taken over.
     */
    open suspend fun startOrJoin(groupId: String, requestId: String, clientSessionId: String, mode: CallMode) =
        call(Operation.CALL_MUTATE) { remote.startOrJoin(uuid(groupId), uuid(requestId), uuid(clientSessionId), mode) }

    open suspend fun leave(callId: String) = call(Operation.CALL_MUTATE) { remote.leave(uuid(callId)); Unit }

    /** End for everyone: allowed for the current owner or the initiator who is still a member. */
    open suspend fun end(callId: String) = call(Operation.CALL_MUTATE) { remote.end(uuid(callId)).call }

    private suspend fun <T> call(operation: Operation, block: suspend () -> T): ApiResponse<T> = api.execute(operation) {
        val actor = currentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
        val key = currentSessionKey()
        try {
            val result = block()
            if (currentUserId() != actor || currentSessionKey() != key) throw DomainException(ErrorType.UNAUTHENTICATED)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CallFunctionException) {
            if (currentUserId() != actor || currentSessionKey() != key) throw DomainException(ErrorType.UNAUTHENTICATED)
            throw DomainException(functionFailure(error))
        } catch (error: Exception) {
            if (currentUserId() != actor || currentSessionKey() != key) throw DomainException(ErrorType.UNAUTHENTICATED)
            throw error
        }
    }

    private fun functionFailure(error: CallFunctionException): ErrorType = when (error.code) {
        "provider_quota" -> ErrorType.CALL_QUOTA_EXCEEDED
        "provider_unavailable", "server_error", "method_not_allowed" -> ErrorType.CALL_PROVIDER_UNAVAILABLE
        else -> mapper.postgrestType(error.code, Operation.CALL_MUTATE).let {
            if (it == ErrorType.UNKNOWN_ERROR) ErrorType.CALL_PROVIDER_UNAVAILABLE else it
        }
    }

    private fun uuid(value: String): String {
        val parsed = try { UUID.fromString(value).toString() } catch (_: IllegalArgumentException) { invalid() }
        if (!parsed.equals(value, ignoreCase = true)) invalid()
        return parsed
    }
    private fun invalid(): Nothing = throw DomainException(ErrorType.VALIDATION_FAILED)
}
