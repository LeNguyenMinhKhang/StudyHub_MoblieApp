package com.doan.studyhub.core.data.remote

import android.util.Log
import com.doan.studyhub.core.data.SupabaseClient
import io.github.jan.supabase.storage.storage
import java.io.File

private const val BUCKET = "avatars"
private const val TAG = "AvatarRemoteDataSource"

open class AvatarRemoteDataSource(
    private val clientProvider: () -> SupabaseClient = { SupabaseClient }
) {
    private val storage get() = clientProvider().client.storage

    open suspend fun upload(objectPath: String, file: File) {
        val bytes = file.readBytes()
        storage.from(BUCKET).upload(path = objectPath, data = bytes) {
            upsert = false
            contentType = io.ktor.http.ContentType.Image.JPEG
        }
    }

    open suspend fun deleteIfUnreferenced(objectPath: String) {
        try {
            storage.from(BUCKET).delete(objectPath)
        } catch (error: Exception) {
            Log.w(TAG, "cleanup delete failed for $objectPath: ${error.javaClass.simpleName}")
        }
    }

    open fun publicUrl(objectPath: String): String = storage.from(BUCKET).publicUrl(objectPath)
}
