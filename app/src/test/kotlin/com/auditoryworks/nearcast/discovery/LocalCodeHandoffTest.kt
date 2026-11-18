package com.auditoryworks.nearcast.discovery

import org.junit.Assert.*
import org.junit.Test

class LocalCodeHandoffTest {
    private val pin = "A1".repeat(32)
    private val device = ReceiverDevice("board", "board-id", "Board", listOf("192.0.2.4"),
        8443, "", "", setOf("webcast", "code-display-v1"), runtimeInstanceId = "run-1", localReady = true)
    private val shown = ReceiverActionState(ReceiverPhase.SHOWN, device, fingerprint = pin)

    @Test fun displayedCodeHandsTheVerifiedLiveReceiverStraightToPairing() {
        assertEquals(device, shown.localCodeReceiver(device, pin))
    }

    @Test fun anOfflineChangedOrRevokedReceiverCannotBeSelectedByALateCompletion() {
        assertNull(shown.localCodeReceiver(null, pin))
        assertNull(shown.localCodeReceiver(device.copy(runtimeInstanceId = "run-2"), pin))
        assertNull(shown.localCodeReceiver(device.copy(addresses = listOf("192.0.2.5")), pin))
        assertNull(shown.localCodeReceiver(device.copy(localReady = false), pin))
        assertNull(shown.localCodeReceiver(device.copy(capabilities = emptySet()), pin))
        assertNull(shown.localCodeReceiver(device, null))
        assertNull(shown.localCodeReceiver(device, "B2".repeat(32)))
    }

    @Test fun progressErrorsAndUnconfirmedTrustNeverAdvanceToPairing() {
        for (phase in ReceiverPhase.entries.filter { it != ReceiverPhase.SHOWN }) {
            assertNull("Unexpected handoff for $phase", shown.copy(phase = phase).localCodeReceiver(device, pin))
        }
        assertNull(shown.copy(fingerprint = null).localCodeReceiver(device, pin))
        assertNull(shown.copy(fingerprint = "bad").localCodeReceiver(device, "bad"))
    }
}
