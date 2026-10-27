package com.doan.studyhub.user.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.PublicProfile
import com.doan.studyhub.core.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val repository: ProfileRepository = ProfileRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    private var generation = 0

    fun load(id: String?) {
        val request = ++generation
        val viewer = repository.currentUserId()
        _uiState.value = ProfileUiState(isLoading = true)
        viewModelScope.launch {
            if (id == null) {
                when (val result = repository.getMine()) {
                    is ApiResponse.Success -> if (request == generation && repository.currentUserId() == viewer) _uiState.value = ProfileUiState(
                        profile = result.data.toPublic(), isOwner = true
                    )
                    is ApiResponse.Failure -> if (request == generation && repository.currentUserId() == viewer) _uiState.value = ProfileUiState(
                        errorMessage = result.error.message
                    )
                }
            } else {
                when (val result = repository.getPublic(id)) {
                    is ApiResponse.Success -> if (request == generation && repository.currentUserId() == viewer) _uiState.value = ProfileUiState(
                        profile = result.data, unavailable = result.data == null
                    )
                    is ApiResponse.Failure -> if (request == generation && repository.currentUserId() == viewer) _uiState.value = ProfileUiState(
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun updateOwn(profile: Profile) {
        if (_uiState.value.isOwner && repository.currentUserId() == profile.id) {
            generation++
            _uiState.value = _uiState.value.copy(profile = profile.toPublic())
        }
    }
}

private fun Profile.toPublic() = PublicProfile(id, username, displayName, avatarUrl, bio, createdAt)
