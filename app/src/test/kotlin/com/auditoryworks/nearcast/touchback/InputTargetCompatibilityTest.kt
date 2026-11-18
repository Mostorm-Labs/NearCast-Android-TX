package com.auditoryworks.nearcast.touchback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InputTargetCompatibilityTest {
    private fun receiverTarget() = JSONObject(javaClass.getResource("/touchback/rx-target-0082.json")!!.readText()).getJSONObject("data")

    @Test fun deployedReceiverContractMatchesGeneratedAndroidSnapshot() {
        assertEquals("The live RX contract must be consumed through the checked generator", receiverTarget().getString("contractFingerprint"), AxtpContract.FINGERPRINT)
    }

    @Test fun deployedReceiverTargetIsAcceptedButForeignContractIsRejected() {
        val data = receiverTarget()
        assertNull(inputTargetFailure(data))
        data.put("contractFingerprint", "feac6fa2a4b9c927708c077746166259ffeb7d5f06dc6972b29f1fc30954c2d0")
        assertEquals("TARGET_CONTRACT_MISMATCH", inputTargetFailure(data))
    }

    @Test fun malformedTargetDoesNotBecomeAnInputBinding() {
        val data = receiverTarget()
        data.getJSONObject("target").put("sessionGeneration", "0")
        assertEquals("TARGET_DESCRIPTOR_INVALID", inputTargetFailure(data))
    }

    @Test fun compatibilityFailureRemainsVisibleAfterGateRefresh() {
        val gate = RemoteInputGate(enabled = true, supported = true, permissionReady = true,
            captureActive = true, peerReady = true, appReady = true, targetReady = false,
            geometryProven = true, cleanupPending = false, screenUsable = true,
            targetFailure = "TARGET_CONTRACT_MISMATCH")
        repeat(3) { assertEquals("TARGET_CONTRACT_MISMATCH", gate.disabledReason()) }
        assertEquals("LOCAL_INPUT_DISABLED", gate.copy(enabled = false).disabledReason())
    }
}
