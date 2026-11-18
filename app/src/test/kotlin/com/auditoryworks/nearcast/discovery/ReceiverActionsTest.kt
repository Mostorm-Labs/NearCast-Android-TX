package com.auditoryworks.nearcast.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiverActionsTest {
    private val device = ReceiverDevice("Board", "exact-ID", "Board", listOf("192.0.2.21"), 8443, "", "", setOf("webcast", "trust-display-v1", "code-display-v1"))
    private class Pins : ReceiverPins {
        val values = mutableMapOf<String, String>()
        override fun get(deviceId: String) = values[deviceId]
        override fun save(deviceId: String, fingerprint: String) { values[deviceId] = fingerprint }
        override fun revoke(deviceId: String) { values.remove(deviceId) }
    }
    private class Peer : ReceiverTransport {
        var key = "A1".repeat(32)
        var gate: CompletableDeferred<Unit>? = null
        var timeout = false
        val operations = mutableListOf<DisplayOperation>()
        override suspend fun observe(device: ReceiverDevice): String {
            if (timeout) withTimeout(1) { delay(1000) }
            gate?.let { withContext(NonCancellable) { it.await() } }; return key
        }
        override suspend fun post(device: ReceiverDevice, operation: DisplayOperation, bytes: ByteArray, fingerprint: String): ByteArray {
            if (fingerprint != key) throw ReceiverKeyChanged()
            operations.add(operation)
            val request = JSONObject(String(bytes))
            return JSONObject().put("type", "nearcast.${operation.wireName}.result").put("version", 1)
                .put("requestId", request.getString("requestId")).put("status", "shown").toString().toByteArray()
        }
    }
    private suspend fun reaches(actions: ReceiverActions, phase: ReceiverPhase) {
        val state = withTimeoutOrNull(500) { actions.state.first { it.phase == phase } }
        assertNotNull("Expected $phase, got ${actions.state.value.phase}", state)
    }
    @Test fun firstSelectionNeverPersistsOrRequestsACodeUntilPhysicalConfirmation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer(); val pins = Pins()
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); reaches(actions, ReceiverPhase.COMPARE)
            assertTrue(pins.values.isEmpty()); assertEquals(listOf(DisplayOperation.TRUST), peer.operations)
            actions.confirm(false); assertTrue(pins.values.isEmpty())
            actions.confirm(true); reaches(actions, ReceiverPhase.SHOWN)
            assertEquals(peer.key, pins.get("exact-ID"))
            assertEquals(listOf(DisplayOperation.TRUST, DisplayOperation.CODE), peer.operations)
            actions.forget(); reaches(actions, ReceiverPhase.UNTRUSTED); assertTrue(pins.values.isEmpty())
            actions.showCode(); assertEquals(2, peer.operations.size)
        } finally { scope.cancel() }
    }
    @Test fun cancelledLateObservationAndOfflineConfirmationCannotGrantTrust() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer().apply { gate = CompletableDeferred() }; val pins = Pins()
            var current: ReceiverDevice? = device
            val actions = ReceiverActions(scope, peer, pins, { current })
            actions.select(device); actions.cancel(); peer.gate!!.complete(Unit); delay(10)
            assertEquals(ReceiverPhase.NONE, actions.state.value.phase); assertTrue(pins.values.isEmpty()); assertTrue(peer.operations.isEmpty())
            peer.gate = null; actions.select(device); reaches(actions, ReceiverPhase.COMPARE)
            current = null; actions.devicesChanged(); actions.confirm(true)
            assertEquals(ReceiverPhase.OFFLINE, actions.state.value.phase); assertTrue(pins.values.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun changedSavedKeyRequiresAnotherExplicitConfirmation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pins = Pins().apply { save("exact-ID", "A1".repeat(32)) }
            val peer = Peer().apply { key = "B2".repeat(32) }
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); reaches(actions, ReceiverPhase.KEY_CHANGED)
            assertEquals("A1".repeat(32), pins.get("exact-ID")); assertTrue(peer.operations.isEmpty())
            actions.retryTrust(); reaches(actions, ReceiverPhase.COMPARE)
            assertEquals("A1".repeat(32), actions.state.value.previousFingerprint)
            actions.confirm(true); reaches(actions, ReceiverPhase.SHOWN)
            assertEquals(peer.key, pins.get("exact-ID"))
        } finally { scope.cancel() }
    }
    @Test fun savedTrustCanBeRemovedAfterTheReceiverGoesOffline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pins = Pins().apply { save("exact-ID", "A1".repeat(32)) }
            var current: ReceiverDevice? = device
            val actions = ReceiverActions(scope, Peer(), pins, { current })
            actions.select(device); reaches(actions, ReceiverPhase.SHOWN)
            current = null; actions.devicesChanged(); actions.forget()
            reaches(actions, ReceiverPhase.UNTRUSTED)
            assertNull(pins.get("exact-ID"))
        } finally { scope.cancel() }
    }
    @Test fun networkTimeoutLeavesARecoverableErrorInsteadOfAnEndlessSpinner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer().apply { timeout = true }
            val pins = Pins()
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); reaches(actions, ReceiverPhase.ERROR)
            assertEquals(ReceiverFailure.NETWORK, actions.state.value.failure)
            assertTrue(pins.values.isEmpty())
            peer.timeout = false; actions.retryTrust(); reaches(actions, ReceiverPhase.COMPARE)
        } finally { scope.cancel() }
    }
}
