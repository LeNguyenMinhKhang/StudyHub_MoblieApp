package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Profile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Layer gọi trực tiếp API của Supabase Auth và Postgrest
class AuthRemoteDataSource {

    private val auth = SupabaseClient.client.auth
    private val profiles = ProfileRemoteDataSource()

    // Hồ sơ được tạo từ Auth metadata bởi database trigger.
    suspend fun signUp(email: String, password: String, username: String, displayName: String): String? {
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

    // Đăng nhập bằng Email và Mật khẩu
    suspend fun signIn(email: String, password: String) {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    // Đăng xuất khỏi phiên hiện tại
    suspend fun signOut() {
        auth.signOut()
    }

    // Gửi email đặt lại mật khẩu
    suspend fun sendPasswordResetEmail(email: String) {
        auth.resetPasswordForEmail(email)
    }

    // Cập nhật mật khẩu mới cho người dùng
    suspend fun updatePassword(newPassword: String) {
        auth.updateUser {
            this.password = newPassword
        }
    }

    // Kiểm tra phiên làm việc hiện tại
    fun getCurrentSession(): UserSession? {
        return auth.currentSessionOrNull()
    }

    // Lấy thông tin Profile từ bảng "profiles"
    suspend fun getProfile(userId: String): Profile? {
        if (auth.currentUserOrNull()?.id != userId) return null
        return profiles.getMine()
    }
}
