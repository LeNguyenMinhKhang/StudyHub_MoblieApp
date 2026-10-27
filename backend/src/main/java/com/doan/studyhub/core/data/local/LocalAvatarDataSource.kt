package com.doan.studyhub.core.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import com.doan.studyhub.common.DomainException
import com.doan.studyhub.common.ErrorType
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AvatarMode { REMOTE, LOCAL, HIDDEN }
data class AvatarSelection(val mode: AvatarMode, val file: File? = null)

class LocalAvatarDataSource(context: Context) {
    companion object {
        @Volatile private var shared: LocalAvatarDataSource? = null
        fun shared(context: Context): LocalAvatarDataSource = shared ?: synchronized(this) {
            shared ?: LocalAvatarDataSource(context.applicationContext).also { shared = it }
        }
    }
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "avatars")
    private val mutex = Mutex()
    private val activeDrafts = Collections.synchronizedSet(mutableSetOf<String>())
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    suspend fun selection(userId: String): AvatarSelection = withContext(Dispatchers.IO) {
        val directory = directory(userId)
        val meta = AtomicFile(File(directory, "selection.txt"))
        val parts = try { meta.openRead().bufferedReader().use { it.readText() }.split('|', limit = 2) }
            catch (_: IOException) { return@withContext AvatarSelection(AvatarMode.REMOTE) }
        val mode = AvatarMode.entries.firstOrNull { it.name == parts.firstOrNull() } ?: AvatarMode.REMOTE
        if (mode != AvatarMode.LOCAL) return@withContext AvatarSelection(mode)
        val name = parts.getOrNull(1)?.takeIf { it.matches(Regex("[a-f0-9-]+\\.jpg")) }
            ?: return@withContext AvatarSelection(AvatarMode.REMOTE)
        val file = File(directory, name)
        if (file.isFile) AvatarSelection(AvatarMode.LOCAL, file) else AvatarSelection(AvatarMode.REMOTE)
    }

    suspend fun stage(uri: Uri): File = withContext(Dispatchers.IO) {
        root.mkdirs()
        cleanupExpiredDrafts()
        val source = File.createTempFile("incoming-", ".bin", root)
        val draft = File.createTempFile("draft-", ".jpg", root)
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open image")
            input.use { stream ->
                source.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var count = 0L
                    while (true) {
                        val length = stream.read(buffer)
                        if (length < 0) break
                        count += length
                        if (count > 10L * 1024 * 1024) throw DomainException(ErrorType.INVALID_IMAGE)
                        output.write(buffer, 0, length)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw DomainException(ErrorType.INVALID_IMAGE)
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw DomainException(ErrorType.INVALID_IMAGE)
            val orientation = ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                ExifInterface.ORIENTATION_TRANSPOSE -> 90f
                ExifInterface.ORIENTATION_TRANSVERSE -> 270f
                else -> 0f
            }
            val flipHorizontal = orientation in setOf(
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE
            )
            val flipVertical = orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL
            val rotated = if (degrees == 0f && !flipHorizontal && !flipVertical) bitmap else Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply {
                    if (flipHorizontal) postScale(-1f, 1f)
                    if (flipVertical) postScale(1f, -1f)
                    if (degrees != 0f) postRotate(degrees)
                }, true
            ).also { bitmap.recycle() }
            val scale = minOf(1f, 1024f / maxOf(rotated.width, rotated.height))
            val result = if (scale == 1f) rotated else Bitmap.createScaledBitmap(
                rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true
            ).also { rotated.recycle() }
            draft.outputStream().use { if (!result.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("Image encode failed") }
            result.recycle()
            activeDrafts += draft.canonicalPath
            draft
        } catch (error: Exception) {
            draft.delete()
            throw error
        } finally {
            source.delete()
        }
    }

    suspend fun commit(userId: String, draft: File): AvatarSelection = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(draft.parentFile?.canonicalFile == root.canonicalFile && draft.name.startsWith("draft-"))
            val directory = directory(userId).apply { mkdirs() }
            val old = selection(userId).file
            val target = File(directory, "${UUID.randomUUID()}.jpg")
            draft.copyTo(target)
            try {
                writeSelection(directory, AvatarMode.LOCAL, target.name)
            } catch (error: Exception) {
                target.delete()
                throw error
            }
            old?.takeIf { it != target }?.delete()
            activeDrafts.remove(draft.canonicalPath)
            draft.delete()
            _revision.value++
            AvatarSelection(AvatarMode.LOCAL, target)
        }
    }

    suspend fun hide(userId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val directory = directory(userId).apply { mkdirs() }
            val old = selection(userId).file
            writeSelection(directory, AvatarMode.HIDDEN, null)
            old?.delete()
            _revision.value++
        }
    }

    suspend fun discard(draft: File) = withContext(Dispatchers.IO) {
        if (draft.parentFile?.canonicalFile == root.canonicalFile && draft.name.startsWith("draft-")) {
            activeDrafts.remove(draft.canonicalPath)
            draft.delete()
        }
    }

    fun protectDraft(draft: File) {
        if (draft.parentFile?.canonicalFile == root.canonicalFile && draft.name.startsWith("draft-")) {
            activeDrafts += draft.canonicalPath
        }
    }

    private fun cleanupExpiredDrafts() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        root.listFiles()?.filter { it.name.startsWith("draft-") && it.lastModified() < cutoff }
            ?.forEach { if (it.canonicalPath !in activeDrafts) it.delete() }
        root.listFiles()?.filter(File::isDirectory)?.forEach { account ->
            val selected = try {
                val parts = AtomicFile(File(account, "selection.txt")).openRead().bufferedReader().use {
                    it.readText().split('|', limit = 2)
                }
                if (parts.firstOrNull() == AvatarMode.LOCAL.name) parts.getOrNull(1) else null
            } catch (_: IOException) {
                return@forEach
            }
            account.listFiles()?.filter { it.extension == "jpg" && it.name != selected && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    private fun directory(userId: String): File {
        val normalized = UUID.fromString(userId).toString()
        return File(root, normalized)
    }

    private fun writeSelection(directory: File, mode: AvatarMode, name: String?) {
        val target = AtomicFile(File(directory, "selection.txt"))
        val stream = target.startWrite()
        try {
            stream.write("${mode.name}|${name.orEmpty()}".toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }
}
