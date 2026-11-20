package com.doan.studyhub.user.ui.group.call

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.CallMode
import com.doan.studyhub.core.data.model.GroupCall
import com.doan.studyhub.core.data.repository.GroupCallRepository
import com.doan.studyhub.core.data.repository.GroupRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class CallPhase { IDLE, JOINING, CONNECTED, RECONNECTING, ENDED, FAILED }

data class CallSessionState(
    val phase: CallPhase = CallPhase.IDLE,
    val groupId: String? = null,
    val call: GroupCall? = null,
    val participants: List<CallParticipant> = emptyList(),
    val microphoneOn: Boolean = false,
    val cameraOn: Boolean = false,
    /** Camera stopped because the app went to the background; resumed on return. */
    val cameraPaused: Boolean = false,
    val routes: List<AudioRoute> = emptyList(),
    val route: AudioRoute? = null,
    val error: ErrorType? = null,
    /** Removed from the call/group, or the call ended for everyone. */
    val removed: Boolean = false,
    /** A media control failed (e.g. camera busy); shown once, never reported as success. */
    val controlFailed: Boolean = false,
    val endingForAll: Boolean = false
) {
    val active get() = phase == CallPhase.JOINING || phase == CallPhase.CONNECTED || phase == CallPhase.RECONNECTING
}

/** Starts/stops the microphone foreground service; Android implementation in [CallService]. */
interface CallForeground {
    fun start()
    fun stop()
}

/**
 * The app's single call session. Lives outside fragments so rotation and opening the in-app PDF
 * keep the connection; released on leave, server removal, logout or account switch. Join
 * credentials are used once for [CallEngine.connect] and never stored. Capture only starts from a
 * user action with granted permission; nothing restarts after process death.
 */
