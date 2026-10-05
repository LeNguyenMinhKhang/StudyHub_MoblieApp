package com.doan.studyhub.user.ui.auth

import com.doan.studyhub.core.data.model.Profile

// State chứa trạng thái của giao diện Auth
data class AuthUiState(
    val isLoading: Boolean = false,
    val isCheckingSession: Boolean = true, // Cờ kiểm tra phiên đăng nhập lúc mở app
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val profile: Profile? = null,
    val isAuthenticated: Boolean = false
)
