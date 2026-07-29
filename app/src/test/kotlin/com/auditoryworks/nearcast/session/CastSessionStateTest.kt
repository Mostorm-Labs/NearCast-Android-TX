package com.auditoryworks.nearcast.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSessionStateTest {
    @Test
    fun hiddenContentRetainsSessionAndUsesPrivacyFrame() {
        val state = CastSessionState(
            transport = TransportState.CONNECTED,
            capture = CaptureState.ACTIVE
        ).reduce(CastSessionEvent.CapturedContentHidden)

        assertEquals(CaptureState.PAUSED_HIDDEN, state.capture)
        assertEquals(StatusFrameMessage.CONTENT_HIDDEN, state.statusFrameMessage)
        assertTrue(state.shouldKeepForegroundService)
        assertFalse(state.isContentVisible)
        assertTrue(state.isP2PReady)
    }

    @Test
    fun visibleContentAutomaticallyReturnsToActive() {
        val state = CastSessionState(
            transport = TransportState.CONNECTED,
            capture = CaptureState.PAUSED_HIDDEN
        ).reduce(CastSessionEvent.CapturedContentVisible)

        assertEquals(CaptureState.ACTIVE, state.capture)
        assertTrue(state.isContentVisible)
        assertEquals(null, state.statusFrameMessage)
    }

    @Test
    fun screenLockRequiresReselectionWithoutDroppingTransport() {
        val state = CastSessionState(
            transport = TransportState.CONNECTED,
            capture = CaptureState.ACTIVE
        ).reduce(
            CastSessionEvent.ProjectionStopped(ProjectionStopReason.SCREEN_LOCKED)
        )

        assertEquals(TransportState.CONNECTED, state.transport)
        assertEquals(CaptureState.AWAITING_RESELECTION, state.capture)
        assertEquals(StatusFrameMessage.SCREEN_LOCKED, state.statusFrameMessage)
        assertTrue(state.requiresReselection)
        assertTrue(state.shouldKeepForegroundService)
    }

    @Test
    fun permissionCancellationKeepsExistingPlaceholderSession() {
        val awaiting = CastSessionState(
            transport = TransportState.CONNECTED,
            capture = CaptureState.AWAITING_RESELECTION,
            stopReason = ProjectionStopReason.PROJECTION_STOPPED
        )

        val requested = awaiting.reduce(CastSessionEvent.CapturePermissionRequested)
        val denied = requested.reduce(CastSessionEvent.CapturePermissionDenied)

        assertEquals(CaptureState.AWAITING_RESELECTION, denied.capture)
        assertEquals(ProjectionStopReason.PERMISSION_DENIED, denied.stopReason)
        assertEquals(StatusFrameMessage.PERMISSION_DENIED, denied.statusFrameMessage)
        assertTrue(denied.shouldKeepForegroundService)
    }

    @Test
    fun firstPermissionCancellationDoesNotStartForegroundRetention() {
        val denied = CastSessionState(
            transport = TransportState.CONNECTED
        ).reduce(CastSessionEvent.CapturePermissionRequested)
            .reduce(CastSessionEvent.CapturePermissionDenied)

        assertEquals(CaptureState.IDLE, denied.capture)
        assertFalse(denied.shouldKeepForegroundService)
    }

    @Test
    fun transportFailureDoesNotOverwriteCaptureState() {
        val state = CastSessionState(
            transport = TransportState.CONNECTED,
            capture = CaptureState.ACTIVE
        ).reduce(CastSessionEvent.TransportChanged(TransportState.DISCONNECTED))

        assertEquals(CaptureState.ACTIVE, state.capture)
        assertEquals(TransportState.DISCONNECTED, state.transport)
        assertFalse(state.isP2PReady)
    }

    @Test
    fun explicitStopReturnsToMediaFreeConnectedState() {
        val state = CastSessionState(
            transport = TransportState.CONNECTED,
            capture = CaptureState.AWAITING_RESELECTION,
            stopReason = ProjectionStopReason.PROJECTION_STOPPED
        ).reduce(CastSessionEvent.UserStoppedCapture)

        assertEquals(TransportState.CONNECTED, state.transport)
        assertEquals(CaptureState.IDLE, state.capture)
        assertEquals(null, state.statusFrameMessage)
        assertFalse(state.shouldKeepForegroundService)
    }
}
