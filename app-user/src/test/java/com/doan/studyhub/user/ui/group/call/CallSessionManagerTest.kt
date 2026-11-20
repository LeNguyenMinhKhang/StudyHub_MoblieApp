package com.doan.studyhub.user.ui.group.call

import android.Manifest
import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.repository.GroupCallRepository
import com.doan.studyhub.core.data.repository.GroupRepository
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test

class CallSessionManagerTest {
    private val group = "00000000-0000-0000-0000-000000000201"
    private val call = GroupCall("00000000-0000-0000-0000-0000000000c1", group, CallStatus.ACTIVE, CallMode.AUDIO,
        CallPeer("me", "Me"), "t", "t", null, 1, 6, true, false, "reserved")
    private val connection = CallConnection("wss://example.livekit.cloud", "secret.token.value", "identity-me", "2026-11-18T09:05:00Z")
    private fun failure(type: ErrorType) = ApiResponse.Failure(ApiError(type), "t")

    private class FakeEngine : CallEngine {
        val flow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
        override val events: kotlinx.coroutines.flow.Flow<EngineEvent> get() = flow
        var connected: Pair<String, String>? = null
        var connectError: Exception? = null
        var mic = false
        var camera = false
        var cameraWorks = true
        var released = 0
        override suspend fun connect(url: String, token: String) { connectError?.let { throw it }; connected = url to token }
        override suspend fun setMicrophone(enabled: Boolean): Boolean { mic = enabled; return true }
        override suspend fun setCamera(enabled: Boolean): Boolean { if (enabled && !cameraWorks) return false; camera = enabled; return true }
        override fun switchCamera() = camera
        override fun participants() = listOf(CallParticipant("identity-me", "Me", true, mic, camera, false))
        override fun audioRoutes() = listOf(AudioRoute("Speaker", "Speaker", AudioRoute.Kind.SPEAKER), AudioRoute("Earpiece", "Earpiece", AudioRoute.Kind.EARPIECE))
        var route = "Earpiece"
        override fun selectedAudioRoute() = audioRoutes().first { it.id == route }
        override fun selectAudioRoute(id: String): Boolean { route = id; return true }
        override fun attachVideo(identity: String, renderer: TextureViewRenderer) = false
        override fun detachVideo(identity: String, renderer: TextureViewRenderer) = Unit
        override fun initRenderer(renderer: TextureViewRenderer) = Unit
        override fun release() { released++ }
    }

    private inner class Calls : GroupCallRepository() {
        var user: String? = "me"
        var sessionKey = "s1"
        val starts = mutableListOf<Triple<String, String, String>>()
        var startResult: suspend () -> ApiResponse<CallJoin> = { ApiResponse.Success(CallJoin(call, connection), "t") }
        val leaves = mutableListOf<String>()
        var endResult: ApiResponse<GroupCall> = ApiResponse.Success(call.copy(status = CallStatus.ENDING, cleanupPending = true), "t")
        override fun currentUserId() = user
        override fun currentSessionKey() = sessionKey
        override suspend fun startOrJoin(groupId: String, requestId: String, clientSessionId: String, mode: CallMode): ApiResponse<CallJoin> {
            starts += Triple(groupId, requestId, clientSessionId); return startResult()
        }
        override suspend fun leave(callId: String): ApiResponse<Unit> { leaves += callId; return ApiResponse.Success(Unit, "t") }
        override suspend fun end(callId: String) = endResult
    }

    private class Groups : GroupRepository() {
        val changes = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        override fun sessionChanges() = changes
    }

    private class Foreground : CallForeground {
        var running = false
        var starts = 0
        override fun start() { running = true; starts++ }
        override fun stop() { running = false }
    }

    private inner class Fixture(val scope: CoroutineScope, granted: Set<String> = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) {
        val calls = Calls()
        val groups = Groups()
        val engines = mutableListOf<FakeEngine>()
        val foreground = Foreground()
        val permissions = granted.toMutableSet()
        val manager = CallSessionManager(calls, groups, { FakeEngine().also { engines += it } }, foreground,
            { it in permissions }, scope)
    }

