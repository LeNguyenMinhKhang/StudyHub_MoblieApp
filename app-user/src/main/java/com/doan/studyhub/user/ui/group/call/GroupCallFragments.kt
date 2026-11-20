package com.doan.studyhub.user.ui.group.call

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.CallMode
import com.doan.studyhub.core.data.model.CallStatus
import com.doan.studyhub.core.data.model.GroupCall
import com.doan.studyhub.core.data.repository.GroupCallRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentGroupCallBinding
import com.doan.studyhub.user.databinding.ItemCallParticipantBinding
import com.doan.studyhub.user.ui.group.document.DocumentLibraryFragment
import com.doan.studyhub.user.ui.home.Home
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private fun Fragment.home() = parentFragment as? Home

/** Screen-level wrapper around the process-wide [CallSessionManager]; keeps the start request key. */
class GroupCallViewModel(private val saved: SavedStateHandle, private val manager: CallSessionManager) : ViewModel() {
    val state: StateFlow<CallSessionState> = manager.state
    /** Same key across rotation/retries of this screen, so the server returns the same call. */
    val requestId: String = saved.get<String>(REQUEST) ?: UUID.randomUUID().toString().also { saved[REQUEST] = it }
    var permissionsAsked: Boolean
        get() = saved.get<Boolean>(ASKED) ?: false
        set(value) { saved[ASKED] = value }
    /** In-memory only: after process death the screen must not auto-close or auto-rejoin. */
    var seenActive = false

    fun join(groupId: String, mode: CallMode) = manager.join(groupId, mode, requestId)
    /** A new attempt after a failure or lost connection needs a new key (the old call may be over). */
    fun rejoin(groupId: String, mode: CallMode): Boolean {
        saved[REQUEST] = UUID.randomUUID().toString()
        manager.acknowledgeEnded()
        return manager.join(groupId, mode, requireNotNull(saved.get<String>(REQUEST)))
    }
    fun engine() = manager.engine()
    fun setMicrophone(on: Boolean) = manager.setMicrophone(on)
    fun setCamera(on: Boolean) = manager.setCamera(on)
    fun switchCamera() = manager.switchCamera()
    fun selectAudioRoute(id: String) = manager.selectAudioRoute(id)
    fun leave() = manager.leave()
    fun endForAll() = manager.endForAll()
    fun consumeControlFailure() = manager.consumeControlFailure()
    fun acknowledgeEnded() = manager.acknowledgeEnded()

    private companion object {
        const val REQUEST = "call_request"
        const val ASKED = "permissions_asked"
    }
}

/** Participant tiles. Each renderer is initialised once for its engine and released with the view. */
private class TileAdapter(private val engine: CallEngine) : RecyclerView.Adapter<TileAdapter.Holder>() {
    private var items: List<CallParticipant> = emptyList()
    private val renderers = mutableSetOf<TextureViewRenderer>()

    class Holder(val binding: ItemCallParticipantBinding) : RecyclerView.ViewHolder(binding.root) {
        var attached: String? = null
    }

    fun submit(list: List<CallParticipant>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCallParticipantBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        engine.initRenderer(binding.video)
        renderers += binding.video
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = items[position]
        val context = holder.binding.root.context
        val name = if (p.isLocal) context.getString(R.string.call_you) else p.name.ifBlank { context.getString(R.string.call_member) }
        holder.attached?.let { engine.detachVideo(it, holder.binding.video) }
        holder.attached = null
        val video = p.cameraOn && engine.attachVideo(p.identity, holder.binding.video)
        if (video) holder.attached = p.identity
        holder.binding.video.isVisible = video
        holder.binding.video.setMirror(p.isLocal)
        holder.binding.initial.isVisible = !video
        holder.binding.initial.text = name.take(1).uppercase()
        holder.binding.label.text = (if (p.microphoneOn) "🎙 " else "🔇 ") + name + if (p.speaking) " •" else ""
        holder.binding.root.contentDescription = context.getString(R.string.call_tile_desc, name,
            context.getString(if (p.microphoneOn) R.string.call_on else R.string.call_off),
            context.getString(if (p.cameraOn) R.string.call_on else R.string.call_off))
    }

    override fun onViewRecycled(holder: Holder) {
        holder.attached?.let { engine.detachVideo(it, holder.binding.video) }
        holder.attached = null
    }

    fun release() {
        renderers.forEach { runCatching { it.release() } }
        renderers.clear()
    }
}

/**
 * Voice/video call screen. Microphone and camera permissions are requested independently;
 * denied camera keeps audio, denied microphone keeps listening/viewing. Leaving this screen
 * (e.g. to the group PDF) keeps the session; only Leave/End or server removal disconnects.
 */
