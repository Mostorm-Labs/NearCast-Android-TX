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
    private val lookup: (String) -> ReceiverDevice?) {
    private val mutableState = MutableStateFlow(ReceiverActionState())
    val state: StateFlow<ReceiverActionState> = mutableState
    private var generation = 0L
    private var job: Job? = null
    private class Offline : Exception()
    // Serialize persistence and pinned requests across Activity instances. Revocation waits
    // for an in-flight request; queued requests re-read the pin after acquiring this lock.
    private companion object { val trustLock = Mutex() }
    private fun current(device: ReceiverDevice, token: Long): ReceiverDevice {
        if (token != generation) throw CancellationException()
        return lookup(device.instance)?.takeIf { it.routeKey == device.routeKey && it.capabilities == device.capabilities } ?: throw Offline()
    }
    private fun run(device: ReceiverDevice, phase: ReceiverPhase, checkLive: Boolean = true, block: suspend (Long) -> Unit) {
        job?.cancel(); val token = ++generation
        mutableState.value = state.value.copy(phase = phase, device = device, candidate = null, failure = null)
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
            if (!device.selectable || device.deviceId == null || !validIdentity(device.deviceId)) {
                mutableState.value = state.value.copy(phase = ReceiverPhase.UNSUPPORTED); return@run
            }
            val saved = pins.get(device.deviceId)
            if (saved != null) require(validFingerprint(saved))
            mutableState.value = state.value.copy(fingerprint = saved)
            // Match Desktop's automatic pairing: observe the exact selected route without HTTP,
            // retain its SPKI key, then pin every code and local signaling request to that key.
            val observed = transport.observe(current(device, token))
            current(device, token)
            require(validFingerprint(observed))
            if (pins.get(device.deviceId) != saved) {
                mutableState.value = state.value.copy(phase = ReceiverPhase.UNTRUSTED, fingerprint = null, previousFingerprint = null)
                return@run
            }
            if (saved != null && saved != observed) throw ReceiverKeyChanged()
            if (saved == null) {
                mutableState.value = state.value.copy(phase = ReceiverPhase.SAVING)
                pins.save(device.deviceId, observed)
            }
            current(device, token)
            mutableState.value = state.value.copy(fingerprint = observed)
            code(device, token)
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
    fun retryTrust() { state.value.device?.let(::select) }
    fun showCode() { state.value.device?.let { device -> run(device, ReceiverPhase.SHOWING) { code(device, it) } } }
    fun forget() { state.value.device?.let { device -> run(device, ReceiverPhase.SAVING, checkLive = false) {
        device.deviceId?.let(pins::revoke); mutableState.value = state.value.copy(phase = ReceiverPhase.UNTRUSTED, fingerprint = null, previousFingerprint = null)
    } } }
    fun cancel() { generation++; job?.cancel(); job = null; mutableState.value = ReceiverActionState() }
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
