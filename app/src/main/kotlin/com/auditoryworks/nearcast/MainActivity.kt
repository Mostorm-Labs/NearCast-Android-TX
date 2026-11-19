package com.auditoryworks.nearcast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auditoryworks.nearcast.touchback.*
import com.auditoryworks.nearcast.discovery.*
import com.auditoryworks.nearcast.ui.screens.ReceiverConnectionSheet
import com.auditoryworks.nearcast.ui.screens.ConnectionTab
import com.auditoryworks.nearcast.ui.screens.SettingsScreen
import com.auditoryworks.nearcast.ui.screens.StartupPermissionDialog
import com.auditoryworks.nearcast.permissions.*
import com.auditoryworks.nearcast.ui.screens.CapturePermissionDialog
import com.auditoryworks.nearcast.ui.screens.PermissionDialog
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class AppScreen { HOME, SESSION }

/** Keeps the active WebRTC session alive while the UI task is removed or recreated. */
@SuppressLint("StaticFieldLeak")
private object ActiveCastingSession {
    val remotePermissionFlow = RemotePermissionFlow()
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
    private var statusText by mutableStateOf("")
    private val serverUrl = "https://cast.nearhub.us/"
    private var pairCode by mutableStateOf("")
    private var sessionState by mutableStateOf(CastSessionState())
    private var lastAudioModeStatus by mutableStateOf("")
    private var sessionStateJob: Job? = null
    private var remoteControlState by mutableStateOf(RemoteControlAccess.state.value)
    private var permissionDialog by mutableStateOf<PermissionDialog?>(null)
    private var pendingStartSession: String? = null
    private var remoteSettingsResumeCapture = false
    private var checkingRemoteSettings = false
    private var audioSettingsSession: String? = null
    private var permissionReturnJob: Job? = null
    private var startCaptureAfterPermissions: (() -> Unit)? = null
    private val permissionPrefs get() = getSharedPreferences("permission-ui", MODE_PRIVATE)
    private val startupPermissions = StartupPermissionFlow()
    private var startupPermissionSnapshot by mutableStateOf(StartupPermissionSnapshot())
    private var startupSettingsPending = false
    private var showLogUploadDialog by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var manageReceiver by mutableStateOf(false)
    private var connectionTab by mutableStateOf(ConnectionTab.CODE)
    private var discoveryRevision by mutableStateOf(0)
    private var discoverySnapshot by mutableStateOf(DiscoverySnapshot())
    private var selectedLocalInstance by mutableStateOf<String?>(null)
    private var selectedLocalId: String? = null
    private var selectedLocalName by mutableStateOf("")
    private var isJoining by mutableStateOf(false)
    private var signalingEventsJob: Job? = null
    private var joiningTimeout: Job? = null
    private val nsdDiscovery by lazy { NsdDiscoveryManager(applicationContext) }
    private val receiverPins by lazy { ReceiverTrustStore(applicationContext) }
    private val receiverActions by lazy { ReceiverActions(lifecycleScope, ReceiverTls(nsdDiscovery::socketFactory), receiverPins, nsdDiscovery::current) }
    private var isLogUploadInProgress by mutableStateOf(false)

    private var updateInfo by mutableStateOf<AppUpdateInfo?>(null)
    private var showUpdateDialog by mutableStateOf(false)
    private var isCheckingUpdate by mutableStateOf(false)
    private var updateMessage by mutableStateOf("")
    private var isDownloadingUpdate by mutableStateOf(false)
    private var isDownloadProgressVisible by mutableStateOf(false)
    private var downloadProgress by mutableStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedLocalInstance = savedInstanceState?.getString("local-instance")
        selectedLocalId = savedInstanceState?.getString("local-device-id")
        selectedLocalName = savedInstanceState?.getString("local-name").orEmpty()
        connectionTab = ConnectionTab.entries.firstOrNull { it.name == savedInstanceState?.getString("connection-tab") } ?: ConnectionTab.CODE
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

