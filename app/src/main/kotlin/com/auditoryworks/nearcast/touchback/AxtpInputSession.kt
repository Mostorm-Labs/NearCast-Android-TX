package com.auditoryworks.nearcast.touchback

import org.json.JSONObject
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class AxtpInputSession(private val send: (ByteArray) -> Unit,
    private val onRequest: suspend (String, JSONObject, String) -> JSONObject,
    private val onStream: suspend (InputSample) -> Unit, private val onFault: (Throwable) -> Unit,
    private val onReady: () -> Unit = {}, private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sessionIdFactory: () -> String = { java.util.UUID.randomUUID().toString().take(8).uppercase() }) {
    var ready = false; private set
    var sessionId = ""; private set
    var disposed = false; private set
    private val assembler = FrameAssembler(now)
    private var controlOpen = false
    private var subscribed = false
    private var messageId = 0
    private var generation = 0L
    private val startedAt = now()
    private var lastReceiveAt = startedAt
    private var heartbeatMs = 1000L
    private val incoming = mutableMapOf<Long, Long>()
    private data class Outgoing(val deadline: Long, val complete: (Result<JSONObject>) -> Unit)
    private val outgoing = mutableMapOf<Long, Outgoing>()
    private var nextRequestId = 0L

    /** Correlated standard RPC on this same authenticated sid, used for read-only RX diagnostics. */
    fun request(method: String, params: JSONObject, timeoutMs: Long = 5000, complete: (Result<JSONObject>) -> Unit): Long {
        requireInput(ready && !disposed, "Receiver query requires APP_READY", AxtpContract.Error.INVALID_STATE)
        requireInput(method in AxtpContract.registeredMethods && timeoutMs in 100..5000 && params.toString().toByteArray().size <= 4096, "Receiver query arguments")
        requireInput(outgoing.size < 8 && nextRequestId < 0xffffffffL, "Receiver query budget", AxtpContract.Error.RESOURCE_EXHAUSTED)
        val id = ++nextRequestId
        outgoing[id] = Outgoing(now() + timeoutMs, complete)
        try { sendRpc(AxtpContract.Rpc.Request, JSONObject().put("id", id).put("method", method).put("params", params)) }
        catch (failure: Throwable) { finishQuery(id, Result.failure(failure)) }
        return id
    }
    private fun finishQuery(id: Long, result: Result<JSONObject>) {
        val query = outgoing.remove(id) ?: return
        // A diagnostic consumer cannot fault the shared input transport.
        runCatching { query.complete(result) }
    }

    private fun sendPayload(type: Int, payload: ByteArray) {
        if (disposed) return
        messageId = (messageId + 1) and 65535
        FrameCodec.encode(payload, type, assembler.maxFrameBytes, messageId).forEach(send)
    }
    private fun sendRpc(op: Int, d: JSONObject, sid: String = sessionId) {
        sendPayload(2, byteArrayOf(1) + JSONObject().put("sid", sid).put("op", op).put("d", d).toString().toByteArray(Charsets.UTF_8))
    }
    suspend fun accept(bytes: ByteArray) {
        if (disposed) return
        try {
            val frame = assembler.accept(bytes)
            lastReceiveAt = now()
            if (frame == null) return
            when (frame.type) {
                1 -> control(frame.payload)
                2 -> { requireInput(controlOpen, "CONTROL OPEN required"); rpc(frame.payload) }
                3 -> { requireInput(ready, "STREAM requires APP_READY"); onStream(InputCodec.decode(frame.payload)) }
            }
        } catch (failure: Throwable) { fault(failure) }
    }
    private fun parseTlvs(bytes: ByteArray): Map<Int, ByteArray> {
        val fields = mutableMapOf<Int, ByteArray>()
        var at = 0
        while (at < bytes.size) {
            requireInput(at + 2 <= bytes.size, "CONTROL TLV header")
            val tag = bytes[at++].toInt() and 255; val length = bytes[at++].toInt() and 255
            requireInput(at + length <= bytes.size && tag !in fields, "CONTROL TLV length/duplicate")
            fields[tag] = bytes.copyOfRange(at, at + length); at += length
        }
        return fields
    }
    private fun Map<Int, ByteArray>.number(tag: Int, default: Long): Long {
        val bytes = get(tag) ?: return default
        requireInput(bytes.size in setOf(1, 2, 4), "CONTROL numeric width")
        return bytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 255) }
    }
    private fun controlPayload(op: Int, id: Int, body: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(5 + body.size).put(op.toByte()).putShort(id.toShort()).putShort(0).put(body).array()
    private fun control(payload: ByteArray) {
        requireInput(payload.size >= 5, "CONTROL payload truncated")
        val b = ByteBuffer.wrap(payload)
        val op = b.get().toInt() and 255; val id = b.short.toInt() and 65535
        requireInput(id > 0 && b.short.toInt() == 0, "CONTROL id/status")
        if (op == AxtpContract.Control.Open) {
            requireInput(!controlOpen, "Duplicate CONTROL OPEN")
            val fields = parseTlvs(payload.copyOfRange(5, payload.size))
            val frameBytes = fields.number(AxtpContract.Tlv.MaxFrameSize, 4096)
            val types = fields.number(AxtpContract.Tlv.SupportedPayloadTypes, 7)
            val encodings = fields.number(AxtpContract.Tlv.SupportedRpcEncodings, 1)
            heartbeatMs = fields.number(AxtpContract.Tlv.HeartbeatIntervalMs, 1000)
            requireInput(frameBytes in 15..65549 && types and 7 == 7L && encodings and 1 == 1L &&
                fields.number(AxtpContract.Tlv.AckMode, 0) == 0L, "CONTROL negotiation unsupported")
            requireInput(fields.number(AxtpContract.Tlv.ProtocolVersion, 1) == 1L && heartbeatMs in 500..60000, "CONTROL version/heartbeat")
            assembler.maxFrameBytes = minOf(frameBytes.toInt(), 4096)
            val out = ByteArrayOutputStream()
            fun field(tag: Int, value: Long, width: Int) {
                out.write(tag); out.write(width)
                for (shift in width - 1 downTo 0) out.write(((value shr (shift * 8)) and 255).toInt())
            }
            field(AxtpContract.Tlv.SessionId, fields.number(AxtpContract.Tlv.SessionId, 0), 4)
            field(AxtpContract.Tlv.MaxFrameSize, assembler.maxFrameBytes.toLong(), 2)
            field(AxtpContract.Tlv.SupportedPayloadTypes, 7, 1)
            field(AxtpContract.Tlv.SelectedRpcEncoding, 1, 1)
            field(AxtpContract.Tlv.HeartbeatIntervalMs, heartbeatMs, 2)
            field(AxtpContract.Tlv.AckMode, 0, 1)
            if (AxtpContract.Tlv.ProtocolVersion in fields) field(AxtpContract.Tlv.ProtocolVersion, 1, 1)
            if (AxtpContract.Tlv.Mtu in fields) field(AxtpContract.Tlv.Mtu, minOf(fields.number(AxtpContract.Tlv.Mtu, 4096), assembler.maxFrameBytes.toLong()), 2)
            controlOpen = true
            sendPayload(1, controlPayload(AxtpContract.Control.Accept, id, out.toByteArray()))
            sendRpc(AxtpContract.Rpc.Hello, JSONObject().put("axtpVersion", AxtpContract.VERSION), "")
            return
        }
        requireInput(controlOpen && payload.size == 5, "Unexpected CONTROL state/body")
        when (op) {
            AxtpContract.Control.Heartbeat -> sendPayload(1, controlPayload(AxtpContract.Control.HeartbeatAck, id))
            AxtpContract.Control.Ping -> sendPayload(1, controlPayload(AxtpContract.Control.Pong, id))
            AxtpContract.Control.Close -> {
                sendPayload(1, controlPayload(AxtpContract.Control.CloseAck, id))
                fault(ProtocolFailure(AxtpContract.Error.CANCELED, "Peer closed input session"))
            }
            else -> throw ProtocolFailure(AxtpContract.Error.NOT_SUPPORTED, "Unsupported CONTROL opcode")
        }
    }
    private fun subscription(value: Any?): Boolean {
        requireInput(value is String && value.length <= 1024 && value.matches(Regex("(?:[0-9a-fA-F]{2})*")), "RPC eventMasks")
        val bytes = (value as String).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mask = parseTlvs(bytes)[AxtpContract.STATE_EVENT_DOMAIN] ?: return false
        val offset = AxtpContract.STATE_EVENT_BIT
        return mask.size > offset / 8 && mask[offset / 8].toInt() and (1 shl (offset % 8)) != 0
    }
    private suspend fun rpc(payload: ByteArray) {
        requireInput(payload.size in 2..65536 && payload[0].toInt() == 1, "RPC requires JSON encoding")
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val message = JSONObject(decoder.decode(ByteBuffer.wrap(payload, 1, payload.size - 1)).toString())
        val op = message.uint("op", 0, 255).toInt()
        requireInput(message.opt("sid") is String && message.opt("d") is JSONObject, "RPC envelope")
        val sid = message.getString("sid"); val d = message.getJSONObject("d")
        if (op == AxtpContract.Rpc.Identify) {
            requireInput(!ready && sid.isEmpty(), "RPC Identify state")
            if (d.has("randomSeed")) d.uint("randomSeed")
            subscribed = subscription(d.opt("eventMasks") ?: "")
            sessionId = sessionIdFactory(); requireInput(isText(sessionId), "Generated sid")
            ready = true; sendRpc(AxtpContract.Rpc.Identified, JSONObject()); onReady(); return
        }
        requireInput(ready && sid == sessionId, "RPC sid/APP_READY mismatch")
        when (op) {
            AxtpContract.Rpc.Subscribe -> subscribed = subscription(d.opt("eventMasks") ?: "")
            AxtpContract.Rpc.Request -> {
                val id = d.uint("id", 1)
                requireInput(isText(d.opt("method")) && (!d.has("params") || d.opt("params") is JSONObject) && !d.has("result") && !d.has("error"), "RPC request fields")
                requireInput(id !in incoming, "Duplicate pending RPC id", AxtpContract.Error.RPC_REQUEST_ID_INVALID)
                if (incoming.size >= 32) { respond(id, null, ProtocolFailure(AxtpContract.Error.BUSY, "RPC queue budget")); return }
                val version = generation
                incoming[id] = now()
                try {
                    val result = onRequest(d.getString("method"), d.optJSONObject("params") ?: JSONObject(), sid)
                    if (!disposed && generation == version) respond(id, result, null)
                } catch (failure: Throwable) {
                    if (!disposed && generation == version) respond(id, null, failure)
                } finally { incoming.remove(id) }
            }
            AxtpContract.Rpc.RequestResponse -> {
                val id = d.uint("id", 1)
                val status = d.getJSONObject("status")
                requireInput(status.opt("ok") is Boolean, "RPC response status"); val code = status.uint("code", 0, 65535).toInt()
                if (id in outgoing) {
                    if (!status.getBoolean("ok")) finishQuery(id, Result.failure(ProtocolFailure(code, "Receiver query rejected")))
                    else if (d.opt("result") is JSONObject) finishQuery(id, Result.success(d.getJSONObject("result")))
                    else finishQuery(id, Result.failure(ProtocolFailure(AxtpContract.Error.INVALID_ARGUMENT, "Receiver query result missing")))
                }
            }
            AxtpContract.Rpc.Event -> requireInput(isText(d.opt("event")) && d.opt("data") is JSONObject, "RPC event fields")
            else -> throw ProtocolFailure(AxtpContract.Error.NOT_SUPPORTED, "Unsupported RPC operation")
        }
    }
    private fun respond(id: Long, result: JSONObject?, failure: Throwable?) {
        val code = when (failure) { null -> 0; is ProtocolFailure -> failure.code; is JSONException -> AxtpContract.Error.INVALID_ARGUMENT; else -> AxtpContract.Error.INTERNAL_ERROR }
        val status = JSONObject().put("ok", failure == null).put("code", code)
        if (failure != null) status.put("msg", failure.message?.take(128) ?: "Input request failed")
        if (failure is ProtocolFailure && failure.details != null) status.put("details", failure.details)
        val d = JSONObject().put("id", id).put("status", status)
        if (failure == null && result != null) d.put("result", result)
        sendRpc(AxtpContract.Rpc.RequestResponse, d)
    }
    fun notifyState(streamId: Long, state: JSONObject) {
        if (ready && subscribed && !disposed) sendRpc(AxtpContract.Rpc.Event,
            JSONObject().put("event", "stream.stateChanged").put("data", JSONObject().put("streamId", streamId).put("state", state)))
    }
    fun tick() {
        if (disposed) return
        try {
            requireInput(ready || now() - startedAt <= 5000, "Input handshake timeout", AxtpContract.Error.TIMEOUT)
            requireInput(!controlOpen || now() - lastReceiveAt <= maxOf(heartbeatMs * 3, 5000), "Input heartbeat timeout", AxtpContract.Error.TIMEOUT)
            requireInput(incoming.values.all { now() - it <= 5000 }, "Input RPC timeout", AxtpContract.Error.TIMEOUT)
            outgoing.filterValues { now() >= it.deadline }.keys.toList().forEach {
                finishQuery(it, Result.failure(ProtocolFailure(AxtpContract.Error.TIMEOUT, "Receiver query timed out")))
            }
            assembler.expire()
        } catch (failure: Throwable) { fault(failure) }
    }
    private fun fault(failure: Throwable) { if (!disposed) { dispose(); onFault(failure) } }
    fun dispose() {
        disposed = true; ready = false; generation++; assembler.clear(); incoming.clear()
        outgoing.keys.toList().forEach { finishQuery(it, Result.failure(ProtocolFailure(AxtpContract.Error.CANCELED, "Input session retired"))) }
    }
}
