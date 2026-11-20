package com.doan.studyhub.core.data.document

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.GroupDocumentRemoteDataSource
import com.doan.studyhub.core.data.repository.GroupDocumentRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.UUID

/**
 * In-memory server with the SQL contract's semantics (CAS row versions, op ledger, per-version
 * revisions, tombstones, snapshot-at-revision). SQL behaviour itself is covered by
 * supabase/tests/group_documents; this exercises the client sync logic against it.
 */
private class FakeServer(val versionId: String) {
    data class Row(val a: DocumentAnnotation)
    val history = mutableListOf<DocumentAnnotation>() // one entry per revision
    val current = LinkedHashMap<String, DocumentAnnotation>()
    val ledger = HashMap<Pair<String, String>, Pair<AnnotationMutation, AnnotationMutationResult>>()
    val snapshots = HashMap<String, Long>()
    var revision = 0L
    var members = mutableSetOf<String>()
    var offline = false
    var failAfterCommit = false
    var mutateCalls = 0
    var pageLimit = 2

    @Synchronized fun mutate(actor: String, m: AnnotationMutation, owner: Boolean = false): AnnotationMutationResult {
        if (offline) throw UnknownHostException()
        mutateCalls++
        if (actor !in members) throw DomainException(ErrorType.DOCUMENT_UNAVAILABLE)
        ledger[actor to m.opId]?.let { (prior, result) ->
            if (prior != m) throw DomainException(ErrorType.OPERATION_CONFLICT)
            return result
        }
        val existing = current[m.annotationId]
        val now = "2026-11-17T00:00:${revision.toString().padStart(2, '0')}Z"
        val next: DocumentAnnotation = when (m.action) {
            AnnotationAction.CREATE -> {
                if (existing != null) throw DomainException(ErrorType.ANNOTATION_CONFLICT)
                DocumentAnnotation(m.annotationId, versionId, m.pageIndex!!, m.kind!!, m.data, AnnotationAuthor(actor, "U-$actor".take(10)),
                    1, revision + 1, false, now, now)
            }
            else -> {
                existing ?: throw DomainException(ErrorType.DOCUMENT_UNAVAILABLE)
                if (existing.author.id != actor && !owner) throw DomainException(ErrorType.PERMISSION_DENIED)
                if (existing.rowVersion != m.expectedRowVersion) throw DomainException(ErrorType.ANNOTATION_CONFLICT)
                when (m.action) {
                    AnnotationAction.UPDATE -> if (existing.deleted) throw DomainException(ErrorType.ANNOTATION_CONFLICT)
                        else existing.copy(data = m.data, rowVersion = existing.rowVersion + 1, revision = revision + 1)
                    AnnotationAction.DELETE -> if (existing.deleted) throw DomainException(ErrorType.ANNOTATION_CONFLICT)
                        else existing.copy(deleted = true, rowVersion = existing.rowVersion + 1, revision = revision + 1)
                    else -> if (!existing.deleted) throw DomainException(ErrorType.ANNOTATION_CONFLICT)
                        else existing.copy(deleted = false, rowVersion = existing.rowVersion + 1, revision = revision + 1)
                }
            }
        }
        revision++
        val stored = if (next.deleted) next.copy(data = null) else next
        val full = next.copy(data = next.data ?: existing?.data)
        current[m.annotationId] = full
        history += stored
        val result = AnnotationMutationResult(m.opId, m.action, revision, stored)
        ledger[actor to m.opId] = m to result
        if (failAfterCommit) { failAfterCommit = false; throw UnknownHostException() } // committed, ACK lost
        return result
    }

    fun view(a: DocumentAnnotation) = if (a.deleted) a.copy(data = null) else a

    @Synchronized fun snapshot(actor: String): DocumentSnapshot {
        if (actor !in members) throw DomainException(ErrorType.DOCUMENT_UNAVAILABLE)
        val id = UUID.randomUUID().toString()
        snapshots[id] = revision
        return DocumentSnapshot(id, versionId, revision, "h", "view", "2099-01-01T00:00:00Z")
    }

