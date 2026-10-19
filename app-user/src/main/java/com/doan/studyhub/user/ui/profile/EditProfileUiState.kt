package com.doan.studyhub.user.ui.profile

import com.doan.studyhub.core.data.model.Profile
import java.io.File

enum class PendingAvatar { KEEP, REPLACE, HIDE }

data class EditProfileUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val original: Profile? = null,
    val username: String = "",
    val displayName: String = "",
    val bio: String = "",
    val avatarAction: PendingAvatar = PendingAvatar.KEEP,
    val avatarDraft: File? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    val partialSave: Boolean = false,
    val updatedProfile: Profile? = null,
    val completed: Boolean = false
) {
    val textDirty: Boolean get() = original?.let {
        username.trim() != it.username || displayName.trim() != it.displayName || bio.trim() != it.bio.orEmpty()
    } ?: false
    val isDirty: Boolean get() = textDirty || avatarAction != PendingAvatar.KEEP
}
