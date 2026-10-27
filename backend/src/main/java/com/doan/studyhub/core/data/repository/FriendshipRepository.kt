package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.FriendshipRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

open class FriendshipRepository(
    private val remote: FriendshipRemoteDataSource = FriendshipRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall(),
    private val session: () -> String? = { SupabaseClient.client.auth.currentUserOrNull()?.id }
) {
    open fun currentUserId(): String? = session()
    open fun sessionChanges(): Flow<String?> = SupabaseClient.client.auth.sessionStatus
        .map { currentUserId() }.distinctUntilChanged()

    open suspend fun state(target: String): ApiResponse<FriendshipSnapshot> = call(Operation.FRIENDSHIP_READ) {
        remote.state(uuid(target))
    }

    open suspend fun list(kind: FriendshipListKind, cursor: FriendshipCursor? = null, limit: Int = 20): ApiResponse<FriendshipPage> =
        call(Operation.FRIENDSHIP_LIST) {
            if (limit <= 0) throw DomainException(ErrorType.VALIDATION_FAILED)
            cursor?.let {
                uuid(it.id)
                if (it.createdAt.length !in 10..64 || !it.createdAt.contains('T')) throw DomainException(ErrorType.VALIDATION_FAILED)
            }
            remote.list(kind, cursor, limit.coerceAtMost(50))
        }

    open suspend fun mutate(action: FriendshipAction, id: String): ApiResponse<FriendshipSnapshot> = call(Operation.FRIENDSHIP_MUTATE) {
        val validId = uuid(id)
        if (action == FriendshipAction.SEND && validId == currentUserId()) throw DomainException(ErrorType.VALIDATION_FAILED)
        remote.mutate(action, validId)
    }

    private suspend fun <T> call(operation: Operation, block: suspend () -> T): ApiResponse<T> = api.execute(operation) {
        val owner = currentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
        val result = block()
        if (currentUserId() != owner) throw DomainException(ErrorType.UNAUTHENTICATED)
        result
    }

    private fun uuid(value: String): String {
        val parsed = try { UUID.fromString(value).toString() } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorType.VALIDATION_FAILED)
        }
        if (!parsed.equals(value, ignoreCase = true)) throw DomainException(ErrorType.VALIDATION_FAILED)
        return parsed
    }
}
