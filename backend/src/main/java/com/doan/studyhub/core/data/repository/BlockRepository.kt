package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.BlockRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

open class BlockRepository(
    private val remote: BlockRemoteDataSource = BlockRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall(),
    private val session: () -> String? = {
        SupabaseClient.client.auth.currentUserOrNull()?.id
    }
) {

    open fun currentUserId(): String? = session()

    open fun sessionChanges(): Flow<String?> =
        SupabaseClient.client.auth.sessionStatus
            .map { currentUserId() }
            .distinctUntilChanged()

    /**
     * Kiểm tra trạng thái Block giữa tài khoản hiện tại và target.
     */
    open suspend fun state(
        targetId: String
    ): ApiResponse<BlockSnapshot> =
        call(Operation.BLOCK_READ) {
            val validId = uuid(targetId)

            if (validId == currentUserId()) {
                throw DomainException(ErrorType.VALIDATION_FAILED)
            }

            remote.state(validId)
        }

    /**
     * Chặn người dùng.
     */
    open suspend fun block(
        targetId: String
    ): ApiResponse<BlockSnapshot> =
        call(Operation.BLOCK_MUTATE) {
            val validId = uuid(targetId)

            if (validId == currentUserId()) {
                throw DomainException(ErrorType.VALIDATION_FAILED)
            }

            remote.block(validId)
        }

    /**
     * Bỏ chặn người dùng.
     */
    open suspend fun unblock(
        targetId: String
    ): ApiResponse<BlockSnapshot> =
        call(Operation.BLOCK_MUTATE) {
            val validId = uuid(targetId)

            if (validId == currentUserId()) {
                throw DomainException(ErrorType.VALIDATION_FAILED)
            }

            remote.unblock(validId)
        }

    /**
     * Lấy danh sách người dùng mà tài khoản hiện tại đã chặn.
     */
    open suspend fun list(
        cursor: BlockCursor? = null,
        limit: Int = 20
    ): ApiResponse<BlockedUsersPage> =
        call(Operation.BLOCK_LIST) {

            if (limit <= 0) {
                throw DomainException(ErrorType.VALIDATION_FAILED)
            }

            cursor?.let {
                uuid(it.id)

                if (
                    it.createdAt.length !in 10..64 ||
                    !it.createdAt.contains('T')
                ) {
                    throw DomainException(ErrorType.VALIDATION_FAILED)
                }
            }

            remote.list(
                cursor = cursor,
                limit = limit.coerceAtMost(50)
            )
        }

    /**
     * Bảo vệ session: không tiếp tục trả kết quả nếu tài khoản
     * thay đổi trong lúc request đang chạy.
     */
    private suspend fun <T> call(
        operation: Operation,
        block: suspend () -> T
    ): ApiResponse<T> =
        api.execute(operation) {
            val owner = currentUserId()
                ?: throw DomainException(ErrorType.UNAUTHENTICATED)

            val result = block()

            if (currentUserId() != owner) {
                throw DomainException(ErrorType.UNAUTHENTICATED)
            }

            result
        }

    /**
     * Chuẩn hóa và xác thực UUID.
     */
    private fun uuid(value: String): String {
        val parsed = try {
            UUID.fromString(value).toString()
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorType.VALIDATION_FAILED)
        }

        if (!parsed.equals(value, ignoreCase = true)) {
            throw DomainException(ErrorType.VALIDATION_FAILED)
        }

        return parsed
    }
}