package com.auditoryworks.nearcast.discovery

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class DisplayOperation(val path: String, val wireName: String) {
    TRUST("/nearcast/trust-display", "trust_display"), CODE("/nearcast/code-display", "code_display")
}
data class CodeSelection(val runtimeInstanceId: String)
object DisplayProtocol {
    fun request(operation: DisplayOperation, deviceId: String, requestId: String, selection: CodeSelection? = null): ByteArray {
        require(validIdentity(deviceId) && validIdentity(requestId))
        require(selection == null || operation == DisplayOperation.CODE && validIdentity(selection.runtimeInstanceId))
        val request = JSONObject().put("type", "nearcast.${operation.wireName}.request").put("version", if (selection != null) 2 else 1)
            .put("deviceId", deviceId).put("requestId", requestId)
        if (operation == DisplayOperation.CODE) request.put("reason", "sender-device-selected")
        if (selection != null) request.put("backend", "local").put("runtimeInstanceId", selection.runtimeInstanceId)
        return request.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 1024) }
    }
    fun response(operation: DisplayOperation, requestId: String, bytes: ByteArray, deviceId: String? = null, selection: CodeSelection? = null): String {
        require(bytes.size <= 4096)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val result = JSONObject(text)
        require(result.opt("type") == "nearcast.${operation.wireName}.result" && result.opt("version") == (if (selection != null) 2 else 1) && result.opt("requestId") == requestId)
        if (operation == DisplayOperation.TRUST) require(result.keys().asSequence().toSet() == setOf("type", "version", "requestId", "status"))
        else if (result.has("deviceId")) require(result.opt("deviceId") == deviceId)
        if (selection != null) require(result.opt("deviceId") == deviceId && result.opt("backend") == "local" && result.opt("runtimeInstanceId") == selection.runtimeInstanceId)
        return result.getString("status").also { require(it in setOf("shown", "unavailable", "rate_limited", "not_supported")) }
    }
}
data class TrustCandidate(val device: ReceiverDevice, val fingerprint: String, val generation: Long, val expiresAt: Long)
class TrustCandidates(private val now: () -> Long) {
    private var generation = 0L
    private var pending: TrustCandidate? = null
    fun begin(): Long { generation++; pending = null; return generation }
    fun cancel() { begin() }
    fun offer(generation: Long, device: ReceiverDevice, fingerprint: String): TrustCandidate? {
        if (generation != this.generation || !device.supportsTrustDisplay || device.deviceId == null || !validFingerprint(fingerprint)) return null
        return TrustCandidate(device, fingerprint, generation, now() + 60000).also { pending = it }
    }
    fun confirm(candidate: TrustCandidate, current: ReceiverDevice?, compared: Boolean): Boolean {
        if (!compared || pending !== candidate || generation != candidate.generation || now() >= candidate.expiresAt ||
            current?.routeKey != candidate.device.routeKey || !current.supportsTrustDisplay) return false
        cancel(); return true
    }
}
internal fun validFingerprint(value: String) = value.matches(Regex("[A-F0-9]{64}"))
