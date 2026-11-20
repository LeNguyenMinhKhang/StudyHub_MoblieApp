package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.DocumentFunctionException
import com.doan.studyhub.core.data.remote.DocumentStorageException
import com.doan.studyhub.core.data.remote.GroupDocumentRemoteDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.UUID

/**
 * Group PDF library and collaborative annotations. Every result is fenced to the login session
 * that started the call: a response that completes after logout/account switch is discarded.
 */
open class GroupDocumentRepository(
    private val remote: GroupDocumentRemoteDataSource = GroupDocumentRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall(),
    private val session: () -> String? = { SupabaseClient.client.auth.currentUserOrNull()?.id },
    private val sessionKey: () -> String? = { groupSessionId(SupabaseClient.client.auth.currentSessionOrNull()?.accessToken) },
    private val mapper: ExceptionMapper = ExceptionMapper()
) {
    open fun currentUserId(): String? = session()
    open fun currentSessionKey(): String? = sessionKey()

    open suspend fun documents(groupId: String, cursor: GroupCursor? = null, limit: Int = 20) = read {
        page(cursor, limit); remote.documents(uuid(groupId), cursor, limit)
    }
    open suspend fun document(id: String) = read { remote.document(uuid(id)) }
    open suspend fun versions(documentId: String, cursor: GroupCursor? = null, limit: Int = 20) = read {
        page(cursor, limit); remote.versions(uuid(documentId), cursor, limit)
    }
    open suspend fun version(id: String) = read { remote.version(uuid(id)) }
    open suspend fun archive(documentId: String) = call(Operation.DOCUMENT_MUTATE) { remote.archive(uuid(documentId)) }

    /**
     * Reserve → private upload → server validation/finalize. Retrying with the same [requestId]
     * (e.g. after a lost response) resumes the same reservation and never creates a duplicate.
     */
    open suspend fun upload(groupId: String, requestId: String, bytes: ByteArray, title: String?, documentId: String?) =
        call(Operation.DOCUMENT_UPLOAD) {
            val cleanTitle = title?.trim()?.ifEmpty { null }
            if (bytes.isEmpty()) invalid()
            if (bytes.size > MAX_PDF_BYTES) throw DomainException(ErrorType.PDF_TOO_LARGE)
            if (!hasPdfHeader(bytes)) throw DomainException(ErrorType.PDF_INVALID)
            if ((documentId == null) == (cleanTitle == null)) invalid()
            if (cleanTitle != null && cleanTitle.codePointCount(0, cleanTitle.length) > 200) invalid()
            var upload = remote.reserve(uuid(requestId), uuid(groupId), cleanTitle, bytes.size.toLong(), documentId?.let(::uuid))
            when (upload.status) {
                UploadStatus.READY -> return@call upload
                UploadStatus.FAILED -> throw DomainException(pdfFailure(upload.failureReason))
                UploadStatus.EXPIRED -> throw DomainException(ErrorType.UPLOAD_UNAVAILABLE)
                UploadStatus.PENDING -> Unit
            }
            try {
                remote.uploadObject(upload, bytes)
            } catch (error: DocumentStorageException) {
                throw storageFailure(error)
            }
            upload = try {
                remote.finalize(upload.id)
            } catch (error: DocumentFunctionException) {
                throw DomainException(functionFailure(error))
            }
            if (upload.status != UploadStatus.READY) throw DomainException(ErrorType.UPLOAD_UNAVAILABLE)
            upload
        }

    /** Downloads and verifies the immutable source bytes against the server-derived SHA-256. */
    open suspend fun download(version: DocumentVersion) = read {
        val bytes = try { remote.download(version) } catch (error: DocumentStorageException) { throw storageFailure(error) }
        if (sha256(bytes) != version.contentHash) throw DomainException(ErrorType.DOCUMENT_SERVICE_UNAVAILABLE)
        bytes
    }

    open suspend fun mutate(mutation: AnnotationMutation) = call(Operation.DOCUMENT_MUTATE) {
        validate(mutation)
        remote.mutate(mutation)
    }

    open suspend fun snapshot(versionId: String, purpose: SnapshotPurpose = SnapshotPurpose.VIEW) =
        read { remote.snapshot(uuid(versionId), purpose) }
    open suspend fun snapshotPage(snapshotId: String, after: String?, limit: Int = 200) = read {
        if (limit !in 1..500) invalid()
        remote.snapshotPage(uuid(snapshotId), after?.let(::uuid), limit)
    }
    open suspend fun changes(versionId: String, since: Long, limit: Int = 200) = read {
        if (since < 0 || limit !in 1..500) invalid()
        remote.changes(uuid(versionId), since, limit)
    }
    open suspend fun revision(versionId: String) = read { remote.revision(uuid(versionId)) }
    open suspend fun channel(groupId: String) = read { remote.channel(uuid(groupId)) }

    /** Current guide of an open call, or null guidance. Expired leases already read as none. */
    open suspend fun guidance(callId: String) = read { remote.guidance(uuid(callId)) }
    open suspend fun claimGuidance(callId: String, versionId: String, pageIndex: Int) = call(Operation.DOCUMENT_MUTATE) {
        if (pageIndex !in 0..99) invalid()
        remote.claimGuidance(uuid(callId), uuid(versionId), pageIndex)
    }
    /** Moves followers (or heartbeats on the same page). Fenced by the [generation] returned by claim. */
    open suspend fun updateGuidance(callId: String, generation: Long, versionId: String, pageIndex: Int) =
        call(Operation.DOCUMENT_MUTATE) {
            if (generation < 1 || pageIndex !in 0..99) invalid()
            remote.updateGuidance(uuid(callId), generation, uuid(versionId), pageIndex)
        }
    /** Idempotent: a stale or already-released caller gets `released = false`. */
    open suspend fun releaseGuidance(callId: String, generation: Long? = null) = call(Operation.DOCUMENT_MUTATE) {
        if (generation != null && generation < 1) invalid()
        remote.releaseGuidance(uuid(callId), generation)
    }
    open fun observe(topic: String): Flow<DocumentSignal> = remote.observe(topic)

    private suspend fun <T> read(block: suspend () -> T) = call(Operation.DOCUMENT_READ, block)
    private suspend fun <T> call(operation: Operation, block: suspend () -> T): ApiResponse<T> = api.execute(operation) {
        val actor = currentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
        val key = currentSessionKey()
        try {
            val result = block()
            if (currentUserId() != actor || currentSessionKey() != key) throw DomainException(ErrorType.UNAUTHENTICATED)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (currentUserId() != actor || currentSessionKey() != key) throw DomainException(ErrorType.UNAUTHENTICATED)
            throw error
        }
    }

    private fun validate(m: AnnotationMutation) {
        uuid(m.opId); uuid(m.versionId); uuid(m.annotationId)
        when (m.action) {
            AnnotationAction.CREATE -> {
                val kind = m.kind ?: invalid()
                val data = m.data ?: invalid()
                if (m.expectedRowVersion != null || m.pageIndex == null || m.pageIndex !in 0..99) invalid()
                val shape = AnnotationShape.parse(kind, data) ?: invalid()
                if (!AnnotationShape.isValid(shape)) invalid()
            }
            AnnotationAction.UPDATE -> {
                if (m.expectedRowVersion == null || m.expectedRowVersion < 1 || m.kind == null) invalid()
                val shape = AnnotationShape.parse(m.kind, m.data ?: invalid()) ?: invalid()
                if (!AnnotationShape.isValid(shape)) invalid()
            }
            AnnotationAction.DELETE, AnnotationAction.RESTORE ->
                if (m.expectedRowVersion == null || m.expectedRowVersion < 1 || m.data != null) invalid()
        }
    }

    private fun functionFailure(error: DocumentFunctionException): ErrorType = when (error.code) {
        "invalid_pdf" -> pdfFailure(error.reason)
        "object_missing" -> ErrorType.UPLOAD_INCOMPLETE
        "server_error", "storage_unavailable", "method_not_allowed" -> ErrorType.DOCUMENT_SERVICE_UNAVAILABLE
        else -> mapper.postgrestType(error.code, Operation.DOCUMENT_UPLOAD).let {
            if (it == ErrorType.UNKNOWN_ERROR) ErrorType.DOCUMENT_SERVICE_UNAVAILABLE else it
        }
    }

    private fun storageFailure(error: DocumentStorageException): DomainException = DomainException(
        when (error.status) {
            400, 403, 404 -> ErrorType.DOCUMENT_UNAVAILABLE // RLS denials surface as "not found"
            413 -> ErrorType.PDF_TOO_LARGE
            415 -> ErrorType.PDF_INVALID
            in 500..599 -> ErrorType.DOCUMENT_SERVICE_UNAVAILABLE
            else -> ErrorType.UNKNOWN_ERROR
        }
    )

    private fun uuid(value: String): String {
        val parsed = try { UUID.fromString(value).toString() } catch (_: IllegalArgumentException) { invalid() }
        if (!parsed.equals(value, ignoreCase = true)) invalid()
        return parsed
    }
    private fun page(cursor: GroupCursor?, limit: Int) {
        if (limit !in 1..50) invalid()
        cursor?.let { uuid(it.id) }
    }
    private fun invalid(): Nothing = throw DomainException(ErrorType.VALIDATION_FAILED)

    companion object {
        const val MAX_PDF_BYTES = 20 * 1024 * 1024
        const val MAX_PDF_PAGES = 100

        fun pdfFailure(reason: String?): ErrorType = when (reason) {
            "encrypted" -> ErrorType.PDF_ENCRYPTED
            "too_large" -> ErrorType.PDF_TOO_LARGE
            "too_many_pages" -> ErrorType.PDF_TOO_MANY_PAGES
            "missing_object" -> ErrorType.UPLOAD_INCOMPLETE
            else -> ErrorType.PDF_INVALID
        }

        fun hasPdfHeader(bytes: ByteArray): Boolean {
            val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
            return head.contains("%PDF-")
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
