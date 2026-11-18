package com.auditoryworks.nearcast.webrtc

import org.webrtc.RtpParameters

/** Screen text needs a larger budget than WebRTC's default camera-video ceiling. */
internal object ScreenVideoQuality {
    const val MAX_BITRATE_BPS = 12_000_000
    const val MAX_FRAMERATE = 30

    fun configure(parameters: RtpParameters): Boolean {
        // Never invent encodings, SSRCs or a transaction ID before negotiation.
        if (parameters.encodings.isEmpty()) return false
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        parameters.encodings.forEach { encoding ->
            encoding.maxBitrateBps = MAX_BITRATE_BPS
            encoding.minBitrateBps = null
            encoding.maxFramerate = MAX_FRAMERATE
            encoding.scaleResolutionDownBy = 1.0
        }
        return true
    }
}
