package com.auditoryworks.nearcast.discovery

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalCodeDisplayTest {
    @Test fun receiverBackendIsExplicitAndBoundToAdvertisedRuntime() {
        val device = ReceiverDiscovery.normalize(ReceiverAdvertisement("receiver", "_nearcast._tcp.", listOf("127.0.0.1"), 443,
            mapOf("id" to "board", "capability" to "webcast,signaling-select-v1,code-display-v2", "runtime" to "runtime-1", "local-state" to "ready", "cloud-state" to "ready")))!!
        assertEquals("runtime-1", device.codeSelection?.runtimeInstanceId)
        val request = JSONObject(String(DisplayProtocol.request(DisplayOperation.CODE, "board", "request", device.codeSelection)))
        assertEquals(2, request.getInt("version")); assertEquals("local", request.getString("backend"))
        assertEquals("runtime-1", request.getString("runtimeInstanceId"))
        val result = JSONObject("""{"type":"nearcast.code_display.result","version":2,"deviceId":"board","requestId":"request","status":"shown","backend":"local","runtimeInstanceId":"runtime-1"}""")
        assertEquals("shown", DisplayProtocol.response(DisplayOperation.CODE, "request", result.toString().toByteArray(), "board", device.codeSelection))
        for (change in listOf("backend" to "cloud", "runtimeInstanceId" to "old", "deviceId" to "another")) {
            val invalid = JSONObject(result.toString()).put(change.first, change.second)
            assertThrows(IllegalArgumentException::class.java) { DisplayProtocol.response(DisplayOperation.CODE, "request", invalid.toString().toByteArray(), "board", device.codeSelection) }
        }
    }
    @Test fun legacyReceiverUsesV1AndUnavailableLocalBackendRemainsVisible() {
        val legacy = ReceiverDevice("legacy", "board", "Legacy", listOf("127.0.0.1"), 443, "", "1", setOf("webcast"))
        assertNull(legacy.codeSelection)
        assertEquals(1, JSONObject(String(DisplayProtocol.request(DisplayOperation.CODE, "board", "request", legacy.codeSelection))).getInt("version"))
        val offline = ReceiverDiscovery.normalize(ReceiverAdvertisement("receiver", "_nearcast._tcp.", listOf("127.0.0.1"), 443,
            mapOf("id" to "board", "capability" to "webcast,signaling-select-v1,code-display-v2", "runtime" to "runtime", "local-state" to "unavailable")))!!
        assertEquals(false, offline.localReady)
    }
}
