package com.auditoryworks.nearcast.webrtc

import android.util.Log
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder
import com.auditoryworks.nearcast.discovery.LocalReceiverConnection
import com.auditoryworks.nearcast.discovery.ReceiverKeyChanged
import com.auditoryworks.nearcast.discovery.ReceiverTrustRevoked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "NearHubSignaling"

sealed class NearHubEvent {
    object Connected : NearHubEvent()
    data class TokenReceived(val token: String, val ttl: Long) : NearHubEvent()
    data class Joined(
        val roomId: String,
        val userId: String,
        val hostUserId: String,
        val roomName: String
    ) : NearHubEvent()
    data class JoinFailed(val reason: String, val message: String) : NearHubEvent()
    data class OfferReceived(val sdp: String, val userId: String) : NearHubEvent()
    data class AnswerReceived(val sdp: String, val userId: String) : NearHubEvent()
    data class IceCandidateReceived(
        val candidate: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val userId: String
    ) : NearHubEvent()
    data class PeerLeave(val reason: String?, val message: String?) : NearHubEvent()
    object RoomClosed : NearHubEvent()
    data class Restored(
        val roomId: String,
        val userId: String,
        val hostUserId: String,
        val needsRenegotiation: Boolean
    ) : NearHubEvent()
    data class ServerError(val reason: String, val message: String) : NearHubEvent()
    object Disconnected : NearHubEvent()
}

/**
 * NearHub WebCast signaling client.
 *
 * Protocol: wss://cast.nearhub.us/?role=sender[&token=<reconnectionToken>]
 * All messages are JSON with a "type" field.
 */
