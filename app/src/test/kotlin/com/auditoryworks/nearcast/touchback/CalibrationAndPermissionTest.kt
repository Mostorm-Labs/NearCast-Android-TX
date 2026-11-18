package com.auditoryworks.nearcast.touchback

import org.junit.Assert.*
import org.junit.Test

class CalibrationAndPermissionTest {
    @Test fun ordinarySharingDoesNotWaitForOptionalRemoteControlPermission() {
        val flow = RemotePermissionFlow()
        assertEquals(PermissionAction.START_CAPTURE, flow.start("cast", true, false, explainRemote = false))
        assertEquals(PermissionAction.SHOW_EXPLANATION, flow.start("cast", true, false))
        flow.openSettings("cast", resumeCapture = false)
        assertEquals(PermissionAction.NONE, flow.returnFromSettings("cast", true))
    }

    @Test fun challengeNeedsAllFourFreshMarkersInTwoConsecutiveFrames() {
        val challenge = DisplayChallenge("0123456789abcdef", 1080, 2400, 7, 0)
        fun pixel(x: Int, y: Int): Int {
            for (corner in 0..3) {
                val rect = DisplayMarker.rect(1080, 2400, corner)
                if (x in rect.x until rect.x + 96 && y in rect.y until rect.y + 96) {
                    val bit = DisplayMarker.bits("0123456789abcdef", corner)[((y - rect.y) / 8) * 12 + (x - rect.x) / 8]
                    return if (bit) 235 else 16
                }
            }
            return 128
        }
        assertFalse(challenge.observe(1080, 2400, 7, 1, ::pixel))
        assertTrue(challenge.observe(1080, 2400, 7, 2, ::pixel))
        assertFalse(challenge.observe(1080, 2400, 8, 3, ::pixel))
        assertFalse(challenge.observe(1080, 2400, 7, 2001, ::pixel))
        val wrong = DisplayChallenge("fedcba9876543210", 1080, 2400, 7, 0)
        repeat(2) { assertFalse(wrong.observe(1080, 2400, 7, it.toLong(), ::pixel)) }
    }

    @Test fun permissionReturnCannotStartCaptureAfterSessionOrRequestWasCancelled() {
        val flow = RemotePermissionFlow()
        assertEquals(PermissionAction.SHOW_EXPLANATION, flow.start("peer1", true, false))
        flow.openSettings("peer1", resumeCapture = true)
        assertEquals(PermissionAction.NONE, flow.returnFromSettings("peer2", true))
        flow.openSettings("peer1", resumeCapture = true)
        flow.cancel()
        assertEquals(PermissionAction.NONE, flow.returnFromSettings("peer1", true))
        flow.openSettings("peer1", resumeCapture = true)
        assertEquals(PermissionAction.START_CAPTURE, flow.returnFromSettings("peer1", true))
        assertEquals(PermissionAction.NONE, flow.returnFromSettings("peer1", true))
    }

    @Test fun skippingOptionalRemotePermissionDoesNotTurnOffUserPreferenceOrLoop() {
        val flow = RemotePermissionFlow()
        assertEquals(PermissionAction.SHOW_EXPLANATION, flow.start("p", true, false))
        assertEquals(PermissionAction.START_CAPTURE, flow.skip("p"))
        assertEquals(PermissionAction.START_CAPTURE, flow.start("p", true, false))
        flow.openSettings("p", true)
        assertEquals(PermissionAction.SHOW_NOT_GRANTED, flow.returnFromSettings("p", false))
        assertEquals(PermissionAction.START_CAPTURE, flow.start("p", true, false))
        assertEquals(PermissionAction.SHOW_EXPLANATION, flow.start("new", true, false))
    }
}
