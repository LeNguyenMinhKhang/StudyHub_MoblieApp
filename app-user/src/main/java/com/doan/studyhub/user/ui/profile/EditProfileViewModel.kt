package com.doan.studyhub.user.ui.profile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.UpdateProfileRequest
import com.doan.studyhub.core.data.repository.AvatarRepository
import com.doan.studyhub.core.data.repository.ProfileRepository
import com.doan.studyhub.core.data.repository.ProfileValidator
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EditProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val profiles = ProfileRepository()
    val avatars = AvatarRepository(application)
    private val validator = ProfileValidator()
    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()
    private var imageGeneration = 0

    fun load(savedUsername: String? = null, savedName: String? = null, savedBio: String? = null,
             savedAvatarAction: String? = null, savedDraftPath: String? = null) {
        if (_uiState.value.original != null) return
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            when (val result = profiles.getMine()) {
                is ApiResponse.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
                is ApiResponse.Success -> {
                    val profile = result.data
                    if (profiles.currentUserId() != profile.id) {
                        _uiState.value = EditProfileUiState(errorMessage = "Phiên đăng nhập đã thay đổi.")
                        return@launch
                    }
                    val action = PendingAvatar.entries.firstOrNull { it.name == savedAvatarAction } ?: PendingAvatar.KEEP
                    val draft = savedDraftPath?.let(::File)?.takeIf { it.isFile }
                    draft?.let(avatars::protectDraft)
                    _uiState.value = EditProfileUiState(
                        original = profile,
                        username = savedUsername ?: profile.username,
                        displayName = savedName ?: profile.displayName,
                        bio = savedBio ?: profile.bio.orEmpty(),
                        avatarAction = if (action == PendingAvatar.REPLACE && draft == null) PendingAvatar.KEEP else action,
                        avatarDraft = draft,
                        errorMessage = if (action == PendingAvatar.REPLACE && draft == null) "Ảnh nháp không còn. Vui lòng chọn lại." else null
                    )
                }
            }
        }
    }

    fun setUsername(value: String) { _uiState.value = _uiState.value.copy(username = value, fieldErrors = emptyMap()) }
    fun setDisplayName(value: String) { _uiState.value = _uiState.value.copy(displayName = value, fieldErrors = emptyMap()) }
    fun setBio(value: String) { _uiState.value = _uiState.value.copy(bio = value, fieldErrors = emptyMap()) }

    fun selectImage(uri: Uri) {
        val owner = _uiState.value.original ?: return
        if (_uiState.value.isSaving) return
        val request = ++imageGeneration
        viewModelScope.launch {
            when (val result = avatars.stage(uri)) {
                is ApiResponse.Success -> {
                    if (request != imageGeneration || profiles.currentUserId() != owner.id) {
                        avatars.discard(result.data)
                        return@launch
                    }
                    _uiState.value.avatarDraft?.let { avatars.discard(it) }
                    _uiState.value = _uiState.value.copy(
                        avatarAction = PendingAvatar.REPLACE, avatarDraft = result.data, errorMessage = null
                    )
                }
                is ApiResponse.Failure -> if (request == imageGeneration && profiles.currentUserId() == owner.id) {
                    _uiState.value = _uiState.value.copy(errorMessage = result.error.message)
                }
            }
        }
    }

    fun hideImage() {
        if (_uiState.value.isSaving) return
        imageGeneration++
        val draft = _uiState.value.avatarDraft
        viewModelScope.launch { draft?.let { avatars.discard(it) } }
        _uiState.value = _uiState.value.copy(avatarAction = PendingAvatar.HIDE, avatarDraft = null)
    }

    fun discardDraft() {
        imageGeneration++
        val draft = _uiState.value.avatarDraft
        viewModelScope.launch { draft?.let { avatars.discard(it) } }
        _uiState.value = _uiState.value.copy(avatarAction = PendingAvatar.KEEP, avatarDraft = null)
    }

    fun save() {
        val current = _uiState.value
        val owner = current.original ?: return
        if (current.isSaving || !current.isDirty) return
        if (profiles.currentUserId() != owner.id) {
            _uiState.value = current.copy(errorMessage = "Phiên đăng nhập đã thay đổi.")
            return
        }
        val errors = validator.validate(current.username, current.displayName, current.bio, owner.username)
        if (errors.isNotEmpty()) {
            _uiState.value = current.copy(fieldErrors = errors)
            return
        }
        _uiState.value = current.copy(isSaving = true, fieldErrors = emptyMap(), errorMessage = null, partialSave = false)
        viewModelScope.launch {
            var updated = owner
            if (current.textDirty) {
                when (val result = profiles.updateMine(UpdateProfileRequest(current.username, current.displayName, current.bio), owner.username)) {
                    is ApiResponse.Failure -> {
                        if (profiles.currentUserId() != owner.id) return@launch
                        _uiState.value = _uiState.value.copy(isSaving = false, fieldErrors = result.error.fieldErrors,
                            errorMessage = result.error.message)
                        return@launch
                    }
                    is ApiResponse.Success -> {
                        updated = result.data
                        if (profiles.currentUserId() != owner.id) return@launch
                        _uiState.value = _uiState.value.copy(original = updated, updatedProfile = updated)
                    }
                }
            }
            if (profiles.currentUserId() != owner.id) return@launch
            val avatarResult = when (current.avatarAction) {
                PendingAvatar.KEEP -> null
                PendingAvatar.HIDE -> avatars.hide(owner.id)
                PendingAvatar.REPLACE -> current.avatarDraft?.let { avatars.commit(owner.id, it) }
            }
            if (profiles.currentUserId() != owner.id) return@launch
            if (avatarResult is ApiResponse.Failure || (current.avatarAction == PendingAvatar.REPLACE && avatarResult == null)) {
                _uiState.value = _uiState.value.copy(isSaving = false, partialSave = current.textDirty,
                    errorMessage = if (current.textDirty) null else (avatarResult as? ApiResponse.Failure)?.error?.message
                        ?: "Không thể lưu ảnh đại diện")
            } else {
                _uiState.value = _uiState.value.copy(isSaving = false, completed = true,
                    avatarAction = PendingAvatar.KEEP, avatarDraft = null)
            }
        }
    }
}
