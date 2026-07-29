package com.auditoryworks.nearcast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.auditoryworks.nearcast.diagnostics.LogUploadManager
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder
import com.auditoryworks.nearcast.service.ScreenCaptureService
import com.auditoryworks.nearcast.session.CastSessionState
import com.auditoryworks.nearcast.session.CaptureState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class AppScreen { HOME, SESSION }

/** Keeps the active WebRTC session alive while the UI task is removed or recreated. */
@SuppressLint("StaticFieldLeak")
private object ActiveCastingSession {
    var manager: WebRtcManager? = null
    var captureStartPending: Boolean = false
        private set
    private var captureRequestId: Long = 0

    fun beginCaptureStart(): Long {
        captureRequestId += 1
        captureStartPending = true
        return captureRequestId
    }

    fun isCurrentCaptureRequest(requestId: Long): Boolean =
        captureStartPending && captureRequestId == requestId

    fun finishCaptureStart(requestId: Long) {
        if (captureRequestId == requestId) {
            captureStartPending = false
        }
    }

    fun cancelCaptureStart() {
        captureRequestId += 1
        captureStartPending = false
    }
}

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private var webRtcManager: WebRtcManager? = null
    private var currentScreen by mutableStateOf(AppScreen.HOME)
    private var statusText by mutableStateOf("Ready")
    private val serverUrl = "https://cast.nearhub.us/"
    private var pairCode by mutableStateOf("")
    private var sessionState by mutableStateOf(CastSessionState())
    private var lastAudioModeStatus by mutableStateOf("")
    private var sessionStateJob: Job? = null
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
        bindWebRtcManager(ActiveCastingSession.manager)
        if (webRtcManager != null || ActiveCastingSession.captureStartPending) {
            currentScreen = AppScreen.SESSION
            sessionState = webRtcManager?.sessionState?.value ?: CastSessionState(
                capture = CaptureState.STARTING
            )
            statusText = "Restored casting session"
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
                        val captureRequestId = ActiveCastingSession.beginCaptureStart()
                        statusText = "Starting screen capture..."
                        Log.i(TAG, "MediaProjection permission granted requestId=$captureRequestId")
                        // 1. Start foreground service immediately after permission granted.
                        // On Android 14+, the foreground service MUST be started before MediaProjection
                        // is created from the result data.
                        SessionTraceRecorder.record(TAG, "Starting screen capture service")
                        ScreenCaptureService.start(this@MainActivity)

                        // 2. Short delay to ensure the system has processed the foreground service start
                        // and it is in "foreground" state before we attempt to use the MediaProjection.
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!ActiveCastingSession.isCurrentCaptureRequest(captureRequestId)) {
                                Log.i(TAG, "Ignoring cancelled screen capture requestId=$captureRequestId")
                                return@postDelayed
                            }
                            try {
                                val manager = ActiveCastingSession.manager
                                    ?: throw IllegalStateException("Casting session is no longer available")
                                SessionTraceRecorder.record(TAG, "Starting WebRTC screen capture")
                                Log.i(TAG, "Starting WebRTC screen capture requestId=$captureRequestId")
                                if (!manager.isCasting) {
                                    manager.startScreenCapture(data)
                                }
                                SessionTraceRecorder.record(TAG, "Screen capture started")
                            } catch (e: Exception) {
                                val readableError = e.readableMessage()
                                statusText = "Screen capture failed: $readableError"
                                val manager = ActiveCastingSession.manager
                                if (manager == null || !manager.sessionState.value.shouldKeepForegroundService) {
                                    ScreenCaptureService.stop(this@MainActivity)
                                }
                                SessionTraceRecorder.record(TAG, "Screen capture failed: $readableError")
                            } finally {
                                ActiveCastingSession.finishCaptureStart(captureRequestId)
                            }
                        }, 800)
                    } else {
                        ActiveCastingSession.cancelCaptureStart()
                        ActiveCastingSession.manager?.markCapturePermissionDenied()
                            ?: run { statusText = "Screen capture permission denied" }
                    }
                }
                val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        launchScreenCapture(mediaProjectionLauncher)
                    } else {
                        ActiveCastingSession.manager?.markCapturePermissionDenied()
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
                        sessionState = sessionState,
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
                            ActiveCastingSession.cancelCaptureStart()
                            webRtcManager?.stopScreenCapture()
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
        ActiveCastingSession.manager?.markCapturePermissionRequested()
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun bindWebRtcManager(manager: WebRtcManager?) {
        sessionStateJob?.cancel()
        webRtcManager = manager
        sessionState = manager?.sessionState?.value ?: CastSessionState()
        manager?.setOnStatusChange(::handleWebRtcStatus)
        if (manager == null) return

        sessionStateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.sessionState.collect { state ->
                    sessionState = state
                    if (state.transport != com.auditoryworks.nearcast.session.TransportState.IDLE &&
                        state.transport != com.auditoryworks.nearcast.session.TransportState.CLOSED
                    ) {
                        currentScreen = AppScreen.SESSION
                    }
                    if (state.capture == CaptureState.AWAITING_RESELECTION) {
                        currentScreen = AppScreen.SESSION
                    }
                }
            }
        }
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

        val manager = WebRtcManager(
            context = applicationContext,
            signalingClient = signalingClient,
            onStatusChange = ::handleWebRtcStatus
        )
        bindWebRtcManager(manager)
        ActiveCastingSession.manager = manager

        signalingClient.connect()

        lifecycleScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is NearHubEvent.Connected -> {
                        signalingClient.join(effectivePairCode, "Android-${Build.MODEL}")
                    }
                    is NearHubEvent.Joined -> {
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

    private fun handleWebRtcStatus(status: String) {
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

            if (webRtcManager?.sessionState?.value?.transport ==
                com.auditoryworks.nearcast.session.TransportState.CONNECTED
            ) {
                currentScreen = AppScreen.SESSION
            }
            if (
                status.contains("removed from room", ignoreCase = true) ||
                status.contains("room closed", ignoreCase = true)
            ) {
                sessionState = CastSessionState()
                lastAudioModeStatus = ""
                ScreenCaptureService.stop(this@MainActivity)
            }
        }
    }

    private fun leaveRoom() {
        SessionTraceRecorder.record(TAG, "Leave room requested")
        ActiveCastingSession.cancelCaptureStart()
        webRtcManager?.stop()
        sessionStateJob?.cancel()
        sessionStateJob = null
        webRtcManager = null
        ActiveCastingSession.manager = null
        ScreenCaptureService.stop(this)
        sessionState = CastSessionState()
        lastAudioModeStatus = ""
        currentScreen = AppScreen.HOME
        statusText = "Ready"
    }

    override fun onDestroy() {
        if (sessionState.shouldKeepForegroundService ||
            webRtcManager?.sessionState?.value?.shouldKeepForegroundService == true ||
            ActiveCastingSession.captureStartPending
        ) {
            // Do not tear down an active cast when Android removes/recreates the UI task. The
            // foreground service and retained manager own the session until the user taps Stop.
            webRtcManager?.clearOnStatusChange()
            Log.i(
                TAG,
                "Activity destroyed during pending/active capture; keeping session alive " +
                    "pending=${ActiveCastingSession.captureStartPending}"
            )
        } else {
            sessionStateJob?.cancel()
            webRtcManager?.stop()
            webRtcManager = null
            ActiveCastingSession.manager = null
            ScreenCaptureService.stop(this)
        }
        super.onDestroy()
    }
}
