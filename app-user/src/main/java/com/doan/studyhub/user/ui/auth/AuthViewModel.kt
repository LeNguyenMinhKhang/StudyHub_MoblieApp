package com.doan.studyhub.user.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.Resource
import com.doan.studyhub.core.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ViewModel quản lý State cho 4 màn hình Auth
class AuthViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkSession()
    }

    // Kiểm tra phiên đăng nhập khi vừa mở app
    fun checkSession() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingSession = true, errorMessage = null)
            try {
                when (val result = authRepository.checkCurrentSession()) {
                    is Resource.Success -> {
                        if (result.data != null) {
                            _uiState.value = _uiState.value.copy(
                                isCheckingSession = false,
                                isAuthenticated = true,
                                profile = result.data
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isCheckingSession = false,
                                isAuthenticated = false
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isCheckingSession = false,
                            isAuthenticated = false,
                            errorMessage = result.message
                        )
                    }
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCheckingSession = false,
                    isAuthenticated = false,
                    errorMessage = "Lỗi kết nối: ${e.localizedMessage}"
                )
            }
        }
    }

    // Bỏ qua kiểm tra session để vào màn hình Đăng nhập
    fun skipSessionCheck() {
        _uiState.value = _uiState.value.copy(isCheckingSession = false, errorMessage = null)
    }

    // Xử lý Đăng ký
    fun signUp(
        email: String,
        password: String,
        confirmPass: String,
        username: String,
        displayName: String
    ) {
        // Validate dữ liệu đầu vào
        if (email.isBlank() || password.isBlank() || username.isBlank() || displayName.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Vui lòng nhập đầy đủ thông tin")
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Định dạng email không hợp lệ")
            return
        }
        if (password.length < 8) {
            _uiState.value = _uiState.value.copy(errorMessage = "Mật khẩu phải có ít nhất 8 ký tự")
            return
        }
        if (password != confirmPass) {
            _uiState.value = _uiState.value.copy(errorMessage = "Mật khẩu xác nhận không khớp")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = authRepository.signUp(email, password, username, displayName)) {
                is Resource.Success -> {
                    _uiState.value = AuthUiState(
                        isCheckingSession = false,
                        isAuthenticated = true,
                        profile = result.data,
                        successMessage = "Đăng ký tài khoản thành công!"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                is Resource.Loading -> {}
            }
        }
    }

    // Xử lý Đăng nhập
    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Vui lòng nhập email và mật khẩu")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = authRepository.signIn(email, password)) {
                is Resource.Success -> {
                    _uiState.value = AuthUiState(
                        isCheckingSession = false,
                        isAuthenticated = true,
                        profile = result.data,
                        successMessage = "Đăng nhập thành công!"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                is Resource.Loading -> {}
            }
        }
    }

    // Xử lý Quên mật khẩu
    fun sendPasswordResetEmail(email: String) {
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Email không hợp lệ")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = authRepository.sendPasswordResetEmail(email)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = "Đã gửi email đặt lại mật khẩu. Vui lòng kiểm tra hộp thư!"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                is Resource.Loading -> {}
            }
        }
    }

    // Đăng xuất
    fun signOut() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            authRepository.signOut()
            _uiState.value = AuthUiState(isCheckingSession = false, isAuthenticated = false)
        }
    }

    // Xóa thông báo sau khi hiển thị
    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}
