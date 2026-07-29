package com.auditoryworks.nearcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusVideoFrameProducerTest {
    @Test
    fun largePortraitFrameIsScaledToAnEvenMaximumEdge() {
        assertEquals(576 to 1280, StatusVideoFrameProducer.fitWithinMaxEdge(1440, 3200))
    }

    @Test
    fun landscapeAspectRatioIsPreservedWithinRoundingTolerance() {
        val (width, height) = StatusVideoFrameProducer.fitWithinMaxEdge(2340, 1080)

        assertEquals(1280, width)
        assertEquals(590, height)
        assertTrue(width % 2 == 0)
        assertTrue(height % 2 == 0)
    }

    @Test
    fun invalidAndOddDimensionsAreClampedToEvenValues() {
        val (width, height) = StatusVideoFrameProducer.fitWithinMaxEdge(1, 3)

        assertEquals(2, width)
        assertEquals(2, height)
        assertTrue(width % 2 == 0)
        assertTrue(height % 2 == 0)
    }
}
