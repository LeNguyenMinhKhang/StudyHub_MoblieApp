package com.doan.studyhub.user.ui.group.document

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.document.DisplayAnnotation
import com.doan.studyhub.core.data.document.DocumentLocalStore
import com.doan.studyhub.core.data.document.GuidanceUiState
import com.doan.studyhub.core.data.document.SaveStatus
import com.doan.studyhub.core.data.model.AnnotationLimits
import com.doan.studyhub.core.data.model.CallMode
import com.doan.studyhub.core.data.model.AnnotationShape
import com.doan.studyhub.core.data.model.PagePoint
import com.doan.studyhub.core.data.repository.GroupDocumentRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentDocumentViewerBinding
import com.doan.studyhub.user.databinding.FragmentGroupDocumentsBinding
import com.doan.studyhub.user.databinding.ItemStudyGroupBinding
import com.doan.studyhub.user.ui.group.call.CallPhase
import com.doan.studyhub.user.ui.group.call.CallSessionManager
import com.doan.studyhub.user.ui.group.call.CallSessionState
import com.doan.studyhub.user.ui.group.call.GroupCallFragment
import com.doan.studyhub.user.ui.home.Home
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** App-private document storage: journals in no-backup files, verified PDFs/exports in cache. */
internal fun documentStore(context: Context): DocumentLocalStore = context.applicationContext.let {
    DocumentLocalStore(File(it.noBackupFilesDir, "group_documents"), File(it.cacheDir, "group_documents"))
}

private fun Fragment.home() = parentFragment as? Home

/** Shows [dialog] and dismisses it with the view, so it never outlives its fragment. */
private fun Fragment.showBound(dialog: AlertDialog) {
    val lifecycle = viewLifecycleOwner.lifecycle
    val observer = object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() } }
    lifecycle.addObserver(observer)
    dialog.setOnDismissListener { lifecycle.removeObserver(observer) }
    dialog.show()
}

private fun Fragment.whenStarted(action: () -> Unit) {
    if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action()
}

private enum class DocumentAction(val label: Int) {
    OPEN(R.string.doc_open), VERSIONS(R.string.doc_versions), NEW_VERSION(R.string.doc_new_version), ARCHIVE(R.string.doc_archive)
}

private class DocumentAdapter(private val versions: Boolean, private val click: (DocumentRow, DocumentAction) -> Unit) :
    RecyclerView.Adapter<DocumentAdapter.Holder>() {
    private var state = DocumentLibraryState()
    class Holder(val binding: ItemStudyGroupBinding) : RecyclerView.ViewHolder(binding.root)
    fun submit(value: DocumentLibraryState) { state = value; notifyDataSetChanged() }
    override fun getItemCount() = state.rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemStudyGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = state.rows[position]
        with(holder.binding) {
            val context = root.context
            title.text = if (versions) context.getString(R.string.doc_versions) + " " + row.versionNumber +
                (if (row.isCurrent) " " + context.getString(R.string.doc_row_current) else "") else row.title
            subtitle.text = context.getString(R.string.doc_row_subtitle, row.versionNumber, row.pageCount, row.uploader)
            actions.removeAllViews()
            val list = if (versions) listOf(DocumentAction.OPEN)
                else listOfNotNull(DocumentAction.OPEN, DocumentAction.VERSIONS,
                    DocumentAction.NEW_VERSION.takeIf { row.canManage }, DocumentAction.ARCHIVE.takeIf { row.canManage })
            list.forEach { action ->
                actions.addView(Button(context).apply {
                    setText(if (state.busy == row.id && action == DocumentAction.ARCHIVE) R.string.group_saving else action.label)
                    contentDescription = context.getString(R.string.doc_action_for, context.getString(action.label), row.title)
                    isEnabled = state.busy == null && !state.loading
                    setOnClickListener { click(row, action) }
                })
            }
        }
    }
}

