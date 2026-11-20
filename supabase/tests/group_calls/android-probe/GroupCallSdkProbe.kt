package com.doan.studyhub.user.ui.group.spike

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.room.Room

/** Compile contract only. No app entrypoint invokes capture or connects to a room. */
internal object GroupCallSdkProbe {
    fun create(context: Context): Room = LiveKit.create(context.applicationContext)
    suspend fun disableCapture(room: Room) {
        room.localParticipant.setMicrophoneEnabled(false)
        room.localParticipant.setCameraEnabled(false)
    }
    fun release(room: Room) {
        room.disconnect()
        room.release()
    }
}
