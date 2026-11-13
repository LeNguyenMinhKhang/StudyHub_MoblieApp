package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.model.NotificationPage
import com.doan.studyhub.core.data.remote.NotificationRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

open class NotificationRepository(
    private val remote: NotificationRemoteDataSource = NotificationRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall(),
    private val session: () -> String? = { SupabaseClient.client.auth.currentUserOrNull()?.id }
) {
    open fun currentUserId(): String? = session()
    open fun sessionChanges(): Flow<String?> = SupabaseClient.client.auth.sessionStatus
        .map { currentUserId() }.distinctUntilChanged()

    open suspend fun list(cursor: NotificationCursor? = null, limit: Int = 20): ApiResponse<NotificationPage> =
        call(Operation.NOTIFICATION_LIST) {
            if (limit <= 0) throw DomainException(ErrorType.VALIDATION_FAILED)
            cursor?.let {
                uuid(it.id)
                if (it.createdAt.length !in 10..64 || !it.createdAt.contains('T')) throw DomainException(ErrorType.VALIDATION_FAILED)
            }
            remote.list(cursor, limit.coerceAtMost(50))
        }

    open suspend fun markRead(ids: List<String>? = null): ApiResponse<Int> = call(Operation.NOTIFICATION_MARK_READ) {
        if (ids != null && ids.size !in 1..100) throw DomainException(ErrorType.VALIDATION_FAILED)
        remote.markRead(ids?.map(::uuid))
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
