package com.doan.studyhub.user.ui.group.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.doan.studyhub.user.R

/**
 * Foreground service (type microphone) for an ongoing call while the app is in the background.
 * It owns no media: the [CallSessionManager] does. The notification returns to the app or
 * leaves the call. START_NOT_STICKY: after process death nothing restarts the microphone.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = CallSessionManager.get(this)
        if (intent?.action == ACTION_LEAVE) {
            manager.leave()
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (!manager.state.value.active) { // stale start (session already gone)
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        }
        // Android may refuse (e.g. microphone permission revoked): the call continues in the foreground only.
        if (started.isFailure) stopSelfSafely()
        return START_NOT_STICKY
    }

    private fun stopSelfSafely() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.call_channel),
                NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.call_channel_desc) })
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val leave = PendingIntent.getService(this, 1, Intent(this, CallService::class.java).setAction(ACTION_LEAVE), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.logo3)
            .setContentTitle(getString(R.string.call_notification_title))
            .setContentText(getString(R.string.call_notification_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (open != null) setContentIntent(PendingIntent.getActivity(this@CallService, 0, open, flags)) }
            .addAction(0, getString(R.string.call_leave), leave)
            .build()
    }

    companion object {
        private const val CHANNEL = "group_calls"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_LEAVE = "com.doan.studyhub.user.call.LEAVE"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }
    }
}