/** Document library of a group, or the version history of one document. */
class DocumentLibraryFragment : Fragment() {
    private val model: DocumentLibraryViewModel by viewModels {
        viewModelFactory { initializer { DocumentLibraryViewModel(createSavedStateHandle(), store = documentStore(requireContext())) } }
    }
    private var binding: FragmentGroupDocumentsBinding? = null
    private val groupId get() = requireNotNull(requireArguments().getString(GROUP))
    private val documentId get() = arguments?.getString(DOCUMENT)
    private var uploadTarget: String? = null
    private var closing = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::readAndUpload) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uploadTarget = savedInstanceState?.getString(TARGET)
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString(TARGET, uploadTarget) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        closing = false
        return FragmentGroupDocumentsBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = requireNotNull(binding)
        val versions = documentId != null
        val adapter = DocumentAdapter(versions) { row, action ->
            when (action) {
                DocumentAction.OPEN -> model.open(row)
                DocumentAction.VERSIONS -> home()?.openDetail(newInstance(groupId, row.id))
                DocumentAction.NEW_VERSION -> pick(row.id)
                DocumentAction.ARCHIVE -> showBound(MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.group_confirm)
                    .setMessage(getString(R.string.doc_confirm_archive, row.title)).setNegativeButton(R.string.group_cancel, null)
                    .setPositiveButton(R.string.doc_archive) { _, _ -> whenStarted { model.archive(row) } }.create())
            }
        }
        b.rows.layoutManager = LinearLayoutManager(requireContext()); b.rows.adapter = adapter
        b.tvTitle.setText(if (versions) R.string.doc_versions_title else R.string.doc_title)
        b.btnUpload.setText(if (versions) R.string.doc_new_version else R.string.doc_upload)
        b.btnBack.setOnClickListener { home()?.closeDetail() }
        b.btnUpload.setOnClickListener { pick(documentId) }
        b.btnRetry.setOnClickListener { if (model.state.value.canRetryUpload) model.retryUpload() else model.refresh() }
        b.btnMore.setOnClickListener { model.more() }
        b.refresh.setOnRefreshListener { model.refresh() }
        model.show(groupId, documentId)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val current = binding ?: return@collect
                    if (versions) state.rows.firstOrNull()?.let { current.tvTitle.text = getString(R.string.doc_versions_title) + ": " + it.title }
                    val canUpload = !versions || state.rows.firstOrNull()?.canManage == true
                    current.btnUpload.isVisible = canUpload
                    current.btnUpload.isEnabled = state.busy == null && !state.loading
                    current.refresh.isRefreshing = state.loading
                    current.btnRetry.setText(if (state.canRetryUpload) R.string.doc_upload_retry else R.string.group_retry)
                    current.btnRetry.isVisible = state.error != null || state.actionError != null
                    current.btnRetry.isEnabled = state.busy == null && !state.loading
                    current.btnMore.isVisible = state.more
                    current.btnMore.isEnabled = state.busy == null && !state.loading
                    current.tvStatus.text = state.actionError ?: state.error ?: when {
                        state.busy == DocumentLibraryViewModel.UPLOAD -> getString(R.string.doc_uploading)
                        state.loading -> getString(R.string.group_loading)
                        state.loaded && state.rows.isEmpty() -> getString(R.string.doc_empty)
                        else -> ""
                    }
                    adapter.submit(state)
                    if (state.uploaded || state.archived) {
                        Toast.makeText(requireContext(), if (state.uploaded) R.string.doc_uploaded else R.string.doc_archived, Toast.LENGTH_SHORT).show()
                        model.acknowledged()
                    }
                    state.openVersion?.let { id -> model.opened(); home()?.openDetail(DocumentViewerFragment.newInstance(id)) }
                    if (state.closed && !closing) {
                        closing = true
                        Toast.makeText(requireContext(), state.error ?: getString(R.string.doc_closed), Toast.LENGTH_LONG).show()
                        home()?.closeDetail()
                    }
                }
            }
        }
    }

    private fun pick(target: String?) {
        if (model.state.value.busy != null) return
        uploadTarget = target
        picker.launch(arrayOf("application/pdf"))
    }

    /** Reads at most 20 MiB + 1 byte off the main thread; the repository and server enforce the limits. */
    private fun readAndUpload(uri: Uri) {
        val resolver = requireContext().applicationContext.contentResolver
        val target = uploadTarget
        viewLifecycleOwner.lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val limit = GroupDocumentRepository.MAX_PDF_BYTES + 1
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (buffer.size() < limit) {
                            val read = input.read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
                            if (read < 0) break
                            buffer.write(chunk, 0, read)
                        }
                        buffer.toByteArray()
                    } ?: error("unreadable")
                    bytes to name?.removeSuffix(".pdf")?.removeSuffix(".PDF")?.trim()?.take(200)?.ifEmpty { null }
                }.getOrNull()
            }
            if (picked == null) { Toast.makeText(requireContext(), R.string.doc_read_failed, Toast.LENGTH_LONG).show(); return@launch }
            model.upload(picked.first, picked.second ?: getString(R.string.doc_title), target)
        }
    }

    override fun onResume() { super.onResume(); model.refresh() }
    override fun onDestroyView() { binding?.rows?.adapter = null; binding = null; super.onDestroyView() }

    companion object {
        private const val GROUP = "group_id"
        private const val DOCUMENT = "document_id"
        private const val TARGET = "upload_target"
        fun newInstance(groupId: String, documentId: String? = null) = DocumentLibraryFragment().apply {
            arguments = Bundle().apply { putString(GROUP, groupId); documentId?.let { putString(DOCUMENT, it) } }
        }
    }
}