        refreshStartupPermissions()
        if (savedInstanceState?.getBoolean("startup-checked", false) == true) {
            startupPermissions.restore(StartupPermissionState(checked = true,
                showing = savedInstanceState.getBoolean("startup-showing"),
                pending = StartupPermission.entries.firstOrNull { it.name == savedInstanceState.getString("startup-pending") },
                audioGrantedAtCheck = savedInstanceState.getBoolean("startup-audio-ready")))
            startupSettingsPending = savedInstanceState.getBoolean("startup-settings-pending")
        }
        startupPermissions.check(permissionPrefs.getBoolean("startup-introduced", false),
            startupPermissionSnapshot.audioGranted,
            sessionState.isProjectionActive || sessionState.isCaptureBusy || ActiveCastingSession.captureStartPending)

        lifecycleScope.launch {
            updateInfo = UpdateManager.checkUpdate()
        }

        setContent {
            ScreenCastTheme {
                val receiverAction by receiverActions.state.collectAsStateWithLifecycle()
                val needsDiscovery = currentScreen == AppScreen.HOME && (!showSettings || selectedLocalInstance != null) || receiverAction.device != null
                LaunchedEffect(needsDiscovery, discoveryRevision) {
                    if (needsDiscovery) repeatOnLifecycle(Lifecycle.State.STARTED) {
                        try { nsdDiscovery.discoverServices().collect { snapshot -> discoverySnapshot = snapshot; receiverActions.devicesChanged() } }
                        finally { receiverActions.cancel(); discoverySnapshot = DiscoverySnapshot() }
                    }
                }
                LaunchedEffect(receiverAction, currentScreen, showSettings, isJoining, manageReceiver) {
                    if (receiverAction.phase == ReceiverPhase.SHOWN && currentScreen == AppScreen.HOME && !showSettings && !isJoining && !manageReceiver) {
                        val requested = receiverAction.device
                        val current = requested?.let { nsdDiscovery.current(it.instance) }
                        val pin = try { requested?.deviceId?.let(receiverPins::get) } catch (_: TrustStorageFailure) { null }
                        val ready = receiverAction.localCodeReceiver(current, pin)
                        if (ready != null) selectLocalReceiver(ready)
                        else {
                            statusText = getString(if (current?.localReady == false) R.string.local_unavailable else R.string.local_trust_required)
                            receiverActions.cancel()
                        }
                    }
                }
                val preparingReceiver = receiverAction.device?.instance?.takeIf {
                    receiverAction.phase in setOf(ReceiverPhase.CHECKING, ReceiverPhase.PREPARING, ReceiverPhase.SAVING, ReceiverPhase.SHOWING)
                }
                if (receiverAction.device != null && (manageReceiver || preparingReceiver == null && receiverAction.phase != ReceiverPhase.SHOWN) &&
                    currentScreen == AppScreen.HOME && !showSettings && !isJoining) ReceiverConnectionSheet(receiverAction,
                    onRetry = receiverActions::retryTrust, onCode = receiverActions::showCode,
                    onForget = {
                        if (selectedLocalId != null && selectedLocalId == receiverAction.device?.deviceId) {
                            selectedLocalInstance = null; selectedLocalId = null; selectedLocalName = ""; pairCode = ""
                        }
                        receiverActions.forget()
                    },
                    onLocalEntry = ::selectLocalReceiver,
                    onManualEntry = { useCloud(); receiverActions.cancel(); manageReceiver = false },
                    onDismiss = { receiverActions.cancel(); manageReceiver = false }, manageDevice = manageReceiver)
                val accessState by RemoteControlAccess.state.collectAsStateWithLifecycle()
                val startupState by startupPermissions.state.collectAsStateWithLifecycle()
                val displayedRemoteState = if (webRtcManager == null) accessState else remoteControlState
                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) captureConsent@{ result ->
                    if (!isPendingStartValid()) return@captureConsent
                    pendingStartSession = null
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
                ) audioConsent@{ granted ->
                    if (!isPendingStartValid()) return@audioConsent
                    if (granted) {
                        launchScreenCapture(mediaProjectionLauncher)
                    } else {
                        ActiveCastingSession.manager?.markCapturePermissionDenied()
                        statusText = getString(R.string.audio_permission_denied_status)
                        permissionDialog = if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) PermissionDialog.AUDIO_DENIED else PermissionDialog.AUDIO_PERMANENT
                    }
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    // Notification permission is best-effort.
                    // Continue cast flow regardless of user choice to avoid blocking start.
                    if (isPendingStartValid()) requestAudioPermissionAndStartCast(mediaProjectionLauncher)
                }

