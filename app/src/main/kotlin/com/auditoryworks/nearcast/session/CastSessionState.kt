package com.auditoryworks.nearcast.session

/** The network path to the receiver. Capture state is deliberately tracked separately. */
enum class TransportState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED
}

/** The lifecycle of the user-approved MediaProjection capture. */
enum class CaptureState {
    IDLE,
    REQUESTING_PERMISSION,
    STARTING,
    ACTIVE,
    PAUSED_HIDDEN,
    AWAITING_RESELECTION,
    STOPPING,
    ERROR
}

/** A conservative reason for why a new MediaProjection consent is required. */
enum class ProjectionStopReason {
    SCREEN_LOCKED,
    PROJECTION_STOPPED,
    PERMISSION_DENIED,
    CAPTURE_ERROR
}

/** Selects localized text rendered into a synthetic video frame. */
enum class StatusFrameMessage {
    CONTENT_HIDDEN,
    SCREEN_LOCKED,
    PROJECTION_STOPPED,
    PERMISSION_DENIED,
    CAPTURE_ERROR
}

data class CastSessionState(
    val transport: TransportState = TransportState.IDLE,
    val capture: CaptureState = CaptureState.IDLE,
    val stopReason: ProjectionStopReason? = null,
    val errorMessage: String? = null
) {
    val isP2PReady: Boolean
        get() = transport == TransportState.CONNECTED

    val isProjectionActive: Boolean
        get() = capture == CaptureState.ACTIVE || capture == CaptureState.PAUSED_HIDDEN

    val isContentVisible: Boolean
        get() = capture == CaptureState.ACTIVE

    val requiresReselection: Boolean
        get() = capture == CaptureState.AWAITING_RESELECTION

    val isCaptureBusy: Boolean
        get() = capture == CaptureState.REQUESTING_PERMISSION ||
            capture == CaptureState.STARTING ||
            capture == CaptureState.STOPPING

    /**
     * A foreground service must retain the in-process P2P session for these states. A first-time
     * permission request does not qualify because MediaProjection has not started yet; a repeated
     * request carries the previous stop reason and keeps the placeholder stream alive.
     */
    val shouldKeepForegroundService: Boolean
        get() = when (capture) {
            CaptureState.STARTING,
            CaptureState.ACTIVE,
            CaptureState.PAUSED_HIDDEN,
            CaptureState.AWAITING_RESELECTION -> true
            CaptureState.REQUESTING_PERMISSION -> stopReason != null
            else -> false
        }

    val statusFrameMessage: StatusFrameMessage?
        get() = when {
            capture == CaptureState.PAUSED_HIDDEN -> StatusFrameMessage.CONTENT_HIDDEN
            capture != CaptureState.AWAITING_RESELECTION &&
                !(capture == CaptureState.REQUESTING_PERMISSION && stopReason != null) -> null
            stopReason == ProjectionStopReason.SCREEN_LOCKED -> StatusFrameMessage.SCREEN_LOCKED
            stopReason == ProjectionStopReason.PERMISSION_DENIED ->
                StatusFrameMessage.PERMISSION_DENIED
            stopReason == ProjectionStopReason.CAPTURE_ERROR -> StatusFrameMessage.CAPTURE_ERROR
            else -> StatusFrameMessage.PROJECTION_STOPPED
        }
}

sealed interface CastSessionEvent {
    data class TransportChanged(val state: TransportState) : CastSessionEvent
    data object CapturePermissionRequested : CastSessionEvent
    data object CaptureStarting : CastSessionEvent
    data object CaptureStopping : CastSessionEvent
    data object CaptureActivated : CastSessionEvent
    data object CapturedContentHidden : CastSessionEvent
    data object CapturedContentVisible : CastSessionEvent
    data class ProjectionStopped(val reason: ProjectionStopReason) : CastSessionEvent
    data object CapturePermissionDenied : CastSessionEvent
    data object UserStoppedCapture : CastSessionEvent
    data class CaptureFailed(val message: String?) : CastSessionEvent
    data object SessionClosed : CastSessionEvent
}

/** Pure reducer used by WebRTC, Compose, notification, and unit tests as one source of truth. */
fun CastSessionState.reduce(event: CastSessionEvent): CastSessionState = when (event) {
    is CastSessionEvent.TransportChanged -> copy(transport = event.state)

    CastSessionEvent.CapturePermissionRequested -> copy(
        capture = CaptureState.REQUESTING_PERMISSION,
        errorMessage = null
    )

    CastSessionEvent.CaptureStarting -> copy(
        capture = CaptureState.STARTING,
        errorMessage = null
    )

    CastSessionEvent.CaptureStopping -> copy(
        capture = CaptureState.STOPPING,
        errorMessage = null
    )

    CastSessionEvent.CaptureActivated -> copy(
        capture = CaptureState.ACTIVE,
        stopReason = null,
        errorMessage = null
    )

    CastSessionEvent.CapturedContentHidden -> if (capture == CaptureState.ACTIVE) {
        copy(capture = CaptureState.PAUSED_HIDDEN)
    } else {
        this
    }

    CastSessionEvent.CapturedContentVisible -> if (capture == CaptureState.PAUSED_HIDDEN) {
        copy(capture = CaptureState.ACTIVE)
    } else {
        this
    }

    is CastSessionEvent.ProjectionStopped -> copy(
        capture = CaptureState.AWAITING_RESELECTION,
        stopReason = event.reason,
        errorMessage = null
    )

    CastSessionEvent.CapturePermissionDenied -> if (stopReason != null) {
        copy(
            capture = CaptureState.AWAITING_RESELECTION,
            stopReason = ProjectionStopReason.PERMISSION_DENIED,
            errorMessage = null
        )
    } else {
        copy(
            capture = CaptureState.IDLE,
            stopReason = ProjectionStopReason.PERMISSION_DENIED,
            errorMessage = null
        )
    }

    CastSessionEvent.UserStoppedCapture -> copy(
        capture = CaptureState.IDLE,
        stopReason = null,
        errorMessage = null
    )

    is CastSessionEvent.CaptureFailed -> if (stopReason != null) {
        copy(
            capture = CaptureState.AWAITING_RESELECTION,
            stopReason = ProjectionStopReason.CAPTURE_ERROR,
            errorMessage = event.message
        )
    } else {
        copy(
            capture = CaptureState.ERROR,
            stopReason = ProjectionStopReason.CAPTURE_ERROR,
            errorMessage = event.message
        )
    }

    CastSessionEvent.SessionClosed -> CastSessionState(
        transport = TransportState.CLOSED,
        capture = CaptureState.IDLE
    )
}
