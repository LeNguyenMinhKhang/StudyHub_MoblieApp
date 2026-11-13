package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.NotificationCategory
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.model.NotificationFilter
import com.doan.studyhub.core.data.model.NotificationPage
import com.doan.studyhub.core.data.model.NotificationPreferences
import com.doan.studyhub.core.data.model.NotificationSignal
import com.doan.studyhub.core.data.model.NotificationTarget
import com.doan.studyhub.core.data.remote.NotificationRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import java.util.UUID

open class NotificationRepository(
    private val remote: NotificationRemoteDataSource = NotificationRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall(),
    private val session: () -> String? = { SupabaseClient.client.auth.currentUserOrNull()?.id },
    private val sessionFlow: () -> Flow<String?> = {
        SupabaseClient.client.auth.sessionStatus.map { session() }
    },
    private val retryDelayMs: (attempt: Long) -> Long = { attempt -> (1_000L shl attempt.toInt().coerceAtMost(5)) }
) {
    open fun currentUserId(): String? = session()
    open fun sessionChanges(): Flow<String?> = sessionFlow().distinctUntilChanged()

    open suspend fun list(
        cursor: NotificationCursor? = null,
        limit: Int = PAGE_SIZE,
        filter: NotificationFilter = NotificationFilter.ALL
    ): ApiResponse<NotificationPage> = call(Operation.NOTIFICATION_LIST) {
        if (limit <= 0) throw DomainException(ErrorType.VALIDATION_FAILED)
        cursor?.let {
            uuid(it.id)
            if (it.createdAt.length !in 10..64 || !it.createdAt.contains('T')) throw DomainException(ErrorType.VALIDATION_FAILED)
        }
        remote.list(filter, cursor, limit.coerceAtMost(MAX_PAGE_SIZE))
    }

    open suspend fun markRead(ids: List<String>? = null): ApiResponse<Int> = call(Operation.NOTIFICATION_MARK_READ) {
        if (ids != null && ids.size !in 1..100) throw DomainException(ErrorType.VALIDATION_FAILED)
        remote.markRead(ids?.map(::uuid))
    }

    /** Records first open (idempotent) and marks read; fails with NOTIFICATION_UNAVAILABLE for gone/hidden rows. */
    open suspend fun open(id: String): ApiResponse<NotificationTarget> = call(Operation.NOTIFICATION_OPEN) {
        remote.open(uuid(id))
    }

    open suspend fun preferences(): ApiResponse<NotificationPreferences> = call(Operation.NOTIFICATION_PREFERENCES) {
        remote.preferences()
    }

    open suspend fun setPreference(category: NotificationCategory, enabled: Boolean): ApiResponse<NotificationPreferences> =
        call(Operation.NOTIFICATION_PREFERENCES) { remote.setPreference(category, enabled) }

    /**
     * Session-scoped invalidation signals. Switching or losing the session cancels the old
     * channel before a new one is opened. Transport failures are retried with backoff and
     * reported as [NotificationSignal.Disconnected]; cancellation always propagates.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    open fun signals(): Flow<NotificationSignal> = sessionChanges().flatMapLatest { userId ->
        if (userId == null) emptyFlow() else remote.observe(userId)
            .catch { error ->
                if (error is CancellationException) throw error
                emit(NotificationSignal.Disconnected)
                throw error
            }
            .retryWhen { error, attempt ->
                if (error is CancellationException) return@retryWhen false
                delay(retryDelayMs(attempt))
                currentUserId() == userId
            }
    }

    private suspend fun <T> call(operation: Operation, block: suspend () -> T): ApiResponse<T> = api.execute(operation) {
        val owner = currentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
        val result = block()
        // A response that finishes after an account switch must not reach the new session.
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

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
    }
}
