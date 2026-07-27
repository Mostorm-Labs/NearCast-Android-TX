package com.auditoryworks.nearcast

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.auditoryworks.nearcast.diagnostics.LogUploadManager
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder
import com.auditoryworks.nearcast.service.ScreenCaptureService
import com.auditoryworks.nearcast.ui.screens.HomeScreen
import com.auditoryworks.nearcast.ui.screens.LogUploadDialog
import com.auditoryworks.nearcast.ui.screens.SessionScreen
import com.auditoryworks.nearcast.ui.theme.ScreenCastTheme
import com.auditoryworks.nearcast.updates.AppUpdateInfo
import com.auditoryworks.nearcast.updates.UpdateManager
import com.auditoryworks.nearcast.webrtc.NearHubEvent
import com.auditoryworks.nearcast.webrtc.NearHubSignalingClient
import com.auditoryworks.nearcast.webrtc.SignalingClient
import com.auditoryworks.nearcast.webrtc.WebRtcManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

enum class AppScreen { HOME, SESSION }

/** Keeps the active WebRTC session alive while the UI task is removed or recreated. */
private object ActiveCastingSession {
    var manager: WebRtcManager? = null
}

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
    private var showLogUploadDialog by mutableStateOf(false)
    private var isLogUploadInProgress by mutableStateOf(false)

    private var updateInfo by mutableStateOf<AppUpdateInfo?>(null)
    private var isDownloadingUpdate by mutableStateOf(false)
    private var isDownloadProgressVisible by mutableStateOf(false)
    private var downloadProgress by mutableStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionTraceRecorder.record(TAG, "Activity created")

        // A foreground service keeps the process alive after the task is swiped away. Restore the
        // in-process manager when the launcher UI is opened again.
        webRtcManager = ActiveCastingSession.manager
        if (webRtcManager?.isCasting == true) {
            currentScreen = AppScreen.SESSION
            isCasting = true
            isP2PReady = webRtcManager?.isDataChannelOpen == true
            statusText = "Casting"
        }

        lifecycleScope.launch {
            updateInfo = UpdateManager.checkUpdate()
        }

        setContent {
            ScreenCastTheme {
                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        val data = result.data!!
                        statusText = "Starting screen capture..."
                        // 1. Start foreground service immediately after permission granted.
                        // On Android 14+, the foreground service MUST be started before MediaProjection
                        // is created from the result data.
                        SessionTraceRecorder.record(TAG, "Starting screen capture service")
                        ScreenCaptureService.start(this@MainActivity)

                        // 2. Short delay to ensure the system has processed the foreground service start
                        // and it is in "foreground" state before we attempt to use the MediaProjection.
                        window.decorView.postDelayed({
                            try {
                                SessionTraceRecorder.record(TAG, "Starting WebRTC screen capture")
                                webRtcManager?.startScreenCapture(data)
                                isCasting = true
                                SessionTraceRecorder.record(TAG, "Screen capture started")
                            } catch (e: Exception) {
                                val readableError = e.readableMessage()
                                statusText = "Screen capture failed: $readableError"
                                ScreenCaptureService.stop(this@MainActivity)
                                SessionTraceRecorder.record(TAG, "Screen capture failed: $readableError")
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
                        statusText = "Audio capture permission denied, cannot cast playback audio"
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
                        appVersionName = BuildConfig.VERSION_NAME,
                        isLogUploadInProgress = isLogUploadInProgress,
                        isUpdateDownloadInProgress = isDownloadingUpdate,
                        isDownloadProgressVisible = isDownloadProgressVisible,
                        onPairCodeChange = { pairCode = it },
                        onJoin = { joinRoom() },
                        onUploadLogs = {
                            SessionTraceRecorder.record(TAG, "Open log upload dialog from Home")
                            showLogUploadDialog = true
                        },
                        onShowDownloadProgress = { isDownloadProgressVisible = true }
                    )

                    AppScreen.SESSION -> SessionScreen(
                        statusText = statusText,
                        isCasting = isCasting,
                        isP2PReady = isP2PReady,
                        isLogUploadInProgress = isLogUploadInProgress,
                        isUpdateDownloadInProgress = isDownloadingUpdate,
                        isDownloadProgressVisible = isDownloadProgressVisible,
                        onStartCast = {
                            requestNotificationPermissionAndStartCast(
                                notificationPermissionLauncher,
                                recordAudioPermissionLauncher,
                                mediaProjectionLauncher
                            )
                        },
                        onStopCast = {
                            SessionTraceRecorder.record(TAG, "Stop cast requested from UI")
                            webRtcManager?.stopScreenCapture()
                            ScreenCaptureService.stop(this@MainActivity)
                            isCasting = false
                        },
                        onLeave = { leaveRoom() },
                        onUploadLogs = {
                            SessionTraceRecorder.record(TAG, "Open log upload dialog from Session")
                            showLogUploadDialog = true
                        },
                        onShowDownloadProgress = { isDownloadProgressVisible = true }
                    )
                }

                if (showLogUploadDialog) {
                    LogUploadDialog(
                        isUploading = isLogUploadInProgress,
                        onDismiss = {
                            if (!isLogUploadInProgress) {
                                showLogUploadDialog = false
                            }
                        },
                        onUpload = { email, description ->
                            SessionTraceRecorder.record(
                                TAG,
                                "Upload logs requested email=${email.ifBlank { "<blank>" }} description=${description.take(80)}"
                            )
                            uploadLogs(email, description)
                        }
                    )
                }

                updateInfo?.let { info ->
                    UpdateDialog(
                        updateInfo = info,
                        onDismiss = { updateInfo = null },
                        onUpdate = {
                            updateInfo = null
                            isDownloadingUpdate = true
                            isDownloadProgressVisible = true
                            downloadProgress = 0f
                            lifecycleScope.launch {
                                try {
                                    UpdateManager.downloadAndInstall(this@MainActivity, info) { progress ->
                                        downloadProgress = progress
                                    }
                                } catch (e: Exception) {
                                    statusText = "Update failed: ${e.message}"
                                } finally {
                                    isDownloadingUpdate = false
                                    isDownloadProgressVisible = false
                                }
                            }
                        }
                    )
                }

                if (isDownloadingUpdate && isDownloadProgressVisible) {
                    DownloadProgressDialog(
                        progress = downloadProgress,
                        onHide = { isDownloadProgressVisible = false }
                    )
                }
            }
        }
    }

    @Composable
    private fun UpdateDialog(
        updateInfo: AppUpdateInfo,
        onDismiss: () -> Unit,
        onUpdate: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New Update Available: ${updateInfo.version}") },
            text = {
                Column {
                    if (updateInfo.changelog?.isNotBlank() == true) {
                        Text(updateInfo.changelog)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text("Would you like to download and install the new version?")
                }
            },
            confirmButton = {
                Button(onClick = onUpdate) {
                    Text("Update Now")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Later")
                }
            }
        )
    }

    @Composable
    private fun DownloadProgressDialog(progress: Float, onHide: () -> Unit) {
        AlertDialog(
            onDismissRequest = onHide,
            title = { Text("Downloading Update...") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${(progress * 100).toInt()}%")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("The download continues in the background.")
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onHide) {
                    Text("Hide")
                }
            }
        )
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

    private fun uploadLogs(email: String, description: String) {
        if (isLogUploadInProgress) return

        isLogUploadInProgress = true
        statusText = "Uploading logs..."

        lifecycleScope.launch {
            try {
                val result = LogUploadManager.upload(
                    context = applicationContext,
                    email = email,
                    description = description
                )
                statusText = buildString {
                    append("Logs uploaded")
                    append("\nuploadId: ").append(result.uploadId)
                    if (!result.feedbackId.isNullOrBlank()) {
                        append("\nfeedbackId: ").append(result.feedbackId)
                    }
                    append("\nfiles: ").append(result.fileCount)
                    append(", bytes: ").append(result.totalBytes)
                }
                SessionTraceRecorder.record(
                    TAG,
                    "Log upload succeeded uploadId=${result.uploadId} feedbackId=${result.feedbackId ?: "<none>"} files=${result.fileCount}"
                )
            } catch (e: Exception) {
                statusText = "Log upload failed: ${e.readableMessage()}"
                SessionTraceRecorder.record(TAG, "Log upload failed: ${e.readableMessage()}")
            } finally {
                isLogUploadInProgress = false
                showLogUploadDialog = false
            }
        }
    }

    private fun Throwable.readableMessage(): String {
        val messages = generateSequence(this) { it.cause }
            .mapNotNull { throwable ->
                throwable.message
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .toList()

        val message = if (messages.isEmpty()) {
            javaClass.simpleName
        } else {
            messages.joinToString(" -> ")
        }

        return if (message.length > 500) {
            message.substring(0, 500) + "..."
        } else {
            message
        }
    }

    private fun joinRoom() {
        val effectivePairCode = pairCode

        if (effectivePairCode.isBlank()) {
            statusText = "Please enter a pair code"
            return
        }

        SessionTraceRecorder.record(TAG, "Join requested pairCode=$effectivePairCode")
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
        ActiveCastingSession.manager = webRtcManager

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
        SessionTraceRecorder.record(TAG, "Leave room requested")
        webRtcManager?.stop()
        webRtcManager = null
        ActiveCastingSession.manager = null
        ScreenCaptureService.stop(this)
        isCasting = false
        isP2PReady = false
        lastAudioModeStatus = ""
        currentScreen = AppScreen.HOME
        statusText = "Ready"
    }

    override fun onDestroy() {
        if (isCasting || webRtcManager?.isCasting == true) {
            // Do not tear down an active cast when Android removes/recreates the UI task. The
            // foreground service and retained manager own the session until the user taps Stop.
            android.util.Log.i(TAG, "Activity destroyed while casting; keeping session alive")
        } else {
            webRtcManager?.stop()
            webRtcManager = null
            ActiveCastingSession.manager = null
            ScreenCaptureService.stop(this)
        }
        super.onDestroy()
    }
}
