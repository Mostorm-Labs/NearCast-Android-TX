package com.auditoryworks.nearcast.touchback

import org.json.JSONObject

/** NearCast RX product metadata, independently scoped to the authenticated media peer.
 * This classification grants no input permission. A monitor claim requires local calibration.
 */
class SenderAnnouncements(private val peer: MediaIdentity) {
    private var sourceRevision = 0L
    private var shareRevision = 0L
    private var sourceValue: JSONObject? = null
    private var shareValue: JSONObject? = null
    private var surface: String? = null
    private var sharing: Boolean? = null
    private fun context() = JSONObject().put("version", 1).put("roomId", peer.roomId)
        .put("senderUserId", peer.userId).put("receiverUserId", peer.hostUserId)
    fun source(visible: Boolean, calibrated: Boolean): JSONObject {
        val next = if (visible && calibrated) "monitor" else "unknown"
        if (surface != next || sourceValue == null) {
            surface = next
            sourceValue = JSONObject().put("action", "nearcast-source").put("data", context()
                .put("revision", (++sourceRevision).toString()).put("source", JSONObject()
                    .put("platform", "android").put("clientKind", "mobile")
                    .put("product", "nearhub-cast").put("displaySurface", next)))
        }
        return sourceValue!!
    }
    fun share(visible: Boolean): JSONObject {
        if (sharing != visible || shareValue == null) {
            sharing = visible
            shareValue = JSONObject().put("action", "nearcast-share-state").put("data", context()
                .put("revision", (++shareRevision).toString()).put("sharing", visible))
        }
        return shareValue!!
    }
    fun resetTransport() { sourceValue = null; shareValue = null }
}

/** RX sends an eight-second request on the already authenticated/associated control channel. */
class SenderConsent(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var requestId: String? = null
    private var deadline = 0L
    private var status = "pending"
    fun request(value: JSONObject): Boolean {
        val id = value.opt("requestId") as? String ?: return false
        if (!isText(id) || id.any { it.isISOControl() } || value.opt("timeoutMs") != 8000 || value.opt("cancel") !is Boolean ||
            value.keys().asSequence().toSet() != setOf("requestId", "timeoutMs", "cancel")) return false
        if (value.getBoolean("cancel")) { if (id == requestId) clear(); return true }
        if (id != requestId) { requestId = id; deadline = now() + 8000; status = "pending" }
        return true
    }
    fun snapshot(enabled: Boolean, ready: Boolean): JSONObject {
        val id = requestId ?: return JSONObject()
        status = when {
            !enabled -> "denied"
            status == "accepted" && !ready -> "denied"
            status != "pending" -> status
            now() >= deadline -> "timeout"
            ready -> "accepted"
            else -> "pending"
        }
        return JSONObject().put("requestId", id).put("status", status)
    }
    fun clear() { requestId = null; deadline = 0; status = "pending" }
}
