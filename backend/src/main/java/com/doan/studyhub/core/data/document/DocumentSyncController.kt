package com.doan.studyhub.core.data.document

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.repository.GroupDocumentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.UUID

enum class SaveStatus { SAVED, SAVING, UNSYNCED, CONFLICT }

data class SyncState(
    val loading: Boolean = true,
    val annotations: List<DisplayAnnotation> = emptyList(),
    val revision: Long = 0,
    val pendingCount: Int = 0,
    val conflicts: List<PendingAnnotationOp> = emptyList(),
    val status: SaveStatus = SaveStatus.SAVED,
    /** Access was revoked: editing is locked and private state of this version was cleared. */
    val locked: Boolean = false,
    val loadError: ErrorType? = null,
    val message: ErrorType? = null,
    val newerVersionId: String? = null,
    val canUndo: Boolean = false
)

/**
 * Synchronizes one document version: snapshot + ordered deltas, realtime/polling catch-up,
 * a persisted FIFO of mutations replayed with the same op IDs, CAS conflicts that keep the
 * draft, and lock/cleanup on access loss. "Saved" is reported only after server ACKs.
 */
class DocumentSyncController(
    val version: DocumentVersion,
    val me: AnnotationAuthor,
    private val isGroupOwner: Boolean,
    private val repo: GroupDocumentRepository,
    private val store: DocumentLocalStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val retryDelayMs: (attempt: Int) -> Long = { attempt -> minOf(30_000L, 1_000L shl minOf(attempt, 5)) }
) {
    private val state = AnnotationState(version.id)
    private val mutex = Mutex()
    private var pending = store.journal(me.id, version.id).toMutableList()
    private val undo = ArrayDeque<PendingAnnotationOp>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var sender: Job? = null
    private var offline = false
    private var locked = false
    private val _state = MutableStateFlow(SyncState(pendingCount = pending.size))
    val sync: StateFlow<SyncState> = _state.asStateFlow()

    fun canEdit(annotation: DisplayAnnotation) = !locked && !annotation.pending && (annotation.authorId == me.id || isGroupOwner)

    /** Loads the snapshot (which also re-validates access) and starts replaying the journal. */
    fun start() {
        scope.launch {
            if (loadSnapshot()) startSender()
        }
    }

    fun stop() { sender?.cancel(); sender = null }

    private suspend fun loadSnapshot(): Boolean {
        val snapshot = when (val r = repo.snapshot(version.id)) {
            is ApiResponse.Success -> r.data
            is ApiResponse.Failure -> return failLoad(r.error.type)
        }
        val items = mutableListOf<DocumentAnnotation>()
        var after: String? = null
        do {
            val page = when (val r = repo.snapshotPage(snapshot.id, after)) {
                is ApiResponse.Success -> r.data
                is ApiResponse.Failure -> return failLoad(r.error.type)
            }
            items += page.items
            after = page.nextAfter
        } while (after != null)
        mutex.withLock { state.reset(items, snapshot.revision) }
        // Changes committed while paging are applied afterwards in revision order.
        catchUp()
        mutex.withLock { publish { it.copy(loading = false, loadError = null) } }
        return !locked
    }

    private suspend fun failLoad(type: ErrorType): Boolean {
        if (isAccessLoss(type)) lockForAccessLoss(type) else mutex.withLock { publish { it.copy(loading = false, loadError = type) } }
        return false
    }

    /** Fetches every change after the local watermark; falls back to a fresh snapshot on reset. */
    suspend fun catchUp() {
        if (locked) return
        var since = mutex.withLock { state.revision }
        while (true) {
            val page = when (val r = repo.changes(version.id, since)) {
                is ApiResponse.Success -> r.data
                is ApiResponse.Failure -> {
                    if (isAccessLoss(r.error.type)) lockForAccessLoss(r.error.type)
                    else mutex.withLock { offline = r.error.type.retryable; publish() }
                    return
                }
            }
            if (page.resetRequired) { loadSnapshot(); return }
            mutex.withLock {
                page.items.forEach { state.apply(it) }
                if (page.nextSince == null) state.advanceTo(page.revision)
                offline = false
                publish()
            }
            since = page.nextSince ?: break
        }
        wake.trySend(Unit)
    }

    fun onSignal(signal: DocumentSignal) {
        when (signal) {
            is DocumentSignal.AnnotationsChanged ->
                if (signal.versionId == version.id && signal.revision > state.revision) scope.launch { catchUp() }
            DocumentSignal.Subscribed -> scope.launch { catchUp() } // rejoin after a gap
            // Guidance moves followers only; it never changes annotations (see GuidanceController).
            DocumentSignal.LibraryChanged, DocumentSignal.Disconnected, is DocumentSignal.GuidanceChanged -> Unit
        }
    }

    /** Fallback revision check (every 5 s while the viewer is in the foreground). */
    suspend fun poll() {
        if (locked) return
        when (val r = repo.revision(version.id)) {
            is ApiResponse.Success -> {
                mutex.withLock {
                    publish { it.copy(newerVersionId = r.data.currentVersionId.takeIf { id -> id != version.id }) }
                }
                if (r.data.revision > state.revision) catchUp() else { mutex.withLock { offline = false; publish() }; wake.trySend(Unit) }
            }
            is ApiResponse.Failure -> if (isAccessLoss(r.error.type)) lockForAccessLoss(r.error.type)
                else mutex.withLock { offline = r.error.type.retryable; publish() }
        }
    }

    fun create(pageIndex: Int, shape: AnnotationShape): Boolean {
        if (locked || pageIndex !in 0 until version.pageCount || !AnnotationShape.isValid(shape)) return false
        if (state.activeCount + pending.count { it.mutation.action == AnnotationAction.CREATE } >= AnnotationLimits.MAX_ACTIVE) {
            _state.value = _state.value.copy(message = ErrorType.ANNOTATION_LIMIT)
            return false
        }
        enqueue(AnnotationMutation(newId(), version.id, newId(), AnnotationAction.CREATE, null, pageIndex, shape.kind, shape.toJson()))
        return true
    }

    fun update(annotation: DisplayAnnotation, shape: AnnotationShape): Boolean {
        if (!canEdit(annotation) || shape.kind != annotation.shape.kind || !AnnotationShape.isValid(shape)) return false
        val row = annotation.rowVersion ?: return false
        enqueue(AnnotationMutation(newId(), version.id, annotation.id, AnnotationAction.UPDATE, row, null, shape.kind, shape.toJson()),
            previous = annotation.shape.toJson())
        return true
    }

    fun delete(annotation: DisplayAnnotation): Boolean {
        if (!canEdit(annotation)) return false
        val row = annotation.rowVersion ?: return false
        enqueue(AnnotationMutation(newId(), version.id, annotation.id, AnnotationAction.DELETE, row), previous = annotation.shape.toJson())
        return true
    }

    /** Undo of the user's last acknowledged op, sent as a new version-checked mutation. */
    fun undo(): Boolean {
        if (locked) return false
        val inverse = undo.removeLastOrNull() ?: return false
        enqueue(inverse.mutation.copy(opId = newId()), undoOp = true)
        return true
    }

    /** Conflict resolution chosen by the user: re-apply the draft on the current version, or discard it. */
    fun resolveConflict(opId: String, keepMine: Boolean) {
        scope.launch {
            mutex.withLock {
                val index = pending.indexOfFirst { it.mutation.opId == opId && it.status == PendingStatus.CONFLICT }
                if (index < 0) return@withLock
                val op = pending.removeAt(index)
                val current = state.committed(op.mutation.annotationId)
                if (keepMine && current != null && !current.deleted &&
                    op.mutation.action in setOf(AnnotationAction.UPDATE, AnnotationAction.DELETE)) {
                    pending.add(index, op.copy(status = PendingStatus.QUEUED,
                        mutation = op.mutation.copy(opId = newId(), expectedRowVersion = current.rowVersion)))
                } else if (keepMine) {
                    _state.value = _state.value.copy(message = ErrorType.DOCUMENT_UNAVAILABLE)
                }
                persist(); publish()
            }
            wake.trySend(Unit)
        }
    }

    fun consumeMessage() { _state.value = _state.value.copy(message = null) }

    /** Called before export: true when everything local is acknowledged. */
    fun hasUnsavedWork(): Boolean = pending.isNotEmpty()

    private fun enqueue(mutation: AnnotationMutation, previous: JsonObject? = null, undoOp: Boolean = false) {
        scope.launch {
            mutex.withLock {
                pending.add(PendingAnnotationOp(mutation, PendingStatus.QUEUED, previous, clock(), undoOp))
                persist(); publish()
            }
            wake.trySend(Unit)
        }
    }

    private fun startSender() {
        if (sender != null) return
        wake.trySend(Unit)
        sender = scope.launch {
            var attempt = 0
            while (true) {
                if (attempt == 0) wake.receive()
                else withTimeoutOrNull(retryDelayMs(attempt)) { wake.receive() }
                attempt = drain(attempt)
                if (locked) return@launch
            }
        }
    }

    /** Sends queued ops in order; returns the retry attempt counter (0 when idle/done). */
    private suspend fun drain(attempt: Int): Int {
        var retries = attempt
        while (!locked) {
            val op = mutex.withLock { pending.firstOrNull { it.status == PendingStatus.QUEUED } } ?: return 0
            mutex.withLock { publish() }
            when (val result = repo.mutate(op.mutation)) {
                is ApiResponse.Success -> mutex.withLock {
                    pending.removeAll { it.mutation.opId == op.mutation.opId }
                    state.apply(result.data.annotation)
                    if (!op.undo) inverseOf(op, result.data.annotation)?.let { undo.addLast(it); if (undo.size > 50) undo.removeFirst() }
                    offline = false
                    persist(); publish()
                    retries = 0
                }
                is ApiResponse.Failure -> {
                    val type = result.error.type
                    when {
                        isAccessLoss(type) -> { lockForAccessLoss(type); return 0 }
                        type == ErrorType.ANNOTATION_CONFLICT -> {
                            mutex.withLock {
                                if (op.undo) {
                                    pending.removeAll { it.mutation.opId == op.mutation.opId }
                                    publish { it.copy(message = ErrorType.ANNOTATION_CONFLICT) }
                                } else {
                                    val i = pending.indexOfFirst { it.mutation.opId == op.mutation.opId }
                                    if (i >= 0) pending[i] = op.copy(status = PendingStatus.CONFLICT)
                                    publish()
                                }
                                persist()
                            }
                            catchUp() // show the current server version next to the kept draft
                        }
                        type.retryable -> {
                            mutex.withLock { offline = true; publish() }
                            return retries + 1
                        }
                        else -> mutex.withLock { // validation, permission, limit, reused op id: not replayable
                            pending.removeAll { it.mutation.opId == op.mutation.opId }
                            persist(); publish { it.copy(message = type) }
                        }
                    }
                }
            }
        }
        return 0
    }

    private fun inverseOf(op: PendingAnnotationOp, committed: DocumentAnnotation): PendingAnnotationOp? {
        val m = op.mutation
        val inverse = when (m.action) {
            AnnotationAction.CREATE -> m.copy(action = AnnotationAction.DELETE, expectedRowVersion = committed.rowVersion,
                pageIndex = null, kind = null, data = null)
            AnnotationAction.UPDATE -> m.copy(expectedRowVersion = committed.rowVersion, data = op.previousData ?: return null)
            AnnotationAction.DELETE -> m.copy(action = AnnotationAction.RESTORE, expectedRowVersion = committed.rowVersion,
                pageIndex = null, kind = null, data = null)
            AnnotationAction.RESTORE -> return null
        }
        return PendingAnnotationOp(inverse, PendingStatus.QUEUED, op.previousData, clock(), true)
    }

    private suspend fun lockForAccessLoss(type: ErrorType) {
        mutex.withLock {
            locked = true
            pending.clear()
            undo.clear()
            state.reset(emptyList(), 0)
            store.clearVersion(me.id, version.id)
            publish { it.copy(loading = false, locked = true, loadError = type, annotations = emptyList()) }
        }
        sender?.cancel()
    }

    private fun persist() = store.saveJournal(me.id, version.id, pending)

    private fun publish(transform: (SyncState) -> SyncState = { it }) {
        val conflicts = pending.filter { it.status == PendingStatus.CONFLICT }
        val status = when {
            conflicts.isNotEmpty() -> SaveStatus.CONFLICT
            pending.isEmpty() -> SaveStatus.SAVED
            offline -> SaveStatus.UNSYNCED
            else -> SaveStatus.SAVING
        }
        _state.value = transform(_state.value.copy(
            annotations = if (locked) emptyList() else state.visible(pending, me),
            revision = state.revision, pendingCount = pending.size, conflicts = conflicts, status = status,
            locked = locked, canUndo = undo.isNotEmpty() && !locked))
    }

    companion object {
        private val ACCESS_LOSS = setOf(ErrorType.DOCUMENT_UNAVAILABLE, ErrorType.GROUP_UNAVAILABLE,
            ErrorType.GROUP_ACCOUNT_UNAVAILABLE, ErrorType.UNAUTHENTICATED)
        fun isAccessLoss(type: ErrorType) = type in ACCESS_LOSS
    }
}
