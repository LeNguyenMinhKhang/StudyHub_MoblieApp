package com.doan.studyhub.user.ui.group.document

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.document.DisplayAnnotation
import com.doan.studyhub.core.data.document.DocumentLocalStore
import com.doan.studyhub.core.data.document.DocumentSyncController
import com.doan.studyhub.core.data.document.FollowAction
import com.doan.studyhub.core.data.document.GuidanceController
import com.doan.studyhub.core.data.document.GuidanceUiState
import com.doan.studyhub.core.data.document.SaveStatus
import com.doan.studyhub.core.data.document.SyncState
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.repository.GroupDocumentRepository
import com.doan.studyhub.core.data.repository.GroupRepository
import com.doan.studyhub.user.ui.group.call.CallSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

private val LOSES_ACCESS = setOf(ErrorType.DOCUMENT_UNAVAILABLE, ErrorType.GROUP_UNAVAILABLE,
    ErrorType.GROUP_ACCOUNT_UNAVAILABLE, ErrorType.UNAUTHENTICATED, ErrorType.ACCOUNT_BANNED)
private fun ApiResponse.Failure.losesAccess() = error.type in LOSES_ACCESS

/** Removes private document data of accounts other than the signed-in one (all of it after logout). */
internal fun DocumentLocalStore.syncAccount(userId: String?) { if (userId == null) clearAll() else retainOnly(userId) }

data class DocumentRow(
    val id: String,
    val title: String,
    val versionId: String,
    val versionNumber: Int,
    val pageCount: Int,
    val uploader: String,
    val canManage: Boolean,
    val isCurrent: Boolean
)

data class DocumentLibraryState(
    val rows: List<DocumentRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val more: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    /** Row ID (or "upload") of the operation in progress; one at a time. */
    val busy: String? = null,
    val canRetryUpload: Boolean = false,
    val uploaded: Boolean = false,
    val archived: Boolean = false,
    val openVersion: String? = null,
    val closed: Boolean = false
)

/**
 * Group document library, or the version history of one document when [show] gets a document ID.
 * Upload request IDs survive retries and recreation for the same file, so a lost response resumes
 * the same server reservation instead of creating a duplicate.
 */
