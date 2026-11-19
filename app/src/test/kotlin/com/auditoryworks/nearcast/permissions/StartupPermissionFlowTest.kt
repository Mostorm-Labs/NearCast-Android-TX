package com.auditoryworks.nearcast.permissions

import org.junit.Assert.*
import org.junit.Test

class StartupPermissionFlowTest {
    @Test fun firstUseShowsOneGuideAndSerializesSystemPermissionRequests() {
        val flow = StartupPermissionFlow()
        flow.check(hasSeenSetup = false, audioGranted = false, sharingActive = false)
        assertTrue(flow.state.value.showing)
        assertTrue(flow.request(StartupPermission.AUDIO))
        assertFalse(flow.request(StartupPermission.NOTIFICATIONS))
        assertFalse(flow.defer(false))
        assertFalse(flow.finish(true))
        flow.returned()
        assertTrue(flow.state.value.showing)
        assertNull(flow.state.value.pending)
        assertFalse(flow.finish(false))
        assertTrue(flow.finish(true))
        assertFalse(flow.state.value.showing)
    }

    @Test fun deferralAndActivityRecreationDoNotLoopBackIntoTheGuide() {
        val flow = StartupPermissionFlow()
        flow.check(false, false, false)
        assertTrue(flow.defer(false))
        flow.check(false, false, false)
        assertFalse(flow.state.value.showing)
        val recreated = StartupPermissionFlow()
        recreated.restore(flow.state.value)
        recreated.check(true, false, false)
        assertFalse(recreated.state.value.showing)
        val nextLaunch = StartupPermissionFlow()
        nextLaunch.check(true, false, false)
        assertTrue(nextLaunch.state.value.showing)
    }

    @Test fun existingSharingIsNotInterruptedAndOptionalPermissionsDoNotBlockReadyUsers() {
        val sharing = StartupPermissionFlow()
        sharing.check(false, false, true)
        assertFalse(sharing.state.value.showing)
        assertFalse(sharing.request(StartupPermission.AUDIO))
        val ready = StartupPermissionFlow()
        ready.check(true, true, false)
        assertTrue(ready.state.value.checked)
        assertFalse(ready.state.value.showing)
        ready.open(true)
        assertTrue(ready.finish(true))
    }

    @Test fun newlyRevokedRequiredPermissionIsDetectedEvenAfterRestoringACompletedCheck() {
        val original = StartupPermissionFlow()
        original.check(true, true, false)
        val restored = StartupPermissionFlow()
        restored.restore(original.state.value)
        restored.check(true, false, false)
        assertTrue(restored.state.value.showing)
        assertFalse(restored.finish(false))
    }

    @Test fun returningFromSettingsOnlyRefreshesTheGuideAndDoesNotCompleteOrStartSharing() {
        val flow = StartupPermissionFlow()
        flow.check(false, false, false)
        flow.request(StartupPermission.AUDIO)
        val restored = StartupPermissionFlow()
        restored.restore(flow.state.value)
        assertEquals(StartupPermission.AUDIO, restored.state.value.pending)
        restored.returned()
        assertTrue(restored.state.value.showing)
        assertNull(restored.state.value.pending)
        assertFalse(restored.finish(false))
        assertTrue(restored.defer(false))
    }
}
