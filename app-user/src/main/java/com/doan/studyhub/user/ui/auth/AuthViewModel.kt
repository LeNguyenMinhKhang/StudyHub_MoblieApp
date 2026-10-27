package com.doan.studyhub.user.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.RegistrationOutcome
import com.doan.studyhub.core.data.model.SessionOutcome
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.core.data.repository.ProfileValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    private var sessionRevision = 0

    init { checkSession() }

    fun checkSession() {
        val request = ++sessionRevision
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingSession = true, errorMessage = null)
            val result = authRepository.checkCurrentSession()
            if (request != sessionRevision) return@launch
            when (result) {
                is ApiResponse.Success -> when (val session = result.data) {
                    is SessionOutcome.Authenticated -> _uiState.value = _uiState.value.copy(
                        isCheckingSession = false, isAuthenticated = true, profile = session.profile
                    )
                    SessionOutcome.SignedOut -> _uiState.value = _uiState.value.copy(
                        isCheckingSession = false, isAuthenticated = false, profile = null
                    )
                }
                is ApiResponse.Failure -> _uiState.value = _uiState.value.copy(
                    isCheckingSession = false, errorMessage = result.error.message
                )
            }
        }
    }

    fun skipSessionCheck() {
        sessionRevision++
        _uiState.value = _uiState.value.copy(isCheckingSession = false, errorMessage = null)
    }

    fun signUp(email: String, password: String, confirmPass: String, username: String, displayName: String) {
        if (email.isBlank() || password.isBlank() || username.isBlank() || displayName.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Vui lòng nhập đầy đủ thông tin")
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Định dạng email không hợp lệ")
            return
        }
        if (password.length < 8 || password != confirmPass) {
            _uiState.value = _uiState.value.copy(errorMessage = if (password.length < 8) "Mật khẩu phải có ít nhất 8 ký tự" else "Mật khẩu xác nhận không khớp")
            return
        }
        val profileErrors = ProfileValidator().validate(username, displayName, null)
        if (profileErrors.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = profileErrors.values.first())
            return
        }
        val request = ++sessionRevision
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = authRepository.signUp(email.trim(), password, username.trim(), displayName.trim())
            if (request != sessionRevision) return@launch
            when (result) {
                is ApiResponse.Success -> when (val outcome = result.data) {
                    is RegistrationOutcome.Authenticated -> _uiState.value = AuthUiState(
                        isCheckingSession = false, isAuthenticated = true, profile = outcome.profile
                    )
                    RegistrationOutcome.ConfirmationRequired -> _uiState.value = AuthUiState(
                        isCheckingSession = false,
                        successMessage = "Tài khoản đã được tạo. Vui lòng xác nhận email trước khi đăng nhập."
                    )
                }
                is ApiResponse.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Vui lòng nhập email và mật khẩu")
            return
        }
        val request = ++sessionRevision
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.signIn(email, password)
            if (request != sessionRevision) return@launch
            when (result) {
                is ApiResponse.Success -> _uiState.value = AuthUiState(
                    isCheckingSession = false, isAuthenticated = true, profile = result.data
                )
                is ApiResponse.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun sendPasswordResetEmail(email: String) {
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Email không hợp lệ")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = authRepository.sendPasswordResetEmail(email)) {
                is ApiResponse.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false, successMessage = "Đã gửi email đặt lại mật khẩu. Vui lòng kiểm tra hộp thư!"
                )
                is ApiResponse.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun signOut() {
        val request = ++sessionRevision
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.signOut()
            if (request != sessionRevision) return@launch
            when (result) {
                is ApiResponse.Success -> _uiState.value = AuthUiState(isCheckingSession = false, isAuthenticated = false)
                is ApiResponse.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun updateProfileSnapshot(profile: Profile) {
        if (_uiState.value.isAuthenticated && _uiState.value.profile?.id == profile.id) {
            _uiState.value = _uiState.value.copy(profile = profile)
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}
