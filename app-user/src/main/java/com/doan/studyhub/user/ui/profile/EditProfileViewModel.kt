package com.doan.studyhub.user.ui.profile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.UpdateProfileRequest
import com.doan.studyhub.core.data.repository.AvatarRepository
import com.doan.studyhub.core.data.repository.ProfileRepository
import com.doan.studyhub.core.data.repository.ProfileValidator
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EditProfileViewModel @JvmOverloads constructor(
    application: Application,
    private val profiles: ProfileRepository = ProfileRepository(),
    val avatars: AvatarRepository = AvatarRepository(application),
    private val validator: ProfileValidator = ProfileValidator(),
    private val externalScope: kotlinx.coroutines.CoroutineScope? = null
) : AndroidViewModel(application) {
    private val scope get() = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()
    private var imageGeneration = 0

    fun load(
        savedUsername: String? = null,
        savedName: String? = null,
        savedBio: String? = null,
        savedAvatarAction: String? = null,
        savedDraftPath: String? = null
    ) {
        if (_uiState.value.original != null) return
        _uiState.value = _uiState.value.copy(isLoading = true)
        scope.launch {
            when (val result = profiles.getMine()) {
                is ApiResponse.Failure -> _uiState.value = _uiState.value.copy(
                    isLoading = false, errorMessage = result.error.message
                )
                is ApiResponse.Success -> {
                    val profile = result.data
                    if (profiles.currentUserId() != profile.id) {
                        _uiState.value = EditProfileUiState(errorMessage = "Phiên đăng nhập đã thay đổi.")
                        return@launch
                    }
                    avatars.reconcilePending(profile.id)

                    val action = PendingAvatar.entries.firstOrNull { it.name == savedAvatarAction }
                        ?: PendingAvatar.KEEP
                    val draft = savedDraftPath?.let(::File)?.takeIf { it.isFile }
                    draft?.let(avatars::protectDraft)
                    _uiState.value = EditProfileUiState(
                        original = profile,
                        username = savedUsername ?: profile.username,
                        displayName = savedName ?: profile.displayName,
                        bio = savedBio ?: profile.bio.orEmpty(),
                        avatarAction = if (action == PendingAvatar.REPLACE && draft == null) PendingAvatar.KEEP else action,
                        avatarDraft = draft,
                        errorMessage = if (action == PendingAvatar.REPLACE && draft == null)
                            "Ảnh nháp không còn. Vui lòng chọn lại." else null
                    )
                }
            }
        }
    }

    fun setUsername(value: String) {
        _uiState.value = _uiState.value.copy(username = value, fieldErrors = emptyMap())
    }

    fun setDisplayName(value: String) {
        _uiState.value = _uiState.value.copy(displayName = value, fieldErrors = emptyMap())
    }

    fun setBio(value: String) {
        _uiState.value = _uiState.value.copy(bio = value, fieldErrors = emptyMap())
    }

    fun selectImage(uri: Uri) {
        val owner = _uiState.value.original ?: return
        if (_uiState.value.isSaving) return
        val request = ++imageGeneration
        scope.launch {
            when (val result = avatars.stage(uri)) {
                is ApiResponse.Success -> {
                    if (request != imageGeneration || profiles.currentUserId() != owner.id) {
                        avatars.discard(result.data)
                        return@launch
                    }
                    _uiState.value.avatarDraft?.let { avatars.discard(it) }
                    _uiState.value = _uiState.value.copy(
                        avatarAction = PendingAvatar.REPLACE,
                        avatarDraft = result.data,
                        errorMessage = null,
                        avatarOutcomeUnknown = false,
                        avatarConflict = false
                    )
                }
                is ApiResponse.Failure -> if (request == imageGeneration && profiles.currentUserId() == owner.id) {
                    _uiState.value = _uiState.value.copy(errorMessage = result.error.message)
                }
            }
        }
    }

    fun useRetainedLocalAvatar(file: File) {
        val owner = _uiState.value.original ?: return
        if (_uiState.value.isSaving || profiles.currentUserId() != owner.id) return
        if (!file.isFile) return
        avatars.protectDraft(file)
        _uiState.value = _uiState.value.copy(
            avatarAction = PendingAvatar.REPLACE,
            avatarDraft = file,
            errorMessage = null,
            avatarOutcomeUnknown = false,
            avatarConflict = false
        )
    }

    fun removeAvatar() {
        if (_uiState.value.isSaving) return
        imageGeneration++
        val draft = _uiState.value.avatarDraft
        scope.launch { draft?.let { avatars.discard(it) } }
        _uiState.value = _uiState.value.copy(
            avatarAction = PendingAvatar.REMOVE,
            avatarDraft = null,
            avatarOutcomeUnknown = false,
            avatarConflict = false
        )
    }

    fun discardDraft() {
        imageGeneration++
        val draft = _uiState.value.avatarDraft
        scope.launch { draft?.let { avatars.discard(it) } }
        _uiState.value = _uiState.value.copy(
            avatarAction = PendingAvatar.KEEP,
            avatarDraft = null,
            avatarOutcomeUnknown = false,
            avatarConflict = false
        )
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
        _uiState.value = current.copy(
            isSaving = true,
            fieldErrors = emptyMap(),
            errorMessage = null,
            partialSave = false,
            avatarOutcomeUnknown = false,
            avatarConflict = false
        )
        scope.launch {
            var updated = owner
            if (current.textDirty) {
                when (val result = profiles.updateMine(
                    UpdateProfileRequest(current.username, current.displayName, current.bio),
                    owner.username
                )) {
                    is ApiResponse.Failure -> {
                        if (profiles.currentUserId() != owner.id) return@launch
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            fieldErrors = result.error.fieldErrors,
                            errorMessage = result.error.message
                        )
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

            when (current.avatarAction) {
                PendingAvatar.KEEP -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false, completed = true,
                        avatarAction = PendingAvatar.KEEP, avatarDraft = null
                    )
                }
                PendingAvatar.REPLACE -> {
                    val draft = current.avatarDraft
                    if (draft == null) {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            partialSave = current.textDirty,
                            errorMessage = "Ảnh nháp không còn. Vui lòng chọn lại."
                        )
                        return@launch
                    }
                    val avatarResult = avatars.commitRemote(owner.id, draft, owner.avatarUrl)
                    if (profiles.currentUserId() != owner.id) return@launch
                    handleAvatarResult(avatarResult, current.textDirty, draft)
                }
                PendingAvatar.REMOVE -> {
                    val avatarResult = avatars.removeRemote(owner.id, owner.avatarUrl)
                    if (profiles.currentUserId() != owner.id) return@launch
                    handleAvatarResult(avatarResult, current.textDirty, null)
                }
            }
        }
    }

    fun retryAvatar() {
        val current = _uiState.value
        val owner = current.original ?: return
        if (!current.partialSave || current.isSaving) return
        if (current.avatarConflict) return
        if (profiles.currentUserId() != owner.id) {
            _uiState.value = current.copy(errorMessage = "Phiên đăng nhập đã thay đổi.")
            return
        }
        _uiState.value = current.copy(
            isSaving = true, errorMessage = null,
            partialSave = false, avatarOutcomeUnknown = false, avatarConflict = false
        )
        scope.launch {
            val avatarResult = when (current.avatarAction) {
                PendingAvatar.REPLACE -> {
                    val draft = current.avatarDraft ?: run {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false, partialSave = true,
                            errorMessage = "Ảnh nháp không còn. Vui lòng chọn lại."
                        )
                        return@launch
                    }
                    avatars.commitRemote(owner.id, draft, owner.avatarUrl)
                }
                PendingAvatar.REMOVE -> avatars.removeRemote(owner.id, owner.avatarUrl)
                PendingAvatar.KEEP -> {
                    _uiState.value = _uiState.value.copy(isSaving = false, completed = true)
                    return@launch
                }
            }
            if (profiles.currentUserId() != owner.id) return@launch
            handleAvatarResult(avatarResult, wasTextSaved = true, draft = current.avatarDraft)
        }
    }

    private fun handleAvatarResult(
        result: ApiResponse<*>,
        wasTextSaved: Boolean,
        draft: File?
    ) {
        when (result) {
            is ApiResponse.Success -> {
                val updatedProfile = result.data as? com.doan.studyhub.core.data.model.Profile
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    completed = true,
                    avatarAction = PendingAvatar.KEEP,
                    avatarDraft = null,
                    updatedProfile = updatedProfile ?: _uiState.value.updatedProfile,
                    original = updatedProfile ?: _uiState.value.original
                )
            }
            is ApiResponse.Failure -> {
                val errorType = result.error.type
                val isConflict = errorType == ErrorType.AVATAR_CONFLICT
                val isUnknown = errorType == ErrorType.AVATAR_OUTCOME_UNKNOWN
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    partialSave = wasTextSaved,
                    avatarOutcomeUnknown = isUnknown,
                    avatarConflict = isConflict,
                    errorMessage = if (wasTextSaved && !isConflict && !isUnknown) null
                        else result.error.message
                )
            }
        }
    }
}
