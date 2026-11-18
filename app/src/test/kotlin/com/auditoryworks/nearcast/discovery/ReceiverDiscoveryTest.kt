package com.auditoryworks.nearcast.discovery

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiverDiscoveryTest {
    private fun advertisement(txt: Map<String, String> = emptyMap(), type: String = "_nearcast._tcp.local.") =
        ReceiverAdvertisement("Board", type, listOf("192.0.2.21", "2001:db8::21"), 9443,
            mapOf("id" to "Board-ID", "display" to "Meeting room", "capability" to "webcast,code-display-v1;trust-display-v1 touchback-v1") + txt)

    @Test fun srvAddressesAndExactIdentityWinOverUnrelatedTxtDestination() {
        val device = ReceiverDiscovery.normalize(advertisement(mapOf("endpoint" to "https://unrelated.invalid:1234")))
        assertNotNull(device)
        assertEquals("Board-ID", device!!.deviceId)
        assertEquals("Meeting room", device.name)
        assertEquals(9443, device.port)
        assertEquals(listOf("192.0.2.21", "2001:db8::21"), device.addresses)
        assertTrue(device.supportsCodeDisplay); assertTrue(device.supportsTrustDisplay)
    }

    @Test fun unrecognizedServicesPrivateFieldsAndInsecureEndpointsCannotEnterTheList() {
        for (type in listOf("_webrtc-signal._tcp.", "_webcast._tcp.", "_airplay._tcp.", "_raop._tcp."))
            assertNull(ReceiverDiscovery.normalize(advertisement(type = type)))
        for (key in listOf("pairCode", "roomCode", "TOKEN", "password", "secret"))
            assertNull(ReceiverDiscovery.normalize(advertisement(mapOf(key to "private"))))
        for (url in listOf("http://board.local", "ws://board.local", "https://a:b@board.local", "https://board.local/?code=1"))
            assertNull(ReceiverDiscovery.normalize(advertisement(mapOf("endpoint" to url))))
        assertNull(ReceiverDiscovery.normalize(advertisement().copy(addresses = listOf("attacker.example"))))
        assertNull(ReceiverDiscovery.normalize(advertisement().copy(port = 0)))
    }

    @Test fun displayCapabilitiesNeverSubstituteForWebcastOrEachOther() {
        val unsupported = ReceiverDiscovery.normalize(advertisement(mapOf("capability" to "code-display-v1,trust-display-v1")))
        assertNotNull(unsupported); assertFalse(unsupported!!.selectable)
        assertFalse(unsupported.supportsCodeDisplay); assertFalse(unsupported.supportsTrustDisplay)
        val codeOnly = ReceiverDiscovery.normalize(advertisement(mapOf("capability" to "webcast,code-display-v1")))!!
        assertTrue(codeOnly.supportsCodeDisplay); assertFalse(codeOnly.supportsTrustDisplay)
    }

    @Test fun displayRequestsMatchDesktopAndResultsNeverReturnAPairCode() {
        val trust = JSONObject(String(DisplayProtocol.request(DisplayOperation.TRUST, "Board-ID", "req-1")))
        assertEquals(setOf("type", "version", "deviceId", "requestId"), trust.keys().asSequence().toSet())
        assertEquals("nearcast.trust_display.request", trust.getString("type"))
        val code = JSONObject(String(DisplayProtocol.request(DisplayOperation.CODE, "Board-ID", "req-2")))
        assertEquals("sender-device-selected", code.getString("reason"))
        assertEquals("nearcast.code_display.request", code.getString("type"))
        val result = """{"type":"nearcast.code_display.result","version":1,"requestId":"req-2","status":"shown","pairCode":"NEVER-USE"}""".toByteArray()
        assertEquals("shown", DisplayProtocol.response(DisplayOperation.CODE, "req-2", result))
        assertThrows(IllegalArgumentException::class.java) { DisplayProtocol.response(DisplayOperation.CODE, "other", result) }
        assertThrows(IllegalArgumentException::class.java) { DisplayProtocol.response(DisplayOperation.TRUST, "req-2", result) }
        assertThrows(IllegalArgumentException::class.java) { DisplayProtocol.request(DisplayOperation.TRUST, "", "req-1") }
    }

    @Test fun trustRequiresExplicitFreshComparisonOfTheCurrentReceiver() {
        var now = 0L
        val candidates = TrustCandidates { now }
        val device = ReceiverDiscovery.normalize(advertisement())
        assertNotNull(device)
        val generation = candidates.begin()
        val candidate = candidates.offer(generation, device!!, "A1".repeat(32))
        assertNotNull(candidate)
        assertFalse(candidates.confirm(candidate!!, device, false))
        assertFalse(candidates.confirm(candidate, device.copy(deviceId = "board-id"), true))
        assertFalse(candidates.confirm(candidate, null, true))
        now = 60000
        assertFalse(candidates.confirm(candidate, device, true))
        now = 0
        val fresh = candidates.offer(candidates.begin(), device, "B2".repeat(32))!!
        assertTrue(candidates.confirm(fresh, device, true))
        assertFalse(candidates.confirm(fresh, device, true))
        val lateGeneration = candidates.begin()
        candidates.cancel()
        assertNull(candidates.offer(lateGeneration, device, "A1".repeat(32)))
    }
}
