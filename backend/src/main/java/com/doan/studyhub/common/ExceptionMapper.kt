package com.doan.studyhub.common

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import android.system.ErrnoException
import android.system.OsConstants
import io.ktor.client.plugins.HttpRequestTimeoutException

/** Maps provider codes, never provider text, into UI-safe failures. */
class ExceptionMapper {
    fun map(error: Exception, operation: Operation): ApiError {
        val type = when (error) {
            is DomainException -> error.type
            is AuthRestException -> when (error.errorCode) {
                AuthErrorCode.EmailExists, AuthErrorCode.UserAlreadyExists -> ErrorType.EMAIL_ALREADY_REGISTERED
                AuthErrorCode.InvalidCredentials -> ErrorType.INVALID_CREDENTIALS
                AuthErrorCode.EmailNotConfirmed -> ErrorType.EMAIL_CONFIRMATION_REQUIRED
                AuthErrorCode.UserBanned -> ErrorType.ACCOUNT_BANNED
                AuthErrorCode.SessionNotFound, AuthErrorCode.SessionExpired -> ErrorType.UNAUTHENTICATED
                AuthErrorCode.OverRequestRateLimit, AuthErrorCode.OverEmailSendRateLimit,
                AuthErrorCode.OverSmsSendRateLimit -> ErrorType.RATE_LIMITED
                AuthErrorCode.RequestTimeout -> ErrorType.REQUEST_TIMEOUT
                AuthErrorCode.ValidationFailed, AuthErrorCode.WeakPassword -> ErrorType.VALIDATION_FAILED
                else -> ErrorType.UNKNOWN_ERROR
            }
            is PostgrestRestException -> postgrestType(error.code, operation)
            is UnknownHostException, is ConnectException -> ErrorType.NETWORK_UNAVAILABLE
            is SocketTimeoutException,
            is HttpRequestTimeoutException,
            is io.ktor.client.network.sockets.SocketTimeoutException -> ErrorType.REQUEST_TIMEOUT
            is IOException -> when {
                (error.cause as? ErrnoException)?.errno == OsConstants.ENOSPC -> ErrorType.STORAGE_FULL
                operation == Operation.AVATAR_UPLOAD -> ErrorType.AVATAR_UPLOAD_FAILED
                operation in setOf(Operation.AVATAR_STAGE, Operation.AVATAR_COMMIT, Operation.AVATAR_REMOVE) -> ErrorType.AVATAR_SAVE_FAILED
                else -> ErrorType.UNKNOWN_ERROR
            }
            else -> ErrorType.UNKNOWN_ERROR
        }
        val fieldErrors = if (type == ErrorType.USERNAME_TAKEN) mapOf("username" to type.userMessage) else emptyMap()
        // Provider/exception text never reaches the UI; SafeApiCall logs the throwable for debugging.
        return ApiError(type, fieldErrors)
    }

    /** Maps a PostgREST/SQLSTATE code; split out so the mapping is unit-testable without HTTP. */
    internal fun postgrestType(code: String?, operation: Operation): ErrorType = when (code) {
        "G0001" -> ErrorType.VALIDATION_FAILED
        "G0002" -> ErrorType.UNAUTHENTICATED
        "G0003" -> ErrorType.GROUP_ACCOUNT_UNAVAILABLE
        "G0004" -> ErrorType.GROUP_UNAVAILABLE
        "G0005" -> ErrorType.PERMISSION_DENIED
        "G0006" -> ErrorType.GROUP_SUBJECT_UNAVAILABLE
        "G0007" -> ErrorType.GROUP_NOT_FRIENDS
        "G0008" -> ErrorType.GROUP_ALREADY_MEMBER
        "G0009" -> ErrorType.GROUP_INVITATION_UNAVAILABLE
        "G0010" -> ErrorType.GROUP_CONFLICT
        "G0011" -> ErrorType.GROUP_OWNER_REQUIRED
        "D0001" -> ErrorType.DOCUMENT_UNAVAILABLE
        "D0002" -> ErrorType.ANNOTATION_CONFLICT
        "D0003" -> ErrorType.OPERATION_CONFLICT
        "D0004" -> ErrorType.ANNOTATION_LIMIT
        "D0005" -> ErrorType.UPLOAD_UNAVAILABLE
        "D0006" -> ErrorType.SNAPSHOT_EXPIRED
        "D0007" -> ErrorType.GUIDANCE_TAKEN
        "D0008" -> ErrorType.GUIDANCE_LOST
        "C0001" -> ErrorType.CALL_FULL
        "C0002" -> ErrorType.CALL_IN_USE_ELSEWHERE
        "C0003" -> ErrorType.CALL_UNAVAILABLE
        "C0004" -> ErrorType.CALL_ENDING
        "F0001" -> ErrorType.VALIDATION_FAILED
        "F0002" -> ErrorType.FRIENDSHIP_UNAVAILABLE
        "F0003" -> ErrorType.PERMISSION_DENIED
        "F0004" -> if (operation in NOTIFICATION_OPERATIONS) ErrorType.NOTIFICATION_UNAVAILABLE else ErrorType.FRIENDSHIP_STALE
        "F0005" -> ErrorType.UNAUTHENTICATED
        "F0006" -> ErrorType.ACCOUNT_BANNED
        "22P02", "22007", "22008" -> if (operation in setOf(Operation.GROUP_READ, Operation.GROUP_MUTATE, Operation.DOCUMENT_READ, Operation.DOCUMENT_UPLOAD, Operation.DOCUMENT_MUTATE, Operation.CALL_READ, Operation.CALL_MUTATE, Operation.FRIENDSHIP_READ, Operation.FRIENDSHIP_LIST, Operation.FRIENDSHIP_MUTATE) + NOTIFICATION_OPERATIONS) ErrorType.VALIDATION_FAILED else ErrorType.UNKNOWN_ERROR
        "23505" -> if (operation == Operation.PROFILE_UPDATE || operation == Operation.PROFILE_LOOKUP || operation == Operation.AUTH_SIGN_UP) ErrorType.USERNAME_TAKEN else ErrorType.UNKNOWN_ERROR
        "42501" -> ErrorType.PERMISSION_DENIED
        "23502", "23514" -> ErrorType.VALIDATION_FAILED
        "40001" -> if (operation == Operation.AVATAR_PUBLISH) ErrorType.AVATAR_CONFLICT else ErrorType.UNKNOWN_ERROR
        else -> ErrorType.UNKNOWN_ERROR
    }

    private companion object {
        val NOTIFICATION_OPERATIONS = setOf(
            Operation.NOTIFICATION_LIST, Operation.NOTIFICATION_MARK_READ,
            Operation.NOTIFICATION_OPEN, Operation.NOTIFICATION_PREFERENCES
        )
    }
}
