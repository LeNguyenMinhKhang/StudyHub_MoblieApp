package com.doan.studyhub.core.data.document

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * App-private storage for collaborative documents, partitioned by account:
 * - [journalRoot] (no-backup files): pending annotation ops per version, written atomically;
 * - [cacheRoot] (cache): verified source PDFs and temporary export files.
 * No tokens are stored. Other accounts' data is deleted on account switch/logout.
 */
class DocumentLocalStore(private val journalRoot: File, private val cacheRoot: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(PendingAnnotationOp.serializer())
    private val lock = Any()

    private fun account(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private fun safe(id: String): String = UUID.fromString(id).toString()
    private fun journalFile(userId: String, versionId: String) = File(File(journalRoot, account(userId)), "${safe(versionId)}.json")

    fun journal(userId: String, versionId: String): List<PendingAnnotationOp> = synchronized(lock) {
        val file = journalFile(userId, versionId)
        if (!file.isFile) return emptyList()
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse {
            file.delete() // corrupt journal: never replay partial/unknown data
            emptyList()
        }
    }

    fun saveJournal(userId: String, versionId: String, ops: List<PendingAnnotationOp>) = synchronized(lock) {
        val file = journalFile(userId, versionId)
        if (ops.isEmpty()) { file.delete(); return@synchronized }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(serializer, ops))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "journal write failed" }
        }
    }

    /** Pending (unsynced or conflicting) ops of [userId] across all versions. */
    fun pendingCount(userId: String): Int = synchronized(lock) {
        File(journalRoot, account(userId)).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sumOf { f ->
            runCatching { json.decodeFromString(serializer, f.readText()).size }.getOrDefault(0)
        }
    }

    fun cachedPdf(userId: String, versionId: String, contentHash: String): File? {
        val file = pdfFile(userId, versionId)
        if (!file.isFile) return null
        if (sha256(file) == contentHash) return file
        file.delete()
        return null
    }

    fun writePdf(userId: String, versionId: String, bytes: ByteArray): File {
        val file = pdfFile(userId, versionId)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".part")
        temp.writeBytes(bytes)
        file.delete()
        check(temp.renameTo(file)) { "cache write failed" }
        return file
    }

    fun exportFile(userId: String): File =
        File(File(File(cacheRoot, account(userId)), "exports"), "${UUID.randomUUID()}.pdf").also { it.parentFile?.mkdirs() }

    /** Removes the private data of one version (e.g. after access to it was revoked). */
    fun clearVersion(userId: String, versionId: String) = synchronized(lock) {
        journalFile(userId, versionId).delete()
        pdfFile(userId, versionId).delete()
    }

    /** Deletes every other account's journals, PDFs and exports. */
    fun retainOnly(userId: String) = synchronized(lock) {
        val keep = account(userId)
        listOf(journalRoot, cacheRoot).forEach { root ->
            root.listFiles().orEmpty().filter { it.name != keep }.forEach { it.deleteRecursively() }
        }
        File(File(cacheRoot, keep), "exports").deleteRecursively() // abandoned partial exports
    }

    fun clearAll() = synchronized(lock) {
        journalRoot.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    private fun pdfFile(userId: String, versionId: String) = File(File(File(cacheRoot, account(userId)), "pdf"), "${safe(versionId)}.pdf")

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