    @Synchronized fun snapshotPage(actor: String, id: String, after: String?, onPage: () -> Unit): DocumentSnapshotPage {
        if (actor !in members) throw DomainException(ErrorType.DOCUMENT_UNAVAILABLE)
        val at = snapshots.getValue(id)
        val states = history.filter { it.revision <= at }.groupBy { it.id }.mapValues { it.value.last() }
            .values.filter { !it.deleted }.sortedBy { it.id }.filter { after == null || it.id > after }
        val page = states.take(pageLimit)
        onPage()
        return DocumentSnapshotPage(id, versionId, at, page, if (states.size > pageLimit) page.last().id else null)
    }

    @Synchronized fun changes(actor: String, since: Long): DocumentChangesPage {
        if (offline) throw UnknownHostException()
        if (actor !in members) throw DomainException(ErrorType.DOCUMENT_UNAVAILABLE)
        if (since > revision) return DocumentChangesPage(versionId, revision, emptyList(), null, true)
        val items = current.values.filter { it.revision > since }.sortedBy { it.revision }.map(::view)
        val page = items.take(pageLimit)
        return DocumentChangesPage(versionId, revision, page, if (items.size > pageLimit) page.last().revision else null, false)
    }
}

class DocumentSyncControllerTest {
    private val me = "00000000-0000-0000-0000-00000000000a"
    private val other = "00000000-0000-0000-0000-00000000000b"
    private val versionId = "00000000-0000-0000-0000-0000000000f1"
    private val root: File = Files.createTempDirectory("doc-sync").toFile()
    private val store = DocumentLocalStore(File(root, "journal"), File(root, "cache"))
    private val server = FakeServer(versionId).apply { members += setOf(me, other) }
    private val api = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> })
    private val version = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(DocumentVersion.serializer(),
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/group_documents/rpc_payloads.json")).readText())
            .jsonObject.getValue("version")).copy(id = versionId, currentVersionId = versionId)
    private var onSnapshotPage: () -> Unit = {}
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cleanup() { scopes.forEach { it.cancel() }; root.deleteRecursively() }

    private fun repo(actor: String) = GroupDocumentRepository(object : GroupDocumentRemoteDataSource() {
        override suspend fun snapshot(versionId: String, purpose: SnapshotPurpose) = server.snapshot(actor)
        override suspend fun snapshotPage(snapshotId: String, after: String?, limit: Int) =
            server.snapshotPage(actor, snapshotId, after, onSnapshotPage)
        override suspend fun changes(versionId: String, since: Long, limit: Int) = server.changes(actor, since)
        override suspend fun mutate(mutation: AnnotationMutation) = server.mutate(actor, mutation)
        override suspend fun revision(versionId: String): DocumentRevision {
            if (server.offline) throw UnknownHostException()
            if (actor !in server.members) throw DomainException(ErrorType.DOCUMENT_UNAVAILABLE) // RPC checks access
            return DocumentRevision(versionId, server.revision, versionId)
        }
    }, api, { actor }, { "session" })

    private fun controller(actor: String = me, owner: Boolean = false): DocumentSyncController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return DocumentSyncController(version, AnnotationAuthor(actor, "Me"), owner, repo(actor), store, scope,
            retryDelayMs = { 20L }).also { it.start() }
    }

    private fun await(c: DocumentSyncController, what: String = "condition", predicate: (SyncState) -> Boolean): SyncState =
        runBlocking {
            withTimeoutOrNull(5_000) {
                while (!predicate(c.sync.value)) delay(5)
                c.sync.value
            } ?: fail("timed out waiting for $what: ${c.sync.value}").let { c.sync.value }
        }

    private val note = AnnotationShape.Note(PagePoint(0.2, 0.3), "Ghi chú tiếng Việt 😀", "#FDE047")
    private fun ink(y: Double) = AnnotationShape.Ink(listOf(PagePoint(0.1, y), PagePoint(0.4, y)), "#DC2626", 0.004)
    private fun seed(actor: String, shape: AnnotationShape) = server.mutate(actor, AnnotationMutation(UUID.randomUUID().toString(),
        versionId, UUID.randomUUID().toString(), AnnotationAction.CREATE, null, 0, shape.kind, shape.toJson())).annotation

    @Test fun lateJoinWithChangesDuringSnapshotPagingConverges() {
        repeat(5) { seed(other, ink(0.1 + it * 0.1)) }
        val target = server.current.values.first()
        var injected = false
        onSnapshotPage = {
            if (!injected) {
                injected = true
                // Edits land between snapshot pages; the snapshot stays at its revision and catch-up follows.
                server.mutate(other, AnnotationMutation(UUID.randomUUID().toString(), versionId, target.id, AnnotationAction.DELETE, 1))
                seed(other, note)
            }
        }
        val c = controller()
        val state = await(c, "loaded") { !it.loading && it.revision == 7L }
        assertEquals(5, state.annotations.size)
        assertFalse(state.annotations.any { it.id == target.id })
        assertTrue(state.annotations.any { it.shape is AnnotationShape.Note })
        assertEquals(SaveStatus.SAVED, state.status)
    }

    @Test fun duplicateAndReorderedRowsNeverDuplicateOrRegress() {
        val state = AnnotationState(versionId)
        val a = seed(other, note)
        val newer = server.mutate(other, AnnotationMutation(UUID.randomUUID().toString(), versionId, a.id, AnnotationAction.UPDATE, 1,
            null, AnnotationKind.NOTE, note.copy(text = "mới").toJson())).annotation
        assertTrue(state.apply(newer))
        assertFalse(state.apply(a))      // late older delivery
        assertFalse(state.apply(newer))  // duplicate
        val shown = state.visible(emptyList(), AnnotationAuthor(me, "Me"))
        assertEquals(listOf("mới"), shown.map { (it.shape as AnnotationShape.Note).text })
        assertFalse(state.apply(newer.copy(versionId = UUID.randomUUID().toString()))) // other version ignored
    }

    @Test fun offlineEditsStayUnsyncedThenReplaySameOperation() {
        val c = controller()
        await(c, "loaded") { !it.loading }
        server.offline = true
        assertTrue(c.create(0, ink(0.5)))
        val unsynced = await(c, "unsynced") { it.status == SaveStatus.UNSYNCED }
        assertEquals(1, unsynced.annotations.single { it.pending }.let { if (it.pending) 1 else 0 })
        val journaled = store.journal(me, versionId).single().mutation
        server.offline = false
        runBlocking { c.poll() } // revision check succeeds → sender wakes
        await(c, "saved") { it.status == SaveStatus.SAVED && it.pendingCount == 0 }
        assertEquals(journaled.opId, server.ledger.keys.single().second)
        assertEquals(1, server.current.size)
        assertTrue(store.journal(me, versionId).isEmpty())
    }

    @Test fun crashAfterCommitBeforeAckReplaysWithoutDuplicate() {
        val first = controller()
        await(first, "loaded") { !it.loading }
        server.failAfterCommit = true
        server.offline = false
        first.create(0, note)
        await(first, "unsynced") { it.status == SaveStatus.UNSYNCED }
        first.stop()
        scopes.forEach { it.cancel() } // process death: only the journal survives
        val restarted = controller()
        val state = await(restarted, "replayed") { !it.loading && it.status == SaveStatus.SAVED && it.pendingCount == 0 }
        assertEquals(1, server.current.size)
        assertEquals(1, server.history.size)
        assertEquals(1, state.annotations.size)
        assertFalse(state.annotations.single().pending)
    }

    @Test fun concurrentNoteEditKeepsDraftUntilUserDecides() {
        val a = seed(me, note)
        val c = controller()
        val loaded = await(c, "loaded") { !it.loading && it.annotations.isNotEmpty() }
        // Another device/owner changes the same note first.
        server.mutate(other, AnnotationMutation(UUID.randomUUID().toString(), versionId, a.id, AnnotationAction.UPDATE, 1, null,
            AnnotationKind.NOTE, note.copy(text = "của trưởng nhóm").toJson()), owner = true)
        c.update(loaded.annotations.single(), note.copy(text = "của tôi"))
        val conflict = await(c, "conflict") { it.status == SaveStatus.CONFLICT && it.revision == 2L }
        assertEquals("của tôi", (conflict.annotations.single().shape as AnnotationShape.Note).text) // draft preserved
        assertTrue(conflict.annotations.single().conflict)
        assertEquals("của trưởng nhóm", (server.current.getValue(a.id).shape as AnnotationShape.Note).text)
        c.resolveConflict(conflict.conflicts.single().mutation.opId, keepMine = true)
        await(c, "resolved") { it.status == SaveStatus.SAVED && it.pendingCount == 0 }
        assertEquals("của tôi", (server.current.getValue(a.id).shape as AnnotationShape.Note).text)
        assertEquals(3, server.current.getValue(a.id).rowVersion)
        // Discard path: the server keeps the other user's text.
        server.mutate(other, AnnotationMutation(UUID.randomUUID().toString(), versionId, a.id, AnnotationAction.UPDATE, 3, null,
            AnnotationKind.NOTE, note.copy(text = "lần hai").toJson()), owner = true)
        c.update(c.sync.value.annotations.single(), note.copy(text = "nháp bỏ"))
        val second = await(c, "second conflict") { it.status == SaveStatus.CONFLICT }
        c.resolveConflict(second.conflicts.single().mutation.opId, keepMine = false)
        val discarded = await(c, "discarded") { it.status == SaveStatus.SAVED && it.revision == 4L }
        assertEquals("lần hai", (discarded.annotations.single().shape as AnnotationShape.Note).text)
    }

    @Test fun undoIsVersionCheckedAndNeverOverwritesNewerWork() {
        val c = controller()
        await(c, "loaded") { !it.loading }
        c.create(0, ink(0.2))
        await(c, "acked") { it.canUndo && it.pendingCount == 0 }
        assertTrue(c.undo())
        val undone = await(c, "undone") { it.pendingCount == 0 && it.annotations.isEmpty() }
        assertFalse(undone.canUndo)
        assertTrue(server.current.values.single().deleted) // undo was a version-checked delete
        assertEquals(2L, server.revision)
        // Undo after another user's change conflicts and is dropped without restoring/overwriting.
        c.create(0, note)
        val created = await(c, "note acked") { it.canUndo && it.pendingCount == 0 }
        val id = created.annotations.single().id
        server.mutate(other, AnnotationMutation(UUID.randomUUID().toString(), versionId, id, AnnotationAction.UPDATE, 1, null,
            AnnotationKind.NOTE, note.copy(text = "sửa sau").toJson()), owner = true)
        c.undo()
        val result = await(c, "undo rejected") { it.message == ErrorType.ANNOTATION_CONFLICT }
        assertEquals(0, result.pendingCount)
        assertFalse(server.current.getValue(id).deleted)
        assertEquals("sửa sau", (server.current.getValue(id).shape as AnnotationShape.Note).text)
    }

    @Test fun reconnectWithAnAheadWatermarkFallsBackToFreshSnapshotAndRebasesPendingEdits() {
        seed(other, note); seed(other, ink(0.2))
        val c = controller()
        await(c, "loaded") { !it.loading && it.revision == 2L }
        server.offline = true
        c.create(0, ink(0.9))
        await(c, "unsynced") { it.status == SaveStatus.UNSYNCED }
        // The delta window no longer matches (server restored/rolled back): the client watermark is ahead.
        val restored = DocumentAnnotation(UUID.randomUUID().toString(), versionId, 0, AnnotationKind.INK, ink(0.6).toJson(),
            AnnotationAuthor(other, "U-other"), 1, 1, false, "2026-11-17T00:00:00Z", "2026-11-17T00:00:00Z")
        server.history.clear(); server.current.clear(); server.revision = 1
        server.history += restored; server.current[restored.id] = restored // written directly: the fake server is offline
        server.offline = false
        c.onSignal(DocumentSignal.Subscribed) // a realtime rejoin catches up through RPCs, never trusts the stream
        val state = await(c, "rebased") { it.status == SaveStatus.SAVED && it.pendingCount == 0 && it.annotations.size == 2 }
        assertEquals(2, server.current.size) // the other member's mark plus our replayed mark, none duplicated
        assertTrue("stale annotations from before the reset are gone", state.annotations.none { it.shape is AnnotationShape.Note })
        assertTrue(store.journal(me, versionId).isEmpty())
    }

    @Test fun signalsForOlderRevisionsAreIgnoredAndNewerOnesCatchUp() {
        val c = controller()
        await(c, "loaded") { !it.loading }
        seed(other, ink(0.3))
        c.onSignal(DocumentSignal.AnnotationsChanged(versionId, 1))
        await(c, "caught up") { it.revision == 1L && it.annotations.size == 1 }
        val before = server.mutateCalls
        c.onSignal(DocumentSignal.AnnotationsChanged(versionId, 1))            // duplicate delivery
        c.onSignal(DocumentSignal.AnnotationsChanged(versionId, 0))            // out of order
        c.onSignal(DocumentSignal.AnnotationsChanged("00000000-0000-0000-0000-0000000000f2", 99)) // another version
        c.onSignal(DocumentSignal.GuidanceChanged("00000000-0000-0000-0000-000000000301")) // guidance never touches annotations
        Thread.sleep(150)
        assertEquals(1, c.sync.value.annotations.size)
        assertEquals(1L, c.sync.value.revision)
        assertEquals(before, server.mutateCalls)
    }

    @Test fun removalBeforeReplayLocksEditorAndClearsPrivateState() {
        val c = controller()
        await(c, "loaded") { !it.loading }
        server.offline = true
        c.create(0, ink(0.3))
        await(c, "unsynced") { it.status == SaveStatus.UNSYNCED }
        store.writePdf(me, versionId, "%PDF-cached".toByteArray())
        server.members -= me
        server.offline = false
        runBlocking { c.poll() }
        runBlocking { c.catchUp() }
        val locked = await(c, "locked") { it.locked }
        assertEquals(ErrorType.DOCUMENT_UNAVAILABLE, locked.loadError)
        assertTrue(locked.annotations.isEmpty())
        assertTrue(store.journal(me, versionId).isEmpty())
        assertNull(store.cachedPdf(me, versionId, DocumentLocalStore.sha256(File(root, "x").apply { writeText("x") })))
        assertEquals(0, server.mutateCalls)
        assertFalse(c.create(0, ink(0.4)))
    }

    @Test fun queuedOpsAreNotReplayedWhenSnapshotIsDenied() {
        store.saveJournal(me, versionId, listOf(PendingAnnotationOp(AnnotationMutation(UUID.randomUUID().toString(), versionId,
            UUID.randomUUID().toString(), AnnotationAction.CREATE, null, 0, AnnotationKind.INK, ink(0.1).toJson()), createdAt = 1)))
        server.members -= me
        val c = controller()
        await(c, "locked") { it.locked }
        assertEquals(0, server.mutateCalls)
        assertTrue(store.journal(me, versionId).isEmpty())
    }

    @Test fun permissionsGateEditsOfOthersAnnotations() {
        seed(other, note)
        val member = controller()
        val shown = await(member, "loaded") { !it.loading && it.annotations.isNotEmpty() }.annotations.single()
        assertFalse(member.canEdit(shown))
        assertFalse(member.delete(shown))
        val owner = controller(owner = true)
        assertTrue(owner.canEdit(await(owner, "owner loaded") { !it.loading && it.annotations.isNotEmpty() }.annotations.single()))
    }

    @Test fun localStoreIsolatesAccountsAndSurvivesCorruption() {
        val op = PendingAnnotationOp(AnnotationMutation(UUID.randomUUID().toString(), versionId, UUID.randomUUID().toString(),
            AnnotationAction.DELETE, 1), createdAt = 1)
        store.saveJournal(me, versionId, listOf(op))
        store.saveJournal(other, versionId, listOf(op))
        val pdf = store.writePdf(other, versionId, "%PDF-x".toByteArray())
        assertEquals(1, store.pendingCount(me))
        assertTrue(store.journal(me, versionId).single() == op)
        store.retainOnly(me)
        assertTrue(store.journal(other, versionId).isEmpty())
        assertFalse(pdf.exists())
        assertEquals(1, store.journal(me, versionId).size)
        root.walkTopDown().filter { it.name.endsWith(".json") }.forEach { it.writeText("{corrupt") }
        assertTrue(store.journal(me, versionId).isEmpty())
        val cached = store.writePdf(me, versionId, "%PDF-1".toByteArray())
        assertNull(store.cachedPdf(me, versionId, "0".repeat(64)))
        assertFalse(cached.exists())
    }
}
