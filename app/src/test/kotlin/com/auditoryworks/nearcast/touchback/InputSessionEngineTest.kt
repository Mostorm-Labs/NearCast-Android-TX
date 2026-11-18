package com.auditoryworks.nearcast.touchback

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InputSessionEngineTest {
    @Test fun foreignNativeOwnerIsRejectedBeforeAllocatingOrPreparingAnotherOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val native = RecordingExecutor().apply { externalOwner = "retiring-owner" }
            val engine = InputEngine(scope, native)
            engine.bind(binding())
            assertFails { engine.open(open(), "sid") }
            assertEquals(0, native.prepares)
            native.externalOwner = null
            assertEquals(1L, engine.open(open(), "sid").getLong("streamId"))
            engine.close()
            Unit
        } finally { scope.cancel() }
    }
    @Test fun standardHandshakeAndRpcUseDesktopEnvelopeAndDoNotAckSamples() = runBlocking {
        val sent = mutableListOf<ByteArray>()
        val received = mutableListOf<InputSample>()
        val faults = mutableListOf<Throwable>()
        val server = AxtpInputSession({ sent.add(it) }, { _, _, _ -> JSONObject().put("accepted", true) },
            { received.add(it) }, { faults.add(it) }, {}, { 0L }, { "AABBCCDD" })
        server.accept("4158010100160000000100010100010000040210000701070801010a0203e80b010034f2".hexBytes())
        val accept = FrameCodec.decode(sent[0])
        assertEquals(1, accept.type)
        assertEquals(2, accept.payload[0].toInt())
        val hello = JSONObject(String(FrameCodec.decode(sent[1]).payload.drop(1).toByteArray(), Charsets.UTF_8))
        assertEquals(0, hello.getInt("op"))
        server.accept(rpc(2, "", JSONObject().put("randomSeed", 123).put("eventMasks", "050101")))
        assertTrue(server.ready)
        assertEquals("AABBCCDD", server.sessionId)
        server.accept(rpc(7, server.sessionId, JSONObject().put("id", 1).put("method", "input.openStream").put("params", JSONObject())))
        val response = JSONObject(String(FrameCodec.decode(sent.last()).payload.drop(1).toByteArray(), Charsets.UTF_8))
        assertEquals(8, response.getInt("op"))
        assertTrue(response.getJSONObject("d").getJSONObject("status").getBoolean("ok"))
        val before = sent.size
        server.accept("415801030018011000010001000000070000000100000000000003e80100070027104e2085fb".hexBytes())
        assertEquals(1, received.size)
        assertEquals(before, sent.size)
        assertTrue(faults.isEmpty())
    }

    @Test fun streamBeforeHandshakeFaultsAndFaultDoesNotCallTransportClose() = runBlocking {
        val faults = mutableListOf<Throwable>()
        val server = AxtpInputSession({}, { _, _, _ -> JSONObject() }, { fail("unauthenticated input") }, { faults.add(it) })
        server.accept("415801030018011000010001000000070000000100000000000003e80100070027104e2085fb".hexBytes())
        assertEquals(1, faults.size)
        assertFalse(server.ready)
    }

    @Test fun bindsOnlyCurrentTargetNegotiatesActualCapabilitiesAndDeduplicatesOpen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val native = RecordingExecutor()
            val engine = InputEngine(scope, native)
            val binding = binding()
            engine.bind(binding)
            val wrong = open().put("sourceBindingId", "old")
            assertFails { engine.open(wrong, "sid") }
            assertEquals(0, native.prepares)
            val result = engine.open(open(), "sid")
            assertEquals(1L, result.getLong("streamId"))
            assertEquals(10, result.getJSONObject("touch").getInt("maxContacts"))
            assertEquals(3, result.getJSONObject("mouse").getInt("buttonsMask"))
            assertEquals(0, result.getJSONObject("mouse").getInt("modifierMask"))
            assertFalse(result.getJSONObject("mouse").getBoolean("continuousScroll"))
            assertEquals(result.getLong("streamId"), engine.open(open(), "sid").getLong("streamId"))
            assertEquals(1, native.prepares)
            assertFails { engine.open(open().put("maxDataBytes", 1000), "sid") }
            assertTrue(engine.accept(InputSample(1, 1, 1u, "touch", listOf(Contact(7, 0, 65535, 65535)))).await())
            assertEquals(PixelPoint(1079f, 2399f), native.plans.single().strokes.single().end)
            engine.bind(null)
            assertFalse(engine.accept(InputSample(1, 2, 2u, "touch", listOf(Contact(7, 1, 1, 1)))).await())
            assertEquals(1, native.plans.size)
            assertEquals("closed", engine.state)
        } finally { scope.cancel() }
    }

    @Test fun mouseOnlyStreamConsumesFilteredRightClicksAndKeepsLeftAndWheelWorking() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val native = RecordingExecutor()
            val engine = InputEngine(scope, native)
            engine.bind(binding())
            val request = open().put("inputTypes", JSONArray(listOf("mouse"))).apply { remove("touch") }
            request.getJSONObject("mouse").put("buttonsMask", 3)
            val opened = engine.open(request, "sid")
            assertEquals(listOf("mouse"), opened.strings("acceptedInputTypes"))
            assertEquals(3, opened.getJSONObject("mouse").getInt("buttonsMask"))
            assertEquals(AndroidMousePolicy.capability().getInt("buttonsMask"), opened.getJSONObject("mouse").getInt("buttonsMask"))
            assertFalse(opened.has("touch"))
            suspend fun mouse(seq: Long, kind: Int, buttons: Long, y: Int = 40000, wheel: Int = 0) {
                assertTrue(engine.accept(InputSample(1, seq, seq.toULong(), "mouse", kind = kind,
                    buttons = buttons, x = 30000, y = y, wheelY = wheel, dataBytes = if (kind == 2) 22 else 12)).await())
            }
            mouse(1, 1, 2); mouse(2, 0, 2, 35000); mouse(3, 1, 0)
            assertTrue("Filtered right click must not inject a touch", native.plans.all { it.strokes.isEmpty() })
            assertEquals(3L, engine.streamState(1, "sid").getLong("lastSeqId"))
            assertEquals("active", engine.state)
            mouse(4, 1, 1); mouse(5, 0, 1, 35000); mouse(6, 1, 0, 35000)
            val drag = native.plans.filter { it.strokes.isNotEmpty() }
            assertEquals(3, drag.size)
            assertFalse(drag.first().strokes.single().continuation)
            assertTrue(drag.last().strokes.single().finish)
            mouse(7, 2, 0, 35000, 120)
            assertEquals(120L, native.plans.last().durationMs)
            assertEquals(108f, native.plans.last().strokes.single().end.y - native.plans.last().strokes.single().start.y, 0.01f)
            assertEquals(7L, engine.streamState(1, "sid").getLong("lastSeqId"))
            assertEquals("active", engine.state); assertEquals(0, native.releases)
            engine.close()
            assertEquals(1, native.releases)
        } finally { scope.cancel() }
    }

    @Test fun lateNativeCompletionKeepsCleanupOwnerAndCannotResurrectStream() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val native = RecordingExecutor().apply { gate = CompletableDeferred() }
            val engine = InputEngine(scope, native, operationTimeoutMs = 30, cleanupTimeoutMs = 30)
            engine.bind(binding()); engine.open(open(), "sid")
            val submitted = engine.accept(InputSample(1, 1, 1u, "touch", listOf(Contact(1, 0, 30000, 30000))))
            assertFalse(withTimeout(2000) { submitted.await() })
            assertEquals("cleanup_blocked", engine.state)
            assertEquals(0, native.releases)
            assertFails { engine.open(open().put("commandId", "open2"), "sid") }
            native.gate!!.complete(Unit)
            withTimeout(2000) { while (engine.state != "closed") delay(5) }
            assertEquals(1, native.releases)
            assertEquals("closed", engine.state)
            engine.bind(binding())
            assertEquals(2L, engine.open(open().put("commandId", "open2"), "sid").getLong("streamId"))
            engine.close()
            Unit
        } finally { scope.cancel() }
    }

    @Test fun motionCoalescingKeepsDownAndUpBarriersAndRejectsGaps() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val native = RecordingExecutor().apply { gate = CompletableDeferred() }
            val engine = InputEngine(scope, native)
            engine.bind(binding()); engine.open(open(), "sid")
            val down = engine.accept(InputSample(1, 1, 1u, "touch", listOf(Contact(1, 0, 0, 0))))
            val move = engine.accept(InputSample(1, 2, 2u, "touch", listOf(Contact(1, 1, 20000, 20000))))
            val newest = engine.accept(InputSample(1, 3, 3u, "touch", listOf(Contact(1, 1, 40000, 40000))))
            val up = engine.accept(InputSample(1, 4, 4u, "touch", listOf(Contact(1, 2, 65535, 65535))))
            native.gate!!.complete(Unit)
            assertTrue(down.await()); assertFalse(move.await()); assertTrue(newest.await()); assertTrue(up.await())
            assertEquals(3, native.plans.size)
            assertFalse(native.plans.first().strokes.single().continuation)
            assertTrue(native.plans.last().strokes.single().finish)
            assertFalse(engine.accept(InputSample(1, 6, 6u, "touch", listOf(Contact(1, 0, 0, 0)))).await())
            assertEquals("closed", engine.state)
        } finally { scope.cancel() }
    }

    @Test fun wheelBurstAccumulatesDeltasInsteadOfAgingOutBehind120msGestures() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var clock = 0L
        try {
            val native = RecordingExecutor().apply {
                gate = CompletableDeferred()
                afterApply = { if (it.strokes.isNotEmpty()) clock += it.durationMs }
            }
            val engine = InputEngine(scope, native, now = { clock })
            engine.bind(binding()); engine.open(open(), "sid")
            val results = (1L..8L).map { seq ->
                if (seq > 1) clock += 6
                engine.accept(wheel(seq, -6))
            }
            native.gate!!.complete(Unit)
            assertTrue("A valid wheel burst must remain usable", results.last().await())
            assertEquals("active", engine.state)
            assertEquals(8L, engine.streamState(1, "sid").getLong("lastSeqId"))
            assertEquals(2, native.plans.size)
            val distance = native.plans.sumOf { (it.strokes.single().end.y - it.strokes.single().start.y).toDouble() }
            assertEquals("All wheel deltas must contribute", -43.2, distance, 0.01)
            assertEquals(0, native.releases)
            engine.close()
        } finally { scope.cancel() }
    }

    @Test fun wheelAggregationKeepsAnchorDirectionAndButtonBarriers() = runBlocking<Unit> {
        val cases = listOf(
            listOf(wheel(1, -6), wheel(2, -6), wheel(3, -6).copy(x = 40000)),
            listOf(wheel(1, -6), wheel(2, -6), wheel(3, 6)),
            listOf(wheel(1, -6), wheel(2, -6),
                InputSample(1, 3, 3u, "mouse", kind = 1, buttons = 2, dataBytes = 12),
                InputSample(1, 4, 4u, "mouse", kind = 1, buttons = 0, dataBytes = 12), wheel(5, -6))
        )
        for (samples in cases) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val native = RecordingExecutor().apply { gate = CompletableDeferred() }
                val engine = InputEngine(scope, native)
                engine.bind(binding()); engine.open(open(), "sid")
                val results = samples.map { engine.accept(it) }
                native.gate!!.complete(Unit)
                assertTrue(results.last().await())
                assertEquals(3, native.plans.count { it.strokes.isNotEmpty() })
                assertEquals("active", engine.state)
                engine.close()
            } finally { scope.cancel() }
        }
    }

    @Test fun wheelAggregationRetainsTheOldestDeadlineRatherThanExtendingStaleInput() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var clock = 0L
        try {
            val native = RecordingExecutor().apply { gate = CompletableDeferred() }
            val engine = InputEngine(scope, native, now = { clock })
            engine.bind(binding()); engine.open(open(), "sid")
            engine.accept(wheel(1, -6))
            clock = 10; engine.accept(wheel(2, -6))
            clock = 200; val last = engine.accept(wheel(3, -6))
            clock = 270; native.gate!!.complete(Unit)
            assertFalse(last.await())
            assertEquals("Input queue age", engine.lastError)
            assertEquals("closed", engine.state)
        } finally { scope.cancel() }
    }

    @Test fun wheelAggregationNeverWrapsAnInt32Delta() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val native = RecordingExecutor().apply { gate = CompletableDeferred() }
            val engine = InputEngine(scope, native)
            engine.bind(binding()); engine.open(open(), "sid")
            engine.accept(wheel(1, -6))
            engine.accept(wheel(2, Int.MAX_VALUE))
            val last = engine.accept(wheel(3, Int.MAX_VALUE))
            native.gate!!.complete(Unit)
            assertTrue(last.await())
            assertEquals(3, native.plans.size)
            assertTrue(native.plans.drop(1).all { it.strokes.single().end.y > it.strokes.single().start.y })
            engine.close()
        } finally { scope.cancel() }
    }

    private fun wheel(seq: Long, delta: Int) = InputSample(1, seq, seq.toULong(), "mouse", kind = 2,
        x = 30000, y = 40000, wheelY = delta, dataBytes = 22)

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); fail("Expected protocol rejection") } catch (_: ProtocolFailure) { }
    }
    private fun rpc(op: Int, sid: String, d: JSONObject): ByteArray = FrameCodec.encode(byteArrayOf(1) +
        JSONObject().put("sid", sid).put("op", op).put("d", d).toString().toByteArray(), 2).single()
    private fun binding() = InputBinding("sid", "rx-runtime-1", JSONObject(javaClass.getResource("/touchback/typed-input-webcast.json")!!.readText())
        .getJSONObject("targetMessage").getJSONObject("data").getJSONObject("target"),
        "source1", ScreenGeometry(1080, 2400, 0, "g1", 1))
    private fun open() = JSONObject().put("controlVersion", 1).put("runtimeInstanceId", "rx-runtime-1")
        .put("target", binding().target).put("sourceBindingId", "source1").put("commandId", "open1")
        .put("streamKind", "touchback").put("format", "input-sample-v1").put("maxDataBytes", 4096)
        .put("inputTypes", JSONArray(listOf("touch", "mouse"))).put("touch", JSONObject().put("maxContacts", 20))
        .put("mouse", JSONObject().put("buttonsMask", 7).put("modifierMask", 15).put("allowChords", true)
            .put("wheelUnits", JSONArray(listOf("detent120", "regionQ16_16"))).put("continuousScroll", true))

    private class RecordingExecutor : InputExecutor {
        var externalOwner: String? = null
        var prepares = 0; var releases = 0
        var gate: CompletableDeferred<Unit>? = null
        var afterApply: (GesturePlan) -> Unit = {}
        val plans = mutableListOf<GesturePlan>()
        override fun capabilities() = NativeCapabilities(true, 10, ownerId = externalOwner)
        override suspend fun prepare(owner: InputOwner): Boolean { prepares++; return true }
        override suspend fun apply(owner: InputOwner, plan: GesturePlan): Boolean { gate?.await(); plans.add(plan); afterApply(plan); return true }
        override suspend fun release(owner: InputOwner): Boolean { releases++; return true }
    }
}