                val startupPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    // This result only refreshes startup state; it never enters a capture request.
                    startupPermissions.returned()
                    refreshStartupPermissions()
                }

                startCaptureAfterPermissions = {
                    if (isPendingStartValid()) requestNotificationPermissionAndStartCast(
                        notificationPermissionLauncher, mediaProjectionLauncher)
                }

                if (showSettings) {
                    SettingsScreen(
                        remoteControlState = displayedRemoteState,
                        onRemoteEnabled = { enabled ->
                            RemoteControlAccess.setEnabled(enabled)
                            if (enabled && !RemoteControlAccess.state.value.permissionEnabled) {
                                remoteSettingsResumeCapture = false
                                permissionDialog = PermissionDialog.REMOTE_EXPLANATION
                            }
                        },
                        onRemoteSettings = { remoteSettingsResumeCapture = false; permissionDialog = PermissionDialog.REMOTE_EXPLANATION },
                        onRemoteHelp = { permissionDialog = PermissionDialog.REMOTE_HELP },
                        onRemoteRetry = { webRtcManager?.retryRemoteControl() ?: RemoteControlAccess.retryCleanup() },
                        isLogUploadInProgress = isLogUploadInProgress,
                        onUploadLogs = { showLogUploadDialog = true },
                        isCheckingUpdate = isCheckingUpdate,
                        availableVersion = updateInfo?.version,
                        updateMessage = updateMessage,
                        onCheckUpdate = ::checkForUpdatesFromSettings,
                        isUpdateDownloadInProgress = isDownloadingUpdate,
                        isDownloadProgressVisible = isDownloadProgressVisible,
                        onShowDownloadProgress = { isDownloadProgressVisible = true },
                        onBack = { showSettings = false },
                        onPermissions = { refreshStartupPermissions(); startupPermissions.open(startupPermissionSnapshot.audioGranted) })
                } else when (currentScreen) {
                    AppScreen.HOME -> HomeScreen(
                        pairCode = pairCode,
                        statusText = statusText.takeUnless { it in setOf("Ready", getString(R.string.local_enter_code), getString(R.string.cloud_enter_code)) }.orEmpty(),
                        tab = connectionTab,
                        onTabSelected = { receiverActions.cancel(); manageReceiver = false; connectionTab = it; statusText = "" },
                        snapshot = discoverySnapshot,
                        onSelectReceiver = { if (!isJoining) { manageReceiver = false; statusText = ""; receiverActions.select(it) } },
                        onRefresh = { receiverActions.cancel(); discoverySnapshot = DiscoverySnapshot(scanning = true); discoveryRevision++; statusText = "" },
                        onNetworkSettings = ::openAppSettings,
                        isUpdateDownloadInProgress = isDownloadingUpdate,
                        isDownloadProgressVisible = isDownloadProgressVisible,
                        onPairCodeChange = { pairCode = it; statusText = "" },
                        onJoin = ::joinRoom,
                        onOpenSettings = { receiverActions.cancel(); showSettings = true },
                        onShowDownloadProgress = { isDownloadProgressVisible = true },
                        localReceiverName = if (selectedLocalInstance != null) selectedLocalName else null,
                        isJoining = isJoining,
                        onUseCloud = ::useCloud,
                        onCancelJoin = ::leaveRoom,
                        preparingReceiver = preparingReceiver,
                        onCancelPreparation = receiverActions::cancel,
                        onReceiverOptions = { if (!isJoining) { manageReceiver = true; receiverActions.select(it) } })
                    AppScreen.SESSION -> SessionScreen(
                        statusText = statusText,
                        sessionState = sessionState,
                        isUpdateDownloadInProgress = isDownloadingUpdate,
                        isDownloadProgressVisible = isDownloadProgressVisible,
                        onStartCast = {
                            val manager = webRtcManager
                            if (manager != null && manager.sessionState.value.isP2PReady && !manager.sessionState.value.isCaptureBusy) {
                                cancelPendingPermissions()
                                pendingStartSession = manager.permissionSessionKey
                                remoteSettingsResumeCapture = false
                                val access = RemoteControlAccess.state.value
                                when (ActiveCastingSession.remotePermissionFlow.start(manager.permissionSessionKey, access.enabled && access.supported,
                                    access.permissionEnabled && access.serviceConnected, explainRemote = false)) {
                                    PermissionAction.SHOW_EXPLANATION -> permissionDialog = PermissionDialog.REMOTE_EXPLANATION
                                    else -> startCaptureAfterPermissions?.invoke()
                                }
                            }
                        },
                        onStopCast = {
                            SessionTraceRecorder.record(TAG, "Stop cast requested from UI")
                            ActiveCastingSession.cancelCaptureStart()
                            cancelPendingPermissions()
                            webRtcManager?.stopScreenCapture()
                        },
                        onLeave = ::leaveRoom,
                        onOpenSettings = { showSettings = true },
                        onShowDownloadProgress = { isDownloadProgressVisible = true },
                        remoteControlState = displayedRemoteState,
                        onRemoteSettings = { remoteSettingsResumeCapture = false; permissionDialog = PermissionDialog.REMOTE_EXPLANATION },
                        onRemoteRetry = { webRtcManager?.retryRemoteControl() })
                }

                if (startupState.showing && permissionDialog == null && !showLogUploadDialog && !showUpdateDialog) {
                    StartupPermissionDialog(startupState, startupPermissionSnapshot, displayedRemoteState,
                        projectionActive = sessionState.isProjectionActive,
                        onAudio = {
                            if (startupPermissions.request(StartupPermission.AUDIO)) {
                                if (startupPermissionSnapshot.audioNeedsSettings) {
                                    startupSettingsPending = true; openAppSettings()
                                } else {
                                    permissionPrefs.edit().putBoolean("audio-requested", true).apply()
                                    startupPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }, onNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && startupPermissions.request(StartupPermission.NOTIFICATIONS)) {
                                if (startupPermissionSnapshot.notificationsNeedSettings) {
                                    startupSettingsPending = true; openAppSettings()
                                } else {
                                    permissionPrefs.edit().putBoolean("notifications-requested", true).apply()
                                    startupPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }, onRemote = { remoteSettingsResumeCapture = false; permissionDialog = PermissionDialog.REMOTE_EXPLANATION },
                        onContinue = { finishStartupSetup(defer = false) }, onDefer = { finishStartupSetup(defer = true) })
                }

                permissionDialog?.let { kind ->
                    CapturePermissionDialog(kind,
                        onDismiss = { permissionDialog = null; if (kind != PermissionDialog.REMOTE_HELP) cancelPendingPermissions() },
                        onOpenAccessibility = { permissionDialog = null; openAccessibilitySettings(remoteSettingsResumeCapture && isPendingStartValid()) },
                        onSkipRemote = {
                            permissionDialog = null
                            val key = pendingStartSession
                            if (key != null && isPendingStartValid()) {
                                ActiveCastingSession.remotePermissionFlow.skip(key)
                                startCaptureAfterPermissions?.invoke()
                            }
                        },
                        onOpenAppSettings = {
                            permissionDialog = null
                            if (kind == PermissionDialog.AUDIO_PERMANENT && isPendingStartValid()) audioSettingsSession = pendingStartSession
                            openAppSettings()
                        },
                        onRequestAudio = {
                            permissionDialog = null
                            if (isPendingStartValid()) {
                                permissionPrefs.edit().putBoolean("audio-requested", true).apply()
                                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }, sharingPending = isPendingStartValid())
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

                if (showUpdateDialog) updateInfo?.let { info ->
                    UpdateDialog(
                        updateInfo = info,
                        onDismiss = { showUpdateDialog = false },
                        onUpdate = {
                            updateInfo = null
                            showUpdateDialog = false
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

    private fun refreshStartupPermissions() {
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val supportsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationsGranted = !supportsNotifications ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        startupPermissionSnapshot = StartupPermissionSnapshot(
            audioGranted = audioGranted,
            audioNeedsSettings = !audioGranted && permissionPrefs.getBoolean("audio-requested", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = notificationsGranted,
            notificationsNeedSettings = supportsNotifications && !notificationsGranted &&
                permissionPrefs.getBoolean("notifications-requested", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
            notificationsSupported = supportsNotifications)
    }

    private fun finishStartupSetup(defer: Boolean) {
        val audioGranted = startupPermissionSnapshot.audioGranted
        val closed = if (defer) startupPermissions.defer(audioGranted) else startupPermissions.finish(audioGranted)
        if (closed) permissionPrefs.edit().putBoolean("startup-introduced", true).apply()
    }

    private fun requestNotificationPermissionAndStartCast(
        notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
        mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        if (!isPendingStartValid()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestAudioPermissionAndStartCast(mediaProjectionLauncher)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted || permissionPrefs.getBoolean("notifications-requested", false)) {
            requestAudioPermissionAndStartCast(mediaProjectionLauncher)
        } else {
            permissionPrefs.edit().putBoolean("notifications-requested", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestAudioPermissionAndStartCast(
        mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        if (!isPendingStartValid()) return
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
            permissionDialog = if (permissionPrefs.getBoolean("audio-requested", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) PermissionDialog.AUDIO_PERMANENT else PermissionDialog.AUDIO_EXPLANATION
        }
    }

    private fun launchScreenCapture(
        mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        if (!isPendingStartValid()) return
        ActiveCastingSession.manager?.markCapturePermissionRequested()
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun isPendingStartValid(): Boolean {
        val manager = webRtcManager ?: return false
        return pendingStartSession != null && pendingStartSession == manager.permissionSessionKey && manager.sessionState.value.isP2PReady
    }

    private fun cancelPendingPermissions() {
        pendingStartSession = null; audioSettingsSession = null; checkingRemoteSettings = false
        remoteSettingsResumeCapture = false; permissionDialog = null
        permissionReturnJob?.cancel(); permissionReturnJob = null
        ActiveCastingSession.remotePermissionFlow.cancel()
    }

    private fun openAccessibilitySettings(resumeCapture: Boolean) {
        ActiveCastingSession.remotePermissionFlow.openSettings(webRtcManager?.permissionSessionKey, resumeCapture)
        checkingRemoteSettings = true
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            checkingRemoteSettings = false; ActiveCastingSession.remotePermissionFlow.cancel()
            permissionDialog = PermissionDialog.SETTINGS_UNAVAILABLE
        } catch (_: SecurityException) {
            checkingRemoteSettings = false; ActiveCastingSession.remotePermissionFlow.cancel()
            permissionDialog = PermissionDialog.SETTINGS_UNAVAILABLE
        }
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: ActivityNotFoundException) {
            audioSettingsSession = null; startupSettingsPending = false; startupPermissions.returned()
            permissionDialog = PermissionDialog.SETTINGS_UNAVAILABLE
        }
        catch (_: SecurityException) {
            audioSettingsSession = null; startupSettingsPending = false; startupPermissions.returned()
            permissionDialog = PermissionDialog.SETTINGS_UNAVAILABLE
        }
    }

    override fun onResume() {
        super.onResume()
        RemoteControlAccess.refresh()
        refreshStartupPermissions()
        if (startupSettingsPending) { startupSettingsPending = false; startupPermissions.returned() }
        val checkRemote = checkingRemoteSettings
        val audioSession = audioSettingsSession
        if (!checkRemote && audioSession == null) return
        checkingRemoteSettings = false; audioSettingsSession = null
        permissionReturnJob?.cancel()
        permissionReturnJob = lifecycleScope.launch {
            if (checkRemote) {
                val access = RemoteControlAccess.state.value
                if (access.permissionEnabled && !access.serviceConnected) {
                    withTimeoutOrNull(1500) { RemoteControlAccess.state.first { it.permissionEnabled && it.serviceConnected } }
                }
                val current = RemoteControlAccess.state.value
                when (ActiveCastingSession.remotePermissionFlow.returnFromSettings(webRtcManager?.permissionSessionKey,
                    current.permissionEnabled && current.serviceConnected)) {
                    PermissionAction.START_CAPTURE -> if (isPendingStartValid()) startCaptureAfterPermissions?.invoke()
                    PermissionAction.SHOW_NOT_GRANTED -> if (isPendingStartValid()) {
                        statusText = getString(R.string.remote_settings_failed_status)
                        permissionDialog = PermissionDialog.REMOTE_NOT_GRANTED
                    }
                    else -> Unit
                }
            }
            if (audioSession != null && audioSession == pendingStartSession && isPendingStartValid()) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startCaptureAfterPermissions?.invoke()
                } else statusText = getString(R.string.audio_permission_denied_status)
            }
        }
    }

    private fun bindWebRtcManager(manager: WebRtcManager?) {
        sessionStateJob?.cancel()
        webRtcManager?.clearOnStatusChange()
        webRtcManager = manager
        remoteControlState = manager?.remoteControlState?.value ?: RemoteControlAccess.state.value
        sessionState = manager?.sessionState?.value ?: CastSessionState()
        manager?.setOnStatusChange { status -> runOnUiThread { if (webRtcManager === manager) handleWebRtcStatus(status) } }
        if (manager == null) return

        sessionStateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.remoteControlState.collect { remoteControlState = it } }
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
        if (isJoining) return
        val effectivePairCode = pairCode

        if (effectivePairCode.isBlank()) {
            statusText = "Please enter a pair code"
            return
        }

        if (!effectivePairCode.matches(Regex("[A-Za-z0-9]{4}"))) { statusText = getString(R.string.connection_invalid_code); return }
        val local = try {
            selectedLocalInstance?.let { instance ->
                val receiver = nsdDiscovery.current(instance)?.takeIf { it.deviceId == selectedLocalId && it.selectable }
                    ?: run { statusText = getString(R.string.discovery_offline); return }
                if (receiver.localReady == false) { statusText = getString(R.string.local_unavailable); return }
                val pin = receiver.deviceId?.let(receiverPins::get)
                    ?: run { statusText = getString(R.string.local_trust_required); return }
                LocalReceiverConnection(receiver, pin, receiverPins, nsdDiscovery.socketFactory(receiver))
            }
        } catch (_: TrustStorageFailure) { statusText = getString(R.string.discovery_storage_error); return }
        catch (_: Exception) { statusText = getString(R.string.discovery_network_error); return }
        webRtcManager?.stop(); signalingEventsJob?.cancel(); joiningTimeout?.cancel()
        SessionTraceRecorder.record(TAG, "Join requested backend=${if (local != null) "local" else "cloud"}")
        statusText = getString(R.string.connection_connecting)
        isJoining = true

        val signalingClient: SignalingClient = NearHubSignalingClient(
            baseUrl = serverUrl,
            onStatusChange = { status -> runOnUiThread { if (isJoining) statusText = status } },
            localConnection = local
        )

        val manager = WebRtcManager(
            context = applicationContext,
            signalingClient = signalingClient,
            onStatusChange = {}
        )
        bindWebRtcManager(manager)
        ActiveCastingSession.manager = manager

        signalingEventsJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            signalingClient.events.collect { event ->
                if (webRtcManager !== manager) return@collect
                when (event) {
                    is NearHubEvent.Connected -> {
                        signalingClient.join(effectivePairCode, "Android-${Build.MODEL}")
                    }
                    is NearHubEvent.Joined -> {
                        isJoining = false; joiningTimeout?.cancel()
                        currentScreen = AppScreen.SESSION
                    }
                    is NearHubEvent.JoinFailed -> {
                        leaveRoom()
                        statusText = if (event.reason == "invalid_code") getString(R.string.connection_wrong_code) else getString(R.string.connection_join_failed, event.message)
                    }
                    is NearHubEvent.ServerError -> if (local != null) {
                        leaveRoom()
                        statusText = getString(when (event.reason) {
                            "NEARCAST_KEY_MISMATCH" -> R.string.discovery_key_changed
                            "NEARCAST_TRUST_REVOKED" -> R.string.local_trust_required
                            else -> R.string.discovery_network_error
                        })
                    }
                    is NearHubEvent.Disconnected -> if (local != null || isJoining) {
                        leaveRoom(); statusText = getString(R.string.discovery_network_error)
                    }
                    is NearHubEvent.PeerLeave,
                    is NearHubEvent.RoomClosed -> {
                        leaveRoom()
                    }
                    else -> {}
                }
            }
        }
        joiningTimeout = lifecycleScope.launch {
            delay(15000)
            if (webRtcManager === manager && isJoining) { leaveRoom(); statusText = getString(R.string.connection_timeout) }
        }
        signalingClient.connect()
    }

    private fun useCloud() {
        if (isJoining) leaveRoom()
        selectedLocalInstance = null; selectedLocalId = null; selectedLocalName = ""; pairCode = ""
        statusText = ""; connectionTab = ConnectionTab.CODE
    }

    private fun selectLocalReceiver(device: ReceiverDevice) {
        val current = nsdDiscovery.current(device.instance)
        if (current?.routeKey != device.routeKey || current.capabilities != device.capabilities || current.localReady == false) {
            statusText = getString(R.string.discovery_offline); receiverActions.cancel(); return
        }
        val action = receiverActions.state.value
        val pin = try { device.deviceId?.let(receiverPins::get) } catch (_: TrustStorageFailure) { null }
        if (pin == null || !validFingerprint(pin) || pin != action.fingerprint || action.candidate != null ||
            action.device?.routeKey != device.routeKey ||
            action.phase !in setOf(ReceiverPhase.SHOWN, ReceiverPhase.UNAVAILABLE, ReceiverPhase.RATE_LIMITED, ReceiverPhase.UNSUPPORTED, ReceiverPhase.ERROR)) {
            statusText = getString(R.string.local_trust_required); receiverActions.cancel(); return
        }
        selectedLocalInstance = device.instance; selectedLocalId = device.deviceId; selectedLocalName = device.name
        pairCode = ""; statusText = ""; connectionTab = ConnectionTab.CODE
        manageReceiver = false
        receiverActions.cancel()
    }

    private fun checkForUpdatesFromSettings() {
        if (isCheckingUpdate || isDownloadingUpdate) return
        if (updateInfo != null) { showUpdateDialog = true; return }
        isCheckingUpdate = true; updateMessage = ""
        lifecycleScope.launch {
            try {
                updateInfo = UpdateManager.checkUpdate()
                if (updateInfo != null) showUpdateDialog = true
                else updateMessage = getString(R.string.ui_update_none)
            } finally { isCheckingUpdate = false }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val startup = startupPermissions.state.value
        outState.putBoolean("startup-checked", startup.checked)
        outState.putBoolean("startup-showing", startup.showing)
        outState.putString("startup-pending", startup.pending?.name)
        outState.putBoolean("startup-audio-ready", startup.audioGrantedAtCheck)
        outState.putBoolean("startup-settings-pending", startupSettingsPending)
        outState.putString("local-instance", selectedLocalInstance)
        outState.putString("local-device-id", selectedLocalId)
        outState.putString("local-name", selectedLocalName)
        outState.putString("connection-tab", connectionTab.name)
        super.onSaveInstanceState(outState)
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
        isJoining = false; joiningTimeout?.cancel(); joiningTimeout = null
        signalingEventsJob?.cancel(); signalingEventsJob = null
        SessionTraceRecorder.record(TAG, "Leave room requested")
        ActiveCastingSession.cancelCaptureStart()
        cancelPendingPermissions()
        webRtcManager?.clearOnStatusChange()
        webRtcManager?.stop()
        sessionStateJob?.cancel()
        sessionStateJob = null
        webRtcManager = null
        ActiveCastingSession.manager = null
        ScreenCaptureService.stop(this)
        sessionState = CastSessionState()
        lastAudioModeStatus = ""
        currentScreen = AppScreen.HOME
        statusText = ""
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
