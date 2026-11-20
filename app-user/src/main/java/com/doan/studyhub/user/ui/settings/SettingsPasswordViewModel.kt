
package com.doan.studyhub.user.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsPasswordUiState(
    val isUpdating: Boolean = false,
    val error: String? = null,
    val success: String? = null
)

class SettingsPasswordViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    private val mutable =
        MutableStateFlow(SettingsPasswordUiState())

    val uiState: StateFlow<SettingsPasswordUiState> =
        mutable.asStateFlow()

    fun updatePassword(
        currentPassword: String,
        newPassword: String,
        confirmation: String
    ) {
        if (mutable.value.isUpdating) return

        val error = when {
            currentPassword.isBlank() ->
                "Vui lòng nhập mật khẩu hiện tại"

            newPassword.isBlank() ->
                "Vui lòng nhập mật khẩu mới"

            newPassword.length < 8 ->
                "Mật khẩu mới phải có ít nhất 8 ký tự"

            newPassword == currentPassword ->
                "Mật khẩu mới phải khác mật khẩu hiện tại"

            confirmation.isBlank() ->
                "Vui lòng xác nhận mật khẩu mới"

            newPassword != confirmation ->
                "Mật khẩu xác nhận không khớp"

            else -> null
        }

        if (error != null) {
            mutable.value = SettingsPasswordUiState(error = error)
            return
        }

        viewModelScope.launch {
            mutable.value = SettingsPasswordUiState(
                isUpdating = true
            )

            when (
                val result = authRepository.updatePassword(
                    currentPassword = currentPassword,
                    newPassword = newPassword
                )
            ) {
                is ApiResponse.Success -> {
                    mutable.value = SettingsPasswordUiState(
                        success = "Đổi mật khẩu thành công!"
                    )
                }

                is ApiResponse.Failure -> {
                    mutable.value = SettingsPasswordUiState(
                        error = result.error.message
                    )
                }
            }
        }
    }

    fun clearMessages() {
        mutable.value = mutable.value.copy(
            error = null,
            success = null
        )
    }
}
