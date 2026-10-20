package com.doan.studyhub.core.data.repository

import android.content.Context
import android.net.Uri
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.DomainException
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.Operation
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.local.LocalAvatarDataSource
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.local.AvatarMode
import io.github.jan.supabase.auth.auth
import java.io.File
import kotlinx.coroutines.flow.StateFlow

class AvatarRepository(context: Context, private val api: SafeApiCall = SafeApiCall()) {
    private val local = LocalAvatarDataSource.shared(context)
    val revision: StateFlow<Long> = local.revision

    suspend fun selection(userId: String): AvatarSelection = if (isOwner(userId)) local.selection(userId)
        else AvatarSelection(AvatarMode.REMOTE)
    suspend fun stage(uri: Uri): ApiResponse<File> = api.execute(Operation.AVATAR_STAGE) { local.stage(uri) }
    suspend fun commit(userId: String, draft: File): ApiResponse<AvatarSelection> =
        api.execute(Operation.AVATAR_COMMIT) {
            if (!isOwner(userId)) throw DomainException(ErrorType.PERMISSION_DENIED)
            local.commit(userId, draft)
        }
    suspend fun hide(userId: String): ApiResponse<Unit> = api.execute(Operation.AVATAR_REMOVE) {
        if (!isOwner(userId)) throw DomainException(ErrorType.PERMISSION_DENIED)
        local.hide(userId)
    }
    suspend fun discard(draft: File) = local.discard(draft)
    fun protectDraft(draft: File) = local.protectDraft(draft)

    private fun isOwner(userId: String) = SupabaseClient.client.auth.currentUserOrNull()?.id == userId
}
