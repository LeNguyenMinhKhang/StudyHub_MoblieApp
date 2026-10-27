package com.doan.studyhub.common

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class Operation {
    AUTH_SIGN_UP, AUTH_SIGN_IN, AUTH_SIGN_OUT, AUTH_SESSION,
    AUTH_PASSWORD_RESET, AUTH_PASSWORD_UPDATE,
    PROFILE_READ, PROFILE_LOOKUP, PROFILE_UPDATE,
    AVATAR_STAGE, AVATAR_COMMIT, AVATAR_REMOVE,
    FRIENDSHIP_READ, FRIENDSHIP_LIST, FRIENDSHIP_MUTATE,
    NOTIFICATION_LIST, NOTIFICATION_MARK_READ
}

fun interface ErrorLogger {
    fun log(operation: Operation, error: ErrorType, traceId: String, elapsedMs: Long)
}

object AndroidErrorLogger : ErrorLogger {
    override fun log(operation: Operation, error: ErrorType, traceId: String, elapsedMs: Long) {
        log(operation, error, traceId, elapsedMs, null)
    }

    fun log(operation: Operation, error: ErrorType, traceId: String, elapsedMs: Long, throwable: Throwable?) {
        val entry = "operation=${operation.name} code=${error.code} traceId=$traceId durationMs=$elapsedMs message=${error.logMessage}"
        when (error.logLevel) {
            LogLevel.INFO -> Log.i("StudyHubOperation", entry)
            LogLevel.WARN -> Log.w("StudyHubOperation", entry, throwable)
            LogLevel.ERROR -> Log.e("StudyHubOperation", entry, throwable)
        }
    }
}

class SafeApiCall(
    private val mapper: ExceptionMapper = ExceptionMapper(),
    private val logger: ErrorLogger = AndroidErrorLogger,
    private val traceId: () -> String = { UUID.randomUUID().toString() },
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    suspend fun <T> execute(operation: Operation, block: suspend () -> T): ApiResponse<T> {
        val id = traceId()
        val started = clockMs()
        return try {
            ApiResponse.Success(block(), id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val mapped = mapper.map(error, operation)
            if (logger is AndroidErrorLogger) {
                logger.log(operation, mapped.type, id, (clockMs() - started).coerceAtLeast(0), error)
            } else {
                logger.log(operation, mapped.type, id, (clockMs() - started).coerceAtLeast(0))
            }
            ApiResponse.Failure(mapped, id)
        }
    }

    fun failure(operation: Operation, type: ErrorType, fieldErrors: Map<String, String> = emptyMap()): ApiResponse.Failure {
        val id = traceId()
        logger.log(operation, type, id, 0)
        return ApiResponse.Failure(ApiError(type, fieldErrors), id)
    }
}
