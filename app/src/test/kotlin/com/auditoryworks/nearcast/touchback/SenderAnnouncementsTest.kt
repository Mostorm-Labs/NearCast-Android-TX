package com.auditoryworks.nearcast.touchback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SenderAnnouncementsTest {
    private val peer = MediaIdentity("room", "android-sender", "receiver")
    @Test fun nativeAndroidIdentityIsIndependentOfInputAvailability() {
        val announcements = SenderAnnouncements(peer)
        val source = announcements.source(false, false).getJSONObject("data")
        assertEquals(6, source.length())
        assertEquals("android-sender", source.getString("senderUserId"))
        assertEquals("receiver", source.getString("receiverUserId"))
        assertEquals("1", source.getString("revision"))
        val info = source.getJSONObject("source")
        assertEquals(4, info.length())
        assertEquals("android", info.getString("platform"))
        assertEquals("mobile", info.getString("clientKind"))
        assertEquals("nearhub-cast", info.getString("product"))
        assertEquals("unknown", info.getString("displaySurface"))
    }
    @Test fun onlyVisibleCalibratedCaptureAdvertisesMonitor() {
        val announcements = SenderAnnouncements(peer)
        assertEquals("unknown", announcements.source(true, false).getJSONObject("data").getJSONObject("source").getString("displaySurface"))
        val monitor = announcements.source(true, true).getJSONObject("data")
        assertEquals("monitor", monitor.getJSONObject("source").getString("displaySurface"))
        val hidden = announcements.source(false, true).getJSONObject("data")
        assertEquals("unknown", hidden.getJSONObject("source").getString("displaySurface"))
        assertTrue(hidden.getString("revision").toLong() > monitor.getString("revision").toLong())
        assertFalse(announcements.share(false).getJSONObject("data").getBoolean("sharing"))
    }
    @Test fun consentEchoesExactRequestAndFencesCancelledOrExpiredRequests() {
        var now = 100L
        val consent = SenderConsent { now }
        assertTrue(consent.request(JSONObject("""{"requestId":"request-1","timeoutMs":8000,"cancel":false}""")))
        assertEquals("pending", consent.snapshot(enabled = true, ready = false).getString("status"))
        assertEquals("accepted", consent.snapshot(enabled = true, ready = true).getString("status"))
        assertEquals("denied", consent.snapshot(enabled = false, ready = true).getString("status"))
        consent.clear()
        consent.request(JSONObject("""{"requestId":"request-2","timeoutMs":8000,"cancel":false}"""))
        now += 8001
        assertEquals("timeout", consent.snapshot(true, true).getString("status"))
        assertEquals("request-2", consent.snapshot(true, true).getString("requestId"))
        consent.request(JSONObject("""{"requestId":"request-2","timeoutMs":8000,"cancel":true}"""))
        assertEquals(0, consent.snapshot(true, true).length())
    }
    @Test fun duplicateConsentCannotExtendDeadlineOrReplaceOwnerWithMalformedRequest() {
        var now = 0L
        val consent = SenderConsent { now }
        val request = JSONObject("""{"requestId":"one","timeoutMs":8000,"cancel":false}""")
        assertTrue(consent.request(request)); now = 7999; assertTrue(consent.request(request)); now = 8001
        assertEquals("timeout", consent.snapshot(true, true).getString("status"))
        assertFalse(consent.request(JSONObject("""{"requestId":"two","timeoutMs":999999,"cancel":false}""")))
        assertEquals("one", consent.snapshot(true, true).getString("requestId"))
    }
}
