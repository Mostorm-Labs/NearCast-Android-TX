package com.example.screencast.webrtc

import android.util.Log
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
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected: $wsUrl")
                onStatusChange("Connected to local receiver")
                emit(NearHubEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t.message}", t)
                onStatusChange("Local connection error: ${t.message}")
                emit(NearHubEvent.Disconnected)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $code $reason")
                onStatusChange("Disconnected")
                emit(NearHubEvent.Disconnected)
            }
        })
    }

    override fun join(pairCode: String, name: String) {
        // Local mode can still use pairCode for receiver-side auth if implemented.
        send(JSONObject().apply {
            put("type", "join")
            put("pairCode", pairCode)
            put("name", name)
        })
        onStatusChange("Waiting for local peer...")
    }

    override fun sendOffer(sdp: String) {
        send(JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp)
        })
    }

    override fun sendAnswer(sdp: String) {
        send(JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp)
        })
    }

    override fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        send(JSONObject().apply {
            put("type", "ice")
            put("candidate", candidate)
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        })
    }

    override fun sendPeerLeave() {
        send(JSONObject().apply { put("type", "peer_leave") })
    }

    override fun dispose() {
        webSocket?.close(1000, "Client closing")
        scope.cancel()
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "state" -> {
                    if (json.optString("state").equals("Ready", ignoreCase = true)) {
                        markReady()
                    }
                }
                "joined" -> {
                    markReady()
                }
                "offer" -> {
                    markReady()
                    emit(NearHubEvent.OfferReceived(json.getString("sdp"), "local-peer"))
                }
                "answer" -> emit(NearHubEvent.AnswerReceived(json.getString("sdp"), "local-peer"))
                "ice", "ice-candidate" -> {
                    if (json.has("candidate") && json.optJSONObject("candidate") != null) {
                        val c = json.getJSONObject("candidate")
                        emit(
                            NearHubEvent.IceCandidateReceived(
                                candidate = c.getString("candidate"),
                                sdpMid = c.optString("sdpMid", "0"),
                                sdpMLineIndex = c.optInt("sdpMLineIndex", 0),
                                userId = "local-peer"
                            )
                        )
                    } else {
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
                "peer_leave" -> emit(NearHubEvent.PeerLeave("peer_leave", "Peer left local session"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse local signal: ${e.message}", e)
        }
    }

    private fun markReady() {
        if (hasReadySignal) return
        hasReadySignal = true
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
        webSocket?.send(json.toString())
    }

    private fun emit(event: NearHubEvent) {
        scope.launch { _events.emit(event) }
    }
}
