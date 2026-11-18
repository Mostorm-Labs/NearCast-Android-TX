package com.auditoryworks.nearcast.touchback

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder
import com.auditoryworks.nearcast.session.CastSessionState
import com.auditoryworks.nearcast.session.CaptureState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class MediaIdentity(val roomId: String, val userId: String, val hostUserId: String)

/** Product association only: this module neither starts capture nor negotiates RTP. */
class TouchbackController(private val context: Context) {
    private val scope = RemoteControlAccess.scope
    private val mutableState = MutableStateFlow(RemoteControlAccess.state.value)
    val state: StateFlow<RemoteControlState> = mutableState.asStateFlow()
    private var identity: MediaIdentity? = null
    private var announcements: SenderAnnouncements? = null
    private val consent = SenderConsent()
    private var lastSourceInfo: String? = null
    private var lastShareState: String? = null
    private var channel: DataChannel? = null
    private var session: AxtpInputSession? = null
    private var target: JSONObject? = null
    private var runtimeId: String? = null
    private var targetFailure: String? = null
    private var lastPublishedStatus: String? = null
    private val recentInput = ArrayDeque<String>()
    private var receiverFeedback: ReceiverInputFeedback? = null
    private var receiverQueryPending = false
    private var receiverQueryAt = 0L
    private var receiverQueryGeneration = 0L
    private var receiverQueryUnsupported = false
    private var capture = CastSessionState()
    @Volatile private var epoch = 0L
    @Volatile private var captureGeneration = 0L
    @Volatile private var challenge: DisplayChallenge? = null
    private var geometry: ScreenGeometry? = null
    private var calibration = CalibrationState.IDLE
    private var calibrationTimer: Job? = null
    private var sourceBindingId: String? = null
    private var sourceKey: String? = null
    private var descriptor: String? = null
    private var revision = 0L
    private var reason: String? = "CAPTURE_UNAVAILABLE"
    private var detail: String? = null
    private var closed = false
    private var wireState: String? = null
    private var previousEngineState = "closed"
    private var notifiedStream: Pair<String, Long>? = null
    private val engine = InputEngine(scope, AccessibilityInput, onChanged = ::engineChanged)
    private data class Packet(val epoch: Long, val bytes: ByteArray)
    private val packets = Channel<Packet>(128)
    private val queuedBytes = AtomicInteger()
    private val reading = AtomicBoolean()
    private var lastSampleAt = 0L
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) onMain { invalidateGeometry() } }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onMain {
                val known = geometry
                if (known != null && !NativeScreen.matches(context, known)) invalidateGeometry()
                else if (challenge != null) {
                    val pending = challenge!!
                    val current = runCatching { NativeScreen.geometry(context, captureGeneration) }.getOrNull()
                    if (current == null || current.width != pending.width || current.height != pending.height) invalidateGeometry()
                }
            }
        }
    }
    private val worker = scope.launch {
        for (packet in packets) {
            queuedBytes.addAndGet(-packet.bytes.size)
            if (packet.epoch != epoch || closed) continue
            if (session?.disposed == true && runCatching {
                    val frame = FrameCodec.decode(packet.bytes, 4096)
                    frame.type == 1 && frame.payload.firstOrNull()?.toInt() == AxtpContract.Control.Open
                }.getOrDefault(false)) makeSession()
            session?.accept(packet.bytes)
        }
    }
    private val accessObserver = scope.launch {
        var last: RemoteControlState? = null
        RemoteControlAccess.state.collect { access ->
            if (closed) return@collect
            val old = last; last = access
            if (old != null && (old.enabled != access.enabled || old.serviceConnected != access.serviceConnected || old.permissionEnabled != access.permissionEnabled)) {
                invalidateGeometry()
            }
            refreshSource(); maybeCalibrate(); publish()
        }
    }
    private val timer = scope.launch {
        while (isActive) {
            delay(250)
            session?.tick()
            val g = geometry
            if (g != null && (!NativeScreen.usable(context) || !NativeScreen.matches(context, g))) invalidateGeometry()
            refreshSource(); maybeCalibrate()
        }
    }
    init { displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper())) }
    private fun onMain(block: () -> Unit) { scope.launch { if (!closed) block() } }

    fun setIdentity(value: MediaIdentity) = onMain {
        if (identity != value) { fence("MEDIA_IDENTITY_CHANGED"); channel = null; identity = value; announcements = SenderAnnouncements(value) }
    }
    fun setChannel(value: DataChannel?) = onMain {
        if (channel === value) return@onMain
        fence("CONTROL_CHANNEL_CHANGED"); channel = value
        announcements?.resetTransport(); lastSourceInfo = null; lastShareState = null
        if (value != null && identity?.let { isText(it.roomId) && isText(it.userId) && isText(it.hostUserId) } == true) makeSession()
        refreshSource()
    }
    private fun makeSession() {
        resetReceiverQuery()
        session?.dispose(); descriptor = null; wireState = null
        lateinit var next: AxtpInputSession
        next = AxtpInputSession(send = { bytes ->
            requireInput(session === next && send(bytes, true), "Input transport write failed", AxtpContract.Error.TRANSPORT_WRITE_FAILED)
        }, onRequest = { method, params, sid ->
            android.util.Log.i("Touchback", "RX request method=$method")
            when (method) {
                "input.openStream" -> { requireInput(sourceBindingId != null, "Input source unavailable", AxtpContract.Error.PERMISSION_DENIED); recentInput.clear(); engine.open(params, sid) }
                "input.closeStream" -> engine.closeStream(params, sid)
                "stream.getState" -> {
                    val snapshot = engine.streamState(if (params.has("streamId")) params.uint("streamId", 1) else null, sid)
                    android.util.Log.d("Touchback", "Input stats state=${engine.state} lastSeq=${snapshot.opt("lastSeqId")} submitted=${engine.submittedSamples} wheelMerged=${engine.coalescedWheelSamples}")
                    snapshot
                }
                else -> throw ProtocolFailure(if (method in AxtpContract.registeredMethods) AxtpContract.Error.NOT_SUPPORTED else AxtpContract.Error.RPC_METHOD_NOT_FOUND, method)
            }
        }, onStream = { sample ->
            // Keep a bounded structural trace for failures, without coordinates or content.
            val phases = sample.contacts.groupingBy { it.phase }.eachCount()
            val wheel = if (sample.type == "mouse" && sample.kind == 2) " wheel=${sample.wheelX},${sample.wheelY}" else ""
            recentInput.addLast("seq=${sample.seqId} cursor=${sample.cursor} type=${sample.type} kind=${sample.kind} buttons=${sample.buttons} phases=$phases$wheel")
            while (recentInput.size > 8) recentInput.removeFirst()
            if (sourceBindingId != null && gateReason() == null && geometry?.let { NativeScreen.matches(context, it) } == true) {
                engine.accept(sample) // Queue receipt is not a native-input ACK.
            } else invalidateSource("CAPTURE_UNAVAILABLE")
        }, onFault = { failure ->
            if (session === next) {
                detail = failure.message?.take(180); invalidateSource("INPUT_SESSION_FAULT")
                SessionTraceRecorder.record("Touchback", "input session fault ${failure.javaClass.simpleName}")
            }
        }, onReady = { if (session === next) refreshSource() })
        session = next; refreshSource(); publish()
    }
    fun acceptBinary(bytes: ByteArray, from: DataChannel) {
        if (closed) return
        if (bytes.size > 65549 || queuedBytes.addAndGet(bytes.size) > 262144) {
            if (bytes.size <= 65549) queuedBytes.addAndGet(-bytes.size)
            onMain { inputFault("Input transport queue budget") }; return
        }
        scope.launch {
            if (closed || channel !== from || !packets.trySend(Packet(epoch, bytes)).isSuccess) {
                queuedBytes.addAndGet(-bytes.size)
                if (!closed && channel === from) inputFault("Input transport queue full")
            }
        }
    }
    fun acceptTarget(message: JSONObject, from: DataChannel) = onMain {
        if (channel !== from) return@onMain
        val data = message.optJSONObject("data"); val nextTarget = data?.optJSONObject("target")
        val failure = inputTargetFailure(data)
        if (failure != null) {
            targetFailure = failure; consent.clear(); target = null; runtimeId = null
            android.util.Log.w("Touchback", "target rejected reason=$failure local=${AxtpContract.FINGERPRINT} peer=${data?.optString("contractFingerprint")?.take(64)}")
            invalidateSource(failure); return@onMain
        }
        targetFailure = null
        check(data != null && nextTarget != null)
        if (runtimeId != data.getString("runtimeInstanceId") || canonicalJson(target) != canonicalJson(nextTarget)) {
            consent.clear()
            invalidateSource("TARGET_CHANGED")
            target = JSONObject(nextTarget.toString()); runtimeId = data.getString("runtimeInstanceId")
        }
        data.optJSONObject("controlRequest")?.let {
            val accepted = consent.request(it)
            android.util.Log.i("Touchback", "RX controlRequest parsed=$accepted")
        }
        refreshSource()
    }
    fun updateCapture(value: CastSessionState, generation: Long) = onMain {
        val changed = captureGeneration != generation || capture.capture != value.capture
        capture = value; captureGeneration = generation
        if (changed) invalidateGeometry()
        if (!value.isP2PReady) invalidateSource("MEDIA_PEER_UNAVAILABLE")
        refreshSource(); maybeCalibrate()
    }
    fun captureGeometryChanged() = onMain { invalidateGeometry() }
    private fun invalidateGeometry() {
        val old = challenge; challenge = null
        old?.let { AccessibilityInput.hideMarkers(it.token) }
        calibrationTimer?.cancel(); calibrationTimer = null
        geometry = null; calibration = CalibrationState.IDLE
        invalidateSource("NATIVE_GEOMETRY_UNAVAILABLE")
        maybeCalibrate()
    }
    private fun invalidateSource(value: String) {
        revision++; sourceBindingId = null; sourceKey = null; reason = value
        engine.revoke()
        scope.launch { runCatching { engine.close() }; publish() }
        sendSource(); publish()
    }
    private fun gateReason(): String? {
        val access = RemoteControlAccess.state.value
        val caps = AccessibilityInput.capabilities()
        return RemoteInputGate(access.enabled, access.supported, access.permissionEnabled && access.serviceConnected,
            capture.capture == CaptureState.ACTIVE && captureGeneration != 0L, capture.isP2PReady,
            session?.ready == true, target != null && runtimeId != null, geometry != null,
            engine.state in setOf("closing", "cleanup_blocked") || AccessibilityInput.cleanupBlocked, NativeScreen.usable(context), targetFailure).disabledReason()
            ?: when { caps.ownerId != null && caps.ownerId != engine.owner?.id -> "NATIVE_DOMAIN_BUSY"
                !caps.ready -> "NATIVE_INPUT_UNAVAILABLE"; else -> null }
    }
    private fun refreshSource() {
        if (closed) return
        val unavailable = gateReason()
        if (unavailable != null) {
            if (sourceBindingId != null) invalidateSource(unavailable) else { reason = if (session?.disposed == true) "INPUT_SESSION_FAULT" else unavailable; sendSource(); publish() }
            return
        }
        val inputSession = session ?: return; val g = geometry ?: return
        val key = "${inputSession.sessionId}:${runtimeId}:${canonicalJson(target)}:${g.revision}:$epoch"
        // Consent and its deadline change without replacing the calibrated input binding.
        if (sourceKey == key && sourceBindingId != null) { sendSource(); publish(); return }
        val version = ++revision
        scope.launch {
            try {
                val id = UUID.randomUUID().toString()
                engine.bind(InputBinding(inputSession.sessionId, runtimeId!!, JSONObject(target.toString()), id, g))
                if (version != revision || session !== inputSession || gateReason() != null) return@launch
                sourceBindingId = id; sourceKey = key; reason = null; detail = null
                resetReceiverQuery()
                sendSource(); publish()
            } catch (failure: Throwable) { if (version == revision) { detail = failure.message?.take(180); reason = "NATIVE_CLEANUP_PENDING"; sendSource(); publish() } }
        }
    }
    private fun capability(): JSONObject {
        val supported = RemoteControlAccess.state.value.supported
        val result = JSONObject().put("capability", "input.stream").put("direction", "receiver_to_source").put("reliability", "ordered_reliable")
            .put("profiles", JSONArray(listOf("AXTP-TYPED-INPUT-V1"))).put("formats", JSONArray(listOf(AxtpContract.FORMAT)))
            .put("inputTypes", JSONArray(if (supported) listOf("touch", "mouse") else emptyList<String>())).put("maxDataBytes", 4096)
        if (supported) result.put("touch", JSONObject().put("maxContacts", AccessibilityInput.capabilities().maxContacts))
            .put("mouse", AndroidMousePolicy.capability())
        return result
    }
    private fun sendSource() {
        val peer = identity ?: return
        val visible = capture.capture == CaptureState.ACTIVE && captureGeneration != 0L
        announcements?.let { metadata ->
            val info = metadata.source(visible, geometry != null && calibration == CalibrationState.VERIFIED).toString()
            if (info != lastSourceInfo && send(info.toByteArray(Charsets.UTF_8), false)) lastSourceInfo = info
            val share = metadata.share(visible).toString()
            if (share != lastShareState && send(share.toByteArray(Charsets.UTF_8), false)) lastShareState = share
        }
        val message = JSONObject().put("action", "nearcast-input-source").put("data", JSONObject().put("version", 1)
            .put("contractFingerprint", AxtpContract.FINGERPRINT).put("sourceBindingId", sourceBindingId ?: JSONObject.NULL)
            .put("roomId", peer.roomId).put("senderUserId", peer.userId).put("receiverUserId", peer.hostUserId)
            .put("geometryRevision", if (sourceBindingId != null) geometry?.revision?.toString() else JSONObject.NULL)
            .put("available", sourceBindingId != null).put("disabledReason", if (sourceBindingId != null) JSONObject.NULL else reason)
            .put("displaySurface", if (visible && geometry != null) "monitor" else "unknown")
            .put("controlConsent", consent.snapshot(RemoteControlAccess.state.value.enabled, sourceBindingId != null && gateReason() == null))
            .put("inputCapability", capability())).toString()
        if (descriptor != message && send(message.toByteArray(Charsets.UTF_8), false)) {
            descriptor = message
            android.util.Log.i("Touchback", "TX input-source queued available=${sourceBindingId != null} surface=${if (visible && geometry != null) "monitor" else "unknown"} reason=$reason")
        }
        if (descriptor == message) queryReceiver()
    }
    private fun resetReceiverQuery() {
        receiverQueryGeneration++; receiverQueryPending = false; receiverQueryAt = 0
        receiverQueryUnsupported = false; receiverFeedback = null
    }
    private fun queryReceiver() {
        val owner = session?.takeIf { it.ready && !it.disposed } ?: return
        val currentTarget = target ?: return
        if (closed || sourceBindingId == null || receiverQueryPending || receiverQueryUnsupported || SystemClock.elapsedRealtime() - receiverQueryAt < 2000) return
        val expectedTarget = JSONObject(currentTarget.toString())
        val generation = ++receiverQueryGeneration
        receiverQueryPending = true; receiverQueryAt = SystemClock.elapsedRealtime()
        try {
            owner.request("cast.getTouchbackState", JSONObject().put("target", expectedTarget)) { result ->
                if (closed || generation != receiverQueryGeneration || session !== owner || canonicalJson(target) != canonicalJson(expectedTarget)) return@request
                receiverQueryPending = false
                result.fold(onSuccess = { body ->
                    runCatching { ReceiverInputFeedback.parse(body, expectedTarget) }.onSuccess { state ->
                        if (receiverFeedback != state) {
                            val status = "RX feedback optIn=${state.receiverOptIn} requested=${state.controllerRequested} effective=${state.effectiveEnabled} lifecycle=${state.lifecycleState} reason=${state.diagnosticReason}"
                            android.util.Log.i("Touchback", status); SessionTraceRecorder.record("Touchback", status)
                        }
                        receiverFeedback = state
                    }.onFailure { android.util.Log.w("Touchback", "RX feedback invalid target/result") }
                }, onFailure = { failure ->
                    val code = (failure as? ProtocolFailure)?.code
                    if (code == AxtpContract.Error.NOT_SUPPORTED) receiverQueryUnsupported = true
                    android.util.Log.w("Touchback", "RX feedback query failed code=$code")
                })
                publish()
            }
        } catch (failure: Exception) {
            receiverQueryPending = false
            android.util.Log.w("Touchback", "RX feedback query not sent type=${failure.javaClass.simpleName}")
        }
    }
    private fun send(bytes: ByteArray, binary: Boolean): Boolean = try {
        channel?.takeIf { it.state() == DataChannel.State.OPEN }?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), binary)) == true
    } catch (_: RuntimeException) { false }
    private fun engineChanged() {
        val prior = previousEngineState; previousEngineState = engine.state
        if (engine.state in setOf("closing", "cleanup_blocked") && sourceBindingId != null) {
            sourceBindingId = null; sourceKey = null; revision++; reason = "NATIVE_CLEANUP_PENDING"; sendSource()
        }
        engine.owner?.let { notifiedStream = it.binding.sessionId to it.streamId }
        val id = notifiedStream?.takeIf { it.first == session?.sessionId }?.second
        val key = "${engine.state}:$id"
        if (key != wireState && id != null && session?.ready == true) {
            wireState = key
            runCatching { session?.notifyState(id, engine.streamState(id, session!!.sessionId)) }
        }
        publish()
        if (engine.state == "closed" && prior in setOf("closing", "cleanup_blocked")) { refreshSource(); maybeCalibrate() }
    }
    private fun publish() {
        mutableState.value = RemoteControlAccess.state.value.copy(channelReady = channel != null, appReady = session?.ready == true,
            calibration = calibration, sourceReady = sourceBindingId != null, inputState = engine.state,
            cleanupPending = engine.state in setOf("closing", "cleanup_blocked") || AccessibilityInput.cleanupBlocked ||
                AccessibilityInput.capabilities().ownerId?.let { it != engine.owner?.id } == true,
            disabledReason = reason, error = detail ?: engine.lastError, submittedSamples = engine.submittedSamples, discardedSamples = engine.discardedSamples)
            .copy(compatibilityFailure = targetFailure, receiverFeedback = receiverFeedback)
        val status = "appReady=${session?.ready == true} calibrated=$calibration sourceReady=${sourceBindingId != null} input=${engine.state} reason=$reason compatibility=$targetFailure error=${mutableState.value.error}"
        if (status != lastPublishedStatus) {
            lastPublishedStatus = status
            android.util.Log.i("Touchback", status)
            SessionTraceRecorder.record("Touchback", status)
            if (engine.state == "closing" && engine.lastError != null) {
                val trace = "Input failure submitted=${engine.submittedSamples} discarded=${engine.discardedSamples} wheelMerged=${engine.coalescedWheelSamples} recent=${recentInput.joinToString("; ")}"
                android.util.Log.w("Touchback", trace); SessionTraceRecorder.record("Touchback", trace)
            }
        }
    }
    private fun maybeCalibrate() {
        val access = RemoteControlAccess.state.value
        val caps = AccessibilityInput.capabilities()
        if (closed || calibration != CalibrationState.IDLE || capture.capture != CaptureState.ACTIVE || captureGeneration == 0L ||
            !access.enabled || !access.supported || !access.serviceConnected || !access.permissionEnabled ||
            !caps.ready || (caps.ownerId != null && caps.ownerId != engine.owner?.id) ||
            engine.state in setOf("closing", "cleanup_blocked") || AccessibilityInput.cleanupBlocked) return
        val g = runCatching { NativeScreen.geometry(context, captureGeneration) }.getOrNull() ?: return
        val random = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        val pending = DisplayChallenge(random, g.width, g.height, captureGeneration, SystemClock.elapsedRealtime())
        challenge = pending; calibration = CalibrationState.RUNNING
        if (!AccessibilityInput.showMarkers(random, g)) { calibration = CalibrationState.FAILED; challenge = null; detail = "Display calibration overlay unavailable"; publish(); return }
        calibrationTimer = scope.launch {
            delay(2000)
            if (challenge === pending) {
                challenge = null; AccessibilityInput.hideMarkers(random); calibration = CalibrationState.FAILED
                detail = "Full-display mapping could not be verified"; refreshSource()
            }
        }
        publish()
    }
    /** Retain at most one frame, only for the bounded calibration interval. Never alter it. */
    fun onFrame(frame: VideoFrame, generation: Long) {
        val pending = challenge ?: return
        val now = SystemClock.elapsedRealtime()
        if (generation != pending.generation || now - lastSampleAt < 60 || !AccessibilityInput.markersReady(pending.token) || !reading.compareAndSet(false, true)) return
        lastSampleAt = now; frame.retain()
        scope.launch(Dispatchers.Default) {
            var matched = false
            try {
                val buffer = frame.buffer.toI420()
                if (buffer != null) try {
                    matched = pending.observe(frame.rotatedWidth, frame.rotatedHeight, generation, now) { x, y ->
                        val (rx, ry) = when (frame.rotation) {
                            90 -> y to buffer.height - 1 - x
                            180 -> buffer.width - 1 - x to buffer.height - 1 - y
                            270 -> buffer.width - 1 - y to x
                            else -> x to y
                        }
                        buffer.dataY.get(ry * buffer.strideY + rx).toInt() and 255
                    }
                } finally { buffer.release() }
            } catch (_: RuntimeException) { /* The challenge will expire; media is independent. */ }
            finally { frame.release(); reading.set(false) }
            if (matched) withContext(Dispatchers.Main.immediate) {
                if (challenge === pending && captureGeneration == generation && capture.capture == CaptureState.ACTIVE) {
                    AccessibilityInput.hideMarkers(pending.token); challenge = null; calibrationTimer?.cancel(); calibrationTimer = null
                    val g = runCatching { NativeScreen.geometry(context, generation) }.getOrNull()
                    if (g != null && g.width == pending.width && g.height == pending.height) {
                        geometry = g; calibration = CalibrationState.VERIFIED; detail = null
                        SessionTraceRecorder.record("Touchback", "full display calibration verified ${g.width}x${g.height} rotation=${g.rotation}")
                    } else calibration = CalibrationState.FAILED
                    refreshSource()
                }
            }
        }
    }
    fun retry() = onMain {
        RemoteControlAccess.retryCleanup()
        scope.launch {
            runCatching { engine.close() }
            if (engine.state == "closed") { detail = null; invalidateGeometry(); refreshSource() }
        }
    }
    private fun inputFault(message: String) { session?.dispose(); detail = message; invalidateSource("INPUT_SESSION_FAULT") }
    private fun fence(value: String) {
        resetReceiverQuery()
        consent.clear()
        targetFailure = null
        epoch++; session?.dispose(); session = null; target = null; runtimeId = null; descriptor = null; wireState = null; notifiedStream = null
        invalidateSource(value)
    }
    fun dispose() {
        scope.launch {
            if (closed) return@launch
            closed = true; fence("SESSION_CLOSED")
            challenge?.let { AccessibilityInput.hideMarkers(it.token) }; challenge = null
            calibrationTimer?.cancel(); accessObserver.cancel(); timer.cancel(); worker.cancel(); packets.close()
            displayManager.unregisterDisplayListener(displayListener)
            RemoteControlAccess.retire(engine)
        }
    }
}
