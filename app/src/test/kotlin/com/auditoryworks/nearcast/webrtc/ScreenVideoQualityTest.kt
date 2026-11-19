package com.auditoryworks.nearcast.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RtpParameters

class ScreenVideoQualityTest {
    private fun parameters(vararg encodings: RtpParameters.Encoding): RtpParameters {
        // The library's Java-only value constructor is package-private; no native
        // PeerConnection is needed to verify changes to negotiated parameters.
        val constructor = RtpParameters::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance("negotiated-transaction", RtpParameters.DegradationPreference.BALANCED,
            null, emptyList<RtpParameters.HeaderExtension>(), encodings.toList(), emptyList<RtpParameters.Codec>()) as RtpParameters
    }

    @Test fun keepsNativeResolutionAndAppliesDefaultBudgetWithoutForcingAMinimum() {
        val encoding = RtpParameters.Encoding(null, true, 2.0).apply {
            maxBitrateBps = 2_500_000; minBitrateBps = 2_500_000; maxFramerate = 15
        }
        val parameters = parameters(encoding)
        assertTrue(ScreenVideoQuality.configure(parameters))
        assertEquals(Integer.valueOf(8_000_000), encoding.maxBitrateBps)
        assertNull("Congestion control must be able to reduce the rate", encoding.minBitrateBps)
        assertEquals(Integer.valueOf(30), encoding.maxFramerate)
        assertEquals(1.0, encoding.scaleResolutionDownBy!!, 0.0)
        assertEquals(RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION, parameters.degradationPreference)
    }

    @Test fun retainsTheNegotiatedTransactionLayersAndIdentifiers() {
        val enabled = RtpParameters.Encoding("screen", true, 1.0).apply {
            ssrc = 42L; scalabilityMode = "L1T1"; bitratePriority = 2.0
        }
        val disabled = RtpParameters.Encoding("disabled", false, 1.0).apply { ssrc = 43L }
        val parameters = parameters(enabled, disabled)
        assertTrue(ScreenVideoQuality.configure(parameters))
        assertEquals("negotiated-transaction", parameters.transactionId)
        assertEquals(2, parameters.encodings.size)
        assertSame(enabled, parameters.encodings[0]); assertSame(disabled, parameters.encodings[1])
        assertEquals("screen", enabled.rid); assertEquals(java.lang.Long.valueOf(42), enabled.ssrc)
        assertEquals("L1T1", enabled.scalabilityMode); assertEquals(2.0, enabled.bitratePriority, 0.0)
        assertFalse(disabled.active)
    }

    @Test fun defersUntilWebrtcProvidesTheActualEncodings() {
        val parameters = parameters()
        assertFalse(ScreenVideoQuality.configure(parameters))
        assertTrue(parameters.encodings.isEmpty())
        assertEquals(RtpParameters.DegradationPreference.BALANCED, parameters.degradationPreference)
    }
}
