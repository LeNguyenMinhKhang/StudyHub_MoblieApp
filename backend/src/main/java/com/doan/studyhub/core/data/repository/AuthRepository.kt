package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.Resource
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.remote.AuthRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Repository xử lý nghiệp vụ Auth và đóng gói kết quả dạng Resource<T>
class AuthRepository(
    private val remoteDataSource: AuthRemoteDataSource = AuthRemoteDataSource()
) {

    // Đăng ký tài khoản
    suspend fun signUp(
        email: String,
        password: String,
        username: String,
        displayName: String
    ): Resource<Profile> = withContext(Dispatchers.IO) {
        try {
            val profile = remoteDataSource.signUp(email, password, username, displayName)
            Resource.Success(profile)
        } catch (e: Exception) {
            Resource.Error(parseError(e), e)
        }
    }

    // Đăng nhập
    suspend fun signIn(email: String, password: String): Resource<Profile> = withContext(Dispatchers.IO) {
        try {
            remoteDataSource.signIn(email, password)
            val userId = SupabaseClient.client.auth.currentUserOrNull()?.id
                ?: return@withContext Resource.Error("Không thể lấy thông tin đăng nhập.")

            // Lấy profile từ cơ sở dữ liệu
            val profile = remoteDataSource.getProfile(userId)
                ?: return@withContext Resource.Error("Không tìm thấy hồ sơ người dùng.")

            // Kiểm tra nếu tài khoản bị khóa (is_banned = true)
            if (profile.isBanned) {
                remoteDataSource.signOut()
                return@withContext Resource.Error("Tài khoản đã bị khóa")
            }

            Resource.Success(profile)
        } catch (e: Exception) {
            Resource.Error(parseError(e), e)
        }
    }

    // Đăng xuất
    suspend fun signOut(): Resource<Unit> = withContext(Dispatchers.IO) {
        try {
            remoteDataSource.signOut()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(parseError(e), e)
        }
    }

    // Gửi email đặt lại mật khẩu
    suspend fun sendPasswordResetEmail(email: String): Resource<Unit> = withContext(Dispatchers.IO) {
        try {
            remoteDataSource.sendPasswordResetEmail(email)
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(parseError(e), e)
        }
    }

    // Đặt lại mật khẩu mới
    suspend fun updatePassword(newPassword: String): Resource<Unit> = withContext(Dispatchers.IO) {
        try {
            remoteDataSource.updatePassword(newPassword)
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(parseError(e), e)
        }
    }

    // Kiểm tra phiên làm việc hiện tại khi khởi động app
    suspend fun checkCurrentSession(): Resource<Profile?> = withContext(Dispatchers.IO) {
        try {
            val session = remoteDataSource.getCurrentSession()
            if (session != null) {
                val userId = session.user?.id ?: SupabaseClient.client.auth.currentUserOrNull()?.id
                if (userId != null) {
                    val profile = remoteDataSource.getProfile(userId)
                    if (profile?.isBanned == true) {
                        remoteDataSource.signOut()
                        return@withContext Resource.Error("Tài khoản đã bị khóa")
                    }
                    return@withContext Resource.Success(profile)
                }
            }
            Resource.Success(null)
        } catch (e: Exception) {
            Resource.Error(parseError(e), e)
        }
    }

    // Xử lý và việt hóa các lỗi phổ biến từ Supabase
    private fun parseError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("over_email_send_rate_limit", ignoreCase = true) || msg.contains("rate limit exceeded", ignoreCase = true) -> "Đã vượt quá giới hạn gửi email xác nhận trong giờ. Hãy tắt 'Confirm email' trong Supabase Auth Settings để tạo tài khoản ngay."
            msg.contains("email_address_invalid", ignoreCase = true) || msg.contains("invalid email", ignoreCase = true) -> "Địa chỉ email không hợp lệ hoặc Supabase chưa cho phép đăng ký email này"
            msg.contains("Invalid login credentials", ignoreCase = true) -> "Email hoặc mật khẩu không chính xác"
            msg.contains("User already registered", ignoreCase = true) || msg.contains("already exists", ignoreCase = true) -> "Email này đã được đăng ký tài khoản"
            msg.contains("Unable to resolve host", ignoreCase = true) || msg.contains("ConnectException", ignoreCase = true) -> "Không có kết nối mạng, vui lòng thử lại"
            msg.contains("Password should be at least", ignoreCase = true) -> "Mật khẩu phải chứa ít nhất 8 ký tự"
            else -> e.localizedMessage ?: "Đã xảy ra lỗi, vui lòng thử lại sau"
        }
    }
}
