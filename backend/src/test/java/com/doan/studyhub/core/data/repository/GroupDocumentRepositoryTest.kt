package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.DocumentFunctionException
import com.doan.studyhub.core.data.remote.DocumentStorageException
import com.doan.studyhub.core.data.remote.GroupDocumentRemoteDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupDocumentRepositoryTest {
    private val actor = "00000000-0000-0000-0000-000000000002"
    private val group = "00000000-0000-0000-0000-000000000201"
    private val api = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> })
    private val payloads = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/group_documents/rpc_payloads.json")).readText()).jsonObject
    private inline fun <reified T> fixture(key: String): T = Json.decodeFromJsonElement(payloads.getValue(key))
    private val pdf = "%PDF-1.7\n%%EOF\n".toByteArray()

    private fun upload(status: UploadStatus, reason: String? = null) = fixture<DocumentUpload>("reserve")
        .copy(status = status, failureReason = reason, versionId = if (status == UploadStatus.READY) UUID.randomUUID().toString() else null)
    private fun repo(remote: GroupDocumentRemoteDataSource, session: () -> String? = { "s" }) =
        GroupDocumentRepository(remote, api, { actor }, session)
    private fun ApiResponse<*>.type() = (this as ApiResponse.Failure).error.type

    @Test fun decodesActualPostgresPayloads() {
        assertEquals(UploadStatus.PENDING, fixture<DocumentUpload>("reserve").status)
        val ready = fixture<DocumentUpload>("ready")
        assertEquals(UploadStatus.READY, ready.status)
        assertNotNull(ready.documentId)
        val list = fixture<GroupPage<GroupDocument>>("documents")
        val document = fixture<GroupDocument>("document")
        assertEquals(document, list.items.single())
        assertEquals("Giáo trình 📘", document.title)
        assertTrue(document.canManage)
        assertEquals(1, document.currentVersion.versionNumber)
        assertEquals(document.currentVersion, fixture<GroupPage<DocumentVersionSummary>>("versions").items.single())
        val version = fixture<DocumentVersion>("version")
        assertEquals(listOf(PageSize(612.0, 792.0), PageSize(842.0, 595.0)), version.pages)
        assertEquals(ready.versionId, version.id)
        assertEquals(64, version.contentHash.length)

        val created = fixture<AnnotationMutationResult>("created")
        val note = created.annotation.shape as AnnotationShape.Note
        assertEquals("Ghi chú 😀", note.text)
        assertEquals(created.annotation.data, note.toJson()) // client encoding round-trips server data
        assertEquals(actor, created.annotation.author.id)
        assertTrue(fixture<AnnotationMutationResult>("highlight").annotation.shape is AnnotationShape.Highlight)
        val deleted = fixture<AnnotationMutationResult>("deleted").annotation
        assertTrue(deleted.deleted)
        assertNull(deleted.data)
        assertNull(deleted.shape)
        assertEquals(2, deleted.rowVersion)

        val snapshot = fixture<DocumentSnapshot>("snapshot")
        val page = fixture<DocumentSnapshotPage>("snapshot_page")
        assertEquals(snapshot.revision, page.revision)
        assertEquals(1, page.items.size)
        assertNotNull(page.nextAfter) // two live annotations, page size one
        val changes = fixture<DocumentChangesPage>("changes")
        // Each annotation appears once at its latest revision; the deleted ink moved to revision 4.
        assertEquals(listOf(1L, 3L), changes.items.map { it.revision })
        assertEquals(3L, changes.nextSince)
        assertFalse(changes.resetRequired)
        assertEquals(snapshot.revision, fixture<DocumentRevision>("revision").revision)
        assertTrue(fixture<DocumentChannel>("channel").topic.startsWith("group-docs:"))
        assertEquals("archived", fixture<DocumentArchiveResult>("archive").status)
    }

    @Test fun payloadSizeEstimateMatchesPostgresJsonbText() {
        for (sample in payloads.getValue("jsonb_sizes").jsonArray) {
            val data = sample.jsonObject.getValue("data")
            assertEquals(data.toString(), sample.jsonObject.getValue("bytes").jsonPrimitive.int, AnnotationShape.jsonbTextBytes(data))
        }
    }

    @Test fun uploadResumesSameReservationAndMapsServerRejections() = runBlocking {
        val calls = mutableListOf<String>()
        var finalizeError: DocumentFunctionException? = null
        var reserveStatus = UploadStatus.PENDING
        val remote = object : GroupDocumentRemoteDataSource() {
            override suspend fun reserve(requestId: String, groupId: String, title: String?, sizeBytes: Long, documentId: String?): DocumentUpload {
                calls += "reserve:$requestId:$title:$sizeBytes"; return upload(reserveStatus, "too_many_pages")
            }
            override suspend fun uploadObject(upload: DocumentUpload, bytes: ByteArray) { calls += "put" }
            override suspend fun finalize(uploadId: String): DocumentUpload {
                calls += "finalize"; finalizeError?.let { throw it }; return upload(UploadStatus.READY)
            }
        }
        val repo = repo(remote)
        val request = UUID.randomUUID().toString()
        assertTrue(repo.upload(group, request, pdf, "  Đề cương 📘 ", null) is ApiResponse.Success)
        assertEquals(listOf("reserve:$request:Đề cương 📘:${pdf.size}", "put", "finalize"), calls)
        for ((error, expected) in listOf(
            DocumentFunctionException(422, "invalid_pdf", "encrypted") to ErrorType.PDF_ENCRYPTED,
            DocumentFunctionException(422, "invalid_pdf", "corrupt") to ErrorType.PDF_INVALID,
            DocumentFunctionException(409, "object_missing", null) to ErrorType.UPLOAD_INCOMPLETE,
            DocumentFunctionException(410, "D0005", null) to ErrorType.UPLOAD_UNAVAILABLE,
            DocumentFunctionException(410, "G0004", null) to ErrorType.GROUP_UNAVAILABLE,
            DocumentFunctionException(500, "server_error", null) to ErrorType.DOCUMENT_SERVICE_UNAVAILABLE,
            DocumentFunctionException(500, "XX000", null) to ErrorType.DOCUMENT_SERVICE_UNAVAILABLE,
        )) {
            finalizeError = error
            assertEquals(error.code, expected, repo.upload(group, request, pdf, "T", null).type())
        }
        finalizeError = null
        calls.clear()
        reserveStatus = UploadStatus.READY // response of a committed finalize was lost
        assertTrue(repo.upload(group, request, pdf, "T", null) is ApiResponse.Success)
        assertEquals(1, calls.size)
        reserveStatus = UploadStatus.FAILED
        assertEquals(ErrorType.PDF_TOO_MANY_PAGES, repo.upload(group, request, pdf, "T", null).type())
        reserveStatus = UploadStatus.EXPIRED
        assertEquals(ErrorType.UPLOAD_UNAVAILABLE, repo.upload(group, request, pdf, "T", null).type())
        calls.clear()
        assertEquals(ErrorType.PDF_TOO_LARGE, repo.upload(group, request, ByteArray(20 * 1024 * 1024 + 1), "T", null).type())
        assertEquals(ErrorType.PDF_INVALID, repo.upload(group, request, "PNG...".toByteArray(), "T", null).type())
        assertEquals(ErrorType.VALIDATION_FAILED, repo.upload(group, request, pdf, null, null).type())
        assertEquals(ErrorType.VALIDATION_FAILED, repo.upload(group, request, pdf, "T", UUID.randomUUID().toString()).type())
        assertEquals(ErrorType.VALIDATION_FAILED, repo.upload(group, request, pdf, "📘".repeat(201), null).type())
        assertEquals(ErrorType.VALIDATION_FAILED, repo.upload("not-a-uuid", request, pdf, "T", null).type())
        assertTrue(calls.isEmpty())
    }

    @Test fun storageFailuresAndHashMismatchAreSafe() = runBlocking {
        var bytes = pdf
        var storageError: DocumentStorageException? = null
        val remote = object : GroupDocumentRemoteDataSource() {
            override suspend fun download(version: DocumentVersion): ByteArray { storageError?.let { throw it }; return bytes }
            override suspend fun reserve(requestId: String, groupId: String, title: String?, sizeBytes: Long, documentId: String?) = upload(UploadStatus.PENDING)
            override suspend fun uploadObject(upload: DocumentUpload, bytes: ByteArray) { storageError?.let { throw it } }
        }
        val repo = repo(remote)
        val version = fixture<DocumentVersion>("version").copy(contentHash = GroupDocumentRepository.sha256(pdf))
        assertArrayEquals(pdf, (repo.download(version) as ApiResponse.Success).data)
        bytes = "%PDF-tampered".toByteArray()
        assertEquals(ErrorType.DOCUMENT_SERVICE_UNAVAILABLE, repo.download(version).type())
        storageError = DocumentStorageException(400) // RLS denial after membership loss
        assertEquals(ErrorType.DOCUMENT_UNAVAILABLE, repo.download(version).type())
        assertEquals(ErrorType.DOCUMENT_UNAVAILABLE, repo.upload(group, UUID.randomUUID().toString(), pdf, "T", null).type())
        storageError = DocumentStorageException(503)
        assertEquals(ErrorType.DOCUMENT_SERVICE_UNAVAILABLE, repo.download(version).type())
    }

    @Test fun mutationsAreValidatedLocallyBeforeTheServer() = runBlocking {
        val sent = mutableListOf<AnnotationMutation>()
        val remote = object : GroupDocumentRemoteDataSource() {
            override suspend fun mutate(mutation: AnnotationMutation): AnnotationMutationResult {
                sent += mutation; return fixture("created")
            }
        }
        val repo = repo(remote)
        fun create(shape: AnnotationShape, expected: Int? = null, page: Int? = 0) = AnnotationMutation(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            AnnotationAction.CREATE, expected, page, shape.kind, shape.toJson())
        val emoji = "😀".repeat(2000) // 2,000 code points, 4,000 UTF-16 units
        val valid = listOf(
            AnnotationShape.Note(PagePoint(0.5, 0.5), emoji, "#FDE047"),
            AnnotationShape.Highlight(0.1, 0.1, 0.2, 0.2, "#86EFAC"),
            AnnotationShape.Ink(List(4096) { PagePoint(0.5, 0.5) }, "#111827", 0.004),
        )
        val invalid = listOf(
            AnnotationShape.Note(PagePoint(0.5, 0.5), emoji + "😀", "#FDE047"),
            AnnotationShape.Note(PagePoint(0.5, 0.5), " lề ", "#FDE047"),
            AnnotationShape.Note(PagePoint(0.5, Double.NaN), "x", "#FDE047"), // off-page values clamp; NaN cannot
            AnnotationShape.Highlight(0.3, 0.1, 0.2, 0.2, "#86EFAC"),
            AnnotationShape.Ink(List(4097) { PagePoint(0.5, 0.5) }, "#111827", 0.004),
            AnnotationShape.Ink(listOf(PagePoint(0.1, 0.1)), "#000000", 0.004),
            AnnotationShape.Ink(List(4000) { PagePoint(0.12345, 0.98765) }, "#111827", 0.004), // > 64 KiB
        )
        valid.forEach { assertTrue(it.toString().take(40), repo.mutate(create(it)) is ApiResponse.Success) }
        invalid.forEach { assertEquals(it.toString().take(40), ErrorType.VALIDATION_FAILED, repo.mutate(create(it)).type()) }
        assertEquals(ErrorType.VALIDATION_FAILED, repo.mutate(create(valid[0], expected = 1)).type())
        assertEquals(ErrorType.VALIDATION_FAILED, repo.mutate(create(valid[0], page = null)).type())
        val delete = create(valid[0]).copy(action = AnnotationAction.DELETE, expectedRowVersion = 2, kind = null, data = null)
        assertTrue(repo.mutate(delete) is ApiResponse.Success)
        assertEquals(ErrorType.VALIDATION_FAILED, repo.mutate(delete.copy(expectedRowVersion = null)).type())
        assertEquals(4, sent.size)
    }

    @Test fun strokesAreSimplifiedWithinPointAndPayloadLimits() {
        val noisy = List(12000) { i -> PagePoint(i / 12000.0, 0.5 + 0.2 * kotlin.math.sin(i / 50.0) + (i % 7) * 0.00003) }
        val ink = requireNotNull(StrokeSimplifier.fit(noisy, "#2563EB", 0.004))
        assertTrue(ink.points.size <= AnnotationLimits.MAX_POINTS)
        assertTrue(AnnotationShape.jsonbTextBytes(ink.toJson()) <= AnnotationLimits.MAX_PAYLOAD_BYTES)
        assertTrue(AnnotationShape.isValid(ink))
        assertEquals(PagePoint(0.0, AnnotationShape.round4(noisy.first().y)), ink.points.first())
        assertEquals(AnnotationShape.round4(noisy.last().x), ink.points.last().x, 0.0)
        val line = List(500) { PagePoint(it / 500.0, it / 500.0) }
        assertEquals(2, StrokeSimplifier.simplify(line, 1e-6).size) // collinear points collapse to end points
        assertNull(StrokeSimplifier.fit(emptyList(), "#2563EB", 0.004))
    }

    @Test fun lateResultAfterSessionChangeIsDiscarded() = runBlocking {
        val pending = CompletableDeferred<AnnotationMutationResult>()
        var session = "first"
        val remote = object : GroupDocumentRemoteDataSource() {
            override suspend fun mutate(mutation: AnnotationMutation) = pending.await()
        }
        val repo = repo(remote) { session }
        val shape = AnnotationShape.Highlight(0.1, 0.1, 0.2, 0.2, "#86EFAC")
        val result = async {
            repo.mutate(AnnotationMutation(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                AnnotationAction.CREATE, null, 0, shape.kind, shape.toJson()))
        }
        yield()
        session = "second"
        pending.complete(fixture("created"))
        assertEquals(ErrorType.UNAUTHENTICATED, result.await().type())
    }

    // --- task 9.6: guidance (payloads captured from the real local Postgres RPCs) -------------
    private val guidancePayloads = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/group_documents/guidance_payloads.json")).readText()).jsonObject
    private inline fun <reified T> guidanceFixture(key: String): T = Json.decodeFromJsonElement(guidancePayloads.getValue(key))
    private val call = "00000000-0000-0000-0000-000000000301"
    private val version = "00000000-0000-0000-0000-000000000401"

    @Test fun decodesActualGuidancePayloads() {
        val claim = guidanceFixture<GuidanceState>("claim").guidance!!
        assertTrue(claim.isMe)
        assertEquals(Triple(1L, 1L, 0), Triple(claim.generation, claim.sequence, claim.pageIndex))
        assertEquals("Test 0", claim.guide.displayName)
        assertNull(claim.guide.avatarUrl)
        val follower = guidanceFixture<GuidanceState>("get_follower").guidance!!
        assertFalse(follower.isMe)
        assertEquals(claim.copy(isMe = false), follower) // same projection, only is_me differs
        val moved = guidanceFixture<GuidanceState>("update").guidance!!
        assertEquals(Triple(1L, 2L, 1), Triple(moved.generation, moved.sequence, moved.pageIndex))
        assertTrue(guidanceFixture<GuidanceRelease>("release").released)
        assertFalse(guidanceFixture<GuidanceRelease>("release_again").released)
        assertNull(guidanceFixture<GuidanceState>("get_none").guidance)
    }

    @Test fun guidanceRpcsAreValidatedAndNeverSendBadInput() = runBlocking {
        val sent = mutableListOf<String>()
        val remote = object : GroupDocumentRemoteDataSource() {
            override suspend fun guidance(callId: String): GuidanceState { sent += "get:$callId"; return guidanceFixture("get_follower") }
            override suspend fun claimGuidance(callId: String, versionId: String, pageIndex: Int): GuidanceState {
                sent += "claim:$callId:$versionId:$pageIndex"; return guidanceFixture("claim")
            }
            override suspend fun updateGuidance(callId: String, generation: Long, versionId: String, pageIndex: Int): GuidanceState {
                sent += "update:$generation:$pageIndex"; return guidanceFixture("update")
            }
            override suspend fun releaseGuidance(callId: String, generation: Long?): GuidanceRelease {
                sent += "release:$generation"; return guidanceFixture("release")
            }
        }
        val repo = repo(remote)
        assertEquals(1L, (repo.claimGuidance(call, version, 0) as ApiResponse.Success).data.guidance?.generation)
        assertEquals(1, (repo.updateGuidance(call, 1, version, 1) as ApiResponse.Success).data.guidance?.pageIndex)
        assertTrue((repo.releaseGuidance(call) as ApiResponse.Success).data.released)
        assertTrue((repo.releaseGuidance(call, 1) as ApiResponse.Success).data.released)
        assertTrue(repo.guidance(call) is ApiResponse.Success)
        assertEquals(listOf("claim:$call:$version:0", "update:1:1", "release:null", "release:1", "get:$call"), sent)
        sent.clear()
        val invalid: List<suspend () -> ApiResponse<*>> = listOf(
            { repo.claimGuidance(call, version, 100) }, { repo.claimGuidance(call, version, -1) },
            { repo.claimGuidance("not-a-uuid", version, 0) }, { repo.claimGuidance(call, "x", 0) },
            { repo.updateGuidance(call, 0, version, 0) }, { repo.updateGuidance(call, 1, version, 100) },
            { repo.releaseGuidance(call, 0) }, { repo.releaseGuidance("nope") }, { repo.guidance("nope") },
        )
        for (bad in invalid) assertEquals(ErrorType.VALIDATION_FAILED, bad().type())
        assertTrue(sent.isEmpty())
    }

    @Test fun guidanceErrorsAndLateResultsAfterSessionChange() = runBlocking {
        val mapper = ExceptionMapper()
        assertEquals(ErrorType.GUIDANCE_TAKEN, mapper.postgrestType("D0007", Operation.DOCUMENT_MUTATE))
        assertEquals(ErrorType.GUIDANCE_LOST, mapper.postgrestType("D0008", Operation.DOCUMENT_MUTATE))
        assertEquals(ErrorType.CALL_UNAVAILABLE, mapper.postgrestType("C0003", Operation.DOCUMENT_MUTATE))
        assertEquals(ErrorType.CALL_ENDING, mapper.postgrestType("C0004", Operation.DOCUMENT_MUTATE))
        // Neither guidance error is retryable: the UI falls back to free reading instead of looping.
        assertFalse(ErrorType.GUIDANCE_TAKEN.retryable || ErrorType.GUIDANCE_LOST.retryable)
        val pending = CompletableDeferred<GuidanceState>()
        var session = "first"
        val remote = object : GroupDocumentRemoteDataSource() {
            override suspend fun guidance(callId: String) = pending.await()
        }
        val result = async { repo(remote) { session }.guidance(call) }
        yield()
        session = "second"
        pending.complete(guidanceFixture("get_follower"))
        assertEquals(ErrorType.UNAUTHENTICATED, result.await().type())
    }

    @Test fun documentSqlStatesMapToStableTypes() {
        val mapper = ExceptionMapper()
        mapOf("D0001" to ErrorType.DOCUMENT_UNAVAILABLE, "D0002" to ErrorType.ANNOTATION_CONFLICT,
            "D0003" to ErrorType.OPERATION_CONFLICT, "D0004" to ErrorType.ANNOTATION_LIMIT,
            "D0005" to ErrorType.UPLOAD_UNAVAILABLE, "D0006" to ErrorType.SNAPSHOT_EXPIRED,
            "22P02" to ErrorType.VALIDATION_FAILED).forEach { (code, type) ->
            assertEquals(code, type, mapper.postgrestType(code, Operation.DOCUMENT_MUTATE))
        }
    }
}
