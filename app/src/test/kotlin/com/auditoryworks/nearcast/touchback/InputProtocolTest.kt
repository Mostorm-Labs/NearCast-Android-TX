package com.auditoryworks.nearcast.touchback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InputProtocolTest {
    private fun corpus() = JSONObject(javaClass.getResource("/touchback/input_stream.json")!!.readText())
    private val corpusLimits = InputLimits(setOf("touch", "mouse"), maxContacts = 256,
        buttonsMask = 31, modifierMask = 15, allowChords = true, wheelUnits = setOf(0, 1), continuousScroll = true)

    @Test fun decodesIndependentOfficialFramesWithoutLosingUnsignedCursor() {
        val cases = corpus().getJSONArray("cases")
        var count = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            if (!case.getBoolean("valid")) continue
            val frames = case.getJSONArray("frames")
            var state = InputState()
            for (j in 0 until frames.length()) {
                val frame = frames.getJSONObject(j)
                val wire = FrameCodec.decode(frame.getString("hex").hexBytes())
                val sample = InputCodec.decode(wire.payload)
                val expected = frame.getJSONObject("expectDecode")
                assertEquals(case.getString("name"), expected.getLong("streamId"), sample.streamId)
                assertEquals(expected.getLong("seqId"), sample.seqId)
                assertEquals(expected.get("cursor").toString(), sample.cursor.toString())
                assertEquals(expected.getString("inputType"), sample.type)
                assertEquals(expected.getInt("dataBytes"), sample.dataBytes)
                assertEquals(expected.getInt("frameBytes"), wire.frameBytes)
                if (sample.type == "touch") {
                    val records = expected.getJSONArray("records")
                    assertEquals(records.length(), sample.contacts.size)
                    sample.contacts.forEachIndexed { index, contact ->
                        val record = records.getJSONObject(index)
                        assertEquals(Contact(record.getInt("contactId"), record.getInt("phase"),
                            record.getInt("x"), record.getInt("y")), contact)
                    }
                } else {
                    assertEquals(expected.getInt("eventKind"), sample.kind)
                    if (expected.has("modifiers")) assertEquals(expected.getInt("modifiers"), sample.modifiers)
                    if (expected.has("buttons")) assertEquals(expected.getLong("buttons"), sample.buttons)
                    if (expected.has("x")) assertEquals(expected.getInt("x"), sample.x)
                    if (expected.has("y")) assertEquals(expected.getInt("y"), sample.y)
                    if (expected.has("wheelUnit")) {
                        assertEquals(expected.getInt("wheelUnit"), sample.wheelUnit)
                        assertEquals(expected.getInt("wheelPhase"), sample.wheelPhase)
                        assertEquals(expected.getInt("wheelX"), sample.wheelX)
                        assertEquals(expected.getInt("wheelY"), sample.wheelY)
                    }
                }
                state = state.next(sample, corpusLimits)
                count++
            }
        }
        assertEquals(25, count)
    }

    @Test fun rejectsEveryInvalidOfficialCaseAtItsDecodeOrStateBoundary() {
        val cases = corpus().getJSONArray("cases")
        var rejected = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            if (case.getBoolean("valid")) continue
            assertThrows(case.getString("name"), ProtocolFailure::class.java) {
                var state = InputState()
                val frames = case.getJSONArray("frames")
                for (j in 0 until frames.length()) {
                    val sample = InputCodec.decode(FrameCodec.decode(frames.getJSONObject(j).getString("hex").hexBytes()).payload)
                    state = state.next(sample, corpusLimits)
                }
            }
            rejected++
        }
        assertEquals(13, rejected)
    }

    @Test fun rejectsCorruptedCrcAndLegacyBarePayload() {
        val frame = "415801030018011000010001000000070000000100000000000003e80100070027104e2085fb".hexBytes()
        assertEquals(7L, InputCodec.decode(FrameCodec.decode(frame).payload).streamId)
        val corrupted = frame.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(ProtocolFailure::class.java) { FrameCodec.decode(corrupted) }
        assertThrows(ProtocolFailure::class.java) { FrameCodec.decode(frame.copyOfRange(12, frame.size - 2)) }
    }

    @Test fun reassemblesOutOfOrderFragmentsAndRejectsDuplicatesAndExpiry() {
        var now = 0L
        val payload = ByteArray(60) { it.toByte() }
        val parts = FrameCodec.encode(payload, 3, 29, 9)
        val assembler = FrameAssembler { now }
        assertNull(assembler.accept(parts.last()))
        assertThrows(ProtocolFailure::class.java) { assembler.accept(parts.last()) }
        val clean = FrameAssembler { now }
        var result: StandardFrame? = null
        parts.reversed().forEach { result = clean.accept(it) ?: result }
        assertArrayEquals(payload, result!!.payload)
        val expired = FrameAssembler { now }
        expired.accept(parts[0])
        now = 2001
        assertThrows(ProtocolFailure::class.java) { expired.expire() }
    }
}

internal fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
