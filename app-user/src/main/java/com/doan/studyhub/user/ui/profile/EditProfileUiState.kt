package com.doan.studyhub.user.ui.profile

import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.Profile
import java.io.File

enum class PendingAvatar { KEEP, REPLACE, REMOVE }

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
    val completed: Boolean = false,
    /** True when the last avatar save ended with AVATAR_OUTCOME_UNKNOWN (user must reconcile). */
    val avatarOutcomeUnknown: Boolean = false,
    /** True when save failed due to OCC conflict; user must refresh before retrying. */
    val avatarConflict: Boolean = false
) {
    val textDirty: Boolean get() = original?.let {
        username.trim() != it.username || displayName.trim() != it.displayName || bio.trim() != it.bio.orEmpty()
    } ?: false
    val isDirty: Boolean get() = textDirty || avatarAction != PendingAvatar.KEEP

    /** Whether the avatar retry button should be shown (partial save with retryable avatar). */
    val canRetryAvatar: Boolean get() = partialSave &&
        (avatarAction == PendingAvatar.REPLACE || avatarAction == PendingAvatar.REMOVE) &&
        !avatarOutcomeUnknown && !avatarConflict
}

/**
 * Extension to map ErrorType to a user-facing error string for avatar failures.
 * Callers pass this through to the UI state errorMessage.
 */
fun ErrorType.toAvatarMessage(): String = this.userMessage
