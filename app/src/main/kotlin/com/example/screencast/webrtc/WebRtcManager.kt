package com.example.screencast.webrtc

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

private const val TAG = "WebRtcManager"
private const val AUDIO_SAMPLE_RATE_HZ = 48_000
private const val AUDIO_CHANNEL_COUNT = 1

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
    private val onStatusChange: (String) -> Unit
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
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var systemAudioCapture: SystemAudioCapture? = null
    private var lastRemoteAnswerSdp: String? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mutedLocalPlaybackForCasting = false
    private var previousMusicStreamVolume: Int? = null
    private var shouldRestoreMusicVolumeAfterCasting = false

    private var injectionFrameCount = 0
    private var emptyInjectionCount = 0
    private var samplesReadyFrameCount = 0
    private var audioDataCallbackCount = 0

    @Volatile
    private var isCleanupInProgress = false

    var isCasting = false
        private set

    private val rtcConfig = PeerConnection.RTCConfiguration(
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    init {
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
                        onStatusChange("Joined room, establishing P2P...")
                        connectP2P()
                    }
                    is NearHubEvent.OfferReceived -> handleRemoteOffer(event.sdp)
                    is NearHubEvent.AnswerReceived -> handleRemoteAnswer(event.sdp)
                    is NearHubEvent.IceCandidateReceived -> handleRemoteIceCandidate(event)
                    is NearHubEvent.Restored -> {
                        if (event.needsRenegotiation) {
                            onStatusChange("Session restored, renegotiating...")
                            createAndSendOffer()
                        }
                    }
                    is NearHubEvent.PeerLeave -> {
                        onStatusChange("Removed from room")
                        cleanupP2P()
                    }
                    is NearHubEvent.RoomClosed -> {
                        onStatusChange("Room closed")
                        cleanupP2P()
                    }
                    is NearHubEvent.ServerError -> {
                        onStatusChange("Server error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun createAudioDeviceModule(): JavaAudioDeviceModule {
        // System audio is injected on the direct AudioRecord ByteBuffer via AudioRecordDataCallback,
        // wired in app-local WebRtcAudioRecord (see patchStreamWebrtcAar + org/webrtc/audio sources).
        val builder = JavaAudioDeviceModule.builder(context)
            .setInputSampleRate(AUDIO_SAMPLE_RATE_HZ)
            .setUseStereoInput(false)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordDataCallback { audioFormat, channelCount, sampleRate, audioBuffer ->
                audioDataCallbackCount++
                if (audioDataCallbackCount % 500 == 0) {
                    Log.d(
                        TAG,
                        "AudioRecordDataCallback: $audioDataCallbackCount calls, cap=${audioBuffer.capacity()}, " +
                            "pos=${audioBuffer.position()}, lim=${audioBuffer.limit()}, ch=$channelCount " +
                            "rate=$sampleRate fmt=$audioFormat"
                    )
                }
                systemAudioCapture?.let { capture ->
                    val cap = audioBuffer.capacity()
                    val bytes = ByteArray(cap)
                    val hasSystemAudio = capture.fillAudioBuffer(bytes)
                    if (hasSystemAudio) {
                        audioBuffer.clear()
                        audioBuffer.put(bytes)
                        audioBuffer.flip()
                        injectionFrameCount++
                        if (injectionFrameCount % 500 == 0) {
                            Log.d(TAG, "System audio injected: $injectionFrameCount frames")
                        }
                    } else {
                        emptyInjectionCount++
                        if (emptyInjectionCount % 500 == 0) {
                            Log.w(TAG, "AudioRecordDataCallback: queue empty ($emptyInjectionCount), keeping mic")
                        }
                    }
                }
            }
            .setSamplesReadyCallback { samples ->
                samplesReadyFrameCount++
                if (samplesReadyFrameCount % 500 == 0) {
                    Log.d(
                        TAG,
                        "SamplesReadyCallback(diag): ${samplesReadyFrameCount} frames, ${samples.data.size} bytes, " +
                            "ch=${samples.channelCount} rate=${samples.sampleRate} fmt=${samples.audioFormat}"
                    )
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

        Log.d(TAG, "AudioDeviceModule configured with AudioRecordDataCallback injection (patched WebRtcAudioRecord)")

        return builder.createAudioDeviceModule()
    }

    // ── Phase 1: P2P + DataChannel (no media) ──

    private fun connectP2P() {
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
    }

    private fun setupDataChannel() {
        val config = DataChannel.Init().apply { ordered = true }
        dataChannel = peerConnection?.createDataChannel("control", config)
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                val state = dataChannel?.state()
                isDataChannelOpen = state == DataChannel.State.OPEN
                Log.d(TAG, "DataChannel state: $state")
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
    }

    private fun createAndSendOffer() {
        // Detect if an audio track already exists among the senders (Phase 2 — after addTrack).
        // When it exists, omit OfferToReceiveAudio so WebRTC includes the m=audio
        // line with correct sendonly direction derived from the transceiver itself.
        val hasAudioSender = peerConnection?.senders?.any {
            it.track() is AudioTrack
        } == true

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
                } else {
                    signalingClient.sendOffer(sdp.description)
                    Log.d(TAG, "Offer sent via WebSocket (fallback)")
                }
                onStatusChange("Offer sent")
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create offer failed: $error")
                onStatusChange("Create offer failed")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    // ── Phase 2: Screen Capture + Renegotiate ──

    fun startScreenCapture(resultCode: Int, data: Intent) {
        // Reset audio injection debug counters
        injectionFrameCount = 0
        emptyInjectionCount = 0
        samplesReadyFrameCount = 0
        audioDataCallbackCount = 0

        try {
            screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    onStatusChange("Screen capture stopped")
                }
            })

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread", eglBase.eglBaseContext
            )

            videoSource = peerConnectionFactory.createVideoSource(true).also { source ->
                screenCapturer!!.initialize(surfaceTextureHelper, context, source.capturerObserver)
                val dm = context.resources.displayMetrics
                val width = dm.widthPixels
                val height = dm.heightPixels
                screenCapturer!!.startCapture(width, height, 30)
                Log.d(TAG, "Screen capture at ${width}x${height}@30fps")
            }

            // Start system-audio capture only after screen capture is up.
            // On newer Android versions, reusing the same consent intent can be rejected.
            // If system-audio setup fails, keep casting with microphone fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val capture = SystemAudioCapture(context)
                val started = capture.start(
                    resultCode = resultCode,
                    data = Intent(data),
                    sampleRate = AUDIO_SAMPLE_RATE_HZ,
                    channelCount = AUDIO_CHANNEL_COUNT // Mono to match WebRTC's AudioRecord configuration
                )
                if (started) {
                    systemAudioCapture = capture
                    Log.d(TAG, "SystemAudioCapture wired to audio injection callback")
                    Log.d(TAG, "System playback capture enabled (mono 48kHz, 960 bytes/frame)")
                    onStatusChange("Audio mode: system playback capture")
                } else {
                    capture.stop()
                    systemAudioCapture = null
                    val reason = capture.lastError ?: "unavailable"
                    onStatusChange("Audio mode: fallback to microphone ($reason)")
                    Log.w(TAG, "System playback capture unavailable, fallback to microphone")
                }
            } else {
                onStatusChange("Audio mode: microphone (Android 10+ required for system audio)")
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

            // Stream ID is critical: the receiver uses it to group tracks into a MediaStream
            // and bind it to the video player. Without it, ontrack.streams may be empty.
            val streamId = listOf("screen-share")
            videoTrack?.let { peerConnection?.addTrack(it, streamId) }
            audioTrack?.let { peerConnection?.addTrack(it, streamId) }
            Log.d(TAG, "Tracks added to PeerConnection with streamId=$streamId")

            isCasting = true
            muteLocalPlaybackForCasting()
            onStatusChange("Screen capture started, renegotiating...")

            createAndSendOffer()
        } catch (e: Exception) {
            Log.e(TAG, "startScreenCapture failed", e)
            restoreLocalPlaybackAfterCasting()
            onStatusChange("Screen capture failed: ${e.message}")
            throw e
        }
    }

    fun stopScreenCapture() {
        try { screenCapturer?.stopCapture() } catch (_: Exception) {}
        try { screenCapturer?.dispose() } catch (_: Exception) {}
        screenCapturer = null
        systemAudioCapture?.stop()
        systemAudioCapture = null

        peerConnection?.senders?.forEach { sender ->
            peerConnection?.removeTrack(sender)
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

        isCasting = false
        restoreLocalPlaybackAfterCasting()
        lastRemoteAnswerSdp = null
        onStatusChange("Casting stopped")

        createAndSendOffer()
    }

    // ── Remote SDP/ICE handling ──

    private fun handleRemoteOffer(sdp: String) {
        Log.d(TAG, "Handling remote offer")
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(NoOpSdpObserver("setRemoteOffer"), remoteDesc)

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(NoOpSdpObserver("setLocalAnswer"), sdp)
                val sentViaDataChannel = if (isDataChannelOpen) {
                    trySendSignalViaDataChannel("answer", sdp.description)
                } else {
                    false
                }
                if (!sentViaDataChannel) {
                    signalingClient.sendAnswer(sdp.description)
                }
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create answer failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    private fun handleRemoteAnswer(sdp: String) {
        Log.d(TAG, "=== ANSWER RECEIVED === sdp length=${sdp.length}")
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
            return
        }
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "=== ANSWER SET SUCCESS === isCasting=$isCasting")
                lastRemoteAnswerSdp = sdp
                if (isCasting) {
                    onStatusChange("Casting")
                } else {
                    onStatusChange("P2P connected, ready to cast")
                }
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "=== ANSWER SET FAILED === $error")
                onStatusChange("Answer failed: $error")
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, remoteDesc)
    }

    private fun handleRemoteIceCandidate(event: NearHubEvent.IceCandidateReceived) {
        val candidate = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
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
            signalingClient.sendIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE connection state: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    if (isCasting) {
                        logAudioTransceiverStatus()
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
                Log.d(TAG, "  [$index] mediaType=${transceiver.mediaType}, " +
                        "direction=${transceiver.direction}, " +
                        "sender.track=${transceiver.sender.track()?.id()}, " +
                        "receiver.track=${transceiver.receiver.track()?.id()}")
            }
            val audioSenders = pc.senders.filter { it.track() is AudioTrack }
            Log.d(TAG, "Audio senders count: ${audioSenders.size}")
            audioSenders.forEach { sender ->
                Log.d(TAG, "  AudioSender: trackId=${sender.track()?.id()}")
            }
            Log.d(TAG, "Audio senders: ${audioSenders.size}")
            Log.d(TAG, "AudioRecordDataCallback count: $audioDataCallbackCount")
            Log.d(TAG, "SamplesReadyCallback count: $samplesReadyFrameCount")
            Log.d(TAG, "Audio injection frames: $injectionFrameCount")
            Log.d(TAG, "Audio empty callbacks: $emptyInjectionCount")
            Log.d(TAG, "================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging audio transceiver status", e)
        }
    }

    // ── Cleanup ──

    private fun cleanupP2P() {
        if (isCleanupInProgress) return
        isCleanupInProgress = true

        isCasting = false
        restoreLocalPlaybackAfterCasting()
        isDataChannelOpen = false
        lastRemoteAnswerSdp = null
        try { screenCapturer?.stopCapture() } catch (_: Exception) {}
        try { screenCapturer?.dispose() } catch (_: Exception) {}
        screenCapturer = null
        systemAudioCapture?.stop()
        systemAudioCapture = null
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
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null
    }

    /**
     * Mute local media playback while casting so audio only comes from receiver side.
     * We only restore if we changed volume ourselves.
     */
    private fun muteLocalPlaybackForCasting() {
        if (mutedLocalPlaybackForCasting) return
        try {
            val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            previousMusicStreamVolume = before
            shouldRestoreMusicVolumeAfterCasting = before > 0

            // Force media stream volume to 0 to prevent local speaker output.
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                0,
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Keep mute hint for devices that honor stream mute state.
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
            val after = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            mutedLocalPlaybackForCasting = true
            Log.d(TAG, "Local playback mute request during casting: before=$before after=$after")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mute local playback", e)
        }
    }

    private fun restoreLocalPlaybackAfterCasting() {
        if (!mutedLocalPlaybackForCasting) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (shouldRestoreMusicVolumeAfterCasting) previousMusicStreamVolume?.let { prev ->
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
            }
            val restored = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d(TAG, "Local playback volume restore after casting: restored=$restored")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore local playback volume", e)
        } finally {
            mutedLocalPlaybackForCasting = false
            previousMusicStreamVolume = null
            shouldRestoreMusicVolumeAfterCasting = false
        }
    }

    fun stop() {
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