class GroupCallFragment : Fragment() {
    private val model: GroupCallViewModel by viewModels {
        viewModelFactory { initializer { GroupCallViewModel(createSavedStateHandle(), CallSessionManager.get(requireContext())) } }
    }
    private var binding: FragmentGroupCallBinding? = null
    private var tiles: TileAdapter? = null
    private var tilesEngine: CallEngine? = null
    private var closing = false
    private val groupId get() = requireNotNull(requireArguments().getString(GROUP))
    private val mode get() = CallMode.valueOf(requireArguments().getString(MODE) ?: CallMode.AUDIO.name)

    private fun granted(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Initial request: each permission is optional; the call starts with whatever was granted. */
    private val initialPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (view != null) model.join(groupId, mode)
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) model.setMicrophone(true) else permissionDenied(Manifest.permission.RECORD_AUDIO)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) model.setCamera(true) else permissionDenied(Manifest.permission.CAMERA)
    }

    private fun permissionDenied(permission: String) {
        val permanently = !shouldShowRequestPermissionRationale(permission)
        Toast.makeText(requireContext(), if (permanently) R.string.call_permission_settings
            else if (permission == Manifest.permission.CAMERA) R.string.call_camera_denied else R.string.call_mic_denied,
            Toast.LENGTH_LONG).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        closing = false
        return FragmentGroupCallBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = requireNotNull(binding)
        b.grid.layoutManager = GridLayoutManager(requireContext(), 2)
        b.btnBack.setOnClickListener { home()?.closeDetail() } // the call continues in the background of the app
        b.btnDocuments.setOnClickListener { home()?.openDetail(DocumentLibraryFragment.newInstance(groupId)) }
        b.btnMic.setOnClickListener {
            val on = model.state.value.microphoneOn
            if (!on && !granted(Manifest.permission.RECORD_AUDIO)) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            else model.setMicrophone(!on)
        }
        b.btnCamera.setOnClickListener {
            val on = model.state.value.cameraOn
            if (!on && !granted(Manifest.permission.CAMERA)) cameraPermission.launch(Manifest.permission.CAMERA)
            else model.setCamera(!on)
        }
        b.btnSwitch.setOnClickListener { model.switchCamera() }
        b.btnRoute.setOnClickListener { chooseRoute() }
        b.btnLeave.setOnClickListener { model.leave() }
        b.btnEnd.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.call_end_all).setMessage(R.string.call_confirm_end)
                .setNegativeButton(R.string.group_cancel, null)
                .setPositiveButton(R.string.call_end_all) { _, _ -> if (view != null) model.endForAll() }
                .show()
        }
        b.btnRejoin.setOnClickListener { model.rejoin(groupId, mode) }

        val current = model.state.value
        when {
            current.active && current.groupId != groupId -> {
                Toast.makeText(requireContext(), R.string.call_other_group, Toast.LENGTH_LONG).show()
                closing = true
                home()?.closeDetail()
                return
            }
            current.active -> Unit // re-attach to the ongoing session (rotation, return from PDF)
            // Restored after process death: never restart capture silently; offer "Rejoin" instead.
            !model.permissionsAsked -> {
                model.permissionsAsked = true
                val wanted = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (mode == CallMode.VIDEO) add(Manifest.permission.CAMERA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }.filterNot(::granted)
                if (wanted.isEmpty()) model.join(groupId, mode) else initialPermissions.launch(wanted.toTypedArray())
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
    }

    private fun render(state: CallSessionState) {
        val b = binding ?: return
        if (state.groupId != null && state.groupId != groupId) return
        val engine = model.engine()
        if (engine !== tilesEngine) { // new session: renderers belong to the previous engine
            tiles?.release()
            tiles = engine?.let(::TileAdapter)
            tilesEngine = engine
            b.grid.adapter = tiles
        }
        tiles?.submit(if (state.active) state.participants else emptyList())
        val connected = state.phase == CallPhase.CONNECTED
        b.tvStatus.text = when (state.phase) {
            CallPhase.JOINING -> getString(R.string.call_joining)
            CallPhase.CONNECTED -> getString(R.string.call_connected, state.participants.size, state.call?.capacity ?: 6)
            CallPhase.RECONNECTING -> getString(R.string.call_reconnecting)
            CallPhase.ENDED -> getString(if (state.removed) R.string.call_removed
                else if (state.error != null) R.string.call_connection_lost else R.string.call_ended)
            CallPhase.FAILED -> state.error?.userMessage ?: getString(R.string.call_connection_lost)
            CallPhase.IDLE -> ""
        }
        b.tvNotice.text = when {
            state.endingForAll -> getString(R.string.call_ending)
            state.cameraPaused -> getString(R.string.call_camera_paused)
            connected && !granted(Manifest.permission.RECORD_AUDIO) -> getString(R.string.call_mic_denied)
            state.error != null && state.active -> state.error.userMessage
            else -> ""
        }
        if (state.active) model.seenActive = true
        b.btnRejoin.isVisible = !state.active && !state.removed &&
            (state.phase == CallPhase.FAILED || (state.phase == CallPhase.ENDED && state.error != null) ||
                (state.phase == CallPhase.IDLE && !model.seenActive && model.permissionsAsked)) &&
            state.error !in setOf(ErrorType.GROUP_UNAVAILABLE, ErrorType.GROUP_ACCOUNT_UNAVAILABLE, ErrorType.UNAUTHENTICATED)
        val controls = connected && !state.endingForAll
        b.btnMic.isEnabled = controls; b.btnCamera.isEnabled = controls; b.btnRoute.isEnabled = controls
        b.btnSwitch.isEnabled = controls && state.cameraOn
        b.btnMic.setText(if (state.microphoneOn) R.string.call_mic_on else R.string.call_mic_off)
        b.btnCamera.setText(if (state.cameraOn) R.string.call_cam_on else R.string.call_cam_off)
        b.btnRoute.text = state.route?.let { getString(R.string.call_audio_route, routeName(it)) } ?: getString(R.string.call_audio_route_default)
        b.btnLeave.isEnabled = state.active && !state.endingForAll
        b.btnEnd.isVisible = state.call?.canEnd == true && state.active
        b.btnEnd.isEnabled = controls
        if (state.controlFailed) {
            Toast.makeText(requireContext(), R.string.call_control_failed, Toast.LENGTH_SHORT).show()
            model.consumeControlFailure()
        }
        if (state.phase == CallPhase.IDLE && !closing && model.seenActive) {
            // Left (or the session was released by logout/account switch): back to the group.
            closing = true
            home()?.closeDetail()
        }
    }

    private fun routeName(route: AudioRoute) = when (route.kind) {
        AudioRoute.Kind.SPEAKER -> getString(R.string.call_route_speaker)
        AudioRoute.Kind.EARPIECE -> getString(R.string.call_route_earpiece)
        AudioRoute.Kind.WIRED -> getString(R.string.call_route_wired)
        AudioRoute.Kind.BLUETOOTH -> getString(R.string.call_route_bluetooth, route.name)
        AudioRoute.Kind.OTHER -> route.name
    }

    private fun chooseRoute() {
        val routes = model.state.value.routes
        if (routes.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.call_choose_route)
            .setItems(routes.map(::routeName).toTypedArray()) { _, which ->
                if (view != null) routes.getOrNull(which)?.let { model.selectAudioRoute(it.id) }
            }.show()
    }

    override fun onDestroyView() {
        binding?.grid?.adapter = null
        tiles?.release(); tiles = null; tilesEngine = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val GROUP = "group_id"
        private const val MODE = "mode"
        fun newInstance(groupId: String, mode: CallMode) = GroupCallFragment().apply {
            arguments = Bundle().apply { putString(GROUP, groupId); putString(MODE, mode.name) }
        }
    }
}

