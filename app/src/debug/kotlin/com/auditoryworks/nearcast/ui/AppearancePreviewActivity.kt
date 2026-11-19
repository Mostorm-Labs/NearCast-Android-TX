package com.auditoryworks.nearcast.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.auditoryworks.nearcast.discovery.*
import com.auditoryworks.nearcast.session.*
import com.auditoryworks.nearcast.touchback.*
import com.auditoryworks.nearcast.permissions.*
import com.auditoryworks.nearcast.ui.screens.*
import com.auditoryworks.nearcast.ui.theme.ScreenCastTheme

/** Visual fixture only: never connects, saves trust, starts capture or acquires system input. */
class AppearancePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent.getStringExtra("state") ?: "active"
        val firstScreen = intent.getStringExtra("screen") ?: "session"
        val authorized = intent.getBooleanExtra("permission", true)
        val devices = listOf(
            ReceiverDevice("meeting", "fixture-board", "NearHub · Meeting room", listOf("192.0.2.4"), 8443, "", "", setOf("webcast", "code-display-v1", "trust-display-v1")),
            ReceiverDevice("office", "fixture-office", "Office display", listOf("192.0.2.5"), 8443, "", "", setOf("webcast")))
        setContent {
            ScreenCastTheme {
                var screen by remember { mutableStateOf(firstScreen) }
                var previous by remember { mutableStateOf(if (firstScreen == "session") "session" else "home") }
                var code by remember { mutableStateOf("") }
                var localName by remember { mutableStateOf<String?>(null) }
                var tab by remember { mutableStateOf(if (firstScreen == "devices") ConnectionTab.DEVICES else ConnectionTab.CODE) }
                var permissionDialog by remember { mutableStateOf<PermissionDialog?>(null) }
                var startupGuide by remember { mutableStateOf(firstScreen == "startup") }
                var startupSnapshot by remember { mutableStateOf(StartupPermissionSnapshot()) }
                var capture by remember { mutableStateOf(when (requested) {
                    "ready" -> CaptureState.IDLE
                    "reselect" -> CaptureState.AWAITING_RESELECTION
                    "hidden" -> CaptureState.PAUSED_HIDDEN
                    else -> CaptureState.ACTIVE
                }) }
                var remote by remember { mutableStateOf(RemoteControlState(
                    permissionEnabled = authorized, serviceConnected = authorized,
                    channelReady = true, appReady = true,
                    sourceReady = capture == CaptureState.ACTIVE && authorized,
                    calibration = if (capture == CaptureState.ACTIVE && authorized) CalibrationState.VERIFIED else CalibrationState.IDLE,
                    inputState = if (capture == CaptureState.ACTIVE && authorized) "active" else "closed")) }
                val openSettings = { previous = screen; screen = "settings" }
                when (screen) {
                    "home", "devices", "startup" -> HomeScreen(code, "", tab, { tab = it }, DiscoverySnapshot(devices, true),
                        onSelectReceiver = { localName = it.name; tab = ConnectionTab.CODE }, onRefresh = {}, onNetworkSettings = {},
                        isUpdateDownloadInProgress = false, isDownloadProgressVisible = false,
                        onPairCodeChange = { code = it }, onJoin = { screen = "session"; capture = CaptureState.IDLE },
                        onOpenSettings = openSettings, onShowDownloadProgress = {}, localReceiverName = localName,
                        onUseCloud = { localName = null; code = "" })
                    "settings" -> SettingsScreen(remote, { remote = remote.copy(enabled = it) },
                        { permissionDialog = PermissionDialog.REMOTE_EXPLANATION }, { permissionDialog = PermissionDialog.REMOTE_HELP }, {},
                        false, {}, false, null, "", {}, false, false, {}, { screen = previous }, onPermissions = { startupGuide = true })
                    "trust", "device-options" -> {
                        HomeScreen("", "", ConnectionTab.DEVICES, {}, DiscoverySnapshot(devices, true), {}, {}, {}, false, false, {}, {}, {}, {})
                        val pin = "A1B2C3D4".repeat(8)
                        ReceiverConnectionSheet(ReceiverActionState(ReceiverPhase.SHOWN, devices[0], fingerprint = pin),
                            onRetry = {}, onCode = {}, onForget = {}, onLocalEntry = { screen = "home" },
                            onManualEntry = { screen = "home" }, onDismiss = { screen = "home" }, manageDevice = true)
                    }
                    else -> SessionScreen("", CastSessionState(
                        transport = if (requested == "disconnected") TransportState.DISCONNECTED else TransportState.CONNECTED,
                        capture = capture, stopReason = if (capture == CaptureState.AWAITING_RESELECTION) ProjectionStopReason.SCREEN_LOCKED else null),
                        isUpdateDownloadInProgress = false, isDownloadProgressVisible = false,
                        onStartCast = { capture = CaptureState.ACTIVE },
                        onStopCast = { capture = CaptureState.IDLE; remote = remote.copy(sourceReady = false, inputState = "closed") },
                        onLeave = { screen = "home" }, onOpenSettings = openSettings, onShowDownloadProgress = {},
                        remoteControlState = remote, onRemoteSettings = { permissionDialog = PermissionDialog.REMOTE_EXPLANATION }, onRemoteRetry = {})
                }
                if (startupGuide && permissionDialog == null) StartupPermissionDialog(
                    StartupPermissionState(checked = true, showing = true), startupSnapshot, remote, false,
                    onAudio = { startupSnapshot = startupSnapshot.copy(audioGranted = true) },
                    onNotifications = { startupSnapshot = startupSnapshot.copy(notificationsGranted = true) },
                    onRemote = { permissionDialog = PermissionDialog.REMOTE_EXPLANATION },
                    onContinue = { startupGuide = false }, onDefer = { startupGuide = false })
                permissionDialog?.let { kind -> CapturePermissionDialog(kind, { permissionDialog = null },
                    { permissionDialog = null }, { permissionDialog = null }, { permissionDialog = null }, { permissionDialog = null }) }
            }
        }
    }
}
