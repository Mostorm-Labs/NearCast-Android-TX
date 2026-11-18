package com.auditoryworks.nearcast.touchback

import org.json.JSONObject

data class ReceiverInputFeedback(val receiverOptIn: Boolean, val controllerRequested: Boolean,
    val effectiveEnabled: Boolean, val lifecycleState: String, val diagnosticReason: String?) {
    companion object {
        fun parse(value: JSONObject, expectedTarget: JSONObject): ReceiverInputFeedback {
            requireInput(value.optJSONObject("target")?.let { validTarget(it) && canonicalJson(it) == canonicalJson(expectedTarget) } == true, "Receiver query target changed")
            requireInput(listOf("receiverOptIn", "controllerRequested", "effectiveEnabled").all { value.opt(it) is Boolean }, "Receiver query gate fields")
            val phase = value.optString("lifecycleState")
            requireInput(phase in setOf("inactive", "opening", "active", "closing", "faulted", "cleanup_blocked"), "Receiver query lifecycle")
            val reason = (value.opt("diagnosticReason") as? String)?.takeIf { it.matches(Regex("[A-Z0-9_]{1,128}")) }
            return ReceiverInputFeedback(value.getBoolean("receiverOptIn"), value.getBoolean("controllerRequested"), value.getBoolean("effectiveEnabled"), phase, reason)
        }
    }
}
