package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.DomainException
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.Operation
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.RegistrationOutcome
import com.doan.studyhub.core.data.model.SessionOutcome
import com.doan.studyhub.core.data.remote.AuthRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val remoteDataSource: AuthRemoteDataSource = AuthRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall()
) {
    suspend fun signUp(email: String, password: String, username: String, displayName: String): ApiResponse<RegistrationOutcome> =
        withContext(Dispatchers.IO) {
            api.execute(Operation.AUTH_SIGN_UP) {
                val id = remoteDataSource.signUp(email, password, username, displayName)
                if (id == null) RegistrationOutcome.ConfirmationRequired
                else RegistrationOutcome.Authenticated(requireProfile(id))
            }
        }

    suspend fun signIn(email: String, password: String): ApiResponse<Profile> = withContext(Dispatchers.IO) {
        api.execute(Operation.AUTH_SIGN_IN) {
            remoteDataSource.signIn(email, password)
            val id = SupabaseClient.client.auth.currentUserOrNull()?.id
                ?: throw DomainException(ErrorType.UNAUTHENTICATED)
            requireProfile(id)
        }
    }

    suspend fun signOut(): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        api.execute(Operation.AUTH_SIGN_OUT) { remoteDataSource.signOut() }
    }

    suspend fun sendPasswordResetEmail(email: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        api.execute(Operation.AUTH_PASSWORD_RESET) { remoteDataSource.sendPasswordResetEmail(email) }
    }

    suspend fun updatePassword(newPassword: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        api.execute(Operation.AUTH_PASSWORD_UPDATE) { remoteDataSource.updatePassword(newPassword) }
    }

    suspend fun getCurrentUserId(): String? = withContext(Dispatchers.IO) {
        SupabaseClient.client.auth.currentUserOrNull()?.id
    }

    suspend fun checkCurrentSession(): ApiResponse<SessionOutcome> = withContext(Dispatchers.IO) {
        api.execute(Operation.AUTH_SESSION) {
            val session = remoteDataSource.getCurrentSession()
            val id = session?.user?.id ?: if (session != null) SupabaseClient.client.auth.currentUserOrNull()?.id else null
            if (id == null) SessionOutcome.SignedOut else SessionOutcome.Authenticated(requireProfile(id))
        }
    }

    private suspend fun requireProfile(id: String): Profile {
        val profile = remoteDataSource.getProfile(id) ?: throw DomainException(ErrorType.PROFILE_NOT_FOUND)
        if (profile.isBanned) {
            remoteDataSource.signOut()
            throw DomainException(ErrorType.ACCOUNT_BANNED)
        }
        return profile
    }
}
