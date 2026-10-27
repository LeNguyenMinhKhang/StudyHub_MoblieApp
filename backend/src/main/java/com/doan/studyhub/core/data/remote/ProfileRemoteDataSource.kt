package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.PublicProfile
import com.doan.studyhub.core.data.model.UpdateProfileRequest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

open class ProfileRemoteDataSource(
    private val clientProvider: () -> com.doan.studyhub.core.data.SupabaseClient = { com.doan.studyhub.core.data.SupabaseClient }
) {
    private val postgrest get() = clientProvider().client.postgrest

    open suspend fun getMine(): Profile? = postgrest.rpc("get_my_profile").decodeSingleOrNull<Profile>()

    open suspend fun getPublicById(id: String): PublicProfile? = postgrest.rpc(
        "get_public_profile_by_id", buildJsonObject { put("p_id", id) }
    ).decodeSingleOrNull<PublicProfile>()

    open suspend fun getPublicByUsername(username: String): PublicProfile? = postgrest.rpc(
        "get_public_profile_by_username", buildJsonObject { put("p_username", username) }
    ).decodeSingleOrNull<PublicProfile>()

    open suspend fun updateMine(request: UpdateProfileRequest): Profile? = postgrest.rpc(
        "update_my_profile",
        buildJsonObject {
            put("p_username", request.username)
            put("p_display_name", request.displayName)
            put("p_bio", request.bio?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        }
    ).decodeSingleOrNull<Profile>()

    open suspend fun updateMyAvatar(objectPath: String?, expectedAvatarUrl: String?): Profile? = postgrest.rpc(
        "update_my_avatar",
        buildJsonObject {
            if (objectPath != null) put("p_object_path", objectPath) else put("p_object_path", JsonNull)
            if (expectedAvatarUrl != null) put("p_expected_avatar_url", expectedAvatarUrl)
            else put("p_expected_avatar_url", JsonNull)
        }
    ).decodeSingleOrNull<Profile>()
}
