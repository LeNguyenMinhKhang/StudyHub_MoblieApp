
package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Profile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Layer gọi trực tiếp API của Supabase Auth và Postgrest.
class AuthRemoteDataSource {

    private val auth = SupabaseClient.client.auth
    private val profiles = ProfileRemoteDataSource()

    // Đăng ký tài khoản.
    suspend fun signUp(
        email: String,
        password: String,
        username: String,
        displayName: String
    ): String? {
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
            this.data = buildJsonObject {
                put("username", username)
                put("display_name", displayName)
            }
        }

        return auth.currentUserOrNull()?.id
    }

    // Đăng nhập.
    suspend fun signIn(email: String, password: String) {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    // Đăng xuất.
    suspend fun signOut() {
        auth.signOut()
    }

    // Gửi email đặt lại mật khẩu.
    suspend fun sendPasswordResetEmail(email: String) {
        auth.resetPasswordForEmail(email)
    }

    // Xác minh mật khẩu hiện tại rồi cập nhật mật khẩu mới.
    suspend fun updatePassword(
        currentPassword: String,
        newPassword: String
    ) {
        val currentEmail = auth.currentUserOrNull()?.email
            ?: throw IllegalStateException(
                "Không tìm thấy email của tài khoản hiện tại."
            )

        // Xác thực lại bằng email và mật khẩu hiện tại.
        auth.signInWith(Email) {
            email = currentEmail
            password = currentPassword
        }

        // Chỉ cập nhật khi xác thực thành công.
        auth.updateUser {
            password = newPassword
        }
    }

    // Kiểm tra phiên hiện tại.
    fun getCurrentSession(): UserSession? {
        return auth.currentSessionOrNull()
    }

    // Lấy thông tin hồ sơ.
    suspend fun getProfile(userId: String): Profile? {
        if (auth.currentUserOrNull()?.id != userId) return null
        return profiles.getMine()
    }
}
