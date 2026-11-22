package com.doan.studyhub.admin.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.RegistrationOutcome
import com.doan.studyhub.core.data.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AdminAuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val profile: Profile? = null,
    val errorMessage: String? = null
)

class AdminAuthViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminAuthUiState())
    val uiState: StateFlow<AdminAuthUiState> = _uiState.asStateFlow()

    fun signIn(email: String, pass: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || pass.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Vui lòng nhập đầy đủ Email và Mật khẩu") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val res = authRepository.signIn(trimmedEmail, pass)) {
                is ApiResponse.Success -> {
                    val profile = res.data
                    if (profile.role == "admin") {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = true,
                                profile = profile,
                                errorMessage = null
                            )
                        }
                    } else {
                        // Phân quyền chặt chẽ: Không phải admin thì từ chối ngay và đăng xuất
                        authRepository.signOut()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = false,
                                profile = null,
                                errorMessage = "Tài khoản của bạn không có quyền truy cập trang Quản trị viên!"
                            )
                        }
                    }
                }
                is ApiResponse.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = res.error.message
                        )
                    }
                }
            }
        }
    }

    fun signUp(email: String, pass: String, username: String, displayName: String) {
        val trimmedEmail = email.trim()
        val trimmedUsername = username.trim()
        val trimmedDisplayName = displayName.trim()

        if (trimmedEmail.isBlank() || pass.isBlank() ||
            trimmedUsername.isBlank() || trimmedDisplayName.isBlank()
        ) {
            _uiState.update {
                it.copy(errorMessage = "Vui lòng nhập đầy đủ tất cả thông tin")
            }
            return
        }

        if (pass.length < 6) {
            _uiState.update {
                it.copy(errorMessage = "Mật khẩu phải có tối thiểu 6 ký tự")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, errorMessage = null)
            }

            when (
                val res = authRepository.signUp(
                    trimmedEmail,
                    pass,
                    trimmedUsername,
                    trimmedDisplayName
                )
            ) {
                is ApiResponse.Success -> {
                    when (res.data) {
                        is RegistrationOutcome.Authenticated -> {
                            // Tài khoản mới chỉ có quyền user.
                            // Không gọi RPC cấp quyền Admin.
                            authRepository.signOut()

                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isAuthenticated = false,
                                    profile = null,
                                    errorMessage = "Đăng ký thành công! Tài khoản đã được tạo. Vui lòng đăng nhập bằng tài khoản Admin hiện có."
                                )
                            }
                        }

                        RegistrationOutcome.ConfirmationRequired -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isAuthenticated = false,
                                    profile = null,
                                    errorMessage = "Đăng ký thành công! Vui lòng kiểm tra email để xác nhận tài khoản."
                                )
                            }
                        }
                    }
                }

                is ApiResponse.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = false,
                            profile = null,
                            errorMessage = res.error.message
                        )
                    }
                }
            }
        }
    }

    fun signOut(onComplete: () -> Unit = {}) {
        viewModelScope.launch(NonCancellable) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                authRepository.signOut()
            } catch (_: Exception) {
            }
            _uiState.update {
                AdminAuthUiState(
                    isLoading = false,
                    isAuthenticated = false,
                    profile = null,
                    errorMessage = null
                )
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
