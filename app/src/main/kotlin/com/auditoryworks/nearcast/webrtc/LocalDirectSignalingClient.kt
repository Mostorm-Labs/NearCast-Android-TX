package com.auditoryworks.nearcast.webrtc

import android.util.Log
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder
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

private const val TAG = "LocalDirectSignaling"

/**
 * Local signaling client for LAN direct mode.
 * It connects to a discovered receiver IP:port (no cloud signaling server).
 */
class LocalDirectSignalingClient(
    private val wsUrl: String,
    private val onStatusChange: (String) -> Unit
) : SignalingClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var hasReadySignal = false

    private val _events = MutableSharedFlow<NearHubEvent>(extraBufferCapacity = 50)
    override val events: SharedFlow<NearHubEvent> = _events

    override fun connect(token: String?) {
        val request = Request.Builder().url(wsUrl).build()
        SessionTraceRecorder.record(TAG, "connect url=$wsUrl")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected: $wsUrl")
                SessionTraceRecorder.record(TAG, "websocket open")
                onStatusChange("Connected to local receiver")
                emit(NearHubEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t.message}", t)
                SessionTraceRecorder.record(TAG, "websocket failure: ${t.message}")
                onStatusChange("Local connection error: ${t.message}")
                emit(NearHubEvent.Disconnected)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $code $reason")
                SessionTraceRecorder.record(TAG, "websocket closed code=$code reason=$reason")
                onStatusChange("Disconnected")
                emit(NearHubEvent.Disconnected)
            }
        })
    }

    override fun join(pairCode: String, name: String) {
        // Local mode can still use pairCode for receiver-side auth if implemented.
        SessionTraceRecorder.record(TAG, "join pairCode=$pairCode name=$name")
        send(JSONObject().apply {
            put("type", "join")
            put("pairCode", pairCode)
            put("name", name)
        })
        onStatusChange("Waiting for local peer...")
    }

    override fun sendOffer(sdp: String) {
        SessionTraceRecorder.record(TAG, "sendOffer sdpLen=${sdp.length}")
        send(JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp)
        })
    }

    override fun sendAnswer(sdp: String) {
        SessionTraceRecorder.record(TAG, "sendAnswer sdpLen=${sdp.length}")
        send(JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp)
        })
    }

    override fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        SessionTraceRecorder.record(TAG, "sendIceCandidate mid=$sdpMid index=$sdpMLineIndex")
        send(JSONObject().apply {
            put("type", "ice")
            put("candidate", candidate)
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        })
    }

    override fun sendPeerLeave() {
        SessionTraceRecorder.record(TAG, "sendPeerLeave")
        send(JSONObject().apply { put("type", "peer_leave") })
    }

    override fun dispose() {
        SessionTraceRecorder.record(TAG, "dispose")
        webSocket?.close(1000, "Client closing")
        scope.cancel()
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "state" -> {
                    if (json.optString("state").equals("Ready", ignoreCase = true)) {
                        SessionTraceRecorder.record(TAG, "state Ready")
                        markReady()
                    }
                }
                "joined" -> {
                    SessionTraceRecorder.record(TAG, "joined")
                    markReady()
                }
                "offer" -> {
                    SessionTraceRecorder.record(TAG, "offer received sdpLen=${json.optString("sdp").length}")
                    markReady()
                    emit(NearHubEvent.OfferReceived(json.getString("sdp"), "local-peer"))
                }
                "answer" -> {
                    SessionTraceRecorder.record(TAG, "answer received sdpLen=${json.optString("sdp").length}")
                    emit(NearHubEvent.AnswerReceived(json.getString("sdp"), "local-peer"))
                }
                "ice", "ice-candidate" -> {
                    if (json.has("candidate") && json.optJSONObject("candidate") != null) {
                        val c = json.getJSONObject("candidate")
                        SessionTraceRecorder.record(TAG, "ice received mid=${c.optString("sdpMid", "0")} index=${c.optInt("sdpMLineIndex", 0)}")
                        emit(
                            NearHubEvent.IceCandidateReceived(
                                candidate = c.getString("candidate"),
                                sdpMid = c.optString("sdpMid", "0"),
                                sdpMLineIndex = c.optInt("sdpMLineIndex", 0),
                                userId = "local-peer"
                            )
                        )
                    } else {
                        SessionTraceRecorder.record(TAG, "ice received mid=${json.optString("sdpMid", "0")} index=${json.optInt("sdpMLineIndex", 0)}")
                        emit(
                            NearHubEvent.IceCandidateReceived(
                                candidate = json.getString("candidate"),
                                sdpMid = json.optString("sdpMid", "0"),
                                sdpMLineIndex = json.optInt("sdpMLineIndex", 0),
                                userId = "local-peer"
                            )
                        )
                    }
                }
                "peer_leave" -> {
                    SessionTraceRecorder.record(TAG, "peer_leave")
                    emit(NearHubEvent.PeerLeave("peer_leave", "Peer left local session"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse local signal: ${e.message}", e)
        }
    }

    private fun markReady() {
        if (hasReadySignal) return
        hasReadySignal = true
        SessionTraceRecorder.record(TAG, "peer ready")
        onStatusChange("Local peer ready, establishing P2P...")
        emit(
            NearHubEvent.Joined(
                roomId = "local-room",
                userId = "local-sender",
                hostUserId = "local-host",
                roomName = "LAN"
            )
        )
    }

    private fun send(json: JSONObject) {
        val text = json.toString()
        SessionTraceRecorder.record(TAG, "send ${text.take(120)}")
        webSocket?.send(text)
    }

    private fun emit(event: NearHubEvent) {
        scope.launch { _events.emit(event) }
    }
}
