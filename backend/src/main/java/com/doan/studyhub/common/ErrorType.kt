package com.doan.studyhub.common

enum class LogLevel { INFO, WARN, ERROR }

/** Stable result codes and safe messages shared by repository callers. */
enum class ErrorType(
    val code: String,
    val userMessage: String,
    val logMessage: String,
    val logLevel: LogLevel,
    val retryable: Boolean
) {
    VALIDATION_FAILED("VALIDATION_FAILED", "Vui lòng kiểm tra thông tin đã nhập.", "Input validation failed", LogLevel.INFO, false),
    USERNAME_TAKEN("USERNAME_TAKEN", "Tên người dùng đã được sử dụng.", "Username conflict", LogLevel.INFO, false),
    EMAIL_ALREADY_REGISTERED("EMAIL_ALREADY_REGISTERED", "Email đã được đăng ký.", "Email registration conflict", LogLevel.INFO, false),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "Email hoặc mật khẩu không chính xác.", "Invalid credentials", LogLevel.INFO, false),
    EMAIL_CONFIRMATION_REQUIRED("EMAIL_CONFIRMATION_REQUIRED", "Vui lòng xác nhận email để tiếp tục.", "Email confirmation pending", LogLevel.INFO, false),
    UNAUTHENTICATED("UNAUTHENTICATED", "Vui lòng đăng nhập để tiếp tục.", "Missing authenticated session", LogLevel.INFO, false),
    ACCOUNT_BANNED("ACCOUNT_BANNED", "Tài khoản đã bị khóa.", "Banned account", LogLevel.WARN, false),
    PERMISSION_DENIED("PERMISSION_DENIED", "Bạn không có quyền thực hiện thao tác này.", "Permission denied", LogLevel.WARN, false),
    PROFILE_NOT_FOUND("PROFILE_NOT_FOUND", "Không tìm thấy hồ sơ người dùng.", "Profile not found", LogLevel.WARN, false),
    NETWORK_UNAVAILABLE("NETWORK_UNAVAILABLE", "Không có kết nối mạng. Vui lòng thử lại.", "Network unavailable", LogLevel.WARN, true),
    REQUEST_TIMEOUT("REQUEST_TIMEOUT", "Kết nối quá thời gian chờ. Vui lòng thử lại.", "Request timed out", LogLevel.WARN, true),
    RATE_LIMITED("RATE_LIMITED", "Đã vượt quá giới hạn yêu cầu. Vui lòng thử lại sau.", "Rate limit reached", LogLevel.WARN, true),
    INVALID_IMAGE("INVALID_IMAGE", "Ảnh không hợp lệ hoặc quá lớn.", "Image validation failed", LogLevel.INFO, false),
    STORAGE_FULL("STORAGE_FULL", "Thiết bị không đủ dung lượng để lưu ảnh.", "Local storage full", LogLevel.WARN, true),
    AVATAR_SAVE_FAILED("AVATAR_SAVE_FAILED", "Không thể lưu ảnh đại diện. Vui lòng thử lại.", "Avatar commit failed", LogLevel.ERROR, true),
    FRIENDSHIP_UNAVAILABLE("FRIENDSHIP_UNAVAILABLE", "Không thể thực hiện với người dùng này.", "Friendship peer unavailable", LogLevel.INFO, false),
    FRIENDSHIP_STALE("FRIENDSHIP_STALE", "Quan hệ đã thay đổi. Vui lòng tải lại.", "Friendship state changed", LogLevel.INFO, false),
    UNKNOWN_ERROR("UNKNOWN_ERROR", "Đã xảy ra lỗi. Vui lòng thử lại sau.", "Unclassified operation failure", LogLevel.ERROR, true)
}
