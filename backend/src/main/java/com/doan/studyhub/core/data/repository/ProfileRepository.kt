package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.DomainException
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.Operation
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.PublicProfile
import com.doan.studyhub.core.data.model.UpdateProfileRequest
import com.doan.studyhub.core.data.remote.ProfileRemoteDataSource
import io.github.jan.supabase.auth.auth
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileRepository(
    private val remote: ProfileRemoteDataSource = ProfileRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall(),
    private val validator: ProfileValidator = ProfileValidator()
) {
    fun currentUserId(): String? = SupabaseClient.client.auth.currentUserOrNull()?.id

    suspend fun getMine(): ApiResponse<Profile> = withContext(Dispatchers.IO) {
        api.execute(Operation.PROFILE_READ) {
            requireSession()
            remote.getMine() ?: throw DomainException(ErrorType.PROFILE_NOT_FOUND)
        }
    }

    suspend fun getPublic(id: String): ApiResponse<PublicProfile?> = withContext(Dispatchers.IO) {
        api.execute(Operation.PROFILE_READ) {
            requireSession()
            try { UUID.fromString(id) } catch (_: IllegalArgumentException) { throw DomainException(ErrorType.VALIDATION_FAILED) }
            remote.getPublicById(id)
        }
    }

    suspend fun findByUsername(username: String): ApiResponse<PublicProfile?> = withContext(Dispatchers.IO) {
        api.execute(Operation.PROFILE_LOOKUP) {
            requireSession()
            if (username.isBlank()) throw DomainException(ErrorType.VALIDATION_FAILED)
            remote.getPublicByUsername(username.trim())
        }
    }

    suspend fun updateMine(request: UpdateProfileRequest, originalUsername: String? = null): ApiResponse<Profile> =
        withContext(Dispatchers.IO) {
            val errors = validator.validate(request.username, request.displayName, request.bio, originalUsername)
            if (errors.isNotEmpty()) return@withContext api.failure(Operation.PROFILE_UPDATE, ErrorType.VALIDATION_FAILED, errors)
            api.execute(Operation.PROFILE_UPDATE) {
                val owner = requireSession()
                val updated = remote.updateMine(request.copy(
                    username = request.username.trim(),
                    displayName = request.displayName.trim(),
                    bio = request.bio?.trim()?.ifEmpty { null }
                )) ?: throw DomainException(ErrorType.PROFILE_NOT_FOUND)
                if (updated.id != owner || currentUserId() != owner) throw DomainException(ErrorType.PERMISSION_DENIED)
                updated
            }
        }

    private fun requireSession(): String = SupabaseClient.client.auth.currentUserOrNull()?.id
        ?: throw DomainException(ErrorType.UNAUTHENTICATED)
}
