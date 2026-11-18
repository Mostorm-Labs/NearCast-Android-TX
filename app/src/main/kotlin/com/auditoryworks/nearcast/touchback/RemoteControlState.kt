package com.auditoryworks.nearcast.touchback

enum class CalibrationState { IDLE, RUNNING, VERIFIED, FAILED }
data class RemoteControlState(val enabled: Boolean = true, val supported: Boolean = true,
    val permissionEnabled: Boolean = false, val serviceConnected: Boolean = false,
    val channelReady: Boolean = false, val appReady: Boolean = false,
    val calibration: CalibrationState = CalibrationState.IDLE, val sourceReady: Boolean = false,
    val inputState: String = "closed", val cleanupPending: Boolean = false,
    val disabledReason: String? = "CAPTURE_UNAVAILABLE", val error: String? = null,
    val submittedSamples: Long = 0, val discardedSamples: Long = 0,
    val compatibilityFailure: String? = null, val receiverFeedback: ReceiverInputFeedback? = null)
data class RemoteInputGate(val enabled: Boolean, val supported: Boolean, val permissionReady: Boolean,
    val captureActive: Boolean, val peerReady: Boolean, val appReady: Boolean, val targetReady: Boolean,
    val geometryProven: Boolean, val cleanupPending: Boolean, val screenUsable: Boolean,
    val targetFailure: String? = null) {
    fun disabledReason(): String? = when {
        !enabled -> "LOCAL_INPUT_DISABLED"
        !supported -> "ANDROID_VERSION_UNSUPPORTED"
        !permissionReady -> "NATIVE_PERMISSION_UNAVAILABLE"
        cleanupPending -> "NATIVE_CLEANUP_PENDING"
        !screenUsable -> "SCREEN_UNAVAILABLE"
        !captureActive -> "CAPTURE_UNAVAILABLE"
        !peerReady -> "MEDIA_PEER_UNAVAILABLE"
        !appReady -> "INPUT_SESSION_NOT_READY"
        !targetReady -> targetFailure ?: "TARGET_UNAVAILABLE"
        !geometryProven -> "NATIVE_GEOMETRY_UNAVAILABLE"
        else -> null
    }
}