data class CallBannerState(
    val call: GroupCall? = null,
    val loaded: Boolean = false,
    val error: String? = null
)

/** Call banner of group details: refreshed on demand, polled every 10 s only while visible. */
class GroupCallBannerViewModel @JvmOverloads constructor(
    private val calls: GroupCallRepository = GroupCallRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(CallBannerState())
    val state: StateFlow<CallBannerState> = mutable.asStateFlow()
    private var job: Job? = null
    private var identity = calls.currentUserId()

    fun refresh(groupId: String) {
        if (job?.isActive == true) return
        if (calls.currentUserId() != identity) { identity = calls.currentUserId(); mutable.value = CallBannerState() }
        job = scope.launch {
            when (val result = calls.current(groupId)) {
                is ApiResponse.Success -> mutable.value = CallBannerState(call = result.data, loaded = true)
                is ApiResponse.Failure -> mutable.value = mutable.value.copy(error = result.error.message, loaded = true,
                    call = if (result.error.type.retryable) mutable.value.call else null)
            }
        }
    }

    companion object {
        const val POLL_MS = 10_000L
        fun joinable(call: GroupCall?) = call != null && (call.status == CallStatus.STARTING || call.status == CallStatus.ACTIVE) &&
            (call.myStatus != null || call.participantCount < call.capacity)
    }
}
