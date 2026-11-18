package com.auditoryworks.nearcast.touchback

import org.junit.Assert.*
import org.junit.Test

class RemoteGateTest {
    @Test fun everySourcePrerequisiteMustBeTrueBeforeAdvertisingAvailable() {
        val ready = RemoteInputGate(true, true, true, true, true, true, true, true, false, true)
        assertNull(ready.disabledReason())
        assertEquals("LOCAL_INPUT_DISABLED", ready.copy(enabled = false).disabledReason())
        assertEquals("ANDROID_VERSION_UNSUPPORTED", ready.copy(supported = false).disabledReason())
        assertEquals("NATIVE_PERMISSION_UNAVAILABLE", ready.copy(permissionReady = false).disabledReason())
        assertEquals("CAPTURE_UNAVAILABLE", ready.copy(captureActive = false).disabledReason())
        assertEquals("MEDIA_PEER_UNAVAILABLE", ready.copy(peerReady = false).disabledReason())
        assertEquals("INPUT_SESSION_NOT_READY", ready.copy(appReady = false).disabledReason())
        assertEquals("TARGET_UNAVAILABLE", ready.copy(targetReady = false).disabledReason())
        assertEquals("NATIVE_GEOMETRY_UNAVAILABLE", ready.copy(geometryProven = false).disabledReason())
        assertEquals("NATIVE_CLEANUP_PENDING", ready.copy(cleanupPending = true).disabledReason())
        assertEquals("SCREEN_UNAVAILABLE", ready.copy(screenUsable = false).disabledReason())
    }
}
