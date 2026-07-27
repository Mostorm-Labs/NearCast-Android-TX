package com.auditoryworks.nearcast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder

/**
 * Foreground service required for MediaProjection on Android 10+.
 * Must be started BEFORE MediaProjection.getMediaProjection() is called.
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            SessionTraceRecorder.record(TAG, "Foreground service started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start foreground service", e)
            SessionTraceRecorder.record(TAG, "Foreground service failed: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SessionTraceRecorder.record(TAG, "onStartCommand startId=$startId flags=$flags")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The casting foreground service must survive removal of the launcher task.
        Log.i(TAG, "Launcher task removed; keeping casting service alive")
        SessionTraceRecorder.record(TAG, "Launcher task removed; service kept alive")
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Casting",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen casting is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenCast")
            .setContentText("Your screen is being shared")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_cast_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            SessionTraceRecorder.record(TAG, "start() requested")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            SessionTraceRecorder.record(TAG, "stop() requested")
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
