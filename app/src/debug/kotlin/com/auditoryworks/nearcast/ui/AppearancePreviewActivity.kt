package com.auditoryworks.nearcast.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.auditoryworks.nearcast.session.*
import com.auditoryworks.nearcast.touchback.CalibrationState
import com.auditoryworks.nearcast.touchback.RemoteControlState
import com.auditoryworks.nearcast.ui.screens.SessionScreen
import com.auditoryworks.nearcast.ui.theme.ScreenCastTheme

/** Visual fixture only: renders production UI without connecting or acquiring capture/input. */
class AppearancePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent.getStringExtra("state") ?: "active"
        setContent {
            ScreenCastTheme {
                var capture by remember { mutableStateOf(when (requested) {
                    "ready" -> CaptureState.IDLE
                    "reselect" -> CaptureState.AWAITING_RESELECTION
                    "hidden" -> CaptureState.PAUSED_HIDDEN
                    else -> CaptureState.ACTIVE
                }) }
                var remote by remember { mutableStateOf(RemoteControlState(
                    permissionEnabled = true, serviceConnected = true,
                    channelReady = true, appReady = true,
                    sourceReady = capture == CaptureState.ACTIVE,
                    calibration = if (capture == CaptureState.ACTIVE) CalibrationState.VERIFIED else CalibrationState.IDLE,
                    inputState = if (capture == CaptureState.ACTIVE) "active" else "closed")) }
                SessionScreen(statusText = "", sessionState = CastSessionState(
                    transport = if (requested == "disconnected") TransportState.DISCONNECTED else TransportState.CONNECTED,
                    capture = capture, stopReason = if (capture == CaptureState.AWAITING_RESELECTION) ProjectionStopReason.SCREEN_LOCKED else null),
                    isLogUploadInProgress = false, isUpdateDownloadInProgress = false,
                    isDownloadProgressVisible = false,
                    onStartCast = { capture = CaptureState.ACTIVE },
                    onStopCast = { capture = CaptureState.IDLE; remote = remote.copy(sourceReady = false, inputState = "closed") },
                    onLeave = { finish() }, onUploadLogs = {}, onShowDownloadProgress = {},
                    remoteControlState = remote, onRemoteEnabled = { remote = remote.copy(enabled = it) },
                    onRemoteSettings = {}, onRemoteHelp = {}, onRemoteRetry = {})
            }
        }
    }
}
