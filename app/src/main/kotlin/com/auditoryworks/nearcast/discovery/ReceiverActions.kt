package com.auditoryworks.nearcast.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

interface ReceiverPins {
    fun get(deviceId: String): String?
    fun save(deviceId: String, fingerprint: String)
    fun revoke(deviceId: String)
    fun subscribe(deviceId: String, changed: () -> Unit): AutoCloseable = AutoCloseable { }
}
enum class ReceiverPhase { NONE, CHECKING, PREPARING, COMPARE, SAVING, SHOWING, SHOWN, UNAVAILABLE, RATE_LIMITED, UNSUPPORTED, OFFLINE, ERROR, KEY_CHANGED, EXPIRED, UNTRUSTED }
enum class ReceiverFailure { NETWORK, STORAGE, PROTOCOL }
data class ReceiverActionState(val phase: ReceiverPhase = ReceiverPhase.NONE, val device: ReceiverDevice? = null,
    val candidate: TrustCandidate? = null, val fingerprint: String? = null, val previousFingerprint: String? = null,
    val failure: ReceiverFailure? = null)
class ReceiverActions(private val scope: CoroutineScope, private val transport: ReceiverTransport, private val pins: ReceiverPins,
    private val lookup: (String) -> ReceiverDevice?, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutableState = MutableStateFlow(ReceiverActionState())
    val state: StateFlow<ReceiverActionState> = mutableState
    private val candidates = TrustCandidates(now)
    private var generation = 0L
    private var job: Job? = null
    private var expiry: Job? = null
    private class Offline : Exception()
    // Serialize persistence and pinned requests across Activity instances. Revocation waits
    // for an in-flight request; queued requests re-read the pin after acquiring this lock.
    private companion object { val trustLock = Mutex() }
    private fun current(device: ReceiverDevice, token: Long): ReceiverDevice {
        if (token != generation) throw CancellationException()
        return lookup(device.instance)?.takeIf { it.routeKey == device.routeKey && it.capabilities == device.capabilities } ?: throw Offline()
    }
    private fun run(device: ReceiverDevice, phase: ReceiverPhase, keepCandidate: Boolean = false, checkLive: Boolean = true, block: suspend (Long) -> Unit) {
        job?.cancel(); expiry?.cancel(); val token = ++generation
        if (!keepCandidate) candidates.cancel()
        mutableState.value = state.value.copy(phase = phase, device = device, candidate = if (keepCandidate) state.value.candidate else null, failure = null)
        job = scope.launch {
            try { trustLock.withLock { if (checkLive) current(device, token); else if (token != generation) throw CancellationException(); block(token) } }
            catch (_: TimeoutCancellationException) {
                if (token == generation && currentCoroutineContext().isActive) mutableState.value = state.value.copy(phase = ReceiverPhase.ERROR, candidate = null, failure = ReceiverFailure.NETWORK)
            }
            catch (_: CancellationException) { /* Cancellation never grants trust or updates another selection. */ }
            catch (_: Offline) { if (token == generation) mutableState.value = state.value.copy(phase = ReceiverPhase.OFFLINE, candidate = null) }
            catch (_: ReceiverKeyChanged) { if (token == generation) mutableState.value = state.value.copy(phase = ReceiverPhase.KEY_CHANGED, candidate = null) }
            catch (error: Exception) {
                if (token == generation) mutableState.value = state.value.copy(phase = ReceiverPhase.ERROR, candidate = null,
                    failure = when (error) { is TrustStorageFailure -> ReceiverFailure.STORAGE; is IllegalArgumentException, is org.json.JSONException -> ReceiverFailure.PROTOCOL; else -> ReceiverFailure.NETWORK })
            }
        }
    }
    fun select(device: ReceiverDevice) {
        cancel()
        run(device, ReceiverPhase.CHECKING) { token ->
            if (!device.selectable || device.deviceId == null) { mutableState.value = state.value.copy(phase = ReceiverPhase.UNSUPPORTED); return@run }
            val pin = pins.get(device.deviceId)
            if (pin != null) { require(validFingerprint(pin)); mutableState.value = state.value.copy(fingerprint = pin); code(device, token) }
            else prepare(device, token)
        }
    }
    private suspend fun prepare(device: ReceiverDevice, token: Long) {
        val receiver = current(device, token)
        if (!receiver.supportsTrustDisplay || receiver.deviceId == null) { mutableState.value = state.value.copy(phase = ReceiverPhase.UNSUPPORTED); return }
        mutableState.value = state.value.copy(phase = ReceiverPhase.PREPARING)
        val candidateGeneration = candidates.begin()
        val fingerprint = transport.observe(receiver); current(receiver, token); require(validFingerprint(fingerprint))
        val requestId = UUID.randomUUID().toString()
        val bytes = transport.post(receiver, DisplayOperation.TRUST, DisplayProtocol.request(DisplayOperation.TRUST, receiver.deviceId, requestId), fingerprint)
        current(receiver, token)
        val result = DisplayProtocol.response(DisplayOperation.TRUST, requestId, bytes)
        if (result != "shown") { result(result); return }
        val candidate = candidates.offer(candidateGeneration, receiver, fingerprint) ?: throw CancellationException()
        mutableState.value = state.value.copy(phase = ReceiverPhase.COMPARE, candidate = candidate, fingerprint = fingerprint, previousFingerprint = pins.get(receiver.deviceId))
        expiry = scope.launch {
            delay(60000)
            if (state.value.candidate === candidate) { candidates.cancel(); mutableState.value = state.value.copy(phase = ReceiverPhase.EXPIRED, candidate = null) }
        }
    }
    fun confirm(compared: Boolean) {
        val candidate = state.value.candidate ?: return
        if (!compared) return
        run(candidate.device, ReceiverPhase.SAVING, keepCandidate = true) { token ->
            val receiver = current(candidate.device, token)
            if (!candidates.confirm(candidate, receiver, true)) { mutableState.value = state.value.copy(phase = ReceiverPhase.EXPIRED, candidate = null); return@run }
            pins.save(receiver.deviceId!!, candidate.fingerprint)
            mutableState.value = state.value.copy(fingerprint = candidate.fingerprint, candidate = null)
            code(receiver, token)
        }
    }
    private suspend fun code(device: ReceiverDevice, token: Long) {
        val receiver = current(device, token)
        if (!receiver.supportsCodeDisplay || receiver.deviceId == null) { mutableState.value = state.value.copy(phase = ReceiverPhase.UNSUPPORTED); return }
        val pin = pins.get(receiver.deviceId)
        if (pin == null) { mutableState.value = state.value.copy(phase = ReceiverPhase.UNTRUSTED, fingerprint = null); return }
        require(validFingerprint(pin))
        mutableState.value = state.value.copy(phase = ReceiverPhase.SHOWING, fingerprint = pin)
        val requestId = UUID.randomUUID().toString()
        val bytes = transport.post(receiver, DisplayOperation.CODE, DisplayProtocol.request(DisplayOperation.CODE, receiver.deviceId, requestId, receiver.codeSelection), pin)
        current(receiver, token)
        result(DisplayProtocol.response(DisplayOperation.CODE, requestId, bytes, receiver.deviceId, receiver.codeSelection))
    }
    private fun result(status: String) { mutableState.value = state.value.copy(candidate = null, phase = when (status) {
        "shown" -> ReceiverPhase.SHOWN; "unavailable" -> ReceiverPhase.UNAVAILABLE; "rate_limited" -> ReceiverPhase.RATE_LIMITED; else -> ReceiverPhase.UNSUPPORTED
    }) }
    fun retryTrust() { state.value.device?.let { device -> run(device, ReceiverPhase.PREPARING) { prepare(device, it) } } }
    fun showCode() { state.value.device?.let { device -> run(device, ReceiverPhase.SHOWING) { code(device, it) } } }
    fun forget() { state.value.device?.let { device -> run(device, ReceiverPhase.SAVING, checkLive = false) {
        device.deviceId?.let(pins::revoke); mutableState.value = state.value.copy(phase = ReceiverPhase.UNTRUSTED, fingerprint = null, previousFingerprint = null)
    } } }
    fun cancel() { generation++; job?.cancel(); job = null; expiry?.cancel(); expiry = null; candidates.cancel(); mutableState.value = ReceiverActionState() }
    fun devicesChanged() {
        val device = state.value.device ?: return
        val actual = lookup(device.instance)
        if (actual?.routeKey != device.routeKey || actual.capabilities != device.capabilities) {
            val saved = if (state.value.candidate != null) state.value.previousFingerprint else state.value.fingerprint
            cancel(); mutableState.value = ReceiverActionState(ReceiverPhase.OFFLINE, device, fingerprint = saved)
        } else mutableState.value = state.value.copy(device = actual)
    }
}

class TrustStorageFailure(cause: Throwable? = null) : Exception("Device trust storage unavailable", cause)
