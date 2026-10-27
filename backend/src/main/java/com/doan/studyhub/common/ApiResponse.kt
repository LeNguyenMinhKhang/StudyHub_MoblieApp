package com.doan.studyhub.common

/** A completed repository operation. Loading belongs to the screen state. */
sealed interface ApiResponse<out T> {
    val traceId: String

    data class Success<T>(val data: T, override val traceId: String) : ApiResponse<T>

    data class Failure(
        val error: ApiError,
        override val traceId: String
    ) : ApiResponse<Nothing>
}

data class ApiError(
    val type: ErrorType,
    val fieldErrors: Map<String, String> = emptyMap()
) {
    val code: String get() = type.code
    val message: String get() = type.userMessage
    val retryable: Boolean get() = type.retryable
}
