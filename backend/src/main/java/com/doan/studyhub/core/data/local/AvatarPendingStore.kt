package com.doan.studyhub.core.data.local

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "AvatarPendingStore"

open class AvatarPendingStore(context: Context? = null) {
    enum class Status { UPLOADING, PUBLISHING, CLEANUP, DONE }

    data class Record(
        val status: Status,
        val objectPath: String?,
        val expectedUrl: String?
    )

    companion object {
        @Volatile private var instance: AvatarPendingStore? = null
        fun shared(context: Context): AvatarPendingStore = instance ?: synchronized(this) {
            instance ?: AvatarPendingStore(context.applicationContext).also { instance = it }
        }
    }

    private val root = context?.applicationContext?.noBackupFilesDir?.let { File(it, "avatar_pending") }

    open suspend fun save(userId: String, record: Record) = withContext(Dispatchers.IO) {
        val rootDir = root ?: return@withContext
        try {
            val dir = File(rootDir, userId).apply { mkdirs() }
            val af = AtomicFile(File(dir, "pending.txt"))
            val stream = af.startWrite()
            try {
                val line = "${record.status.name}|${record.objectPath.orEmpty()}|${record.expectedUrl.orEmpty()}"
                stream.write(line.toByteArray(Charsets.UTF_8))
                af.finishWrite(stream)
            } catch (e: Exception) {
                af.failWrite(stream)
                throw e
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to save pending record for $userId: ${e.javaClass.simpleName}")
        }
    }

    open suspend fun load(userId: String): Record? = withContext(Dispatchers.IO) {
        val rootDir = root ?: return@withContext null
        try {
            val file = File(File(rootDir, userId), "pending.txt")
            if (!file.exists()) return@withContext null
            val line = AtomicFile(file).openRead().bufferedReader().use { it.readText() }.trim()
            val parts = line.split('|', limit = 3)
            val status = Status.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return@withContext null
            Record(
                status = status,
                objectPath = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
                expectedUrl = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
            )
        } catch (_: IOException) {
            null
        }
    }

    open suspend fun clear(userId: String) = withContext(Dispatchers.IO) {
        val rootDir = root ?: return@withContext
        try {
            File(File(rootDir, userId), "pending.txt").delete()
        } catch (_: Exception) {}
        Unit
    }
}
