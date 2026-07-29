package com.auditoryworks.nearcast.webrtc

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

private const val TAG = "WebRtcManager"
private const val AUDIO_SAMPLE_RATE_HZ = 48_000
private const val AUDIO_CHANNEL_COUNT = 1
private const val VIDEO_CAPTURE_FPS = 30
private const val VIRTUAL_DISPLAY_DPI = 400

/**
 * Two-phase WebRTC manager for NearHub screen casting.
 *
 * Phase 1 (connect): PeerConnection + DataChannel, no media tracks.
 *   Triggered after receiving "joined" from signaling server.
 *
 * Phase 2 (startScreenCapture): Add screen capture tracks + renegotiate.
 *   Triggered when user clicks "Start Casting" and grants MediaProjection.
 */
class WebRtcManager(
    private val context: Context,
    val signalingClient: SignalingClient,
    private var onStatusChange: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val eglBase: EglBase = EglBase.create()
    private val audioDeviceModule: JavaAudioDeviceModule
    private val peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    var isDataChannelOpen = false
        private set

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioSender: RtpSender? = null
    private var systemAudioCapture: SystemAudioCapture? = null
    private var lastRemoteAnswerSdp: String? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mutedLocalPlaybackForCasting = false
    private var videoStatsPollingJob: Job? = null
    private var videoStatsSnapshotCount = 0
    private val captureResizeLock = Any()
    private val captureDisplayHandler = Handler(Looper.getMainLooper())
    private var captureDisplayManager: DisplayManager? = null
    private var isCaptureDisplayListenerRegistered = false
    private var captureWidth = 0
    private var captureHeight = 0
    private var isCaptureFormatReady = false
    private var pendingCaptureWidth = 0
    private var pendingCaptureHeight = 0
    private var pendingCaptureReason = ""

    @Volatile
    private var receivedCapturedContentSize = false

    private val captureDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            // Android 14+ reports the selected app's actual content bounds. Once
            // available, that is more accurate than the physical display size.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                receivedCapturedContentSize
            ) {
                return
            }
            val (width, height) = readDefaultDisplaySize()
            updateScreenCaptureSize(width, height, "display changed")
        }
    }

    private var injectionFrameCount = 0
    private var emptyInjectionCount = 0
    private var audioDataCallbackCount = 0
    // Pre-allocated reusable buffer for audio injection — avoids per-frame ByteArray allocation.
    private var audioInjectionBuffer: ByteArray? = null
    private var audioSilenceBuffer: ByteArray? = null

    @Volatile
    private var isCleanupInProgress = false

    var isCasting = false
        private set

    /** Rebind UI updates when single-app projection recreates the launcher activity. */
    fun setOnStatusChange(listener: (String) -> Unit) {
        onStatusChange = listener
    }

    private val rtcConfig = PeerConnection.RTCConfiguration(
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    init {
        SessionTraceRecorder.record(TAG, "WebRtcManager init")
        synchronized(Companion) {
            if (!isFactoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                isFactoryInitialized = true
            }
        }
        audioDeviceModule = createAudioDeviceModule()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .createPeerConnectionFactory()

        observeSignalingEvents()
    }

    companion object {
        private var isFactoryInitialized = false
    }

    private fun observeSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is NearHubEvent.Joined -> {
                        SessionTraceRecorder.record(TAG, "signaling joined roomId=${event.roomId} userId=${event.userId}")
                        onStatusChange("Joined room, establishing P2P...")
                        connectP2P()
                    }
                    is NearHubEvent.OfferReceived -> handleRemoteOffer(event.sdp)
                    is NearHubEvent.AnswerReceived -> handleRemoteAnswer(event.sdp)
                    is NearHubEvent.IceCandidateReceived -> handleRemoteIceCandidate(event)
                    is NearHubEvent.Restored -> {
                        SessionTraceRecorder.record(TAG, "session restored needsRenegotiation=${event.needsRenegotiation}")
                        if (event.needsRenegotiation) {
                            onStatusChange("Session restored, renegotiating...")
                            createAndSendOffer()
                        }
                    }
                    is NearHubEvent.PeerLeave -> {
                        SessionTraceRecorder.record(TAG, "peer left")
                        onStatusChange("Removed from room")
                        cleanupP2P()
                    }
                    is NearHubEvent.RoomClosed -> {
                        SessionTraceRecorder.record(TAG, "room closed")
                        onStatusChange("Room closed")
                        cleanupP2P()
                    }
                    is NearHubEvent.ServerError -> {
                        SessionTraceRecorder.record(TAG, "server error: ${event.message}")
                        onStatusChange("Server error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun createAudioDeviceModule(): JavaAudioDeviceModule {
        // System audio is fed into WebRTC by an external 10 ms PCM clock. The patched
        // WebRtcAudioRecord never creates a microphone AudioRecord in this mode.
        SessionTraceRecorder.record(TAG, "configure audio device module (external system audio, mic disabled)")
        val builder = JavaAudioDeviceModule.builder(context)
            .setInputSampleRate(AUDIO_SAMPLE_RATE_HZ)
            .setUseStereoInput(false)
            .setUseExternalAudioInput(true)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordDataCallback { _, _, _, audioBuffer ->
                audioDataCallbackCount++
                val cap = audioBuffer.capacity()
                val capture = systemAudioCapture
                val bytes = audioInjectionBuffer?.takeIf { it.size == cap }
                    ?: ByteArray(cap).also { audioInjectionBuffer = it }
                val silence = audioSilenceBuffer?.takeIf { it.size == cap }
                    ?: ByteArray(cap).also { audioSilenceBuffer = it }
                val hasSystemAudio = capture?.fillAudioBuffer(bytes) == true
                audioBuffer.clear()
                audioBuffer.put(if (hasSystemAudio) bytes else silence)
                audioBuffer.flip()
                if (hasSystemAudio) {
                    injectionFrameCount++
                    if (injectionFrameCount % 500 == 0) {
                        Log.d(TAG, "System audio injected: $injectionFrameCount frames")
                    }
                } else {
                    // Never pass through microphone data. ExternalAudioInput starts with silence,
                    // and this explicit zero-fill also covers an empty playback-capture queue.
                    emptyInjectionCount++
                    if (emptyInjectionCount % 500 == 0) {
                        Log.d(TAG, "System audio queue empty; sending silence ($emptyInjectionCount frames)")
                    }
                }
            }
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                    Log.e(TAG, "AudioRecord init error: $errorMessage")
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    errorMessage: String
                ) {
                    Log.e(TAG, "AudioRecord start error: $errorCode $errorMessage")
                }

                override fun onWebRtcAudioRecordError(errorMessage: String) {
                    Log.e(TAG, "AudioRecord runtime error: $errorMessage")
                }
            })

        Log.d(TAG, "AudioDeviceModule configured for external system-audio PCM (microphone disabled)")

        return builder.createAudioDeviceModule()
    }

    // ── Phase 1: P2P + DataChannel (no media) ──

    private fun connectP2P() {
        SessionTraceRecorder.record(TAG, "connectP2P")
        setupPeerConnection()
        setupDataChannel()
        createAndSendOffer()
    }

    private fun setupPeerConnection() {
        peerConnection?.close()

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig, createPeerConnectionObserver()
        )
        Log.d(TAG, "PeerConnection created")
        SessionTraceRecorder.record(TAG, "PeerConnection created")
    }

    private fun setupDataChannel() {
        val config = DataChannel.Init().apply { ordered = true }
        dataChannel = peerConnection?.createDataChannel("control", config)
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                val state = dataChannel?.state()
                isDataChannelOpen = state == DataChannel.State.OPEN
                Log.d(TAG, "DataChannel state: $state")
                SessionTraceRecorder.record(TAG, "DataChannel state=$state")
                if (isDataChannelOpen) {
                    onStatusChange("P2P connected, ready to cast")
                }
            }

            override fun onBufferedAmountChange(amount: Long) {}

            override fun onMessage(buffer: DataChannel.Buffer) {
                handleDataChannelMessage(buffer)
            }
        })
        Log.d(TAG, "DataChannel created")
        SessionTraceRecorder.record(TAG, "DataChannel created")
    }

    private fun createAndSendOffer() {
        if (isCleanupInProgress) return
        SessionTraceRecorder.record(TAG, "createAndSendOffer")
        // Detect if an audio track is currently attached (Phase 2 — after addTrack).
        // When it exists, omit OfferToReceiveAudio so WebRTC includes the m=audio
        // line with correct sendonly direction derived from the transceiver itself.
        // Do not call PeerConnection.getSenders() here. The WebRTC Java wrapper disposes its
        // previously returned RtpSender wrappers whenever getSenders() is called, which also
        // invalidates the sender retained for stats and for the next capture session.
        val hasAudioSender = audioSender != null && audioTrack != null

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            // Only suppress audio in the initial Phase-1 offer when no audio sender exists.
            if (!hasAudioSender) {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val hasVideo = sdp.description.contains("m=video")
                val hasAudio = sdp.description.contains("m=audio")
                val hasData = sdp.description.contains("m=application")
                Log.d(TAG, "=== OFFER CREATED === video=$hasVideo audio=$hasAudio data=$hasData sdpLen=${sdp.description.length}")
                SessionTraceRecorder.record(
                    TAG,
                    "offer created video=$hasVideo audio=$hasAudio data=$hasData sdpLen=${sdp.description.length}"
                )

                // Extract and log audio m-line details for debugging.
                if (hasAudio) {
                    val audioLineStart = sdp.description.indexOf("m=audio")
                    val audioLineEnd = sdp.description.indexOf("\r\n", audioLineStart)
                    val audioLine = if (audioLineEnd > audioLineStart) {
                        sdp.description.substring(audioLineStart, audioLineEnd)
                    } else "m=audio line not found"
                    Log.d(TAG, "Audio m-line: $audioLine")

                    // Find audio codec info (a=rtpmap lines after m=audio)
                    val afterAudioLine = sdp.description.substring(audioLineStart)
                    val rtpMapLines = afterAudioLine.take(500).split("\r\n").filter { it.startsWith("a=rtpmap:") && it.contains("audio/") }
                    Log.d(TAG, "Audio rtpmap: ${rtpMapLines.take(3).joinToString(" | ")}")
                }

                // Warn if audio track exists but m=audio is missing from SDP.
                if (hasAudioSender && !hasAudio) {
                    Log.w(TAG, "AUDIO TRACK EXISTS but m=audio MISSING from SDP — INVESTIGATE")
                }

                peerConnection?.setLocalDescription(NoOpSdpObserver("setLocalOffer"), sdp)

                val sentViaDataChannel = if (isDataChannelOpen) {
                    trySendSignalViaDataChannel("offer", sdp.description)
                } else {
                    false
                }
                if (sentViaDataChannel) {
                    Log.d(TAG, "Offer sent via DataChannel")
                    SessionTraceRecorder.record(TAG, "offer sent via DataChannel")
                } else {
                    signalingClient.sendOffer(sdp.description)
                    Log.d(TAG, "Offer sent via WebSocket (fallback)")
                    SessionTraceRecorder.record(TAG, "offer sent via WebSocket")
                }
                onStatusChange("Offer sent")
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create offer failed: $error")
                SessionTraceRecorder.record(TAG, "offer create failed: $error")
                onStatusChange("Create offer failed")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    // ── Phase 2: Screen Capture + Renegotiate ──

    @Suppress("DEPRECATION")
    private fun readDefaultDisplaySize(): Pair<Int, Int> {
        val manager = captureDisplayManager
            ?: context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = DisplayMetrics()
        manager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return metrics.widthPixels to metrics.heightPixels
        }

        val fallback = context.resources.displayMetrics
        return fallback.widthPixels to fallback.heightPixels
    }

    private fun registerCaptureDisplayListener() {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        synchronized(captureResizeLock) {
            if (isCaptureDisplayListenerRegistered) return
            captureDisplayManager = manager
            isCaptureDisplayListenerRegistered = true
        }
        try {
            manager.registerDisplayListener(captureDisplayListener, captureDisplayHandler)
            SessionTraceRecorder.record(TAG, "capture display listener registered")
        } catch (e: Exception) {
            synchronized(captureResizeLock) {
                isCaptureDisplayListenerRegistered = false
                captureDisplayManager = null
            }
            Log.w(TAG, "Failed to register capture display listener", e)
            SessionTraceRecorder.record(TAG, "capture display listener failed: ${e.message}")
        }
    }

    private fun unregisterCaptureDisplayListener() {
        val manager = synchronized(captureResizeLock) {
            if (!isCaptureDisplayListenerRegistered) return
            isCaptureDisplayListenerRegistered = false
            captureDisplayManager.also { captureDisplayManager = null }
        }
        try {
            manager?.unregisterDisplayListener(captureDisplayListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister capture display listener", e)
        }
    }

    private fun updateScreenCaptureSize(width: Int, height: Int, reason: String) {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Ignoring invalid capture size ${width}x$height ($reason)")
            return
        }

        synchronized(captureResizeLock) {
            if (isCleanupInProgress || (width == captureWidth && height == captureHeight)) return
            if (!isCaptureFormatReady) {
                pendingCaptureWidth = width
                pendingCaptureHeight = height
                pendingCaptureReason = reason
                Log.d(TAG, "Deferring capture resize to ${width}x$height ($reason)")
                return
            }
            val capturer = screenCapturer ?: return
            val source = videoSource ?: return

            try {
                resizeVirtualDisplayInPlace(capturer, width, height)
                source.adaptOutputFormat(width, height, VIDEO_CAPTURE_FPS)
                captureWidth = width
                captureHeight = height
                Log.i(TAG, "Screen capture resized to ${width}x${height}@${VIDEO_CAPTURE_FPS}fps ($reason)")
                SessionTraceRecorder.record(
                    TAG,
                    "screen capture resized ${width}x${height}@${VIDEO_CAPTURE_FPS}fps reason=$reason"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resize screen capture to ${width}x$height ($reason)", e)
                SessionTraceRecorder.record(
                    TAG,
                    "screen capture resize failed ${width}x$height reason=$reason: ${e.message}"
                )
            }
        }
    }

    /**
     * WebRTC 1.3.9 implements [ScreenCapturerAndroid.changeCaptureFormat] by releasing and
     * recreating its VirtualDisplay. Android 14+ only permits one VirtualDisplay for each screen
     * capture consent token, so that implementation terminates MediaProjection during rotation.
     *
     * Newer upstream WebRTC resizes the existing VirtualDisplay on Android 12+. Keep the same
     * behavior locally while this project remains pinned to 1.3.9.
     */
    private fun resizeVirtualDisplayInPlace(
        capturer: ScreenCapturerAndroid,
        width: Int,
        height: Int
    ) {
        val helper = surfaceTextureHelper
            ?: throw IllegalStateException("SurfaceTextureHelper is unavailable")
        val capturerClass = ScreenCapturerAndroid::class.java
        val virtualDisplayField = capturerClass.getDeclaredField("virtualDisplay").apply {
            isAccessible = true
        }
        val widthField = capturerClass.getDeclaredField("width").apply { isAccessible = true }
        val heightField = capturerClass.getDeclaredField("height").apply { isAccessible = true }

        widthField.setInt(capturer, width)
        heightField.setInt(capturer, height)

        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler, Runnable {
            val virtualDisplay = virtualDisplayField.get(capturer) as? VirtualDisplay
                ?: throw IllegalStateException("Screen capture VirtualDisplay is unavailable")
            helper.setTextureSize(width, height)
            virtualDisplay.resize(width, height, VIRTUAL_DISPLAY_DPI)
            virtualDisplay.surface = Surface(helper.surfaceTexture)
        })
    }

    private fun detachScreenCapturer(): ScreenCapturerAndroid? =
        synchronized(captureResizeLock) {
            screenCapturer.also {
                screenCapturer = null
                captureWidth = 0
                captureHeight = 0
                isCaptureFormatReady = false
                pendingCaptureWidth = 0
                pendingCaptureHeight = 0
                pendingCaptureReason = ""
                receivedCapturedContentSize = false
            }
        }

    fun startScreenCapture(data: Intent) {
        if (isCleanupInProgress) {
            Log.w(TAG, "startScreenCapture ignored: cleanup in progress")
            return
        }
        if (isCasting) {
            Log.i(TAG, "startScreenCapture ignored: capture is already active")
            return
        }
        SessionTraceRecorder.record(TAG, "startScreenCapture begin")
        // Reset audio injection debug counters
        injectionFrameCount = 0
        emptyInjectionCount = 0
        audioDataCallbackCount = 0
        receivedCapturedContentSize = false

        try {
            val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    SessionTraceRecorder.record(TAG, "MediaProjection stopped by system")
                    Handler(Looper.getMainLooper()).post {
                        if (isCasting && !isCleanupInProgress) {
                            stopScreenCaptureInternal(stoppedBySystem = true)
                        }
                    }
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width <= 0 || height <= 0) return
                    receivedCapturedContentSize = true
                    updateScreenCaptureSize(width, height, "captured content changed")
                }
            })
            synchronized(captureResizeLock) {
                screenCapturer = capturer
            }

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread", eglBase.eglBaseContext
            )

            val source = peerConnectionFactory.createVideoSource(true)
            videoSource = source
            capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
            val (width, height) = readDefaultDisplaySize()
            synchronized(captureResizeLock) {
                captureWidth = width
                captureHeight = height
                isCaptureFormatReady = false
            }
            source.adaptOutputFormat(width, height, VIDEO_CAPTURE_FPS)
            capturer.startCapture(width, height, VIDEO_CAPTURE_FPS)
            val pendingResize = synchronized(captureResizeLock) {
                isCaptureFormatReady = true
                if (pendingCaptureWidth > 0 && pendingCaptureHeight > 0) {
                    Triple(pendingCaptureWidth, pendingCaptureHeight, pendingCaptureReason).also {
                        pendingCaptureWidth = 0
                        pendingCaptureHeight = 0
                        pendingCaptureReason = ""
                    }
                } else {
                    null
                }
            }
            pendingResize?.let { (pendingWidth, pendingHeight, pendingReason) ->
                updateScreenCaptureSize(pendingWidth, pendingHeight, pendingReason)
            }
            registerCaptureDisplayListener()
            Log.d(TAG, "Screen capture at ${width}x${height}@${VIDEO_CAPTURE_FPS}fps")
            SessionTraceRecorder.record(
                TAG,
                "screen capture started ${width}x${height}@${VIDEO_CAPTURE_FPS}fps"
            )

            // Start system-audio capture from the same MediaProjection instance used by video.
            // Android 14+ rejects creating a second projection from the same consent result.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val capture = SystemAudioCapture(context)
                val sharedProjection = capturer.mediaProjection
                val started = sharedProjection != null && capture.start(
                    mediaProjection = sharedProjection,
                    sampleRate = AUDIO_SAMPLE_RATE_HZ,
                    channelCount = AUDIO_CHANNEL_COUNT // Mono to match WebRTC input configuration
                )
                if (started) {
                    systemAudioCapture = capture
                    Log.d(TAG, "SystemAudioCapture wired to audio injection callback")
                    Log.d(TAG, "System playback capture enabled (mono 48kHz, 960 bytes/frame)")
                    SessionTraceRecorder.record(TAG, "system audio capture started")
                    onStatusChange("Audio mode: system playback capture")
                } else {
                    capture.stop()
                    systemAudioCapture = null
                    val reason = capture.lastError ?: "unavailable"
                    onStatusChange("Audio mode: silence (system playback unavailable)")
                    Log.w(TAG, "System playback capture unavailable; microphone remains disabled: $reason")
                    SessionTraceRecorder.record(TAG, "system audio unavailable: $reason")
                }
            } else {
                onStatusChange("Audio mode: silence (Android 10+ required for system audio)")
                SessionTraceRecorder.record(TAG, "system audio unavailable: Android 10+ required")
            }

            videoTrack = peerConnectionFactory.createVideoTrack("screen_track", videoSource).apply {
                setEnabled(true)
            }

            Log.d(TAG, "=== Creating audioSource and audioTrack ===")
            audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            audioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource).apply {
                setEnabled(true)
            }
            Log.d(TAG, "audioSource and audioTrack created, audioTrack.enabled=true")
            Log.d(TAG, "systemAudioCapture=${systemAudioCapture != null}, audioSource=${audioSource != null}")
            SessionTraceRecorder.record(
                TAG,
                "audio track created systemAudioCapture=${systemAudioCapture != null} audioSource=${audioSource != null}"
            )

            // Stream ID is critical: the receiver uses it to group tracks into a MediaStream
            // and bind it to the video player. Without it, ontrack.streams may be empty.
            val streamId = listOf("screen-share")
            val currentVideoTrack = checkNotNull(videoTrack)
            val currentAudioTrack = checkNotNull(audioTrack)
            videoSender = videoSender?.also { sender ->
                check(sender.setTrack(currentVideoTrack, false)) {
                    "Failed to attach the new video track to the existing sender"
                }
            } ?: peerConnection?.addTrack(currentVideoTrack, streamId)
            audioSender = audioSender?.also { sender ->
                check(sender.setTrack(currentAudioTrack, false)) {
                    "Failed to attach the new audio track to the existing sender"
                }
            } ?: peerConnection?.addTrack(currentAudioTrack, streamId)
            Log.d(
                TAG,
                "Tracks added to PeerConnection with streamId=$streamId " +
                    "videoSender=${videoSender?.id()} audioSender=${audioSender?.id()}"
            )
            SessionTraceRecorder.record(
                TAG,
                "tracks added videoSender=${videoSender?.id()} audioSender=${audioSender?.id()}"
            )

            isCasting = true
            muteLocalPlaybackForCasting()
            startVideoStatsPolling()
            onStatusChange("Screen capture started, renegotiating...")
            SessionTraceRecorder.record(TAG, "screen capture active, renegotiating")

            createAndSendOffer()
        } catch (e: Exception) {
            Log.e(TAG, "startScreenCapture failed", e)
            SessionTraceRecorder.record(TAG, "startScreenCapture failed: ${e.message}")
            restoreLocalPlaybackAfterCasting()
            onStatusChange("Screen capture failed: ${e.message}")
            throw e
        }
    }

    fun stopScreenCapture() {
        stopScreenCaptureInternal(stoppedBySystem = false)
    }

    private fun stopScreenCaptureInternal(stoppedBySystem: Boolean) {
        SessionTraceRecorder.record(TAG, "stopScreenCapture")
        // Set this before stopping the capturer because stopCapture() invokes MediaProjection.onStop.
        // The callback must not enter this cleanup routine a second time.
        isCasting = false
        stopVideoStatsPolling()
        unregisterCaptureDisplayListener()
        val capturer = detachScreenCapturer()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        systemAudioCapture?.stop()
        systemAudioCapture = null

        // Keep the same RTP senders/transceivers across repeated capture sessions.
        // removeTrack()+addTrack() grows Unified Plan SDP with inactive m-lines, and querying
        // PeerConnection.senders disposes the retained Java RtpSender wrapper.
        try { videoSender?.setTrack(null, false) } catch (e: Exception) {
            Log.w(TAG, "Failed to detach video track: ${e.message}")
        }
        try { audioSender?.setTrack(null, false) } catch (e: Exception) {
            Log.w(TAG, "Failed to detach audio track: ${e.message}")
        }

        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { audioTrack?.dispose() } catch (_: Exception) {}
        audioTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null

        restoreLocalPlaybackAfterCasting()
        lastRemoteAnswerSdp = null
        val status = if (stoppedBySystem) "Screen capture stopped" else "Casting stopped"
        onStatusChange(status)
        SessionTraceRecorder.record(TAG, status.lowercase())

        createAndSendOffer()
    }

    // ── Remote SDP/ICE handling ──

    private fun handleRemoteOffer(sdp: String) {
        Log.d(TAG, "Handling remote offer")
        SessionTraceRecorder.record(TAG, "remote offer received sdpLen=${sdp.length}")
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(NoOpSdpObserver("setRemoteOffer"), remoteDesc)

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(NoOpSdpObserver("setLocalAnswer"), sdp)
                SessionTraceRecorder.record(TAG, "local answer created sdpLen=${sdp.description.length}")
                val sentViaDataChannel = if (isDataChannelOpen) {
                    trySendSignalViaDataChannel("answer", sdp.description)
                } else {
                    false
                }
                if (!sentViaDataChannel) {
                    signalingClient.sendAnswer(sdp.description)
                    SessionTraceRecorder.record(TAG, "answer sent via WebSocket")
                } else {
                    SessionTraceRecorder.record(TAG, "answer sent via DataChannel")
                }
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create answer failed: $error")
                SessionTraceRecorder.record(TAG, "answer create failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    private fun handleRemoteAnswer(sdp: String) {
        Log.d(TAG, "=== ANSWER RECEIVED === sdp length=${sdp.length}")
        SessionTraceRecorder.record(TAG, "remote answer received sdpLen=${sdp.length}")
        // Log audio configuration in the answer.
        val hasAudio = sdp.contains("m=audio")
        val audioLineStart = if (hasAudio) sdp.indexOf("m=audio") else -1
        val audioLineEnd = if (audioLineStart >= 0) sdp.indexOf("\r\n", audioLineStart) else -1
        if (hasAudio && audioLineEnd > audioLineStart) {
            Log.d(TAG, "Answer audio m-line: ${sdp.substring(audioLineStart, audioLineEnd)}")
        } else {
            Log.d(TAG, "Answer has NO audio m-line!")
        }
        if (lastRemoteAnswerSdp == sdp) {
            Log.d(TAG, "Duplicate answer ignored: same SDP")
            return
        }
        val signalingState = peerConnection?.signalingState()
        if (signalingState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.w(TAG, "Answer ignored due to signaling state=$signalingState")
            SessionTraceRecorder.record(TAG, "answer ignored signalingState=$signalingState")
            return
        }
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "=== ANSWER SET SUCCESS === isCasting=$isCasting")
                SessionTraceRecorder.record(TAG, "remote answer set success isCasting=$isCasting")
                lastRemoteAnswerSdp = sdp
                if (isCasting) {
                    onStatusChange("Casting")
                } else {
                    onStatusChange("P2P connected, ready to cast")
                }
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "=== ANSWER SET FAILED === $error")
                SessionTraceRecorder.record(TAG, "remote answer set failed: $error")
                onStatusChange("Answer failed: $error")
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, remoteDesc)
    }

    private fun handleRemoteIceCandidate(event: NearHubEvent.IceCandidateReceived) {
        val candidate = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
        SessionTraceRecorder.record(TAG, "remote ICE candidate mid=${event.sdpMid} index=${event.sdpMLineIndex}")
        peerConnection?.addIceCandidate(candidate)
    }

    // ── DataChannel ──

    /**
     * Send WebRTC signal via DataChannel (NearHub Part 2 protocol).
     * Format: { "action": "webrtc-signal", "data": { "type": "offer", "payload": { "type": "offer", "sdp": "..." } } }
     * Returns false if DataChannel is not open (caller should fallback to WebSocket).
     */
    private fun trySendSignalViaDataChannel(type: String, sdp: String): Boolean {
        if (!isDataChannelOpen) return false
        return try {
            SessionTraceRecorder.record(TAG, "send $type via DataChannel")
            val msg = JSONObject().apply {
                put("action", "webrtc-signal")
                put("data", JSONObject().apply {
                    put("type", type)
                    put("payload", JSONObject().apply {
                        put("type", type)
                        put("sdp", sdp)
                    })
                })
            }
            sendDataChannelMessage(msg)
        } catch (e: Exception) {
            Log.w(TAG, "DataChannel signal send failed, will fallback to WebSocket", e)
            SessionTraceRecorder.record(TAG, "send $type via DataChannel failed: ${e.message}")
            false
        }
    }

    fun sendLeaveViaDataChannel() {
        if (!isDataChannelOpen) return
        try {
            val msg = JSONObject().apply {
                put("action", "leave")
                put("data", JSONObject().apply {
                    put("reason", "user_stop")
                    put("timestamp", System.currentTimeMillis())
                })
            }
            sendDataChannelMessage(msg)
        } catch (e: Exception) {
            Log.w(TAG, "DataChannel leave send failed", e)
        }
    }

    private fun sendDataChannelMessage(json: JSONObject): Boolean {
        val buffer = DataChannel.Buffer(
            ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8)), false
        )
        return dataChannel?.send(buffer) ?: false
    }

    private fun handleDataChannelMessage(buffer: DataChannel.Buffer) {
        if (buffer.binary) return
        val bytes = ByteArray(buffer.data.remaining())
        buffer.data.get(bytes)
        val text = String(bytes, Charsets.UTF_8)
        Log.d(TAG, "DataChannel IN: ${text.take(500)}")

        try {
            val json = JSONObject(text)
            when (json.optString("action")) {
                "webrtc-signal" -> {
                    val data = json.getJSONObject("data")
                    val type = data.optString("type")
                    // Support both formats:
                    //   New: data.payload.sdp
                    //   Old: data.sdp (without payload wrapper)
                    val payload = data.optJSONObject("payload") ?: data
                    Log.d(TAG, "DataChannel webrtc-signal type=$type")

                    when (type) {
                        "offer" -> handleRemoteOffer(payload.getString("sdp"))
                        "answer" -> handleRemoteAnswer(payload.getString("sdp"))
                        "ice-candidate" -> {
                            val candidateObj = if (payload.has("candidate") && payload.optJSONObject("candidate") != null) {
                                payload.getJSONObject("candidate")
                            } else {
                                payload
                            }
                            handleRemoteIceCandidate(NearHubEvent.IceCandidateReceived(
                                candidate = candidateObj.getString("candidate"),
                                sdpMid = candidateObj.optString("sdpMid", "0"),
                                sdpMLineIndex = candidateObj.optInt("sdpMLineIndex", 0),
                                userId = ""
                            ))
                        }
                        "ice-restart" -> {
                            Log.d(TAG, "Received ice-restart via DataChannel")
                        }
                    }
                }
                "leave" -> {
                    val data = json.optJSONObject("data")
                    val reason = data?.optString("reason", "") ?: ""
                    Log.d(TAG, "DataChannel leave: $reason")
                    onStatusChange("Disconnected: $reason")
                }
                else -> {
                    Log.d(TAG, "DataChannel unknown action: ${json.optString("action")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DataChannel message: ${e.message}", e)
        }
    }

    // ── PeerConnection observer ──

    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "Local ICE candidate: ${candidate.sdp.take(50)}")
            SessionTraceRecorder.record(TAG, "local ICE candidate mid=${candidate.sdpMid} index=${candidate.sdpMLineIndex}")
            signalingClient.sendIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE connection state: $state")
            SessionTraceRecorder.record(TAG, "ICE state=$state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    if (isCasting) {
                        logAudioTransceiverStatus()
                        requestVideoStatsSnapshot("ice-connected")
                        onStatusChange("Casting")
                    }
                    else onStatusChange("P2P connected, ready to cast")
                }
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    onStatusChange("P2P disconnected")
                PeerConnection.IceConnectionState.FAILED ->
                    onStatusChange("P2P connection failed")
                else -> {}
            }
        }

        override fun onDataChannel(channel: DataChannel) {
            Log.d(TAG, "Remote DataChannel received: ${channel.label()}")
            SessionTraceRecorder.record(TAG, "remote DataChannel received label=${channel.label()}")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    /**
     * Diagnostic method to log PeerConnection audio transceiver status.
     * Called when ICE CONNECTED to verify audio track is properly configured.
     */
    private fun logAudioTransceiverStatus() {
        try {
            val pc = peerConnection ?: return
            val transceivers = pc.transceivers
            Log.d(TAG, "=== Audio Transceiver Status ===")
            Log.d(TAG, "Total transceivers: ${transceivers.size}")
            transceivers.forEachIndexed { index, transceiver ->
                val trackId = try { transceiver.sender.track()?.id() } catch (_: Exception) { "disposed" }
                val receiverTrackId = try { transceiver.receiver.track()?.id() } catch (_: Exception) { "disposed" }
                Log.d(TAG, "  [$index] mediaType=${transceiver.mediaType}, " +
                        "direction=${transceiver.direction}, " +
                        "sender.track=$trackId, " +
                        "receiver.track=$receiverTrackId")
            }
            val audioSenderTrackId = try { audioSender?.track()?.id() } catch (_: Exception) { "disposed" }
            Log.d(TAG, "Audio sender trackId=${audioSenderTrackId ?: "none"}")
            Log.d(TAG, "AudioRecordDataCallback count: $audioDataCallbackCount")
            Log.d(TAG, "Audio injection frames: $injectionFrameCount")
            Log.d(TAG, "Audio empty callbacks: $emptyInjectionCount")
            Log.d(TAG, "================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging audio transceiver status", e)
        }
    }

    private fun startVideoStatsPolling() {
        if (videoStatsPollingJob?.isActive == true) return

        videoStatsPollingJob = scope.launch {
            while (isActive) {
                requestVideoStatsSnapshot("poll")
                delay(2_000)
            }
        }
        Log.d(TAG, "Video stats polling started")
    }

    private fun stopVideoStatsPolling() {
        videoStatsPollingJob?.cancel()
        videoStatsPollingJob = null
    }

    private fun requestVideoStatsSnapshot(trigger: String) {
        if (isCleanupInProgress) return
        val pc = peerConnection ?: return
        val sender = videoSender ?: return

        val snapshotId = ++videoStatsSnapshotCount
        try {
            pc.getStats(sender, object : RTCStatsCollectorCallback {
                override fun onStatsDelivered(report: RTCStatsReport) {
                    logVideoStatsReport(trigger, snapshotId, report)
                }
            })
        } catch (e: IllegalStateException) {
            Log.d(TAG, "Video stats[$trigger#$snapshotId]: sender already disposed, skipping")
            SessionTraceRecorder.record(TAG, "video stats skipped sender disposed trigger=$trigger")
        } catch (e: Exception) {
            Log.w(TAG, "Video stats[$trigger#$snapshotId]: getStats failed", e)
            SessionTraceRecorder.record(TAG, "video stats failed trigger=$trigger: ${e.message}")
        }
    }

    private fun logVideoStatsReport(trigger: String, snapshotId: Int, report: RTCStatsReport) {
        try {
            val stats = report.statsMap.values
            val videoSource = stats.firstOrNull { it.type == "media-source" && it.isVideoStats() }
            val outboundVideo = stats.firstOrNull { it.type == "outbound-rtp" && it.isVideoStats() }

            if (videoSource == null && outboundVideo == null) {
                Log.d(TAG, "Video stats[$trigger#$snapshotId]: no video entries (${stats.size} stats)")
                return
            }

            val sourceText = videoSource?.let {
                val width = it.doubleMember("width")?.toInt()
                val height = it.doubleMember("height")?.toInt()
                val fps = it.doubleMember("framesPerSecond")
                val frames = it.longMember("frames")
                "source=${width ?: "?"}x${height ?: "?"}@${fps ?: "?"}fps frames=${frames ?: "?"}"
            } ?: "source=none"

            val outboundText = outboundVideo?.let {
                val frameWidth = it.doubleMember("frameWidth")?.toInt()
                val frameHeight = it.doubleMember("frameHeight")?.toInt()
                val fps = it.doubleMember("framesPerSecond")
                val framesSent = it.longMember("framesSent")
                val framesEncoded = it.longMember("framesEncoded")
                val bytesSent = it.longMember("bytesSent")
                val bitrate = it.doubleMember("targetBitrate")?.toLong()
                val encoder = it.stringMember("encoderImplementation")
                val qlr = it.stringMember("qualityLimitationReason")
                "outbound=${frameWidth ?: "?"}x${frameHeight ?: "?"}@${fps ?: "?"}fps " +
                    "encoded=${framesEncoded ?: "?"} sent=${framesSent ?: "?"} bytes=${bytesSent ?: "?"} " +
                    "bitrate=${bitrate ?: "?"} qlr=${qlr ?: "?"} encoder=${encoder ?: "?"}"
            } ?: "outbound=none"

            Log.d(TAG, "Video stats[$trigger#$snapshotId]: $sourceText | $outboundText")
        } catch (e: Exception) {
            Log.w(TAG, "Video stats[$trigger#$snapshotId] parsing failed", e)
        }
    }

    private fun RTCStats.isVideoStats(): Boolean {
        val kind = stringMember("kind") ?: stringMember("mediaType")
        if (kind == "video") return true
        val trackId = stringMember("trackIdentifier")
        return trackId != null && trackId == videoTrack?.id()
    }

    private fun RTCStats.stringMember(name: String): String? {
        val value = members[name] ?: return null
        return value.toString().takeIf { it.isNotBlank() }
    }

    private fun RTCStats.doubleMember(name: String): Double? {
        val value = members[name] ?: return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun RTCStats.longMember(name: String): Long? = doubleMember(name)?.toLong()

    // ── Cleanup ──

    private fun cleanupP2P() {
        if (isCleanupInProgress) return
        isCleanupInProgress = true
        SessionTraceRecorder.record(TAG, "cleanupP2P begin")

        isCasting = false
        stopVideoStatsPolling()
        restoreLocalPlaybackAfterCasting()
        isDataChannelOpen = false
        lastRemoteAnswerSdp = null
        unregisterCaptureDisplayListener()
        val capturer = detachScreenCapturer()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        systemAudioCapture?.stop()
        systemAudioCapture = null
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { audioTrack?.dispose() } catch (_: Exception) {}
        audioTrack = null
        videoSender = null
        audioSender = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null
        SessionTraceRecorder.record(TAG, "cleanupP2P end")
    }

    /**
     * Do not change stream volume during casting.
     * Playback capture already gives us the system audio PCM; touching media volume
     * can cause device-specific loudness swings during debug.
     */
    private fun muteLocalPlaybackForCasting() {
        if (mutedLocalPlaybackForCasting) return
        mutedLocalPlaybackForCasting = true
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "Local playback mute skipped during casting: streamVolume=$current")
        SessionTraceRecorder.record(TAG, "local playback mute skipped volume=$current")
    }

    private fun restoreLocalPlaybackAfterCasting() {
        if (!mutedLocalPlaybackForCasting) return
        try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d(TAG, "Local playback restore skipped after casting: streamVolume=$current")
            SessionTraceRecorder.record(TAG, "local playback restore skipped volume=$current")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore local playback volume", e)
            SessionTraceRecorder.record(TAG, "local playback restore failed: ${e.message}")
        } finally {
            mutedLocalPlaybackForCasting = false
        }
    }

    fun stop() {
        SessionTraceRecorder.record(TAG, "stop")
        sendLeaveViaDataChannel()
        signalingClient.sendPeerLeave()
        cleanupP2P()
        signalingClient.dispose()
        audioDeviceModule.release()
        scope.cancel()
    }

    private class NoOpSdpObserver(private val label: String = "") : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {
            Log.d(TAG, "SDP $label success")
        }
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "SDP $label create failed: $error")
        }
        override fun onSetFailure(error: String) {
            Log.e(TAG, "SDP $label set failed: $error")
        }
    }
}
