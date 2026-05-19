package com.example.screencast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.screencast.service.ScreenCaptureService
import com.example.screencast.ui.screens.HomeScreen
import com.example.screencast.ui.screens.SessionScreen
import com.example.screencast.ui.theme.ScreenCastTheme
import com.example.screencast.webrtc.NearHubEvent
import com.example.screencast.webrtc.NearHubSignalingClient
import com.example.screencast.webrtc.SignalingClient
import com.example.screencast.webrtc.WebRtcManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

enum class AppScreen { HOME, SESSION }

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private var webRtcManager: WebRtcManager? = null
    private var currentScreen by mutableStateOf(AppScreen.HOME)
    private var statusText by mutableStateOf("Ready")
    private val serverUrl = "https://cast.nearhub.us/"
    private var pairCode by mutableStateOf("")
    private var isCasting by mutableStateOf(false)
    private var isP2PReady by mutableStateOf(false)
    private var lastAudioModeStatus by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ScreenCastTheme {
                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        val resultCode = result.resultCode
                        val data = result.data!!
                        ScreenCaptureService.start(this@MainActivity)
                        statusText = "Starting screen capture..."
                        // Delay to ensure foreground service is fully started
                        // (required on Android 10+ for MediaProjection)
                        window.decorView.postDelayed({
                            try {
                                webRtcManager?.startScreenCapture(resultCode, data)
                                isCasting = true
                            } catch (e: Exception) {
                                statusText = "Screen capture failed: ${e.message}"
                                ScreenCaptureService.stop(this@MainActivity)
                            }
                        }, 800)
                    } else {
                        statusText = "Screen capture permission denied"
                    }
                }
                val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        launchScreenCapture(mediaProjectionLauncher)
                    } else {
                        statusText = "Microphone permission denied, cannot cast audio"
                    }
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    // Notification permission is best-effort.
                    // Continue cast flow regardless of user choice to avoid blocking start.
                    requestAudioPermissionAndStartCast(recordAudioPermissionLauncher, mediaProjectionLauncher)
                }

                when (currentScreen) {
                    AppScreen.HOME -> HomeScreen(
                        pairCode = pairCode,
                        statusText = statusText,
                        onPairCodeChange = { pairCode = it },
                        onJoin = { joinRoom() }
                    )

                    AppScreen.SESSION -> SessionScreen(
                        statusText = statusText,
                        isCasting = isCasting,
                        isP2PReady = isP2PReady,
                        onStartCast = {
                            requestNotificationPermissionAndStartCast(
                                notificationPermissionLauncher,
                                recordAudioPermissionLauncher,
                                mediaProjectionLauncher
                            )
                        },
                        onStopCast = {
                            webRtcManager?.stopScreenCapture()
                            ScreenCaptureService.stop(this@MainActivity)
                            isCasting = false
                        },
                        onLeave = { leaveRoom() }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionAndStartCast(
        notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
        audioPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
        mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestAudioPermissionAndStartCast(audioPermissionLauncher, mediaProjectionLauncher)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestAudioPermissionAndStartCast(audioPermissionLauncher, mediaProjectionLauncher)
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestAudioPermissionAndStartCast(
        audioPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
        mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            launchScreenCapture(mediaProjectionLauncher)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchScreenCapture(mediaProjectionLauncher)
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchScreenCapture(
        mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun joinRoom() {
        val effectivePairCode = pairCode

        if (effectivePairCode.isBlank()) {
            statusText = "Please enter a pair code"
            return
        }

        statusText = "Connecting..."

        val signalingClient: SignalingClient = NearHubSignalingClient(
            baseUrl = serverUrl,
            onStatusChange = { status -> runOnUiThread { statusText = status } }
        )

        webRtcManager = WebRtcManager(
            context = applicationContext,
            signalingClient = signalingClient,
            onStatusChange = { status ->
                runOnUiThread {
                    if (status.startsWith("Audio mode:")) {
                        lastAudioModeStatus = status
                    }
                    statusText = when {
                        status == "Casting" && lastAudioModeStatus.isNotBlank() ->
                            "Casting\n$lastAudioModeStatus"
                        status.startsWith("Screen capture started") && lastAudioModeStatus.isNotBlank() ->
                            "$status\n$lastAudioModeStatus"
                        else -> status
                    }
                    if (
                        status.contains("P2P connected") ||
                        status.contains("Joined room") ||
                        status == "Offer sent" ||
                        status == "Casting"
                    ) {
                        isP2PReady = true
                    }
                    if (status.startsWith("P2P connected")) {
                        currentScreen = AppScreen.SESSION
                    }
                    if (
                        status.contains("connection failed", ignoreCase = true) ||
                        status.contains("disconnected", ignoreCase = true) ||
                        status.contains("removed from room", ignoreCase = true) ||
                        status.contains("room closed", ignoreCase = true)
                    ) {
                        isP2PReady = false
                    }
                }
            }
        )

        signalingClient.connect()

        MainScope().launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is NearHubEvent.Connected -> {
                        signalingClient.join(effectivePairCode, "Android-${Build.MODEL}")
                    }
                    is NearHubEvent.Joined -> {
                        isP2PReady = true
                        currentScreen = AppScreen.SESSION
                    }
                    is NearHubEvent.JoinFailed -> {
                        statusText = "Join failed: ${event.message}"
                    }
                    is NearHubEvent.PeerLeave,
                    is NearHubEvent.RoomClosed -> {
                        leaveRoom()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun leaveRoom() {
        webRtcManager?.stop()
        webRtcManager = null
        ScreenCaptureService.stop(this)
        isCasting = false
        isP2PReady = false
        lastAudioModeStatus = ""
        currentScreen = AppScreen.HOME
        statusText = "Ready"
    }

    override fun onDestroy() {
        webRtcManager?.stop()
        webRtcManager = null
        ScreenCaptureService.stop(this)
        super.onDestroy()
    }
}
