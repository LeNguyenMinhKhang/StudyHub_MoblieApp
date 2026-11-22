package com.doan.studyhub.admin.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.Resource
import com.doan.studyhub.core.data.model.Profile
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

    // Đăng nhập Admin
    fun signIn(email: String, pass: String) {
        val trimmedEmail = email.trim()

        if (trimmedEmail.isBlank() || pass.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Vui lòng nhập đầy đủ Email và Mật khẩu")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, errorMessage = null)
            }

            when (val result = authRepository.signIn(trimmedEmail, pass)) {
                is Resource.Success -> {
                    val profile = result.data

                    if (profile.role == "admin" && !profile.isBanned) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = true,
                                profile = profile,
                                errorMessage = null
                            )
                        }
                    } else {
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

                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = false,
                            profile = null,
                            errorMessage = result.message
                        )
                    }
                }

                Resource.Loading -> {
                    _uiState.update {
                        it.copy(isLoading = true)
                    }
                }
            }
        }
    }

    // Đăng ký tài khoản
    fun signUp(
        email: String,
        pass: String,
        username: String,
        displayName: String
    ) {
        val trimmedEmail = email.trim()
        val trimmedUsername = username.trim()
        val trimmedDisplayName = displayName.trim()

        if (
            trimmedEmail.isBlank() ||
            pass.isBlank() ||
            trimmedUsername.isBlank() ||
            trimmedDisplayName.isBlank()
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
                val result = authRepository.signUp(
                    trimmedEmail,
                pass,
                trimmedUsername,
                trimmedDisplayName
                )
            ) {
                is Resource.Success -> {
                    // Tài khoản đăng ký luôn có role user.
                    // Không cấp quyền Admin cho tài khoản mới.
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

                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = false,
                            profile = null,
                            errorMessage = result.message
                        )
                    }
                }

                Resource.Loading -> {
                    _uiState.update {
                        it.copy(isLoading = true)
                    }
                }
            }
        }
    }

    // Đăng xuất
    fun signOut(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, errorMessage = null)
            }

            when (val result = authRepository.signOut()) {
                is Resource.Success -> {
                    _uiState.value = AdminAuthUiState(
                        isLoading = false,
                        isAuthenticated = false,
                        profile = null,
                        errorMessage = null
                    )

                    onComplete()
                }

                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Đăng xuất thất bại: ${result.message}"
                        )
                    }
                }

                is Resource.Loading -> {
                    _uiState.update {
                        it.copy(isLoading = true)
                    }
                }
            }
        }
    }

    // Xóa thông báo lỗi
    fun clearError() {
        _uiState.update {
            it.copy(errorMessage = null)
        }
    }
}