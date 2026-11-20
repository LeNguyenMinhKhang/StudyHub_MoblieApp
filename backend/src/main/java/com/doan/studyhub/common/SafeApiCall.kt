package com.doan.studyhub.common

import android.util.Log
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class Operation {
    AUTH_SIGN_UP, AUTH_SIGN_IN, AUTH_SIGN_OUT, AUTH_SESSION,
    AUTH_PASSWORD_RESET, AUTH_PASSWORD_UPDATE,
    PROFILE_READ, PROFILE_LOOKUP, PROFILE_UPDATE,
    AVATAR_STAGE, AVATAR_COMMIT, AVATAR_REMOVE,
    FRIENDSHIP_READ, FRIENDSHIP_LIST, FRIENDSHIP_MUTATE,
    GROUP_READ, GROUP_MUTATE,
    DOCUMENT_READ, DOCUMENT_UPLOAD, DOCUMENT_MUTATE,
    CALL_READ, CALL_MUTATE,
    NOTIFICATION_LIST, NOTIFICATION_MARK_READ, NOTIFICATION_OPEN, NOTIFICATION_PREFERENCES,
    AVATAR_UPLOAD, AVATAR_PUBLISH,
    CHAT_LIST, CHAT_CREATE, CHAT_MESSAGES, CHAT_SEND, CHAT_UPLOAD, CHAT_MARK_READ
}

fun interface ErrorLogger {
    fun log(operation: Operation, error: ErrorType, traceId: String, elapsedMs: Long)
}

object AndroidErrorLogger : ErrorLogger {
    override fun log(operation: Operation, error: ErrorType, traceId: String, elapsedMs: Long) {
        log(operation, error, traceId, elapsedMs, null)
    }

    fun log(operation: Operation, error: ErrorType, traceId: String, elapsedMs: Long, throwable: Throwable?, cause: String? = null) {
        val entry = "operation=${operation.name} code=${error.code} traceId=$traceId durationMs=$elapsedMs message=${error.logMessage}" +
            (cause?.let { " cause=$it" } ?: "")
        when (error.logLevel) {
            LogLevel.INFO -> Log.i("StudyHubOperation", entry)
            LogLevel.WARN -> Log.w("StudyHubOperation", entry, throwable)
            LogLevel.ERROR -> Log.e("StudyHubOperation", entry, throwable)
        }
    }
}

private val PRIVATE_OPERATIONS = setOf(
    Operation.GROUP_READ, Operation.GROUP_MUTATE,
    Operation.DOCUMENT_READ, Operation.DOCUMENT_UPLOAD, Operation.DOCUMENT_MUTATE,
    Operation.CALL_READ, Operation.CALL_MUTATE
)

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
                // Group/document failures may carry private SQL detail; log only the provider code
                // (e.g. PGRST202 for an undeployed RPC) or the exception class, never its message.
                val isPrivate = operation in PRIVATE_OPERATIONS
                val cause = if (isPrivate) privateCause(error) else null
                logger.log(operation, mapped.type, id, (clockMs() - started).coerceAtLeast(0), if (isPrivate) null else error, cause)
            } else {
                logger.log(operation, mapped.type, id, (clockMs() - started).coerceAtLeast(0))
            }
            ApiResponse.Failure(mapped, id)
        }
    }

    private fun privateCause(error: Exception): String =
        (error as? PostgrestRestException)?.code?.takeIf { it.matches(SAFE_CODE) } ?: error.javaClass.simpleName

    private companion object {
        val SAFE_CODE = Regex("[A-Za-z0-9]{1,12}")
    }

    fun failure(operation: Operation, type: ErrorType, fieldErrors: Map<String, String> = emptyMap()): ApiResponse.Failure {
        val id = traceId()
        logger.log(operation, type, id, 0)
        return ApiResponse.Failure(ApiError(type, fieldErrors), id)
    }
}
