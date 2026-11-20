package com.doan.studyhub.user.ui.group.call

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

data class CallParticipant(
    val identity: String,
    val name: String,
    val isLocal: Boolean,
    val microphoneOn: Boolean,
    val cameraOn: Boolean,
    val speaking: Boolean
)

data class AudioRoute(val id: String, val name: String, val kind: Kind) {
    enum class Kind { SPEAKER, EARPIECE, WIRED, BLUETOOTH, OTHER }
}

sealed interface EngineEvent {
    data object Connected : EngineEvent
    data object Reconnecting : EngineEvent
    data object Reconnected : EngineEvent
    /** [removed]: the server revoked access or closed the room; never reconnect automatically. */
    data class Disconnected(val removed: Boolean, val byUser: Boolean) : EngineEvent
    data object ParticipantsChanged : EngineEvent
}

/** Media transport of one call. One instance per connection; [release] frees capture and tracks. */
interface CallEngine {
    val events: Flow<EngineEvent>
    suspend fun connect(url: String, token: String)
    /** Returns the resulting state; false when capture could not start. */
    suspend fun setMicrophone(enabled: Boolean): Boolean
    suspend fun setCamera(enabled: Boolean): Boolean
    fun switchCamera(): Boolean
    fun participants(): List<CallParticipant>
    fun audioRoutes(): List<AudioRoute>
    fun selectedAudioRoute(): AudioRoute?
    fun selectAudioRoute(id: String): Boolean
    /** Renders a participant's camera into [renderer]; false when there is no video to show. */
    fun attachVideo(identity: String, renderer: TextureViewRenderer): Boolean
    fun detachVideo(identity: String, renderer: TextureViewRenderer)
    fun initRenderer(renderer: TextureViewRenderer)
    fun release()
}

/** LiveKit Android 2.30.0 implementation. Video presets stay modest (≤ 360p capture) for demo quotas. */
class LiveKitCallEngine(context: Context) : CallEngine {
    private val room: Room = LiveKit.create(
        context.applicationContext,
        RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = VideoPreset169.H360.capture)
        )
    )
    private var released = false

    override val events: Flow<EngineEvent> = room.events.events.mapNotNull { event ->
        when (event) {
            is RoomEvent.Connected -> EngineEvent.Connected
            is RoomEvent.Reconnecting -> EngineEvent.Reconnecting
            is RoomEvent.Reconnected -> EngineEvent.Reconnected
            is RoomEvent.Disconnected -> EngineEvent.Disconnected(
                removed = event.reason in setOf(DisconnectReason.PARTICIPANT_REMOVED, DisconnectReason.ROOM_DELETED,
                    DisconnectReason.ROOM_CLOSED, DisconnectReason.DUPLICATE_IDENTITY, DisconnectReason.JOIN_FAILURE),
                byUser = event.reason == DisconnectReason.CLIENT_INITIATED)
            is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected, is RoomEvent.TrackSubscribed,
            is RoomEvent.TrackUnsubscribed, is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted, is RoomEvent.TrackPublished,
            is RoomEvent.TrackUnpublished, is RoomEvent.ActiveSpeakersChanged -> EngineEvent.ParticipantsChanged
            else -> null
        }
    }

    override suspend fun connect(url: String, token: String) { room.connect(url, token) }

    override suspend fun setMicrophone(enabled: Boolean): Boolean =
        runCatching { room.localParticipant.setMicrophoneEnabled(enabled) }.getOrDefault(false) &&
            room.localParticipant.isMicrophoneEnabled == enabled

    override suspend fun setCamera(enabled: Boolean): Boolean =
        runCatching { room.localParticipant.setCameraEnabled(enabled) }.getOrDefault(false) &&
            room.localParticipant.isCameraEnabled == enabled

    override fun switchCamera(): Boolean {
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return false
        val next = if (track.options.position == CameraPosition.BACK) CameraPosition.FRONT else CameraPosition.BACK
        return runCatching { track.switchCamera(position = next) }.isSuccess
    }

    private fun Participant.toCall(local: Boolean) = CallParticipant(
        identity = identity?.value.orEmpty(),
        name = name?.takeIf { it.isNotBlank() } ?: "",
        isLocal = local,
        microphoneOn = isMicrophoneEnabled,
        cameraOn = isCameraEnabled,
        speaking = isSpeaking
    )

    override fun participants(): List<CallParticipant> =
        listOf(room.localParticipant.toCall(true)) + room.remoteParticipants.values.map { it.toCall(false) }

    private val audio get() = room.audioHandler as? AudioSwitchHandler

    private fun route(device: com.twilio.audioswitch.AudioDevice) = AudioRoute(device.name, device.name, when (device) {
        is com.twilio.audioswitch.AudioDevice.Speakerphone -> AudioRoute.Kind.SPEAKER
        is com.twilio.audioswitch.AudioDevice.Earpiece -> AudioRoute.Kind.EARPIECE
        is com.twilio.audioswitch.AudioDevice.WiredHeadset -> AudioRoute.Kind.WIRED
        is com.twilio.audioswitch.AudioDevice.BluetoothHeadset -> AudioRoute.Kind.BLUETOOTH
        else -> AudioRoute.Kind.OTHER
    })

    override fun audioRoutes(): List<AudioRoute> = audio?.availableAudioDevices.orEmpty().map(::route)
    override fun selectedAudioRoute(): AudioRoute? = audio?.selectedAudioDevice?.let(::route)
    override fun selectAudioRoute(id: String): Boolean {
        val handler = audio ?: return false
        val device = handler.availableAudioDevices.firstOrNull { it.name == id } ?: return false
        handler.selectDevice(device)
        return true
    }

    private fun videoOf(identity: String): VideoTrack? {
        val participant: Participant = if (room.localParticipant.identity?.value == identity) room.localParticipant
            else room.remoteParticipants.values.firstOrNull { it.identity?.value == identity } ?: return null
        val publication = participant.getTrackPublication(Track.Source.CAMERA) ?: return null
        if (publication.muted) return null
        return publication.track as? VideoTrack
    }

    override fun initRenderer(renderer: TextureViewRenderer) = room.initVideoRenderer(renderer)
    override fun attachVideo(identity: String, renderer: TextureViewRenderer): Boolean {
        val track = videoOf(identity) ?: return false
        track.addRenderer(renderer)
        return true
    }
    override fun detachVideo(identity: String, renderer: TextureViewRenderer) { videoOf(identity)?.removeRenderer(renderer) }

    override fun release() {
        if (released) return
        released = true
        room.disconnect()
        room.release()
    }
}
