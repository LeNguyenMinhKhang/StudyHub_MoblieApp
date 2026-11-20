package com.doan.studyhub.user.ui.group.document

import androidx.lifecycle.SavedStateHandle
import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.document.DocumentLocalStore
import com.doan.studyhub.core.data.document.GuidanceController
import com.doan.studyhub.core.data.document.SaveStatus
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.repository.GroupDocumentRepository
import com.doan.studyhub.core.data.repository.GroupRepository
import com.doan.studyhub.user.ui.group.call.CallPhase
import com.doan.studyhub.user.ui.group.call.CallSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocumentViewModelsTest {
    private fun <T> T.ok() = ApiResponse.Success(this, "test")
    private fun failure(type: ErrorType) = ApiResponse.Failure(ApiError(type), "test")

    private val pdf = "%PDF-1.4\nbody".toByteArray()
    private val peer = DocumentPeer("me", "Me")
    private val versionId = "00000000-0000-4000-8000-000000000002"
    private val version = DocumentVersion(versionId, "doc", "group", "Giáo trình", 1, 2, pdf.size.toLong(), peer, true, "t",
        "group-documents", "key", GroupDocumentRepository.sha256(pdf), listOf(PageSize(612.0, 792.0), PageSize(842.0, 595.0)),
        2, 1, versionId, true)
    private val summary = DocumentVersionSummary(versionId, "doc", 1, 2, 10, peer, true, "t")
    private val document = GroupDocument("doc", "group", "Giáo trình", peer, true, summary, "t", "t")
    private val group = GroupSummary("group", "Nhóm", null, GroupSubject("s", "Môn", true), GroupRole.MEMBER, 2, "t", "t")

    private fun note(id: String, revision: Long, deleted: Boolean = false) = DocumentAnnotation(id, versionId, 0, AnnotationKind.NOTE,
        if (deleted) null else buildJsonObject { putJsonArray("anchor") { add(0.5); add(0.5) }; put("text", "Ghi chú $id"); put("color", "#FDE047") },
        AnnotationAuthor("other", "Other"), 1, revision, deleted, "t", "t")

    private inner class Docs : GroupDocumentRepository() {
        var actor: String? = "me"
        var session = "s1"
        val uploads = mutableListOf<String>()
        var uploadResult: ApiResponse<DocumentUpload> = failure(ErrorType.NETWORK_UNAVAILABLE)
        var listResult: ApiResponse<GroupPage<GroupDocument>> = GroupPage(listOf(document)).ok()
        var revisionResult: ApiResponse<DocumentRevision> = DocumentRevision(versionId, 2, versionId).ok()
        var downloads = 0
        var snapshotPages = 0
        override fun currentUserId() = actor
        override fun currentSessionKey() = session
        override suspend fun documents(groupId: String, cursor: GroupCursor?, limit: Int) = listResult
        override suspend fun document(id: String) = document.ok()
        override suspend fun versions(documentId: String, cursor: GroupCursor?, limit: Int) = GroupPage(listOf(summary)).ok()
        override suspend fun upload(groupId: String, requestId: String, bytes: ByteArray, title: String?, documentId: String?): ApiResponse<DocumentUpload> {
            uploads += requestId; return uploadResult
        }
        override suspend fun version(id: String) = version.ok()
        override suspend fun download(version: DocumentVersion): ApiResponse<ByteArray> { downloads++; return pdf.ok() }
        override suspend fun snapshot(versionId: String, purpose: SnapshotPurpose) =
            DocumentSnapshot("snap-${purpose.wire}", versionId, 2, version.contentHash, purpose.wire, "2099").ok()
        override suspend fun snapshotPage(snapshotId: String, after: String?, limit: Int): ApiResponse<DocumentSnapshotPage> {
            snapshotPages++
            // Two pages; the second contains a tombstone that must not be exported.
            return if (after == null) DocumentSnapshotPage(snapshotId, versionId, 2, listOf(note("a", 1)), "a").ok()
                else DocumentSnapshotPage(snapshotId, versionId, 2, listOf(note("b", 2, deleted = true)), null).ok()
        }
        override suspend fun changes(versionId: String, since: Long, limit: Int) = DocumentChangesPage(versionId, 2, emptyList(), null, false).ok()
        override suspend fun revision(versionId: String) = revisionResult
        var topic = "topic"
        var channelResult: ApiResponse<DocumentChannel>? = null
        val observedTopics = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun channel(groupId: String) = channelResult ?: DocumentChannel(groupId, topic).ok()
        override fun observe(topic: String): Flow<DocumentSignal> { observedTopics += topic; return emptyFlow() }

        // Acknowledging server for annotation writes (the real RPC is covered by the SQL and sync tests).
        val mutations = java.util.Collections.synchronizedList(mutableListOf<AnnotationMutation>())
        private var ackRevision = 2L
        override suspend fun mutate(mutation: AnnotationMutation): ApiResponse<AnnotationMutationResult> {
            mutations += mutation
            ackRevision++
            return AnnotationMutationResult(mutation.opId, mutation.action, ackRevision, DocumentAnnotation(mutation.annotationId, versionId,
                mutation.pageIndex ?: 0, mutation.kind ?: AnnotationKind.NOTE, mutation.data, AnnotationAuthor("me", "Me"), 1, ackRevision,
                false, "t", "t")).ok()
        }

        // Guidance server stand-in: one lease, generation-fenced (the SQL contract is tested in supabase/tests).
        var guide: DocumentGuidance? = null
        val claims = mutableListOf<Pair<String, Int>>()
        val updates = mutableListOf<Triple<Long, String, Int>>()
        val releases = mutableListOf<Long?>()
        fun guidanceOf(who: String, page: Int, sequence: Long, generation: Long = 1) = DocumentGuidance("call-1",
            DocumentPeer(who, who), "doc", versionId, page, generation, sequence, "t", who == "me")
        override suspend fun guidance(callId: String) = GuidanceState(guide).ok()
        override suspend fun claimGuidance(callId: String, versionId: String, pageIndex: Int): ApiResponse<GuidanceState> {
            claims += versionId to pageIndex
            if (guide != null && !guide!!.isMe) return failure(ErrorType.GUIDANCE_TAKEN)
            guide = guidanceOf("me", pageIndex, (guide?.sequence ?: 0) + 1)
            return GuidanceState(guide).ok()
        }
        override suspend fun updateGuidance(callId: String, generation: Long, versionId: String, pageIndex: Int): ApiResponse<GuidanceState> {
            updates += Triple(generation, versionId, pageIndex)
            guide = guidanceOf("me", pageIndex, (guide?.sequence ?: 0) + 1, generation)
            return GuidanceState(guide).ok()
        }
        override suspend fun releaseGuidance(callId: String, generation: Long?): ApiResponse<GuidanceRelease> {
            releases += generation; guide = null; return GuidanceRelease(true).ok()
        }
    }

    private inner class Groups : GroupRepository() {
        val changes = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        override fun sessionChanges() = changes
        override suspend fun detail(id: String): ApiResponse<GroupDetail> = group.ok()
    }

    private inner class Engine : DocumentExportEngine {
        var rendered: List<DocumentAnnotation>? = null
        val published = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun render(source: File, version: DocumentVersion, annotations: List<DocumentAnnotation>, revision: Long,
                                    target: File, progress: (Int, Int) -> Unit) {
            rendered = annotations
            progress(1, 2)
            gate?.await()
            target.writeText("pdf")
            progress(2, 2)
        }
        override suspend fun publish(file: File, destination: String) { assertTrue(file.isFile); published += destination }
        override fun discard(destination: String) { discarded += destination }
    }

    private fun tempStore(): DocumentLocalStore {
        val root = Files.createTempDirectory("docs").toFile()
        return DocumentLocalStore(File(root, "journal"), File(root, "cache"))
    }

    private suspend fun settle() = repeat(20) { yield() }

    @Test fun uploadRetryReusesRequestIdOnlyForTheSameFile() = runBlocking {
        val docs = Docs()
        val saved = SavedStateHandle()
        val vm = DocumentLibraryViewModel(saved, docs, Groups(), externalScope = this)
        vm.show("group"); settle()
        assertEquals(listOf("Giáo trình"), vm.state.value.rows.map { it.title })
        vm.upload(pdf, "Giáo trình"); settle()
        assertTrue(vm.state.value.canRetryUpload)
        vm.retryUpload(); settle()
        // Recreated after process death: the same file resumes the same reservation.
        val restored = DocumentLibraryViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }), docs, Groups(), externalScope = this)
        restored.show("group"); settle()
        restored.upload(pdf, "Giáo trình"); settle()
        assertEquals(1, docs.uploads.distinct().size)
        // A different file or a rejected file starts a new request.
        restored.upload("%PDF-1.4 other".toByteArray(), "Khác"); settle()
        docs.uploadResult = failure(ErrorType.PDF_ENCRYPTED)
        restored.upload(pdf, "Giáo trình"); settle()
        assertFalse(restored.state.value.canRetryUpload)
        assertNotNull(restored.state.value.actionError)
        restored.upload(pdf, "Giáo trình"); settle()
        assertEquals(4, docs.uploads.distinct().size)
        coroutineContext.cancelChildren()
    }

    @Test fun libraryClosesOnAccessLossAndAccountSwitchClearsPrivateFiles() = runBlocking {
        val docs = Docs()
        val groups = Groups()
        val store = tempStore()
        store.writePdf("other-user", versionId, pdf)
        val vm = DocumentLibraryViewModel(SavedStateHandle(), docs, groups, store, externalScope = this)
        assertNull(store.cachedPdf("other-user", versionId, version.contentHash)) // only the signed-in account is kept
        store.writePdf("me", versionId, pdf)
        vm.show("group"); settle()
        docs.actor = "someone-else"; groups.changes.tryEmit("someone-else"); settle()
        assertTrue(vm.state.value.closed)
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(store.cachedPdf("me", versionId, version.contentHash))

        val removed = Docs().apply { listResult = failure(ErrorType.GROUP_UNAVAILABLE) }
        val lost = DocumentLibraryViewModel(SavedStateHandle(), removed, Groups(), externalScope = this)
        lost.show("group"); settle()
        assertTrue(lost.state.value.closed)
        coroutineContext.cancelChildren()
    }

    @Test fun viewerCachesVerifiedPdfAndExportsOnlyCommittedSnapshot() = runBlocking {
        val docs = Docs()
        val store = tempStore()
        val engine = Engine()
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = DocumentViewerViewModel(SavedStateHandle(), docs, Groups(), store, engine, scope, pollMs = 60_000)
        vm.show(versionId, "Bạn"); settle()
        assertNotNull(vm.state.value.pdfPath)
        assertFalse(vm.sync.value.loading)
        assertEquals(1, docs.downloads)
        vm.goTo(5); assertEquals(1, vm.state.value.page) // clamped to the page count

        vm.requestExport(syncFirst = true); settle()
        assertEquals(ExportState.ChooseTarget, vm.state.value.export) // nothing pending
        vm.targetChosen()
        vm.exportTo("content://out/1"); settle()
        assertEquals(ExportState.Done, vm.state.value.export)
        assertEquals(listOf("a"), engine.rendered?.map { it.id })
        assertEquals(listOf("content://out/1"), engine.published)
        assertTrue(engine.discarded.isEmpty())

        // A second viewer for the same account reuses the verified cached PDF.
        val again = DocumentViewerViewModel(SavedStateHandle(), docs, Groups(), store, engine, scope, pollMs = 60_000)
        again.show(versionId, "Bạn"); settle()
        assertEquals(1, docs.downloads)
        scope.cancel()
    }

    @Test fun exportRevokedOrCancelledMidwayNeverPublishes() = runBlocking {
        val docs = Docs()
        val store = tempStore()
        val engine = Engine()
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = DocumentViewerViewModel(SavedStateHandle(), docs, Groups(), store, engine, scope, pollMs = 60_000)
        vm.show(versionId, "Bạn"); settle()

        engine.gate = CompletableDeferred()
        vm.exportTo("content://out/cancel"); settle()
        assertEquals(ExportState.Running(1, 2), vm.state.value.export)
        vm.cancelExport(); settle()
        assertEquals(ExportState.Cancelled, vm.state.value.export)
        assertEquals(listOf("content://out/cancel"), engine.discarded)
        assertTrue(engine.published.isEmpty())
        vm.exportAcknowledged()

        // Membership removed while rendering: no publish, partial destination removed, viewer closed.
        engine.gate = null
        docs.revisionResult = failure(ErrorType.DOCUMENT_UNAVAILABLE)
        vm.exportTo("content://out/revoked"); settle()
        assertTrue(engine.published.isEmpty())
        assertTrue("content://out/revoked" in engine.discarded)
        assertTrue(vm.state.value.closed)
        assertTrue(vm.sync.value.annotations.isEmpty())
        assertNull(store.cachedPdf("me", versionId, version.contentHash))
        scope.cancel()
    }

    // ---- 10.5: call controls + optional follow-the-guide ------------------------------------------

    private val openCall = GroupCall("call-1", "group", CallStatus.ACTIVE, CallMode.AUDIO, CallPeer("me", "Me"), "t", "t", null, 2, 6,
        true, false, "reserved")
    private fun inCall(group: String = "group", active: Boolean = true) = CallSessionState(
        phase = if (active) CallPhase.CONNECTED else CallPhase.IDLE, groupId = group, call = openCall)

    private suspend fun waitFor(condition: () -> Boolean) { withTimeout(3_000) { while (!condition()) delay(5) } }

    private fun viewer(docs: Docs, session: MutableStateFlow<CallSessionState>, scope: CoroutineScope, saved: SavedStateHandle = SavedStateHandle()) =
        DocumentViewerViewModel(saved, docs, Groups(), tempStore(), Engine(), scope, pollMs = 20, callSession = session,
            guidanceFactory = { id, following -> GuidanceController(id, docs, scope, pollMs = 20, heartbeatMs = 30, initiallyFollowing = following) })

    @Test fun guidanceExistsOnlyWhileThisGroupsCallIsAttached() = runBlocking {
        val docs = Docs()
        val scope = CoroutineScope(coroutineContext + Job())
        val session = MutableStateFlow(CallSessionState())
        val vm = viewer(docs, session, scope)
        vm.show(versionId, "Bạn"); settle()
        assertTrue(vm.guidance.value.closed) // no call: plain independent reading
        session.value = inCall("another-group") // another group call is never shown here
        settle(); assertTrue(vm.guidance.value.closed)
        session.value = inCall()
        waitFor { !vm.guidance.value.closed }
        session.value = inCall(active = false) // call ended or left: guidance is torn down
        waitFor { vm.guidance.value.closed }
        scope.cancel()
    }

    @Test fun readingIsIndependentUntilFollowIsEnabledAndFollowersNeverPush() = runBlocking {
        val docs = Docs().apply { guide = guidanceOf("other", page = 1, sequence = 3) }
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = viewer(docs, MutableStateFlow(inCall()), scope)
        vm.show(versionId, "Bạn"); settle()
        vm.setForeground(true)
        waitFor { vm.guidance.value.otherGuide != null }
        assertEquals(0, vm.state.value.page) // the guide exists but nobody was moved
        vm.setFollowing(true)
        waitFor { vm.state.value.page == 1 }
        // The follower can still read elsewhere; the same guide position is not re-applied.
        vm.goTo(0); delay(100)
        assertEquals(0, vm.state.value.page)
        assertTrue(docs.updates.isEmpty() && docs.claims.isEmpty()) // followers never push
        // A new guide move is applied once.
        docs.guide = docs.guidanceOf("other", page = 1, sequence = 4)
        waitFor { vm.state.value.page == 1 }
        // Follow off: later moves are ignored.
        vm.setFollowing(false)
        vm.goTo(0)
        docs.guide = docs.guidanceOf("other", page = 1, sequence = 5); delay(100)
        assertEquals(0, vm.state.value.page)
        scope.cancel()
    }

    @Test fun followChoiceSurvivesRecreationAndAnotherVersionIsOnlyOffered() = runBlocking {
        val elsewhere = "00000000-0000-4000-8000-0000000000ff"
        val docs = Docs().apply { guide = guidanceOf("other", page = 1, sequence = 1).copy(versionId = elsewhere) }
        val scope = CoroutineScope(coroutineContext + Job())
        val saved = SavedStateHandle()
        val first = viewer(docs, MutableStateFlow(inCall()), scope, saved)
        first.show(versionId, "Bạn"); settle()
        first.setFollowing(true)
        val restored = viewer(docs, MutableStateFlow(inCall()), scope, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        restored.show(versionId, "Bạn"); settle(); restored.setForeground(true)
        waitFor { restored.guidance.value.otherGuide != null }
        assertTrue(restored.guidance.value.following)
        assertEquals(0, restored.state.value.page) // a guide on another version never moves this viewer
        assertEquals(elsewhere, restored.guidance.value.otherGuide?.versionId) // the UI offers "open that version"
        scope.cancel()
    }

    @Test fun guidingPushesOwnNavigationAndNeverDisplacesALiveGuide() = runBlocking {
        val docs = Docs()
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = viewer(docs, MutableStateFlow(inCall()), scope)
        vm.show(versionId, "Bạn"); settle()
        waitFor { !vm.guidance.value.closed }
        vm.startGuiding()
        waitFor { vm.guidance.value.guiding }
        assertEquals(listOf(versionId to 0), docs.claims)
        vm.goTo(1)
        waitFor { docs.updates.any { it.third == 1 } }
        assertTrue(docs.updates.all { it.first == 1L && it.second == versionId })
        assertFalse(vm.guidance.value.following) // guiding and following exclude each other
        vm.stopGuiding()
        waitFor { docs.releases.isNotEmpty() }
        assertEquals(listOf<Long?>(1L), docs.releases)
        // Someone else guides: claiming is refused with a message and nothing is overwritten.
        docs.guide = docs.guidanceOf("other", page = 1, sequence = 9)
        vm.startGuiding()
        waitFor { vm.guidance.value.message == ErrorType.GUIDANCE_TAKEN }
        assertFalse(vm.guidance.value.guiding)
        assertEquals("other", docs.guide?.guide?.id)
        scope.cancel()
    }

    @Test fun annotatingStaysAvailableWhileGuidanceIsActive() = runBlocking {
        val docs = Docs().apply { guide = guidanceOf("other", page = 0, sequence = 1) }
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = viewer(docs, MutableStateFlow(inCall()), scope)
        vm.show(versionId, "Bạn"); settle(); vm.setForeground(true)
        waitFor { vm.guidance.value.otherGuide != null }
        vm.setFollowing(true)
        // Guidance only navigates: the editing entry points keep working for a follower.
        assertTrue(vm.addHighlight(0.1, 0.1, 0.3, 0.3))
        assertTrue(vm.addNote(PagePoint(0.5, 0.5), "Ghi chú khi đang theo dõi"))
        scope.cancel()
    }

    @Test fun accountSwitchClosesGuidanceAndStopsPushing() = runBlocking {
        val docs = Docs()
        val groups = Groups()
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = DocumentViewerViewModel(SavedStateHandle(), docs, groups, tempStore(), Engine(), scope, pollMs = 20,
            callSession = MutableStateFlow(inCall()),
            guidanceFactory = { id, f -> GuidanceController(id, docs, scope, pollMs = 20, heartbeatMs = 30, initiallyFollowing = f) })
        vm.show(versionId, "Bạn"); settle()
        waitFor { !vm.guidance.value.closed }
        vm.startGuiding(); waitFor { vm.guidance.value.guiding }
        docs.actor = "someone-else"; groups.changes.tryEmit("someone-else")
        waitFor { vm.state.value.closed && vm.guidance.value.closed }
        val sent = docs.updates.size
        vm.goTo(1); delay(100)
        assertEquals(sent, docs.updates.size)
        scope.cancel()
    }

    @Test fun channelEpochRotationResubscribesWithTheNewTopicAndAccessLossClosesTheViewer() = runBlocking {
        val docs = Docs()
        val store = tempStore()
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = DocumentViewerViewModel(SavedStateHandle(), docs, Groups(), store, Engine(), scope, pollMs = 20)
        vm.show(versionId, "Bạn"); settle()
        waitFor { "topic" in docs.observedTopics }
        // Membership changed elsewhere: the group epoch rotates and the next subscription uses the new topic.
        docs.topic = "topic-after-rotation"
        waitFor { "topic-after-rotation" in docs.observedTopics }
        assertFalse(vm.state.value.closed)
        // The removed member can no longer fetch a topic: the viewer locks and private data is cleared.
        docs.channelResult = failure(ErrorType.GROUP_UNAVAILABLE)
        waitFor { vm.state.value.closed }
        assertTrue(vm.sync.value.annotations.isEmpty())
        assertNull(store.cachedPdf("me", versionId, version.contentHash))
        scope.cancel()
    }

    @Test fun documentEditingDoesNotDependOnCallCapacityOrCallFailure() = runBlocking {
        val docs = Docs()
        val scope = CoroutineScope(coroutineContext + Job())
        // The call could not be joined (full room): documents stay fully usable and no guidance is attached.
        val failedCall = MutableStateFlow(CallSessionState(phase = CallPhase.FAILED, groupId = "group", error = ErrorType.CALL_FULL))
        val vm = viewer(docs, failedCall, scope)
        vm.show(versionId, "Bạn"); settle()
        assertTrue(vm.guidance.value.closed)
        assertFalse(vm.sync.value.loading)
        assertTrue(vm.addHighlight(0.1, 0.1, 0.3, 0.3))
        assertTrue(vm.addNote(PagePoint(0.5, 0.5), "Vẫn ghi chú được khi cuộc gọi đầy"))
        waitFor { docs.mutations.size == 2 } // both marks reached the server although the call never connected
        assertEquals(SaveStatus.SAVED, waitSaved(vm))
        scope.cancel()
    }

    private suspend fun waitSaved(vm: DocumentViewerViewModel): SaveStatus {
        waitFor { vm.sync.value.status == SaveStatus.SAVED && vm.sync.value.pendingCount == 0 }
        return vm.sync.value.status
    }

    @Test fun pinsAndAppendixShareOneNoteOrder() {
        fun at(id: String, page: Int, y: Double, x: Double) = note(id, 1).copy(pageIndex = page,
            data = buildJsonObject { putJsonArray("anchor") { add(x); add(y) }; put("text", id); put("color", "#FDE047") })
        val (numbers, notes) = numberNotes(listOf(at("late", 1, 0.1, 0.1), at("low", 0, 0.9, 0.1), at("top", 0, 0.1, 0.5)))
        assertEquals(listOf("top", "low", "late"), notes.map { it.text })
        assertEquals(mapOf("top" to 1, "low" to 2, "late" to 3), numbers)
    }
}
