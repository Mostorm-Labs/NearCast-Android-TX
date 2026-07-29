package com.auditoryworks.nearcast.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auditoryworks.nearcast.MainActivity
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder
import com.auditoryworks.nearcast.session.CaptureState
import com.auditoryworks.nearcast.session.CastSessionState
import com.auditoryworks.nearcast.session.ProjectionStopReason

/**
 * Foreground service required for MediaProjection on Android 10+.
 * Must be started BEFORE MediaProjection.getMediaProjection() is called.
 */
class ScreenCaptureService : Service() {

    private var currentState = CastSessionState(capture = CaptureState.STARTING)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(this) {
            running = true
        }
        createNotificationChannel()
        val notification = createNotification(currentState)
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
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to start foreground service", e)
            SessionTraceRecorder.record(TAG, "Foreground service failed: ${e.message}")
            synchronized(this) {
                running = false
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SessionTraceRecorder.record(TAG, "onStartCommand startId=$startId flags=$flags")
        intent?.toCastSessionState()?.let { state ->
            currentState = state
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                createNotification(state)
            )
            SessionTraceRecorder.record(
                TAG,
                "notification state capture=${state.capture} reason=${state.stopReason}"
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(this) {
            running = false
        }
        SessionTraceRecorder.record(TAG, "Foreground service destroyed")
        super.onDestroy()
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
                getString(R.string.screen_cast_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.screen_cast_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(state: CastSessionState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText(state))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun notificationText(state: CastSessionState): String = when (state.capture) {
        CaptureState.ACTIVE -> getString(R.string.notification_capture_active)
        CaptureState.PAUSED_HIDDEN -> getString(R.string.notification_capture_hidden)
        CaptureState.AWAITING_RESELECTION -> when (state.stopReason) {
            ProjectionStopReason.SCREEN_LOCKED ->
                getString(R.string.notification_capture_locked)
            ProjectionStopReason.PERMISSION_DENIED ->
                getString(R.string.notification_capture_permission_denied)
            ProjectionStopReason.CAPTURE_ERROR ->
                getString(R.string.notification_capture_error)
            else -> getString(R.string.notification_capture_stopped)
        }
        CaptureState.REQUESTING_PERMISSION,
        CaptureState.STARTING -> getString(R.string.notification_capture_starting)
        CaptureState.ERROR -> getString(R.string.notification_capture_error)
        else -> getString(R.string.notification_capture_stopped)
    }

    private fun Intent.toCastSessionState(): CastSessionState? {
        val captureName = getStringExtra(EXTRA_CAPTURE_STATE) ?: return null
        val capture = runCatching { CaptureState.valueOf(captureName) }.getOrNull() ?: return null
        val reason = getStringExtra(EXTRA_STOP_REASON)?.let { name ->
            runCatching { ProjectionStopReason.valueOf(name) }.getOrNull()
        }
        return CastSessionState(capture = capture, stopReason = reason)
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_cast_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_CAPTURE_STATE = "capture_state"
        private const val EXTRA_STOP_REASON = "stop_reason"

        /** True only while the service instance has reached onCreate(). */
        @Volatile
        private var running = false

        fun start(
            context: Context,
            state: CastSessionState = CastSessionState(capture = CaptureState.STARTING)
        ) {
            val intent = stateIntent(context, state)
            SessionTraceRecorder.record(TAG, "start() requested")
            try {
                // This is the only path that is allowed to promote a new service to the
                // foreground. It is called from the visible Activity immediately after consent.
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Foreground service start rejected", e)
                SessionTraceRecorder.record(TAG, "Foreground service start rejected: ${e.message}")
            }
        }

        fun update(context: Context, state: CastSessionState) {
            if (!state.shouldKeepForegroundService) return
            SessionTraceRecorder.record(
                TAG,
                "update() requested capture=${state.capture} reason=${state.stopReason}"
            )
            val intent = stateIntent(context, state)
            try {
                if (running) {
                    // The service is already foreground. Delivering a normal start command avoids
                    // Android 14's background-start restriction and does not create a new FGS.
                    context.startService(intent)
                } else {
                    // Covers a state update racing the initial start, or a service that was
                    // reclaimed by the system. The caller's active projection is a valid FGS
                    // exemption, so retry promotion as a best effort.
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (e: RuntimeException) {
                // A notification update must never tear down the WebRTC/session state.
                Log.e(TAG, "Foreground service state update rejected", e)
                SessionTraceRecorder.record(TAG, "Foreground service update rejected: ${e.message}")
            }
        }

        fun stop(context: Context) {
            SessionTraceRecorder.record(TAG, "stop() requested")
            running = false
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        private fun stateIntent(context: Context, state: CastSessionState) =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_CAPTURE_STATE, state.capture.name)
                state.stopReason?.let { putExtra(EXTRA_STOP_REASON, it.name) }
            }
    }
}
