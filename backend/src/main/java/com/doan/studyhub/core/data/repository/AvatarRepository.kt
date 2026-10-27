package com.doan.studyhub.core.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.DomainException
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.Operation
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.local.AvatarPendingStore
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.LocalAvatarDataSource
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.remote.AvatarRemoteDataSource
import com.doan.studyhub.core.data.remote.ProfileRemoteDataSource
import io.github.jan.supabase.auth.auth
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AvatarRepository"

class AvatarRepository(
    context: Context? = null,
    private val local: LocalAvatarDataSource? = context?.let { LocalAvatarDataSource.shared(it) },
    private val remote: AvatarRemoteDataSource = AvatarRemoteDataSource(),
    private val profileRemote: ProfileRemoteDataSource = ProfileRemoteDataSource(),
    private val pendingStore: AvatarPendingStore = context?.let { AvatarPendingStore.shared(it) } ?: AvatarPendingStore(),
    private val api: SafeApiCall = SafeApiCall(),
    private val ownerCheck: (String) -> Boolean = { userId ->
        SupabaseClient.client.auth.currentUserOrNull()?.id == userId
    }
) {
    val revision: StateFlow<Long> = local?.revision ?: kotlinx.coroutines.flow.MutableStateFlow(0L)

    suspend fun selection(userId: String): AvatarSelection =
        if (isOwner(userId)) local?.selection(userId) ?: AvatarSelection(AvatarMode.REMOTE) else AvatarSelection(AvatarMode.REMOTE)

    suspend fun stage(uri: Uri): ApiResponse<File> = api.execute(Operation.AVATAR_STAGE) {
        local?.stage(uri) ?: throw DomainException(ErrorType.INVALID_IMAGE)
    }

    fun protectDraft(draft: File) { local?.protectDraft(draft) }
    suspend fun discard(draft: File) { local?.discard(draft) }

    suspend fun commitRemote(
        userId: String,
        draft: File,
        expectedAvatarUrl: String?
    ): ApiResponse<Profile> {
        if (!isOwner(userId)) return api.failure(Operation.AVATAR_UPLOAD, ErrorType.PERMISSION_DENIED)

        val objectPath = "${UUID.fromString(userId)}/${UUID.randomUUID()}.jpg"

        pendingStore.save(userId, AvatarPendingStore.Record(
            status = AvatarPendingStore.Status.UPLOADING,
            objectPath = objectPath,
            expectedUrl = expectedAvatarUrl
        ))

        val uploadResult = api.execute(Operation.AVATAR_UPLOAD) { remote.upload(objectPath, draft) }
        if (uploadResult is ApiResponse.Failure) {
            pendingStore.clear(userId)
            return uploadResult
        }

        pendingStore.save(userId, AvatarPendingStore.Record(
            status = AvatarPendingStore.Status.PUBLISHING,
            objectPath = objectPath,
            expectedUrl = expectedAvatarUrl
        ))
        val publishResult = api.execute(Operation.AVATAR_PUBLISH) {
            profileRemote.updateMyAvatar(objectPath, expectedAvatarUrl)
                ?: throw DomainException(ErrorType.PROFILE_NOT_FOUND)
        }

        return when (publishResult) {
            is ApiResponse.Success -> {
                pendingStore.save(userId, AvatarPendingStore.Record(
                    status = AvatarPendingStore.Status.CLEANUP,
                    objectPath = objectPath,
                    expectedUrl = expectedAvatarUrl
                ))
                cleanupOldObject(expectedAvatarUrl)
                pendingStore.clear(userId)
                publishResult
            }
            is ApiResponse.Failure -> {
                val errorType = publishResult.error.type
                if (errorType == ErrorType.REQUEST_TIMEOUT || errorType == ErrorType.NETWORK_UNAVAILABLE) {
                    Log.w(TAG, "publish outcome unknown for $userId objectPath=$objectPath")
                    return api.failure(Operation.AVATAR_PUBLISH, ErrorType.AVATAR_OUTCOME_UNKNOWN)
                }
                pendingStore.save(userId, AvatarPendingStore.Record(
                    status = AvatarPendingStore.Status.CLEANUP,
                    objectPath = objectPath,
                    expectedUrl = null
                ))
                remote.deleteIfUnreferenced(objectPath)
                pendingStore.clear(userId)
                publishResult
            }
        }
    }

    suspend fun removeRemote(
        userId: String,
        expectedAvatarUrl: String?
    ): ApiResponse<Profile> {
        if (!isOwner(userId)) return api.failure(Operation.AVATAR_PUBLISH, ErrorType.PERMISSION_DENIED)

        pendingStore.save(userId, AvatarPendingStore.Record(
            status = AvatarPendingStore.Status.PUBLISHING,
            objectPath = null,
            expectedUrl = expectedAvatarUrl
        ))

        val result = api.execute(Operation.AVATAR_PUBLISH) {
            profileRemote.updateMyAvatar(null, expectedAvatarUrl)
                ?: throw DomainException(ErrorType.PROFILE_NOT_FOUND)
        }

        return when (result) {
            is ApiResponse.Success -> {
                cleanupOldObject(expectedAvatarUrl)
                pendingStore.clear(userId)
                result
            }
            is ApiResponse.Failure -> {
                val errorType = result.error.type
                if (errorType == ErrorType.REQUEST_TIMEOUT || errorType == ErrorType.NETWORK_UNAVAILABLE) {
                    Log.w(TAG, "remove outcome unknown for $userId")
                    return api.failure(Operation.AVATAR_PUBLISH, ErrorType.AVATAR_OUTCOME_UNKNOWN)
                }
                pendingStore.clear(userId)
                result
            }
        }
    }

    suspend fun reconcilePending(userId: String) {
        if (!isOwner(userId)) return
        val record = pendingStore.load(userId) ?: return

        when (record.status) {
            AvatarPendingStore.Status.PUBLISHING -> {
                val profile = runCatching { profileRemote.getMine() }.getOrNull()
                val newObjectPath = record.objectPath
                val newUrl = newObjectPath?.let { remote.publicUrl(it) }
                if (profile != null) {
                    if (newUrl != null && profile.avatarUrl == newUrl) {
                        Log.i(TAG, "reconcile: publish confirmed for $userId, cleaning up old object")
                        cleanupOldObject(record.expectedUrl)
                    } else {
                        Log.i(TAG, "reconcile: publish not applied for $userId, cleaning up new object")
                        newObjectPath?.let { runCatching { remote.deleteIfUnreferenced(it) } }
                    }
                    pendingStore.clear(userId)
                } else {
                    Log.i(TAG, "reconcile: profile lookup failed for $userId, keeping pending record")
                }
            }
            AvatarPendingStore.Status.CLEANUP -> {
                record.objectPath?.let { runCatching { remote.deleteIfUnreferenced(it) } }
                pendingStore.clear(userId)
            }
            AvatarPendingStore.Status.UPLOADING -> {
                record.objectPath?.let { runCatching { remote.deleteIfUnreferenced(it) } }
                pendingStore.clear(userId)
            }
            AvatarPendingStore.Status.DONE -> pendingStore.clear(userId)
        }
    }

    private fun isOwner(userId: String) = ownerCheck(userId)

    private suspend fun cleanupOldObject(previousAvatarUrl: String?) {
        if (previousAvatarUrl.isNullOrBlank()) return
        val marker = "/object/public/avatars/"
        val idx = previousAvatarUrl.indexOf(marker)
        if (idx < 0) return
        val oldPath = previousAvatarUrl.substring(idx + marker.length)
        if (oldPath.isBlank()) return
        try {
            remote.deleteIfUnreferenced(oldPath)
        } catch (e: Exception) {
            Log.w(TAG, "cleanupOldObject failed for $oldPath: ${e.javaClass.simpleName}")
        }
    }
}
