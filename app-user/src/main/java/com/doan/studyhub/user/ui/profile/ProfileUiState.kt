package com.doan.studyhub.user.ui.profile

import com.doan.studyhub.core.data.model.PublicProfile

data class ProfileUiState(
    val isLoading: Boolean = false,
    val profile: PublicProfile? = null,
    val isOwner: Boolean = false,
    val errorMessage: String? = null,
    val unavailable: Boolean = false
)
