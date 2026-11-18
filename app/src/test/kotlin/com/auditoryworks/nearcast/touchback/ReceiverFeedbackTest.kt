package com.auditoryworks.nearcast.touchback

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiverFeedbackTest {
    private fun rpc(op: Int, sid: String, d: JSONObject) = FrameCodec.encode(byteArrayOf(1) +
        JSONObject().put("sid", sid).put("op", op).put("d", d).toString().toByteArray(), 2).single()
    private suspend fun ready(sent: MutableList<ByteArray>, now: () -> Long = { 0L }): AxtpInputSession {
        val session = AxtpInputSession({ sent.add(it) }, { _, _, _ -> JSONObject() }, {}, { fail(it.message) }, now = now, sessionIdFactory = { "SESSION1" })
        session.accept("4158010100160000000100010100010000040210000701070801010a0203e80b010034f2".hexBytes())
        session.accept(rpc(2, "", JSONObject().put("eventMasks", "050101")))
        return session
    }
    @Test fun receiverStateQueryUsesExistingRpcAndCorrelatesTheExactResponse() = runBlocking {
        val sent = mutableListOf<ByteArray>()
        val session = ready(sent)
        var result: Result<JSONObject>? = null
        val id = session.request("cast.getTouchbackState", JSONObject().put("target", JSONObject().put("sessionId", "presented"))) { result = it }
        val request = JSONObject(String(FrameCodec.decode(sent.last()).payload.drop(1).toByteArray()))
        assertEquals(7, request.getInt("op")); assertEquals("SESSION1", request.getString("sid"))
        assertEquals("cast.getTouchbackState", request.getJSONObject("d").getString("method"))
        val response = JSONObject().put("id", id + 1).put("status", JSONObject().put("ok", true).put("code", 0)).put("result", JSONObject().put("receiverOptIn", false))
        session.accept(rpc(8, session.sessionId, response)); assertNull(result)
        response.put("id", id)
        session.accept(rpc(8, session.sessionId, response))
        assertFalse(result!!.getOrThrow().getBoolean("receiverOptIn")); assertTrue(session.ready)
    }
    @Test fun unsupportedDiagnosticDoesNotFaultTheInputSession() = runBlocking {
        val sent = mutableListOf<ByteArray>(); val session = ready(sent)
        var result: Result<JSONObject>? = null
        val id = session.request("cast.getTouchbackState", JSONObject()) { result = it }
        session.accept(rpc(8, session.sessionId, JSONObject().put("id", id).put("status", JSONObject().put("ok", false).put("code", 3))))
        assertEquals(AxtpContract.Error.NOT_SUPPORTED, (result!!.exceptionOrNull() as ProtocolFailure).code)
        assertTrue(session.ready); assertFalse(session.disposed)
    }
    @Test fun optionalQueryTimeoutAndOwnerDisposalCompleteCallbacksWithoutLeaking() = runBlocking {
        var time = 0L
        val session = ready(mutableListOf()) { time }
        val results = mutableListOf<Result<JSONObject>>()
        session.request("cast.getTouchbackState", JSONObject(), timeoutMs = 500) { results += it }
        time = 501; session.tick()
        assertEquals(AxtpContract.Error.TIMEOUT, (results.single().exceptionOrNull() as ProtocolFailure).code)
        assertTrue(session.ready)
        session.request("cast.getTouchbackState", JSONObject()) { results += it }
        session.dispose()
        assertEquals(2, results.size)
        assertEquals(AxtpContract.Error.CANCELED, (results.last().exceptionOrNull() as ProtocolFailure).code)
    }
    @Test fun receivedStateMustBelongToCurrentTargetAndPreservesRxGateReason() {
        val target = JSONObject(javaClass.getResource("/touchback/rx-target-0082.json")!!.readText()).getJSONObject("data").getJSONObject("target")
        val response = JSONObject().put("target", target).put("receiverOptIn", false).put("controllerRequested", false)
            .put("effectiveEnabled", false).put("lifecycleState", "inactive").put("diagnosticReason", "RECEIVER_OPT_IN_REQUIRED")
        val feedback = ReceiverInputFeedback.parse(response, target)
        assertFalse(feedback.receiverOptIn); assertEquals("RECEIVER_OPT_IN_REQUIRED", feedback.diagnosticReason)
        val another = JSONObject(target.toString()).put("bindingGeneration", "14")
        assertThrows(ProtocolFailure::class.java) { ReceiverInputFeedback.parse(response, another) }
    }
}