class DocumentLibraryViewModel @JvmOverloads constructor(
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val docs: GroupDocumentRepository = GroupDocumentRepository(),
    private val groups: GroupRepository = GroupRepository(),
    private val store: DocumentLocalStore? = null,
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(DocumentLibraryState())
    val state: StateFlow<DocumentLibraryState> = mutable.asStateFlow()
    private var groupId: String? = null
    private var documentId: String? = null
    private var identity = docs.currentUserId() to docs.currentSessionKey()
    private var generation = 0
    private var job: Job? = null
    private var cursor: GroupCursor? = null
    private var lastUpload: Triple<ByteArray, String?, String?>? = null

    init {
        store?.syncAccount(identity.first)
        scope.launch { groups.sessionChanges().collect { syncSession() } }
    }

    private fun syncSession(): Boolean {
        val current = docs.currentUserId() to docs.currentSessionKey()
        if (current != identity) {
            identity = current; generation++; job?.cancel(); cursor = null; lastUpload = null
            saved.remove<String>(UPLOAD_KEY); saved.remove<String>(UPLOAD_FINGERPRINT)
            store?.syncAccount(current.first)
            mutable.value = DocumentLibraryState(closed = true)
        }
        return current.first != null
    }

    val versionsMode get() = documentId != null

    fun show(group: String, document: String? = null) {
        if (groupId == group && documentId == document && mutable.value.loaded) return
        groupId = group; documentId = document
        refresh()
    }

    fun refresh() = load(append = false)
    fun more() { if (mutable.value.more) load(append = true) }

    private fun load(append: Boolean) {
        if (!syncSession() || mutable.value.loading || mutable.value.busy != null || mutable.value.closed) return
        val group = groupId ?: return
        val request = ++generation
        if (!append) cursor = null
        mutable.value = mutable.value.copy(loading = true, error = null)
        job = scope.launch {
            val document = documentId
            val result: ApiResponse<GroupPage<DocumentRow>> = if (document == null) {
                when (val r = docs.documents(group, cursor)) {
                    is ApiResponse.Success -> ApiResponse.Success(GroupPage(r.data.items.map { d ->
                        DocumentRow(d.id, d.title, d.currentVersion.id, d.currentVersion.versionNumber, d.currentVersion.pageCount,
                            d.uploadedBy.displayName, d.canManage, true)
                    }, r.data.nextCursor), r.traceId)
                    is ApiResponse.Failure -> r
                }
            } else {
                // Title and management permission come from the document itself.
                val head = docs.document(document)
                if (head is ApiResponse.Failure) { if (valid(request)) fail(head); return@launch }
                val doc = (head as ApiResponse.Success).data
                when (val r = docs.versions(document, cursor)) {
                    is ApiResponse.Success -> ApiResponse.Success(GroupPage(r.data.items.map { v ->
                        DocumentRow(doc.id, doc.title, v.id, v.versionNumber, v.pageCount, v.uploadedBy.displayName, doc.canManage, v.isCurrent)
                    }, r.data.nextCursor), r.traceId)
                    is ApiResponse.Failure -> r
                }
            }
            if (!valid(request)) return@launch
            when (result) {
                is ApiResponse.Success -> {
                    cursor = result.data.nextCursor
                    val rows = if (append) (mutable.value.rows + result.data.items).distinctBy { it.versionId } else result.data.items
                    mutable.value = mutable.value.copy(rows = rows, loading = false, loaded = true, more = cursor != null, error = null)
                }
                is ApiResponse.Failure -> fail(result)
            }
        }
    }

    private fun fail(result: ApiResponse.Failure) {
        mutable.value = if (result.losesAccess()) DocumentLibraryState(error = result.error.message, closed = true)
            else mutable.value.copy(loading = false, busy = null, error = result.error.message)
    }

    private fun valid(request: Int) = syncSession() && request == generation

    /**
     * Uploads [bytes] as a new document (with [title]) or as a new version of [targetDocument].
     * The request ID is reused while the same file is retried for the same target.
     */
    fun upload(bytes: ByteArray, title: String?, targetDocument: String? = null) {
        if (!syncSession() || mutable.value.busy != null || mutable.value.closed) return
        val group = groupId ?: return
        val fingerprint = GroupDocumentRepository.sha256(bytes) + "|" + (targetDocument ?: "new")
        if (saved.get<String>(UPLOAD_FINGERPRINT) != fingerprint) {
            saved[UPLOAD_FINGERPRINT] = fingerprint; saved[UPLOAD_KEY] = UUID.randomUUID().toString()
        }
        val key = requireNotNull(saved.get<String>(UPLOAD_KEY))
        lastUpload = Triple(bytes, title, targetDocument)
        val request = ++generation
        mutable.value = mutable.value.copy(busy = UPLOAD, actionError = null, canRetryUpload = false)
        job = scope.launch {
            val result = docs.upload(group, key, bytes, if (targetDocument == null) title else null, targetDocument)
            if (!valid(request)) return@launch
            when (result) {
                is ApiResponse.Success -> {
                    saved.remove<String>(UPLOAD_KEY); saved.remove<String>(UPLOAD_FINGERPRINT); lastUpload = null
                    mutable.value = mutable.value.copy(busy = null, uploaded = true)
                    refresh()
                }
                is ApiResponse.Failure -> {
                    if (result.losesAccess()) { fail(result); return@launch }
                    // A rejected file or expired reservation needs a new request; transient errors resume this one.
                    val resumable = result.error.type.retryable
                    if (!resumable) { saved.remove<String>(UPLOAD_KEY); saved.remove<String>(UPLOAD_FINGERPRINT); lastUpload = null }
                    mutable.value = mutable.value.copy(busy = null, actionError = result.error.message, canRetryUpload = resumable)
                }
            }
        }
    }

    fun retryUpload() { lastUpload?.let { (bytes, title, target) -> upload(bytes, title, target) } }

    fun archive(row: DocumentRow) {
        if (!syncSession() || mutable.value.busy != null || !row.canManage) return
        val request = ++generation
        mutable.value = mutable.value.copy(busy = row.id, actionError = null)
        job = scope.launch {
            val result = docs.archive(row.id)
            if (!valid(request)) return@launch
            when (result) {
                is ApiResponse.Success -> {
                    mutable.value = mutable.value.copy(busy = null, archived = true,
                        rows = mutable.value.rows.filterNot { it.id == row.id })
                    if (versionsMode) mutable.value = DocumentLibraryState(closed = true) else refresh()
                }
                is ApiResponse.Failure -> if (result.error.type == ErrorType.DOCUMENT_UNAVAILABLE && !versionsMode) {
                    // Already archived or no longer visible: drop it from the list.
                    mutable.value = mutable.value.copy(busy = null, actionError = result.error.message); refresh()
                } else if (result.losesAccess()) fail(result)
                else mutable.value = mutable.value.copy(busy = null, actionError = result.error.message)
            }
        }
    }

    fun open(row: DocumentRow) { if (mutable.value.busy == null) mutable.value = mutable.value.copy(openVersion = row.versionId) }
    fun opened() { mutable.value = mutable.value.copy(openVersion = null) }
    fun acknowledged() { mutable.value = mutable.value.copy(uploaded = false, archived = false) }

    companion object {
        const val UPLOAD = "upload"
        private const val UPLOAD_KEY = "upload_request"
        private const val UPLOAD_FINGERPRINT = "upload_fingerprint"
    }
}

