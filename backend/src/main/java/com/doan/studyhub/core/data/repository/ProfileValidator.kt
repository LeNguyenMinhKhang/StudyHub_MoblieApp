package com.doan.studyhub.core.data.repository

class ProfileValidator {
    private val usernamePattern = Regex("[A-Za-z0-9_]{3,30}")

    fun validate(username: String, displayName: String, bio: String?, originalUsername: String? = null): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        val cleanedUsername = username.trim()
        val cleanedName = displayName.trim()
        if (cleanedUsername != originalUsername && !usernamePattern.matches(cleanedUsername)) {
            errors["username"] = "Tên người dùng cần 3–30 ký tự chữ, số hoặc dấu gạch dưới."
        }
        if (cleanedName.isEmpty() || cleanedName.length > 80) {
            errors["displayName"] = "Tên hiển thị cần từ 1 đến 80 ký tự."
        }
        if ((bio?.length ?: 0) > 500) {
            errors["bio"] = "Giới thiệu không được vượt quá 500 ký tự."
        }
        return errors
    }
}
