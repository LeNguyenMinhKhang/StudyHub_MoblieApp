package com.doan.studyhub.core.data.model

sealed interface RegistrationOutcome {
    data class Authenticated(val profile: Profile) : RegistrationOutcome
    data object ConfirmationRequired : RegistrationOutcome
}

sealed interface SessionOutcome {
    data class Authenticated(val profile: Profile) : SessionOutcome
    data object SignedOut : SessionOutcome
}
