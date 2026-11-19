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
        var failWrites = false
        var writes = 0
        override fun get(deviceId: String) = values[deviceId]
        override fun save(deviceId: String, fingerprint: String) {
            if (failWrites) throw TrustStorageFailure()
            values[deviceId] = fingerprint; writes++
        }
        override fun revoke(deviceId: String) { values.remove(deviceId) }
    }
    private class Peer : ReceiverTransport {
        var key = "A1".repeat(32)
        var gate: CompletableDeferred<Unit>? = null
        var timeout = false
        var observations = 0
        val operations = mutableListOf<DisplayOperation>()
        override suspend fun observe(device: ReceiverDevice): String {
            observations++
            if (timeout) withTimeout(1) { delay(1000) }
            gate?.let { withContext(NonCancellable) { it.await() } }
            return key
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
    @Test fun firstSelectionPairsTheObservedKeyAndShowsCodeWithoutATrustDisplayOrCheckbox() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer(); val pins = Pins()
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); reaches(actions, ReceiverPhase.SHOWN)
            assertEquals(peer.key, pins.get("exact-ID"))
            assertEquals(listOf(DisplayOperation.CODE), peer.operations)
            assertNull(actions.state.value.candidate)
        } finally { scope.cancel() }
    }
    @Test fun aMatchingSavedKeyIsRecheckedWithoutRewritingOrInvalidatingItsBinding() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer(); val pins = Pins().apply { save("exact-ID", peer.key) }
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); reaches(actions, ReceiverPhase.SHOWN)
            assertEquals(1, peer.observations); assertEquals(1, pins.writes)
            assertEquals(peer.key, pins.get("exact-ID"))
        } finally { scope.cancel() }
    }
    @Test fun cancelledOrOfflineObservationsCannotPersistAKeyOrRequestACode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer().apply { gate = CompletableDeferred() }; val pins = Pins()
            var current: ReceiverDevice? = device
            val actions = ReceiverActions(scope, peer, pins, { current })
            actions.select(device); actions.cancel(); peer.gate!!.complete(Unit); delay(10)
            assertEquals(ReceiverPhase.NONE, actions.state.value.phase)
            assertTrue(pins.values.isEmpty()); assertTrue(peer.operations.isEmpty())
            peer.gate = CompletableDeferred(); actions.select(device)
            current = null; actions.devicesChanged(); peer.gate!!.complete(Unit); delay(10)
            assertEquals(ReceiverPhase.OFFLINE, actions.state.value.phase)
            assertTrue(pins.values.isEmpty()); assertTrue(peer.operations.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun aChangedKeyStaysBlockedUntilTheUserExplicitlyRemovesTheOldBinding() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = "A1".repeat(32)
            val pins = Pins().apply { save("exact-ID", old) }
            val peer = Peer().apply { key = "B2".repeat(32) }
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); reaches(actions, ReceiverPhase.KEY_CHANGED)
            actions.retryTrust(); reaches(actions, ReceiverPhase.KEY_CHANGED)
            assertEquals(old, pins.get("exact-ID")); assertTrue(peer.operations.isEmpty())
            actions.forget(); reaches(actions, ReceiverPhase.UNTRUSTED)
            actions.select(device); reaches(actions, ReceiverPhase.SHOWN)
            assertEquals(peer.key, pins.get("exact-ID")); assertEquals(listOf(DisplayOperation.CODE), peer.operations)
        } finally { scope.cancel() }
    }
    @Test fun revokingASavedKeyWhileObservationIsPendingDoesNotRecreateTrust() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer().apply { gate = CompletableDeferred() }
            val pins = Pins().apply { save("exact-ID", peer.key) }
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); pins.revoke("exact-ID"); peer.gate!!.complete(Unit)
            reaches(actions, ReceiverPhase.UNTRUSTED)
            assertNull(pins.get("exact-ID")); assertTrue(peer.operations.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun failedStorageCannotSendACodeRequestWithAnUnpersistedBinding() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer(); val pins = Pins().apply { failWrites = true }
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); reaches(actions, ReceiverPhase.ERROR)
            assertEquals(ReceiverFailure.STORAGE, actions.state.value.failure)
            assertTrue(pins.values.isEmpty()); assertTrue(peer.operations.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun savedTrustCanBeRemovedAfterTheReceiverGoesOffline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pins = Pins().apply { save("exact-ID", "A1".repeat(32)) }
            var current: ReceiverDevice? = device
            val actions = ReceiverActions(scope, Peer(), pins, { current })
            actions.select(device); reaches(actions, ReceiverPhase.SHOWN)
            current = null; actions.devicesChanged(); actions.forget(); reaches(actions, ReceiverPhase.UNTRUSTED)
            assertNull(pins.get("exact-ID"))
        } finally { scope.cancel() }
    }
    @Test fun networkTimeoutLeavesARecoverableErrorAndRetryUsesSilentPairing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val peer = Peer().apply { timeout = true }; val pins = Pins()
            val actions = ReceiverActions(scope, peer, pins, { device })
            actions.select(device); reaches(actions, ReceiverPhase.ERROR)
            assertEquals(ReceiverFailure.NETWORK, actions.state.value.failure); assertTrue(pins.values.isEmpty())
            peer.timeout = false; actions.retryTrust(); reaches(actions, ReceiverPhase.SHOWN)
        } finally { scope.cancel() }
    }
}