/** Renders the committed export snapshot to a file and publishes it to the user-chosen destination. */
interface DocumentExportEngine {
    suspend fun render(source: File, version: DocumentVersion, annotations: List<DocumentAnnotation>, revision: Long,
                       target: File, progress: (done: Int, total: Int) -> Unit)
    suspend fun publish(file: File, destination: String)
    /** Removes a partially written destination; must not throw. */
    fun discard(destination: String)
}

sealed interface ExportState {
    data object Idle : ExportState
    /** Waiting for local changes to be acknowledged before export. */
    data object Syncing : ExportState
    /** Ready for the destination picker. */
    data object ChooseTarget : ExportState
    data class Running(val done: Int, val total: Int) : ExportState
    data object Publishing : ExportState
    data object Done : ExportState
    data class Failed(val message: String?) : ExportState
    data object Cancelled : ExportState
}

data class ViewerState(
    val loading: Boolean = true,
    val error: String? = null,
    val version: DocumentVersion? = null,
    val pdfPath: String? = null,
    val page: Int = 0,
    val closed: Boolean = false,
    val closedMessage: String? = null,
    val export: ExportState = ExportState.Idle
)

/**
 * One document version in the viewer/editor. Owns the [DocumentSyncController] (journal, CAS,
 * catch-up), the private invalidation channel and the 5 s foreground revision poll. Survives
 * rotation; private data is cleared on access loss or account switch.
 */
