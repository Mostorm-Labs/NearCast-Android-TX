package com.auditoryworks.nearcast.discovery

import org.junit.Assert.*
import org.junit.Test

class ReceiverDirectoryTest {
    private fun receiver(instance: String) = ReceiverDevice(instance, instance, instance, listOf("192.0.2.21"), 8443, "", "", setOf("webcast"))
    @Test fun lateResolutionCannotResurrectLostOrPreviousScanRecords() {
        val directory = ReceiverDirectory()
        val old = directory.found("Room")!!
        assertTrue(directory.resolved("Room", old, receiver("Room")))
        directory.lost("Room")
        val current = directory.found("Room")!!
        assertFalse(directory.resolved("Room", old, receiver("Room")))
        assertTrue(directory.resolved("Room", current, receiver("Room").copy(port = 9443)))
        assertEquals(9443, directory.list().single().port)
        directory.clear()
        directory.found("Room")
        assertFalse(directory.resolved("Room", current, receiver("Room")))
        assertTrue(directory.list().isEmpty())
    }
    @Test fun repeatedAnnouncementsUpdateOneRecordAndResolveBudgetIsBounded() {
        val directory = ReceiverDirectory()
        val token = directory.found("Room")!!
        assertEquals(token, directory.found("Room"))
        assertTrue(directory.resolved("Room", token, receiver("Room")))
        assertTrue(directory.resolved("Room", token, receiver("Room").copy(name = "Updated")))
        assertEquals("Updated", directory.list().single().name)
        repeat(63) { assertNotNull(directory.found("Room$it")) }
        assertNull(directory.found("overflow"))
    }
}
