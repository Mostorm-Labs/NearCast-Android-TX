package com.example.screencast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.screencast.discovery.DiscoveredService
import com.example.screencast.discovery.NsdDiscoveryManager
import com.example.screencast.service.ScreenCaptureService
import com.example.screencast.ui.screens.HomeScreen
import com.example.screencast.ui.screens.SessionScreen
import com.example.screencast.ui.theme.ScreenCastTheme
import com.example.screencast.webrtc.LocalDirectSignalingClient
import com.example.screencast.webrtc.NearHubEvent
import com.example.screencast.webrtc.NearHubSignalingClient
import com.example.screencast.webrtc.SignalingClient
import com.example.screencast.webrtc.WebRtcManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

enum class AppScreen { HOME, SESSION }

private const val TAG = "MainActivity"
private const val WEBRTC_SIGNAL_SERVICE_TYPE = "_webrtc-signal._tcp."

class MainActivity : ComponentActivity() {

    private var webRtcManager: WebRtcManager? = null
    private var currentScreen by mutableStateOf(AppScreen.HOME)
    private var statusText by mutableStateOf("Ready")
    private var serverUrl by mutableStateOf("wss://cast.nearhub.us/")
    private var pairCode by mutableStateOf("")
    private var isCasting by mutableStateOf(false)
    private var isP2PReady by mutableStateOf(false)
    private var lastAudioModeStatus by mutableStateOf("")

    private var nsdDiscoveryManager: NsdDiscoveryManager? = null
    private var discoveredDevices by mutableStateOf<List<DiscoveredService>>(emptyList())
    private var isDiscovering by mutableStateOf(false)
    private var showDeviceSheet by mutableStateOf(false)
    private var showPairCodeDialog by mutableStateOf(false)
    private var pendingSelectedService by mutableStateOf<DiscoveredService?>(null)
    private var selectedService: DiscoveredService? = null

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
                        serverUrl = serverUrl,
                        pairCode = pairCode,
                        statusText = statusText,
                        onServerUrlChange = { serverUrl = it; selectedService = null },
                        onPairCodeChange = { pairCode = it; selectedService = null },
                        onJoin = { joinRoom() },
                        isDiscovering = isDiscovering,
                        discoveredDevices = discoveredDevices,
                        onScanDevices = { startDeviceDiscovery() },
                        onDeviceSelected = { device -> onDeviceSelected(device) },
                        onDismissDeviceSheet = {
                            showDeviceSheet = false
                            stopDeviceDiscovery()
                        },
                        showDeviceSheet = showDeviceSheet,
                        showPairCodeDialog = showPairCodeDialog,
                        pendingDeviceName = pendingSelectedService?.name ?: "",
                        onPairCodeConfirm = { code ->
                            selectedService = pendingSelectedService
                            pairCode = code
                            showPairCodeDialog = false
                            joinRoom()
                        },
                        onPairCodeDismiss = {
                            showPairCodeDialog = false
                            pendingSelectedService = null
                        }
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

    private var discoveryJob: kotlinx.coroutines.Job? = null
    private var discoveryTimeoutJob: kotlinx.coroutines.Job? = null

    private fun startDeviceDiscovery() {
        if (nsdDiscoveryManager == null) {
            nsdDiscoveryManager = NsdDiscoveryManager(this)
        }
        showDeviceSheet = true
        isDiscovering = true
        discoveredDevices = emptyList()

        discoveryJob?.cancel()
        discoveryTimeoutJob?.cancel()
        discoveryJob = MainScope().launch {
            try {
                // Discovery is WebRTC-only: only scan local signaling service type.
                nsdDiscoveryManager?.discoverServices(WEBRTC_SIGNAL_SERVICE_TYPE)
                    ?.collect { devices ->
                        discoveredDevices = devices
                        // Stop after finding at least one device
                        if (devices.isNotEmpty()) {
                            isDiscovering = false
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            }
        }

        // Timeout after 15 seconds
        discoveryTimeoutJob = MainScope().launch {
            kotlinx.coroutines.delay(15000)
            stopDeviceDiscovery()
        }
    }

    private fun stopDeviceDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        isDiscovering = false
    }

    private fun onDeviceSelected(service: DiscoveredService) {
        stopDeviceDiscovery()
        if (!isWebRtcSignalingService(service)) {
            statusText = "Selected service is not WebRTC signaling"
            showDeviceSheet = false
            return
        }
        pendingSelectedService = service
        showPairCodeDialog = true
        showDeviceSheet = false
    }

    private fun joinRoom() {
        val effectivePairCode = pairCode
        val useLocalDirectMode = selectedService != null
        val effectiveServerUrl = if (useLocalDirectMode) {
            val service = selectedService!!
            buildLocalWsUrl(service.host, service.port)
        } else {
            serverUrl
        }

        if (effectivePairCode.isBlank()) {
            statusText = "Please enter a pair code"
            return
        }

        if (useLocalDirectMode && !isWebRtcSignalingService(selectedService)) {
            statusText = "Local direct mode requires _webrtc-signal._tcp. service"
            return
        }

        if (effectiveServerUrl.isBlank()) {
            statusText = if (useLocalDirectMode) {
                "Selected device has invalid host/port"
            } else {
                "Please enter a valid server URL"
            }
            return
        }

        statusText = if (useLocalDirectMode) {
            "Connecting to local device..."
        } else {
            "Connecting..."
        }

        val signalingClient: SignalingClient = if (useLocalDirectMode) {
            LocalDirectSignalingClient(
                wsUrl = effectiveServerUrl,
                onStatusChange = { status -> runOnUiThread { statusText = status } }
            )
        } else {
            NearHubSignalingClient(
                baseUrl = effectiveServerUrl,
                onStatusChange = { status -> runOnUiThread { statusText = status } }
            )
        }

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

    private fun buildLocalWsUrl(host: String, port: Int): String {
        if (host.isBlank() || port <= 0) return ""
        return "ws://$host:$port"
    }

    private fun isWebRtcSignalingService(service: DiscoveredService?): Boolean {
        if (service == null) return false
        val normalized = service.serviceType.trim().trimEnd('.').lowercase()
        val target = WEBRTC_SIGNAL_SERVICE_TYPE.trim().trimEnd('.').lowercase()
        return normalized == target
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
        selectedService = null
    }

    override fun onDestroy() {
        webRtcManager?.stop()
        webRtcManager = null
        ScreenCaptureService.stop(this)
        super.onDestroy()
    }
}