/** In-app PDF viewer/editor of one immutable version with collaborative annotations and export. */
class DocumentViewerFragment : Fragment(), PdfPageView.Listener {
    private val model: DocumentViewerViewModel by viewModels {
        viewModelFactory {
            initializer {
                val context = requireContext().applicationContext
                DocumentViewerViewModel(createSavedStateHandle(), store = documentStore(context), exporter = AndroidDocumentExporter(context),
                    callSession = CallSessionManager.get(context).state)
            }
        }
    }
    private var binding: FragmentDocumentViewerBinding? = null
    private var renderer: PdfPageRenderer? = null
    private var rendererPath: String? = null
    private var bitmap: Bitmap? = null
    private var renderJob: Job? = null
    private var shownPage = -1
    private var annotations: List<DisplayAnnotation> = emptyList()
    private var closing = false
    private val versionId get() = requireNotNull(requireArguments().getString(VERSION))
    private val exportTarget = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.exportTo(uri.toString())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        closing = false; shownPage = -1
        return FragmentDocumentViewerBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = requireNotNull(binding)
        b.page.listener = this
        applyTool(model.tool)
        b.page.inkColor = model.inkColor; b.page.highlightColor = model.markColor
        b.btnBack.setOnClickListener { model.stopGuiding(); home()?.closeDetail() } // the call itself continues
        b.btnRetry.setOnClickListener { model.retry() }
        bindCallBar(b)
        b.btnPrev.setOnClickListener { model.goTo(model.state.value.page - 1) }
        b.btnNext.setOnClickListener { model.goTo(model.state.value.page + 1) }
        b.btnRotate.setOnClickListener { b.page.rotateClockwise() }
        b.btnFit.setOnClickListener { b.page.resetZoom() }
        b.toolView.setOnClickListener { applyTool(EditorTool.VIEW) }
        b.toolInk.setOnClickListener { applyTool(EditorTool.INK) }
        b.toolHighlight.setOnClickListener { applyTool(EditorTool.HIGHLIGHT) }
        b.toolNote.setOnClickListener { applyTool(EditorTool.NOTE) }
        b.toolSelect.setOnClickListener { applyTool(EditorTool.SELECT) }
        b.btnColor.setOnClickListener { cycleColor() }
        b.btnDelete.setOnClickListener { deleteSelected() }
        b.btnUndo.setOnClickListener { model.undo() }
        b.btnConflicts.setOnClickListener { showConflict() }
        b.btnExport.setOnClickListener { confirmExport() }
        b.btnCancelExport.setOnClickListener { model.cancelExport() }
        b.btnNewer.setOnClickListener {
            model.sync.value.newerVersionId?.let { id -> home()?.let { h -> h.closeDetail(); h.openDetail(newInstance(id)) } }
        }
        model.show(versionId, getString(R.string.doc_self))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch { model.sync.collect(::renderSync) }
                launch {
                    val session = CallSessionManager.get(requireContext())
                    combine(session.state, model.guidance, model.state) { call, guide, viewer -> Triple(call, guide, viewer) }
                        .collect { (call, guide, viewer) -> renderCallBar(call, guide, viewer) }
                }
            }
        }
    }

    /** 10.5: compact call controls and optional follow-guide. Reading and annotating stay independent. */
    private fun bindCallBar(b: FragmentDocumentViewerBinding) {
        val session = CallSessionManager.get(requireContext())
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        b.btnCallMic.setOnClickListener {
            val on = session.state.value.microphoneOn
            if (!on && !granted(android.Manifest.permission.RECORD_AUDIO)) Toast.makeText(requireContext(), R.string.doc_call_permission, Toast.LENGTH_LONG).show()
            else session.setMicrophone(!on)
        }
        b.btnCallCamera.setOnClickListener {
            val on = session.state.value.cameraOn
            if (!on && !granted(android.Manifest.permission.CAMERA)) Toast.makeText(requireContext(), R.string.doc_call_permission, Toast.LENGTH_LONG).show()
            else session.setCamera(!on)
        }
        b.btnCallLeave.setOnClickListener { model.stopGuiding(); session.leave() }
        b.btnCallReturn.setOnClickListener {
            val groupId = model.state.value.version?.groupId ?: return@setOnClickListener
            home()?.openDetail(GroupCallFragment.newInstance(groupId, session.state.value.call?.initialMode ?: CallMode.AUDIO))
        }
        b.btnGuide.setOnClickListener { if (model.guidance.value.guiding) model.stopGuiding() else model.startGuiding() }
        b.chkFollow.setOnCheckedChangeListener { _, checked -> if (checked != model.guidance.value.following) model.setFollowing(checked) }
        b.btnGuideOpen.setOnClickListener {
            model.guidance.value.otherGuide?.let { g -> home()?.let { h -> h.closeDetail(); h.openDetail(newInstance(g.versionId)) } }
        }
    }

    private fun renderCallBar(call: CallSessionState, guide: GuidanceUiState, viewer: ViewerState) {
        val b = binding ?: return
        val version = viewer.version
        val mine = call.active && version != null && call.groupId == version.groupId && !viewer.closed
        b.callBar.isVisible = mine
        if (!mine) return
        val connected = call.phase == CallPhase.CONNECTED
        b.tvCallState.text = when (call.phase) {
            CallPhase.CONNECTED -> getString(R.string.call_connected, call.participants.size, call.call?.capacity ?: 6)
            CallPhase.RECONNECTING -> getString(R.string.call_reconnecting)
            else -> getString(R.string.call_joining)
        }
        b.btnCallMic.setText(if (call.microphoneOn) R.string.call_mic_on else R.string.call_mic_off)
        b.btnCallCamera.setText(if (call.cameraOn) R.string.call_cam_on else R.string.call_cam_off)
        b.btnCallMic.isEnabled = connected; b.btnCallCamera.isEnabled = connected
        if (call.controlFailed) {
            Toast.makeText(requireContext(), R.string.call_control_failed, Toast.LENGTH_SHORT).show()
            CallSessionManager.get(requireContext()).consumeControlFailure()
        }
        b.guideRow.isVisible = !guide.closed
        if (guide.closed) return
        val other = guide.otherGuide
        b.btnGuide.setText(if (guide.guiding) R.string.doc_guide_stop else R.string.doc_guide_start)
        b.btnGuide.isEnabled = viewer.pdfPath != null && (guide.guiding || other == null)
        if (b.chkFollow.isChecked != guide.following) b.chkFollow.isChecked = guide.following
        b.chkFollow.isEnabled = !guide.guiding
        val otherVersion = other != null && other.versionId != version?.id
        b.btnGuideOpen.isVisible = guide.following && otherVersion
        b.tvGuide.text = when {
            guide.guiding -> getString(R.string.doc_guide_you, viewer.page + 1)
            other != null && otherVersion -> getString(R.string.doc_guide_other_version, other.guide.displayName)
            other != null -> getString(R.string.doc_guide_other, other.guide.displayName, other.pageIndex + 1)
            else -> getString(R.string.doc_guide_none)
        }
        guide.message?.let { Toast.makeText(requireContext(), it.userMessage, Toast.LENGTH_LONG).show(); model.consumeGuidanceMessage() }
    }

    override fun onStart() { super.onStart(); model.setForeground(true) }
    override fun onStop() { model.setForeground(false); super.onStop() }

    private fun render(state: ViewerState) {
        val b = binding ?: return
        if (state.closed) {
            if (!closing) {
                closing = true
                releaseRenderer()
                Toast.makeText(requireContext(), state.closedMessage ?: getString(R.string.doc_closed), Toast.LENGTH_LONG).show()
                home()?.closeDetail()
            }
            return
        }
        val version = state.version
        b.tvTitle.text = version?.let { "${it.title} · v${it.versionNumber}" } ?: ""
        b.btnRetry.isVisible = state.error != null || model.sync.value.loadError != null
        b.tvStatus.text = state.error ?: if (state.loading) getString(R.string.group_loading) else ""
        val ready = version != null && state.pdfPath != null
        listOf(b.btnPrev, b.btnNext, b.btnRotate, b.btnFit).forEach { it.isEnabled = ready }
        if (version != null) {
            b.tvPage.text = getString(R.string.doc_page, state.page + 1, version.pageCount)
            b.btnPrev.isEnabled = ready && state.page > 0
            b.btnNext.isEnabled = ready && state.page < version.pageCount - 1
        }
        if (ready && rendererPath != state.pdfPath) openRenderer(requireNotNull(state.pdfPath))
        if (ready && renderer != null && shownPage != state.page) {
            shownPage = state.page
            val size = version!!.pages.getOrNull(state.page)
            b.page.showPage(state.page, size?.w?.toFloat() ?: 612f, size?.h?.toFloat() ?: 792f, resetView = true)
            b.page.selectedId = null
            b.page.annotations = annotations
            b.page.resetZoom() // reports the new page's render size through onRenderSize
        }
        renderExport(state.export)
    }

    private fun renderSync(sync: com.doan.studyhub.core.data.document.SyncState) {
        val b = binding ?: return
        annotations = sync.annotations
        b.page.annotations = annotations
        if (b.page.selectedId != null && annotations.none { it.id == b.page.selectedId }) b.page.selectedId = null
        b.tvSave.text = when (sync.status) {
            SaveStatus.SAVED -> getString(R.string.doc_saved)
            SaveStatus.SAVING -> getString(R.string.doc_saving)
            SaveStatus.UNSYNCED -> getString(R.string.doc_unsynced, sync.pendingCount)
            SaveStatus.CONFLICT -> getString(R.string.doc_conflict, sync.conflicts.size)
        }
        b.btnConflicts.isVisible = sync.conflicts.isNotEmpty()
        b.btnUndo.isEnabled = sync.canUndo
        b.newerBanner.isVisible = sync.newerVersionId != null
        val editable = !sync.loading && !sync.locked
        listOf(b.toolInk, b.toolHighlight, b.toolNote, b.toolSelect, b.btnColor, b.btnDelete, b.btnExport).forEach { it.isEnabled = editable }
        val loadError = sync.loadError
        if (loadError != null && !sync.locked) {
            b.btnRetry.isVisible = true
            b.tvStatus.text = loadError.userMessage
        }
        sync.message?.let { Toast.makeText(requireContext(), it.userMessage, Toast.LENGTH_LONG).show(); model.consumeMessage() }
    }

    private fun renderExport(export: ExportState) {
        val b = binding ?: return
        b.exportRow.isVisible = export is ExportState.Running || export == ExportState.Publishing || export == ExportState.Syncing
        b.btnExport.isEnabled = b.btnExport.isEnabled && !b.exportRow.isVisible
        when (export) {
            is ExportState.Running -> {
                b.exportProgress.isIndeterminate = false
                b.exportProgress.max = export.total.coerceAtLeast(1); b.exportProgress.progress = export.done
                b.tvStatus.text = getString(R.string.doc_export_progress, export.done, export.total)
            }
            ExportState.Publishing -> { b.exportProgress.isIndeterminate = true; b.tvStatus.setText(R.string.doc_export_publishing) }
            ExportState.Syncing -> { b.exportProgress.isIndeterminate = true; b.tvStatus.setText(R.string.doc_export_syncing) }
            ExportState.ChooseTarget -> {
                model.targetChosen()
                val title = model.state.value.version?.let { "${it.title} - v${it.versionNumber} (StudyHub).pdf" } ?: "StudyHub.pdf"
                exportTarget.launch(title)
            }
            ExportState.Done, ExportState.Cancelled, is ExportState.Failed -> {
                val message = when (export) {
                    ExportState.Done -> getString(R.string.doc_export_done)
                    ExportState.Cancelled -> getString(R.string.doc_export_cancelled)
                    else -> (export as ExportState.Failed).message ?: getString(R.string.doc_export_failed)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                b.tvStatus.text = ""
                model.exportAcknowledged()
            }
            ExportState.Idle -> Unit
        }
    }

    private fun openRenderer(path: String) {
        releaseRenderer()
        renderer = runCatching { PdfPageRenderer(File(path)) }.getOrNull()
        rendererPath = path
        shownPage = -1
        if (renderer == null) binding?.tvStatus?.setText(R.string.doc_read_failed)
    }

    private var requested = 0 to 0
    override fun onRenderSize(widthPx: Int, heightPx: Int) { requested = widthPx to heightPx; requestRender() }

    /** Renders the shown page at the on-screen size off the main thread; a newer request cancels the older one. */
    private fun requestRender() {
        val r = renderer ?: return
        val page = shownPage.takeIf { it >= 0 } ?: return
        val (w, h) = requested
        if (w <= 0 || h <= 0) return
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val rendered = runCatching { r.render(page, w, h) }.getOrNull() ?: return@launch
            val b = binding
            if (b == null || renderer !== r || shownPage != page) { rendered.recycle(); return@launch }
            val old = bitmap
            bitmap = rendered
            b.page.setBitmap(rendered)
            old?.recycle()
        }
    }

    private fun releaseRenderer() {
        renderJob?.cancel(); renderJob = null
        binding?.page?.setBitmap(null)
        bitmap?.recycle(); bitmap = null
        renderer?.close(); renderer = null; rendererPath = null
    }

    private fun applyTool(tool: EditorTool) {
        val b = binding ?: return
        model.tool = tool
        b.page.tool = tool
        b.page.selectedId = null
        mapOf(EditorTool.VIEW to b.toolView, EditorTool.INK to b.toolInk, EditorTool.HIGHLIGHT to b.toolHighlight,
            EditorTool.NOTE to b.toolNote, EditorTool.SELECT to b.toolSelect).forEach { (t, button) ->
            button.isSelected = t == tool
            button.alpha = if (t == tool) 1f else 0.6f
        }
        updateColorLabel()
    }

    private fun cycleColor() {
        val b = binding ?: return
        if (model.tool == EditorTool.INK) {
            val colors = AnnotationLimits.INK_COLORS
            model.inkColor = colors[(colors.indexOf(model.inkColor) + 1) % colors.size]
            b.page.inkColor = model.inkColor
        } else {
            val colors = AnnotationLimits.HIGHLIGHT_COLORS
            model.markColor = colors[(colors.indexOf(model.markColor) + 1) % colors.size]
            b.page.highlightColor = model.markColor
        }
        updateColorLabel()
    }

    private fun updateColorLabel() {
        val b = binding ?: return
        val color = if (model.tool == EditorTool.INK) model.inkColor else model.markColor
        b.btnColor.setTextColor(android.graphics.Color.parseColor(color))
        b.btnColor.contentDescription = getString(R.string.doc_color_desc, color)
    }

    private fun deleteSelected() {
        val selected = annotations.firstOrNull { it.id == binding?.page?.selectedId } ?: return
        if (!model.canEdit(selected)) { Toast.makeText(requireContext(), R.string.doc_not_allowed, Toast.LENGTH_SHORT).show(); return }
        if (model.delete(selected)) binding?.page?.selectedId = null
    }

    override fun onStroke(points: List<PagePoint>) {
        val width = binding?.page?.inkWidth ?: return
        if (!model.addStroke(points, width)) Toast.makeText(requireContext(), R.string.doc_stroke_invalid, Toast.LENGTH_SHORT).show()
    }

    override fun onHighlight(left: Double, top: Double, right: Double, bottom: Double) { model.addHighlight(left, top, right, bottom) }

    override fun onNoteAt(point: PagePoint) = noteDialog(null, point)

    override fun onSelect(annotation: DisplayAnnotation?) {
        val b = binding ?: return
        if (annotation?.shape is AnnotationShape.Note) noteDialog(annotation, null)
        else b.page.selectedId = annotation?.id
    }

    /** Creates a note at [anchor], or shows/edits [existing]. Invalid text keeps the dialog open. */
    private fun noteDialog(existing: DisplayAnnotation?, anchor: PagePoint?) {
        val note = existing?.shape as? AnnotationShape.Note
        val editable = existing == null || model.canEdit(existing)
        val input = EditText(requireContext()).apply {
            setText(note?.text.orEmpty())
            hint = getString(R.string.doc_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            isEnabled = editable
        }
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) getString(R.string.doc_note_new) else getString(R.string.doc_note_by, existing.authorName))
            .setView(input)
            .setNegativeButton(R.string.doc_close, null)
        if (editable) builder.setPositiveButton(R.string.doc_save, null)
        if (existing != null && editable) builder.setNeutralButton(R.string.doc_delete) { _, _ -> whenStarted { model.delete(existing) } }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val text = input.text.toString()
                val ok = if (existing == null) anchor != null && model.addNote(anchor, text) else model.editNote(existing, text)
                if (ok) dialog.dismiss() else input.error = getString(R.string.doc_note_invalid)
            }
        }
        showBound(dialog)
    }

    private fun showConflict() {
        val conflict = model.sync.value.conflicts.firstOrNull() ?: return
        showBound(MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.doc_resolve)
            .setMessage(R.string.doc_conflict_message)
            .setPositiveButton(R.string.doc_keep_mine) { _, _ -> whenStarted { model.resolveConflict(conflict.mutation.opId, true) } }
            .setNegativeButton(R.string.doc_use_theirs) { _, _ -> whenStarted { model.resolveConflict(conflict.mutation.opId, false) } }
            .setNeutralButton(R.string.doc_cancel, null).create())
    }

    private fun confirmExport() {
        val pending = model.sync.value.pendingCount
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.doc_export)
        if (model.hasUnsavedWork()) {
            builder.setMessage(getString(R.string.doc_export_pending, pending) + "\n\n" + getString(R.string.doc_export_note))
                .setPositiveButton(R.string.doc_export_sync_first) { _, _ -> whenStarted { model.requestExport(syncFirst = true) } }
                .setNegativeButton(R.string.doc_export_saved_only) { _, _ -> whenStarted { model.requestExport(syncFirst = false) } }
                .setNeutralButton(R.string.doc_cancel, null)
        } else {
            builder.setMessage(R.string.doc_export_note)
                .setPositiveButton(R.string.doc_export) { _, _ -> whenStarted { model.requestExport(syncFirst = false) } }
                .setNegativeButton(R.string.doc_cancel, null)
        }
        showBound(builder.create())
    }

    override fun onDestroyView() {
        binding?.page?.listener = null
        releaseRenderer()
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val VERSION = "version_id"
        fun newInstance(versionId: String) = DocumentViewerFragment().apply { arguments = Bundle().apply { putString(VERSION, versionId) } }
    }
}