class NearHubSignalingClient(
    private val baseUrl: String,
    private val onStatusChange: (String) -> Unit,
    private val localConnection: LocalReceiverConnection? = null
) : SignalingClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = localConnection?.client ?: OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private val generation = AtomicLong()
    private val terminal = AtomicBoolean()
    private var trustSubscription: AutoCloseable? = null
    @Volatile private var disposed = false

    var roomId: String? = null
        private set
    var userId: String? = null
        private set
    var hostUserId: String? = null
        private set
    var reconnectionToken: String? = null
        private set

    private val _events = MutableSharedFlow<NearHubEvent>(extraBufferCapacity = 50)
    override val events: SharedFlow<NearHubEvent> = _events

    override fun connect(token: String?) {
        if (disposed) return
        val owner = generation.incrementAndGet()
        terminal.set(false); trustSubscription?.close(); webSocket?.cancel()
        fun active() = !disposed && generation.get() == owner && !terminal.get()
        fun failed(t: Throwable) {
            if (!active() || !terminal.compareAndSet(false, true)) return
            webSocket?.cancel()
            if (localConnection != null) {
                val causes = generateSequence(t) { it.cause }.toList()
                val reason = when {
                    causes.any { it is ReceiverKeyChanged } -> "NEARCAST_KEY_MISMATCH"
                    causes.any { it is ReceiverTrustRevoked } -> "NEARCAST_TRUST_REVOKED"
                    else -> "LOCAL_CONNECTION_FAILED"
                }
                // Preserve the actionable TLS/trust error before the terminal disconnect.
                scope.launch {
                    _events.emit(NearHubEvent.ServerError(reason, reason))
                    _events.emit(NearHubEvent.Disconnected)
                }
            } else {
                onStatusChange("Connection error: ${t.message}")
                emit(NearHubEvent.Disconnected)
            }
        }
        try { localConnection?.requireTrusted() } catch (error: Exception) { failed(error); return }
        trustSubscription = localConnection?.onTrustChanged { failed(ReceiverTrustRevoked()) }
        if (!active()) return
        val url = localConnection?.endpoint ?: buildString {
            append(baseUrl.trimEnd('/'))
            append(if ('?' in baseUrl) "&" else "?")
            append("role=sender")
            val t = token ?: reconnectionToken
            if (t != null) append("&token=$t")
        }
        SessionTraceRecorder.record(TAG, "connect backend=${if (localConnection != null) "local" else "cloud"}")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!active()) { webSocket.cancel(); return }
                Log.d(TAG, "WebSocket connected")
                SessionTraceRecorder.record(TAG, "websocket open")
                onStatusChange("Connected to server")
                emit(NearHubEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!active()) return
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!active() || !terminal.compareAndSet(false, true)) return
                Log.d(TAG, "WebSocket closed: $code $reason")
                SessionTraceRecorder.record(TAG, "websocket closed code=$code reason=$reason")
                onStatusChange("Disconnected")
                emit(NearHubEvent.Disconnected)
            }
        })
    }

    override fun join(pairCode: String, name: String) {
        SessionTraceRecorder.record(TAG, "join requested")
        send(JSONObject().apply {
            put("type", "join")
            put("pairCode", pairCode)
            put("name", name)
        })
    }

    override fun sendOffer(sdp: String) {
        SessionTraceRecorder.record(TAG, "sendOffer sdpLen=${sdp.length}")
        send(JSONObject().apply {
            put("type", "offer")
            put("roomId", roomId)
            put("userId", userId)
            put("hostUserId", hostUserId)
            put("offer", JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp)
            })
        })
    }

    override fun sendAnswer(sdp: String) {
        SessionTraceRecorder.record(TAG, "sendAnswer sdpLen=${sdp.length}")
        send(JSONObject().apply {
            put("type", "answer")
            put("roomId", roomId)
            put("userId", userId)
            put("hostUserId", hostUserId)
            put("targetId", hostUserId)
            put("answer", JSONObject().apply {
                put("type", "answer")
                put("sdp", sdp)
            })
        })
    }

    override fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        SessionTraceRecorder.record(TAG, "sendIceCandidate mid=$sdpMid index=$sdpMLineIndex")
        send(JSONObject().apply {
            put("type", "ice-candidate")
            put("roomId", roomId)
            put("userId", userId)
            put("hostUserId", hostUserId)
            put("candidate", JSONObject().apply {
                put("candidate", candidate)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
            })
        })
    }

    override fun sendPeerLeave() {
        SessionTraceRecorder.record(TAG, "sendPeerLeave")
        send(JSONObject().apply {
            put("type", "peer_leave")
            put("roomId", roomId)
            put("userId", userId)
            put("hostUserId", hostUserId)
        })
    }

    private fun sendPong(ts: Long) {
        send(JSONObject().apply {
            put("type", "pong")
            put("ts", ts)
        })
    }

    override fun dispose() {
        disposed = true; generation.incrementAndGet()
        trustSubscription?.close(); trustSubscription = null
        SessionTraceRecorder.record(TAG, "dispose")
        webSocket?.close(1000, "Client closing")
        webSocket?.cancel(); webSocket = null
        localConnection?.close()
        scope.cancel()
    }

    // ── message handling ──

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "reconnection_token" -> {
                    reconnectionToken = json.getString("token")
                    val ttl = json.optLong("ttl", 60000)
                    SessionTraceRecorder.record(TAG, "reconnection_token ttl=$ttl")
                    emit(NearHubEvent.TokenReceived(reconnectionToken!!, ttl))
                }

                "joined" -> {
                    roomId = json.getString("roomId")
                    userId = json.getString("userId")
                    hostUserId = json.getString("hostUserId")
                    val roomName = json.optString("RoomName", "")
                    SessionTraceRecorder.record(TAG, "joined roomId=$roomId userId=$userId hostUserId=$hostUserId roomName=$roomName")
                    onStatusChange("Joined room: $roomName")
                    emit(NearHubEvent.Joined(roomId!!, userId!!, hostUserId!!, roomName))
                }

                "join_failed" -> {
                    val reason = json.optString("reason", "unknown")
                    val message = json.optString("message", reason)
                    SessionTraceRecorder.record(TAG, "join_failed reason=$reason message=$message")
                    onStatusChange("Join failed: $message")
                    emit(NearHubEvent.JoinFailed(reason, message))
                }

                "offer" -> {
                    val offer = json.getJSONObject("offer")
                    val fromUserId = json.optString("userId", "")
                    SessionTraceRecorder.record(TAG, "offer received from=$fromUserId sdpLen=${offer.optString("sdp").length}")
                    emit(NearHubEvent.OfferReceived(offer.getString("sdp"), fromUserId))
                }

                "answer" -> {
                    val answer = json.getJSONObject("answer")
                    val fromUserId = json.optString("userId", "")
                    SessionTraceRecorder.record(TAG, "answer received from=$fromUserId sdpLen=${answer.optString("sdp").length}")
                    emit(NearHubEvent.AnswerReceived(answer.getString("sdp"), fromUserId))
                }

                "ice-candidate" -> {
                    val candidate = json.getJSONObject("candidate")
                    val fromUserId = json.optString("userId", "")
                    SessionTraceRecorder.record(TAG, "ice received from=$fromUserId mid=${candidate.optString("sdpMid", "0")} index=${candidate.optInt("sdpMLineIndex", 0)}")
                    emit(NearHubEvent.IceCandidateReceived(
                        candidate = candidate.getString("candidate"),
                        sdpMid = candidate.optString("sdpMid", "0"),
                        sdpMLineIndex = candidate.optInt("sdpMLineIndex", 0),
                        userId = fromUserId
                    ))
                }

                "peer_leave" -> {
                    val reason = json.optString("reason").takeIf { it.isNotBlank() }
                    val message = json.optString("message").takeIf { it.isNotBlank() }
                    SessionTraceRecorder.record(TAG, "peer_leave reason=${reason ?: ""} message=${message ?: ""}")
                    onStatusChange("Left room: ${message ?: reason ?: "disconnected"}")
                    emit(NearHubEvent.PeerLeave(reason, message))
                }

                "room_closed" -> {
                    val reason = json.optString("reason", "host_left")
                    SessionTraceRecorder.record(TAG, "room_closed reason=$reason")
                    onStatusChange("Room closed: $reason")
                    emit(NearHubEvent.RoomClosed)
                }

                "restored" -> {
                    roomId = json.getString("roomId")
                    userId = json.getString("userId")
                    hostUserId = json.getString("hostUserId")
                    val needsRenegotiation = json.optBoolean("needsRenegotiation", true)
                    SessionTraceRecorder.record(TAG, "restored roomId=$roomId needsRenegotiation=$needsRenegotiation")
                    onStatusChange("Session restored")
                    emit(NearHubEvent.Restored(roomId!!, userId!!, hostUserId!!, needsRenegotiation))
                }

                "error" -> {
                    val reason = json.optString("reason", "unknown")
                    val message = json.optString("message", reason)
                    SessionTraceRecorder.record(TAG, "server_error reason=$reason message=$message")
                    onStatusChange("Error: $message")
                    emit(NearHubEvent.ServerError(reason, message))
                }

                "ping" -> {
                    sendPong(json.optLong("ts", System.currentTimeMillis()))
                }

                "pong" -> { /* heartbeat ack, no action needed */ }

                "max_members_updated" -> { /* informational, no action needed for sender */ }

                else -> {
                    Log.w(TAG, "Unknown message type: ${json.optString("type")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}", e)
        }
    }

    private fun send(json: JSONObject) {
        if (disposed || terminal.get()) return
        try { localConnection?.requireTrusted() } catch (_: Exception) {
            webSocket?.cancel(); emit(NearHubEvent.ServerError("NEARCAST_TRUST_REVOKED", "NEARCAST_TRUST_REVOKED")); return
        }
        val text = json.toString()
        SessionTraceRecorder.record(TAG, "send type=${json.optString("type")}")
        webSocket?.send(text)
    }

    private fun emit(event: NearHubEvent) {
        scope.launch { _events.emit(event) }
    }
}
