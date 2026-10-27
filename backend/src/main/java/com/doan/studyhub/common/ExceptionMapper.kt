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
            is PostgrestRestException -> when (error.code) {
                "F0001" -> ErrorType.VALIDATION_FAILED
                "F0002" -> ErrorType.FRIENDSHIP_UNAVAILABLE
                "F0003" -> ErrorType.PERMISSION_DENIED
                "F0004" -> ErrorType.FRIENDSHIP_STALE
                "F0005" -> ErrorType.UNAUTHENTICATED
                "F0006" -> ErrorType.ACCOUNT_BANNED
                "22P02", "22007", "22008" -> if (operation in setOf(Operation.FRIENDSHIP_READ, Operation.FRIENDSHIP_LIST, Operation.FRIENDSHIP_MUTATE, Operation.NOTIFICATION_LIST, Operation.NOTIFICATION_MARK_READ)) ErrorType.VALIDATION_FAILED else ErrorType.UNKNOWN_ERROR
                "23505" -> if (operation == Operation.PROFILE_UPDATE || operation == Operation.PROFILE_LOOKUP || operation == Operation.AUTH_SIGN_UP) ErrorType.USERNAME_TAKEN else ErrorType.UNKNOWN_ERROR
                "42501" -> ErrorType.PERMISSION_DENIED
                "23502", "23514" -> ErrorType.VALIDATION_FAILED
                "40001" -> if (operation == Operation.AVATAR_PUBLISH) ErrorType.AVATAR_CONFLICT else ErrorType.UNKNOWN_ERROR
                else -> ErrorType.UNKNOWN_ERROR
            }
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
        return ApiError(type, fieldErrors)
    }
}
