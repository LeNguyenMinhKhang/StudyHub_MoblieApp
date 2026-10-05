package com.doan.studyhub.common

// Sealed class bọc kết quả trả về cho UI: Loading, Success hoặc Error
sealed class Resource<out T> {
    object Loading : Resource<Nothing>()
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Resource<Nothing>()
}