class CallSessionManager(
    private val calls: GroupCallRepository,
    private val groups: GroupRepository,
    private val engineFactory: () -> CallEngine,
    private val foreground: CallForeground,
    private val hasPermission: (String) -> Boolean,
    private val scope: CoroutineScope
) {
    private val mutable = MutableStateFlow(CallSessionState())
    val state: StateFlow<CallSessionState> = mutable.asStateFlow()
    /** Identifies this app instance to the server, so a retry is not mistaken for another device. */
    private val clientSessionId = UUID.randomUUID().toString()
    private var engine: CallEngine? = null
    private var session: Job? = null
    private var identity = calls.currentUserId() to calls.currentSessionKey()
    private var serviceStarted = false

    init {
        scope.launch {
            groups.sessionChanges().collect {
                val current = calls.currentUserId() to calls.currentSessionKey()
                if (current != identity) {
                    identity = current
                    // Disconnect the previous account's media before the next account is shown.
                    teardown(CallSessionState())
                }
            }
        }
    }

    /** Engine of the connected session, for video renderers only. */
    fun engine(): CallEngine? = engine.takeIf { mutable.value.active }

    /**
     * Joins (or starts) the group's call. A second call while a session for the same group is
     * active is a no-op (re-attach); another group's call is refused until this one is left.
     */
    fun join(groupId: String, mode: CallMode, requestId: String): Boolean {
        val current = mutable.value
        if (current.active) return current.groupId == groupId
        if (calls.currentUserId() == null) return false
        mutable.value = CallSessionState(phase = CallPhase.JOINING, groupId = groupId)
        session = scope.launch {
            val joined = when (val result = calls.startOrJoin(groupId, requestId, clientSessionId, mode)) {
                is ApiResponse.Success -> result.data
                is ApiResponse.Failure -> {
                    mutable.value = CallSessionState(phase = CallPhase.FAILED, groupId = groupId, error = result.error.type)
                    return@launch
                }
            }
            val media = engineFactory()
            engine = media
            launch { media.events.collect(::onEvent) }
            try {
                media.connect(joined.connection.url, joined.connection.token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                teardown(CallSessionState(phase = CallPhase.FAILED, groupId = groupId, call = joined.call,
                    error = ErrorType.CALL_PROVIDER_UNAVAILABLE))
                return@launch
            }
            // Voice starts with the camera off; video only with camera permission. Denied
            // microphone still allows listening/viewing.
            val mic = hasPermission(Manifest.permission.RECORD_AUDIO) && media.setMicrophone(true)
            val camera = mode == CallMode.VIDEO && hasPermission(Manifest.permission.CAMERA) && media.setCamera(true)
            mutable.value = mutable.value.copy(phase = CallPhase.CONNECTED, call = joined.call, microphoneOn = mic,
                cameraOn = camera, error = null)
            refreshParticipants()
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                foreground.start(); serviceStarted = true
            }
        }
        return true
    }

    private fun onEvent(event: EngineEvent) {
        when (event) {
            EngineEvent.Connected, EngineEvent.Reconnected -> {
                if (mutable.value.phase == CallPhase.RECONNECTING) mutable.value = mutable.value.copy(phase = CallPhase.CONNECTED)
                refreshParticipants()
            }
            EngineEvent.Reconnecting -> if (mutable.value.active) mutable.value = mutable.value.copy(phase = CallPhase.RECONNECTING)
            EngineEvent.ParticipantsChanged -> refreshParticipants()
            is EngineEvent.Disconnected -> {
                if (!mutable.value.active || event.byUser) return
                val previous = mutable.value
                scope.launch {
                    teardown(CallSessionState(phase = CallPhase.ENDED, groupId = previous.groupId, call = previous.call,
                        removed = event.removed, error = if (event.removed) null else ErrorType.NETWORK_UNAVAILABLE))
                }
            }
        }
    }

    private fun refreshParticipants() {
        val media = engine ?: return
        if (!mutable.value.active) return
        mutable.value = mutable.value.copy(participants = media.participants(), routes = media.audioRoutes(),
            route = media.selectedAudioRoute())
    }

    fun setMicrophone(enabled: Boolean) = control { media ->
        if (enabled && !hasPermission(Manifest.permission.RECORD_AUDIO)) return@control false
        val ok = media.setMicrophone(enabled)
        if (ok) {
            mutable.value = mutable.value.copy(microphoneOn = enabled)
            // Permission granted mid-call: keep the microphone alive in the background from now on.
            if (enabled && !serviceStarted) { foreground.start(); serviceStarted = true }
        }
        ok
    }

    fun setCamera(enabled: Boolean) = control { media ->
        if (enabled && !hasPermission(Manifest.permission.CAMERA)) return@control false
        val ok = media.setCamera(enabled)
        if (ok) mutable.value = mutable.value.copy(cameraOn = enabled, cameraPaused = false)
        ok
    }

    fun switchCamera() = control { media -> mutable.value.cameraOn && media.switchCamera() }

    fun selectAudioRoute(id: String) = control { media ->
        val ok = media.selectAudioRoute(id)
        refreshParticipants()
        ok
    }

    private fun control(block: suspend (CallEngine) -> Boolean) {
        val media = engine ?: return
        if (mutable.value.phase != CallPhase.CONNECTED) return
        scope.launch {
            val ok = block(media)
            refreshParticipants()
            if (!ok && mutable.value.active) mutable.value = mutable.value.copy(controlFailed = true)
        }
    }

    fun consumeControlFailure() { mutable.value = mutable.value.copy(controlFailed = false) }

    /** Background: stop publishing camera (microphone continues through the foreground service). */
    fun onAppBackground() {
        val media = engine ?: return
        if (!mutable.value.cameraOn) return
        scope.launch {
            if (media.setCamera(false)) mutable.value = mutable.value.copy(cameraOn = false, cameraPaused = true)
        }
    }

    /** Foreground again: resume a camera the user had on, only while permission is still granted. */
    fun onAppForeground() {
        val media = engine ?: return
        if (!mutable.value.cameraPaused) return
        scope.launch {
            val resumed = hasPermission(Manifest.permission.CAMERA) && media.setCamera(true)
            mutable.value = mutable.value.copy(cameraOn = resumed, cameraPaused = false)
        }
    }

    /** Leaves this user's participation; others stay connected. */
    fun leave() {
        val current = mutable.value
        val callId = current.call?.id
        scope.launch {
            teardown(CallSessionState())
            if (callId != null) calls.leave(callId) // server revokes this identity; media is already released
        }
    }

    /** End for all (owner or initiator); disconnects locally once the server accepted it. */
    fun endForAll() {
        val current = mutable.value
        val callId = current.call?.id ?: return
        if (current.endingForAll) return
        mutable.value = current.copy(endingForAll = true)
        scope.launch {
            when (val result = calls.end(callId)) {
                is ApiResponse.Success -> teardown(CallSessionState(phase = CallPhase.ENDED, groupId = current.groupId,
                    call = result.data, removed = false))
                is ApiResponse.Failure -> mutable.value = mutable.value.copy(endingForAll = false, error = result.error.type)
            }
        }
    }

    fun acknowledgeEnded() {
        if (!mutable.value.active) mutable.value = CallSessionState()
    }

    private suspend fun teardown(next: CallSessionState) {
        val running = session
        session = null
        val media = engine
        engine = null
        media?.release()
        if (serviceStarted) { foreground.stop(); serviceStarted = false }
        mutable.value = next
        if (running != null && running != currentCoroutineContext()[Job]) running.cancel()
    }

    companion object {
        @Volatile private var instance: CallSessionManager? = null

        /** Process-wide session, created with the application context (no Activity/View references). */
        fun get(context: Context): CallSessionManager = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): CallSessionManager {
            val manager = CallSessionManager(
                calls = GroupCallRepository(),
                groups = GroupRepository(),
                engineFactory = { LiveKitCallEngine(app) },
                foreground = object : CallForeground {
                    override fun start() = CallService.start(app)
                    override fun stop() = CallService.stop(app)
                },
                hasPermission = { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            )
            (app as? Application)?.registerActivityLifecycleCallbacks(AppVisibility(manager))
            return manager
        }
    }

    /** Adapts activity callbacks to [VisibilityTracker]. */
    private class AppVisibility(manager: CallSessionManager) : Application.ActivityLifecycleCallbacks {
        private val tracker = VisibilityTracker(manager::onAppForeground, manager::onAppBackground)
        override fun onActivityStarted(activity: Activity) = tracker.started()
        override fun onActivityStopped(activity: Activity) = tracker.stopped(activity.isChangingConfigurations)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

/**
 * Started-activity counter: the app is in the background when none of its activities is visible.
 * A stop caused by a configuration change (rotation) is not "background", so the camera is not
 * paused and the session is not disturbed while the activity is recreated.
 */
internal class VisibilityTracker(private val onForeground: () -> Unit, private val onBackground: () -> Unit) {
    private var started = 0
    fun started() { if (started++ == 0) onForeground() }
    fun stopped(changingConfigurations: Boolean) {
        if (changingConfigurations) { started--; return }
        if (--started == 0) onBackground()
    }
}
