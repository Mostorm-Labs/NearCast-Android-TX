package com.example.screencast.webrtc

import kotlinx.coroutines.flow.SharedFlow

interface SignalingClient {
    val events: SharedFlow<NearHubEvent>

    fun connect(token: String? = null)
    fun join(pairCode: String, name: String = "Android-Sender")
    fun sendOffer(sdp: String)
    fun sendAnswer(sdp: String)
    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
    fun sendPeerLeave()
    fun dispose()
}