class DocumentViewerViewModel @JvmOverloads constructor(
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val docs: GroupDocumentRepository = GroupDocumentRepository(),
    private val groups: GroupRepository = GroupRepository(),
    private val store: DocumentLocalStore? = null,
    private val exporter: DocumentExportEngine? = null,
    private val externalScope: CoroutineScope? = null,
    private val pollMs: Long = 5_000L,
    private val syncWaitMs: Long = 20_000L,
    /** The app's call session, to show call controls and guidance while this group's call is open. */
    private val callSession: StateFlow<CallSessionState>? = null,
    private val guidanceFactory: ((callId: String, following: Boolean) -> GuidanceController)? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(ViewerState(page = saved.get<Int>(PAGE) ?: 0))
    val state: StateFlow<ViewerState> = mutable.asStateFlow()
    private val syncFlow = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = syncFlow.asStateFlow()
    private var identity = docs.currentUserId() to docs.currentSessionKey()
    private var controller: DocumentSyncController? = null
    private var work: Job? = null
    private var poller: Job? = null
    private var exportJob: Job? = null
    private var versionId: String? = null
    private var selfName = ""
    private var foreground = false
    private val guidanceFlow = MutableStateFlow(GuidanceUiState(closed = true))
    /** Follow-the-guide state; `closed` while this group's call is not attached (guidance UI hidden). */
    val guidance: StateFlow<GuidanceUiState> = guidanceFlow.asStateFlow()
    private var guide: GuidanceController? = null
    private var guideJob: Job? = null
    private var callWatcher: Job? = null

    init {
        store?.syncAccount(identity.first)
        scope.launch { groups.sessionChanges().collect { syncSession() } }
    }

    private fun syncSession(): Boolean {
        val current = docs.currentUserId() to docs.currentSessionKey()
        if (current != identity) {
            identity = current
            store?.syncAccount(current.first)
            close(null)
        }
        return current.first != null && !mutable.value.closed
    }

    var tool: EditorTool
        get() = saved.get<String>(TOOL)?.let { runCatching { EditorTool.valueOf(it) }.getOrNull() } ?: EditorTool.VIEW
        set(value) { saved[TOOL] = value.name }
    var inkColor: String
        get() = saved.get<String>(INK) ?: AnnotationLimits.INK_COLORS[1]
        set(value) { saved[INK] = value }
    var markColor: String
        get() = saved.get<String>(MARK) ?: AnnotationLimits.HIGHLIGHT_COLORS[0]
        set(value) { saved[MARK] = value }

    /** [self] labels the user's own pending marks until the server acknowledges them. */
    fun show(version: String, self: String) {
        selfName = self
        if (versionId == version && (controller != null || work?.isActive == true)) return
        versionId = version
        load()
    }

    /** Retries a failed load, including a snapshot load that failed inside the sync controller. */
    fun retry() {
        if (mutable.value.closed || (mutable.value.error == null && syncFlow.value.loadError == null)) return
        poller?.cancel(); poller = null
        controller?.stop(); controller = null
        load()
    }

    private fun load() {
        if (!syncSession()) return
        val id = versionId ?: return
        val me = docs.currentUserId() ?: return
        work?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        work = scope.launch {
            val version = when (val r = docs.version(id)) {
                is ApiResponse.Success -> r.data
                is ApiResponse.Failure -> { failLoad(r); return@launch }
            }
            val owner = when (val r = groups.detail(version.groupId)) {
                is ApiResponse.Success -> r.data.role == GroupRole.OWNER
                is ApiResponse.Failure -> { failLoad(r); return@launch }
            }
            val file = store?.cachedPdf(me, version.id, version.contentHash) ?: when (val r = docs.download(version)) {
                is ApiResponse.Success -> store?.writePdf(me, version.id, r.data)
                is ApiResponse.Failure -> { failLoad(r); return@launch }
            }
            if (!syncSession()) return@launch
            val page = mutable.value.page.coerceIn(0, version.pageCount - 1)
            mutable.value = mutable.value.copy(loading = false, version = version, pdfPath = file?.absolutePath, page = page)
            val local = store ?: return@launch
            val sync = DocumentSyncController(version, AnnotationAuthor(me, selfName), owner, docs, local, scope)
            controller = sync
            launch { sync.sync.collect { s -> syncFlow.value = s; if (s.locked) close(s.loadError?.userMessage) } }
            sync.start()
            listen(version.groupId, sync)
            watchCall(version)
            if (foreground) startPolling()
        }
    }

    private fun failLoad(result: ApiResponse.Failure) {
        if (result.losesAccess()) close(result.error.message)
        else mutable.value = mutable.value.copy(loading = false, error = result.error.message)
    }

    /** Invalidation signals only; the topic changes when membership changes, so it is re-read on errors. */
    private fun CoroutineScope.listen(groupId: String, sync: DocumentSyncController) = launch {
        while (isActive && !mutable.value.closed) {
            when (val channel = docs.channel(groupId)) {
                is ApiResponse.Success -> runCatching { docs.observe(channel.data.topic).collect { sync.onSignal(it); guide?.onSignal(it) } }
                    .onFailure { if (it is CancellationException) throw it }
                is ApiResponse.Failure -> if (channel.losesAccess()) { close(channel.error.message); return@launch }
            }
            delay(pollMs * 6) // polling covers the gap until the channel is back
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) { startPolling(); guide?.start() } else { poller?.cancel(); poller = null; guide?.pause() }
    }

    private fun startPolling() {
        val sync = controller ?: return
        if (poller?.isActive == true) return
        poller = scope.launch {
            sync.catchUp()
            while (isActive) { delay(pollMs); sync.poll() }
        }
    }

    fun goTo(page: Int) {
        val version = mutable.value.version ?: return
        val target = showPage(page) ?: return
        // Only the guide pushes its page; everyone else (and followers who scroll away) reads freely.
        guide?.navigated(version.id, target)
    }

    /** Local page change only: never sent to the call, so following cannot echo back to the guide. */
    private fun showPage(page: Int): Int? {
        val count = mutable.value.version?.pageCount ?: return null
        val target = page.coerceIn(0, count - 1)
        saved[PAGE] = target
        mutable.value = mutable.value.copy(page = target)
        return target
    }

    // ---- Call guidance (optional follow-the-guide; never gates annotating) ---------------------

    /** Attaches guidance to this group's open call, and detaches it when the session ends or changes. */
    private fun watchCall(version: DocumentVersion) {
        val calls = callSession ?: return
        callWatcher?.cancel()
        callWatcher = scope.launch {
            calls.collect { call ->
                val callId = call.call?.id?.takeIf { call.active && call.groupId == version.groupId }
                if (callId == guide?.callId) return@collect
                detachGuidance()
                if (callId != null) attachGuidance(callId)
            }
        }
    }

    private fun attachGuidance(callId: String) {
        val following = saved.get<Boolean>(FOLLOW) ?: false
        val controller = guidanceFactory?.invoke(callId, following)
            ?: GuidanceController(callId, docs, scope, initiallyFollowing = following, releaseScope = releaseScope)
        guide = controller
        guideJob = scope.launch {
            controller.state.collect { state ->
                guidanceFlow.value = state
                applyFollow(controller)
            }
        }
        if (foreground) controller.start() else scope.launch { controller.refresh() }
    }

    private fun detachGuidance() {
        guideJob?.cancel(); guideJob = null
        guide?.stop(); guide = null
        guidanceFlow.value = GuidanceUiState(closed = true)
    }

    /** Applies the guide's page once per move, to the open version only; zoom/rotation stay local. */
    private fun applyFollow(controller: GuidanceController) {
        val version = mutable.value.version ?: return
        val action = controller.followAction(version.id) as? FollowAction.Page ?: return
        controller.markFollowed(action.sequence)
        showPage(action.page)
    }

    /** Becomes the guide at the page being viewed. A live guide is never displaced. */
    fun startGuiding() {
        val controller = guide ?: return
        val version = mutable.value.version ?: return
        val page = mutable.value.page
        scope.launch { controller.claim(version.id, page) }
    }

    fun stopGuiding() {
        val controller = guide ?: return
        scope.launch { controller.release() }
    }

    fun setFollowing(on: Boolean) {
        saved[FOLLOW] = on
        val controller = guide ?: return
        controller.setFollowing(on)
        applyFollow(controller)
    }

    fun consumeGuidanceMessage() { guide?.consumeMessage() }

    fun canEdit(annotation: DisplayAnnotation) = controller?.canEdit(annotation) == true

    fun addStroke(points: List<PagePoint>, width: Double): Boolean {
        val ink = StrokeSimplifier.fit(points, inkColor, width) ?: return false
        return controller?.create(mutable.value.page, ink) == true
    }

    fun addHighlight(left: Double, top: Double, right: Double, bottom: Double): Boolean {
        val shape = AnnotationShape.Highlight(left, top, right, bottom, markColor)
        return AnnotationShape.isValid(shape) && controller?.create(mutable.value.page, shape) == true
    }

    /** Returns false when the note text is empty or over the limit; the dialog keeps it for editing. */
    fun addNote(anchor: PagePoint, text: String): Boolean {
        val shape = AnnotationShape.Note(anchor, text.trim(), markColor)
        return AnnotationShape.isValid(shape) && controller?.create(mutable.value.page, shape) == true
    }

    fun editNote(annotation: DisplayAnnotation, text: String): Boolean {
        val note = annotation.shape as? AnnotationShape.Note ?: return false
        val shape = note.copy(text = text.trim())
        return AnnotationShape.isValid(shape) && controller?.update(annotation, shape) == true
    }

    fun delete(annotation: DisplayAnnotation) = controller?.delete(annotation) == true
    fun undo() = controller?.undo() == true
    fun resolveConflict(opId: String, keepMine: Boolean) { controller?.resolveConflict(opId, keepMine) }
    fun consumeMessage() { controller?.consumeMessage() }

    // ---- Export -------------------------------------------------------------------------

    fun hasUnsavedWork() = controller?.hasUnsavedWork() == true

    /** Starts export: [syncFirst] waits for pending changes; otherwise only committed data is exported. */
    fun requestExport(syncFirst: Boolean) {
        if (!syncSession() || controller == null || exportBusy()) return
        if (!syncFirst || !hasUnsavedWork()) { mutable.value = mutable.value.copy(export = ExportState.ChooseTarget); return }
        mutable.value = mutable.value.copy(export = ExportState.Syncing)
        exportJob = scope.launch {
            val synced = withTimeoutOrNull(syncWaitMs) {
                sync.first { it.locked || it.status == SaveStatus.CONFLICT || (it.status == SaveStatus.SAVED && it.pendingCount == 0) }
            }
            if (mutable.value.closed) return@launch
            mutable.value = mutable.value.copy(export = if (synced?.status == SaveStatus.SAVED && !synced.locked) ExportState.ChooseTarget
                else ExportState.Failed(null))
        }
    }

    fun targetChosen() { if (mutable.value.export == ExportState.ChooseTarget) mutable.value = mutable.value.copy(export = ExportState.Idle) }

    private fun exportBusy() = mutable.value.export.let {
        it is ExportState.Running || it == ExportState.Publishing || it == ExportState.Syncing
    }

    /** Exports to [destination] through an app-private temporary file of the current account. */
    fun exportTo(destination: String) {
        val me = docs.currentUserId()
        val local = store
        if (me == null || local == null) { exporter?.discard(destination); return }
        export(destination, local.exportFile(me))
    }

    /** Exports the committed snapshot of this version to [destination] (a document URI). */
    fun export(destination: String, temp: File) {
        val engine = exporter ?: return
        val version = mutable.value.version
        val path = mutable.value.pdfPath
        if (!syncSession() || version == null || path == null || exportBusy()) { engine.discard(destination); return }
        mutable.value = mutable.value.copy(export = ExportState.Running(0, version.pageCount))
        exportJob = scope.launch {
            var published = false
            try {
                val snapshot = when (val r = docs.snapshot(version.id, SnapshotPurpose.EXPORT)) {
                    is ApiResponse.Success -> r.data
                    is ApiResponse.Failure -> { exportFailed(r); return@launch }
                }
                val items = mutableListOf<DocumentAnnotation>()
                var after: String? = null
                do {
                    val page = when (val r = docs.snapshotPage(snapshot.id, after)) {
                        is ApiResponse.Success -> r.data
                        is ApiResponse.Failure -> { exportFailed(r); return@launch }
                    }
                    items += page.items
                    after = page.nextAfter
                } while (after != null)
                engine.render(File(path), version, items.filter { !it.deleted && it.versionId == version.id }, snapshot.revision, temp) { done, total ->
                    if (!mutable.value.closed) mutable.value = mutable.value.copy(export = ExportState.Running(done, total))
                }
                // Access may have been revoked while rendering: re-check before the file leaves the app.
                when (val r = docs.revision(version.id)) {
                    is ApiResponse.Success -> Unit
                    is ApiResponse.Failure -> { exportFailed(r); return@launch }
                }
                if (!syncSession()) return@launch
                mutable.value = mutable.value.copy(export = ExportState.Publishing)
                engine.publish(temp, destination)
                published = true
                if (!mutable.value.closed) mutable.value = mutable.value.copy(export = ExportState.Done)
            } catch (cancelled: CancellationException) {
                if (!mutable.value.closed) mutable.value = mutable.value.copy(export = ExportState.Cancelled)
                throw cancelled
            } catch (error: Exception) {
                if (!mutable.value.closed) mutable.value = mutable.value.copy(export = ExportState.Failed(null))
            } finally {
                temp.delete()
                if (!published) engine.discard(destination)
            }
        }
    }

    private fun exportFailed(result: ApiResponse.Failure) {
        if (result.losesAccess()) close(result.error.message)
        else mutable.value = mutable.value.copy(export = ExportState.Failed(result.error.message))
    }

    fun cancelExport() { exportJob?.cancel() }
    fun exportAcknowledged() {
        if (!exportBusy() && mutable.value.export != ExportState.ChooseTarget) mutable.value = mutable.value.copy(export = ExportState.Idle)
    }

    private fun close(message: String?) {
        if (mutable.value.closed) return
        exportJob?.cancel(); poller?.cancel(); work?.cancel(); callWatcher?.cancel()
        detachGuidance()
        controller?.stop(); controller = null
        val me = docs.currentUserId()
        versionId?.let { v -> if (me != null) store?.clearVersion(me, v) }
        syncFlow.value = SyncState(loading = false, locked = true)
        mutable.value = ViewerState(loading = false, closed = true, closedMessage = message)
    }

    override fun onCleared() { controller?.stop(); guide?.stop(); super.onCleared() }

    companion object {
        private const val PAGE = "page"
        private const val TOOL = "tool"
        private const val INK = "ink_color"
        private const val MARK = "mark_color"
        private const val FOLLOW = "follow_guide"
        /** Outlives a cleared ViewModel so leaving the viewer still releases a held guide lease. */
        private val releaseScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    }
}