    private suspend fun settle() = repeat(20) { yield() }

    @Test fun oneSessionPerAppAndRepeatedJoinReattaches() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Fixture(scope)
        assertTrue(f.manager.join(group, CallMode.AUDIO, "req-1"))
        assertTrue(f.manager.join(group, CallMode.AUDIO, "req-1")) // double tap / rotation: no second session
        settle()
        assertEquals(1, f.calls.starts.size)
        assertEquals(1, f.engines.size)
        val state = f.manager.state.value
        assertEquals(CallPhase.CONNECTED, state.phase)
        assertTrue(state.microphoneOn)
        assertFalse("voice starts with the camera off", state.cameraOn)
        assertTrue(f.foreground.running)
        assertEquals(connection.url to connection.token, f.engines.single().connected)
        assertFalse("token is not kept in state", state.toString().contains(connection.token))
        assertFalse(f.manager.join("00000000-0000-0000-0000-000000000202", CallMode.AUDIO, "req-2"))
        assertEquals(1, f.calls.starts.size)
        scope.cancel()
    }

    @Test fun deniedPermissionsDegradeWithoutCapture() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Fixture(scope, granted = emptySet())
        f.manager.join(group, CallMode.VIDEO, "req"); settle()
        val state = f.manager.state.value
        assertEquals(CallPhase.CONNECTED, state.phase) // still listening/viewing
        assertFalse(state.microphoneOn); assertFalse(state.cameraOn)
        assertFalse(f.engines.single().mic)
        assertFalse("no microphone foreground service without permission", f.foreground.running)
        f.manager.setMicrophone(true); settle()
        assertFalse(f.manager.state.value.microphoneOn)
        assertTrue(f.manager.state.value.controlFailed)
        f.manager.consumeControlFailure()
        // Granted later through a user action: enabled and kept alive in the background.
        f.permissions += Manifest.permission.RECORD_AUDIO
        f.manager.setMicrophone(true); settle()
        assertTrue(f.manager.state.value.microphoneOn)
        assertTrue(f.foreground.running)
        scope.cancel()
    }

    @Test fun videoCameraControlsRoutesAndBackgroundPause() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Fixture(scope)
        f.manager.join(group, CallMode.VIDEO, "req"); settle()
        val engine = f.engines.single()
        assertTrue(f.manager.state.value.cameraOn)
        f.manager.selectAudioRoute("Speaker"); settle()
        assertEquals(AudioRoute.Kind.SPEAKER, f.manager.state.value.route?.kind)
        f.manager.onAppBackground(); settle()
        assertFalse(engine.camera)
        assertTrue(f.manager.state.value.cameraPaused)
        assertTrue("microphone continues in the background", engine.mic)
        f.manager.onAppForeground(); settle()
        assertTrue(engine.camera)
        // Camera permission revoked while in the background: not re-enabled.
        f.manager.onAppBackground(); settle()
        f.permissions -= Manifest.permission.CAMERA
        f.manager.onAppForeground(); settle()
        assertFalse(engine.camera); assertFalse(f.manager.state.value.cameraOn)
        // A failing control is reported, never shown as success.
        f.permissions += Manifest.permission.CAMERA
        engine.cameraWorks = false
        f.manager.setCamera(true); settle()
        assertFalse(f.manager.state.value.cameraOn)
        assertTrue(f.manager.state.value.controlFailed)
        scope.cancel()
    }

    @Test fun rotationIsNotBackgroundButRealBackgroundPausesCamera() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Fixture(scope)
        f.manager.join(group, CallMode.VIDEO, "req"); settle()
        val engine = f.engines.single()
        val tracker = VisibilityTracker(f.manager::onAppForeground, f.manager::onAppBackground)
        tracker.started()
        // Rotation: the activity stops (changing configurations) and a new one starts.
        tracker.stopped(changingConfigurations = true); tracker.started(); settle()
        assertTrue(engine.camera); assertFalse(f.manager.state.value.cameraPaused)
        assertEquals(1, f.engines.size) // the same session, no second engine
        assertEquals(0, engine.released)
        // Two visible activities: stopping one is not background.
        tracker.started(); tracker.stopped(changingConfigurations = false); settle()
        assertTrue(engine.camera)
        // Last visible activity gone for real: camera pauses, microphone and session continue.
        tracker.stopped(changingConfigurations = false); settle()
        assertFalse(engine.camera); assertTrue(f.manager.state.value.cameraPaused)
        assertTrue(engine.mic); assertTrue(f.foreground.running)
        assertEquals(CallPhase.CONNECTED, f.manager.state.value.phase)
        tracker.started(); settle()
        assertTrue(engine.camera)
        scope.cancel()
    }

    @Test fun joinInFlightIsDroppedByLeaveOrAccountSwitchAndNeverConnectsLate() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Fixture(scope)
        // Account switch while the server round trip is still running.
        val gate = CompletableDeferred<Unit>()
        f.calls.startResult = { gate.await(); ApiResponse.Success(CallJoin(call, connection), "t") }
        f.manager.join(group, CallMode.AUDIO, "a"); settle()
        assertEquals(CallPhase.JOINING, f.manager.state.value.phase)
        f.calls.user = "other-account"; f.calls.sessionKey = "s2"
        f.groups.changes.emit("other-account"); settle()
        gate.complete(Unit); settle()
        assertTrue("late result of the old session must not build an engine", f.engines.isEmpty())
        assertEquals(CallSessionState(), f.manager.state.value)
        assertFalse(f.foreground.running)
        // Leaving while joining behaves the same: nothing connects afterwards.
        f.calls.user = "me"; f.calls.sessionKey = "s3"
        val second = CompletableDeferred<Unit>()
        f.calls.startResult = { second.await(); ApiResponse.Success(CallJoin(call, connection), "t") }
        f.manager.join(group, CallMode.AUDIO, "b"); settle()
        f.manager.leave(); settle()
        second.complete(Unit); settle()
        assertTrue(f.engines.isEmpty())
        assertEquals(CallPhase.IDLE, f.manager.state.value.phase)
        assertFalse(f.foreground.running)
        // The app can still join normally afterwards, with exactly one engine.
        f.calls.startResult = { ApiResponse.Success(CallJoin(call, connection), "t") }
        assertTrue(f.manager.join(group, CallMode.AUDIO, "c")); settle()
        assertEquals(1, f.engines.size)
        assertEquals(CallPhase.CONNECTED, f.manager.state.value.phase)
        scope.cancel()
    }

    @Test fun leaveRemovalAndAccountSwitchReleaseMedia() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Fixture(scope)
        f.manager.join(group, CallMode.AUDIO, "a"); settle()
        f.manager.leave(); settle()
        assertEquals(1, f.engines[0].released)
        assertFalse(f.foreground.running)
        assertEquals(listOf(call.id), f.calls.leaves)
        assertEquals(CallPhase.IDLE, f.manager.state.value.phase)

        f.manager.join(group, CallMode.AUDIO, "b"); settle()
        f.engines[1].flow.emit(EngineEvent.Disconnected(removed = true, byUser = false)); settle()
        assertEquals(1, f.engines[1].released)
        assertTrue(f.manager.state.value.removed)
        assertEquals(CallPhase.ENDED, f.manager.state.value.phase)
        assertFalse(f.foreground.running)
        f.manager.acknowledgeEnded()

        f.manager.join(group, CallMode.AUDIO, "c"); settle()
        f.calls.user = "other-account"; f.calls.sessionKey = "s2"
        f.groups.changes.emit("other-account"); settle()
        assertEquals(1, f.engines[2].released)
        assertEquals(CallSessionState(), f.manager.state.value) // nothing of the previous account remains
        assertFalse(f.foreground.running)
        scope.cancel()
    }

    @Test fun serverAndProviderFailuresNeverConnect() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Fixture(scope)
        for (type in listOf(ErrorType.CALL_QUOTA_EXCEEDED, ErrorType.CALL_FULL, ErrorType.GROUP_UNAVAILABLE)) {
            f.calls.startResult = { failure(type) }
            f.manager.join(group, CallMode.AUDIO, "r"); settle()
            assertEquals(CallPhase.FAILED, f.manager.state.value.phase)
            assertEquals(type, f.manager.state.value.error)
        }
        assertTrue(f.engines.isEmpty())
        assertFalse(f.foreground.running)
        // Media connect failure releases the engine and reports a retryable provider error.
        f.calls.startResult = { ApiResponse.Success(CallJoin(call, connection), "t") }
        val failing = FakeEngine().apply { connectError = java.io.IOException("ice failed") }
        val manager = CallSessionManager(f.calls, f.groups, { failing }, f.foreground, { true }, scope)
        manager.join(group, CallMode.AUDIO, "r2"); settle()
        assertEquals(CallPhase.FAILED, manager.state.value.phase)
        assertEquals(ErrorType.CALL_PROVIDER_UNAVAILABLE, manager.state.value.error)
        assertEquals(1, failing.released)
        scope.cancel()
    }

    @Test fun endForAllWaitsForServerAndReconnectStatesAreShown() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Fixture(scope)
        f.manager.join(group, CallMode.AUDIO, "a"); settle()
        val engine = f.engines.single()
        engine.flow.emit(EngineEvent.Reconnecting); settle()
        assertEquals(CallPhase.RECONNECTING, f.manager.state.value.phase)
        engine.flow.emit(EngineEvent.Reconnected); settle()
        assertEquals(CallPhase.CONNECTED, f.manager.state.value.phase)
        f.calls.endResult = failure(ErrorType.PERMISSION_DENIED)
        f.manager.endForAll(); settle()
        assertEquals(CallPhase.CONNECTED, f.manager.state.value.phase) // refused: still connected, error shown
        assertEquals(ErrorType.PERMISSION_DENIED, f.manager.state.value.error)
        f.calls.endResult = ApiResponse.Success(call.copy(status = CallStatus.ENDING, cleanupPending = true), "t")
        f.manager.endForAll(); settle()
        assertEquals(CallPhase.ENDED, f.manager.state.value.phase)
        assertEquals(1, engine.released)
        scope.cancel()
    }

    @Test fun bannerRefreshKeepsLastCallOnTransientErrorsAndChecksCapacity() = runBlocking {
        val calls = Calls()
        var result: ApiResponse<GroupCall?> = ApiResponse.Success(call.copy(myStatus = null, participantCount = 6), "t")
        val repo = object : GroupCallRepository() {
            override fun currentUserId() = calls.user
            override suspend fun current(groupId: String) = result
        }
        val banner = GroupCallBannerViewModel(repo, this)
        banner.refresh(group); banner.refresh(group) // overlapping polls are coalesced
        settle()
        val full = requireNotNull(banner.state.value.call)
        assertFalse("full call is not joinable for a new participant", GroupCallBannerViewModel.joinable(full))
        assertTrue(GroupCallBannerViewModel.joinable(full.copy(myStatus = "connected")))
        assertFalse(GroupCallBannerViewModel.joinable(full.copy(status = CallStatus.ENDING, participantCount = 1)))
        result = failure(ErrorType.NETWORK_UNAVAILABLE)
        banner.refresh(group); settle()
        assertNotNull("keeps last known call while offline", banner.state.value.call)
        assertNotNull(banner.state.value.error)
        result = failure(ErrorType.GROUP_UNAVAILABLE)
        banner.refresh(group); settle()
        assertNull(banner.state.value.call)
        result = ApiResponse.Success(null, "t")
        banner.refresh(group); settle()
        assertNull(banner.state.value.call); assertNull(banner.state.value.error)
    }
}
