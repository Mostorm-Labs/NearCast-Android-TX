package com.auditoryworks.nearcast.touchback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

class ProtocolFailure(val code: Int, message: String, val details: JSONObject? = null) : IllegalArgumentException(message)
internal fun requireInput(condition: Boolean, message: String, code: Int = AxtpContract.Error.INVALID_ARGUMENT) {
    if (!condition) throw ProtocolFailure(code, message)
}
data class StandardFrame(val type: Int, val payload: ByteArray, val source: Int = 0, val destination: Int = 0,
    val messageId: Int = 0, val index: Int = 0, val count: Int = 1, val frameBytes: Int = 0)
object FrameCodec {
    fun crc16(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0xffff
        for (i in 0 until length) {
            crc = crc xor ((bytes[i].toInt() and 255) shl 8)
            repeat(8) { crc = (if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1) and 65535 }
        }
        return crc
    }

    fun decode(bytes: ByteArray, maxFrameBytes: Int = 65549): StandardFrame {
        requireInput(bytes.size in 14..maxFrameBytes, "Frame length/budget")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        requireInput(b.get().toInt() == 65 && b.get().toInt() == 88 && b.get().toInt() == 1, "Frame header")
        val type = b.get().toInt() and 255
        requireInput(type in 1..3, "Frame payload type")
        val length = b.short.toInt() and 65535
        requireInput(bytes.size == length + 14, "Frame length")
        requireInput(crc16(bytes, bytes.size - 2) == (b.getShort(bytes.size - 2).toInt() and 65535), "Frame CRC")
        val source = b.get().toInt() and 255
        val destination = b.get().toInt() and 255
        val message = b.short.toInt() and 65535
        val index = b.get().toInt() and 255
        val count = b.get().toInt() and 255
        requireInput(count > 0 && index < count, "Frame fragment header")
        return StandardFrame(type, bytes.copyOfRange(12, bytes.size - 2), source, destination, message, index, count, bytes.size)
    }

    fun encode(payload: ByteArray, type: Int, maxFrameBytes: Int = 4096, messageId: Int = 1): List<ByteArray> {
        requireInput(maxFrameBytes in 15..65549 && type in 1..3 && messageId in 0..65535, "Outgoing frame fields")
        val budget = maxFrameBytes - 14
        val count = (payload.size + budget - 1) / budget
        requireInput(count in 1..255, "Outgoing fragment budget")
        return (0 until count).map { index ->
            val part = payload.copyOfRange(index * budget, minOf(payload.size, (index + 1) * budget))
            val bytes = ByteArray(14 + part.size)
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
                put(65); put(88); put(1); put(type.toByte()); putShort(part.size.toShort())
                put(0); put(0); putShort(messageId.toShort()); put(index.toByte()); put(count.toByte()); put(part)
                putShort(crc16(bytes, bytes.size - 2).toShort())
            }
            bytes
        }
    }
}
class FrameAssembler(private val now: () -> Long) {
    var maxFrameBytes = 4096
    private data class Pending(val frame: StandardFrame, val started: Long,
        val parts: MutableMap<Int, ByteArray> = mutableMapOf(), var bytes: Int = 0, var frameBytes: Int = 0)
    private val pending = mutableMapOf<String, Pending>()
    private var pendingBytes = 0
    fun clear() { pending.clear(); pendingBytes = 0 }
    fun expire() { requireInput(pending.values.all { now() - it.started <= 2000 }, "Frame fragment timeout") }
    fun accept(bytes: ByteArray): StandardFrame? {
        expire()
        val frame = FrameCodec.decode(bytes, maxFrameBytes)
        val key = "${frame.type}:${frame.source}:${frame.destination}:${frame.messageId}"
        if (frame.count == 1) {
            requireInput(key !in pending, "Frame fragment identity conflict")
            return frame
        }
        val item = pending[key] ?: run {
            requireInput(pending.size < 8, "Frame fragment queue limit")
            Pending(frame, now()).also { pending[key] = it }
        }
        requireInput(item.frame.count == frame.count && frame.index !in item.parts, "Frame fragment identity/duplicate")
        requireInput(item.bytes + frame.payload.size <= 65536 && pendingBytes + frame.payload.size <= 131072, "Frame reassembly budget")
        item.parts[frame.index] = frame.payload
        item.bytes += frame.payload.size; item.frameBytes += frame.frameBytes; pendingBytes += frame.payload.size
        if (item.parts.size != frame.count) return null
        val payload = ByteArray(item.bytes)
        var offset = 0
        repeat(frame.count) { index -> item.parts.getValue(index).let { it.copyInto(payload, offset); offset += it.size } }
        pending.remove(key); pendingBytes -= item.bytes
        return frame.copy(payload = payload, index = 0, count = 1, frameBytes = item.frameBytes)
    }
}
data class Contact(val id: Int, val phase: Int, val x: Int, val y: Int)
data class InputSample(val streamId: Long, val seqId: Long, val cursor: ULong, val type: String,
    val contacts: List<Contact> = emptyList(), val kind: Int = -1, val modifiers: Int = 0, val buttons: Long = 0,
    val x: Int = 0, val y: Int = 0, val wheelUnit: Int = 0, val wheelPhase: Int = 0,
    val wheelX: Int = 0, val wheelY: Int = 0, val dataBytes: Int = 8)
object InputCodec {
    fun decode(payload: ByteArray): InputSample {
        requireInput(payload.size in 18..4112, "Input payload length/budget")
        val b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val stream = b.int.toLong() and 0xffffffffL
        val seq = b.int.toLong() and 0xffffffffL
        val cursor = b.long.toULong()
        requireInput(stream > 0 && seq > 0, "Stream identity/sequence zero")
        val type = b.get().toInt() and 255
        if (type == 1) {
            requireInput((payload.size - 17) % 7 == 0, "Touch length")
            val count = (payload.size - 17) / 7
            requireInput(count in 1..256, "Touch contact limit")
            val contacts = (0 until count).map {
                val id = b.short.toInt() and 65535
                val phase = b.get().toInt() and 255
                requireInput(phase in 0..3, "Touch phase")
                Contact(id, phase, b.short.toInt() and 65535, b.short.toInt() and 65535)
            }
            return InputSample(stream, seq, cursor, "touch", contacts, dataBytes = payload.size - 16)
        }
        requireInput(type == 2, "Unknown input type")
        val kind = b.get().toInt() and 255
        val sizes = intArrayOf(11, 11, 21, 1, 3)
        requireInput(kind in 0..4 && payload.size == 17 + sizes[kind], "Mouse exact length/kind")
        if (kind == 3) return InputSample(stream, seq, cursor, "mouse", kind = kind, dataBytes = 2)
        val modifiers = b.short.toInt() and 65535
        requireInput(modifiers and 15.inv() == 0, "Unknown Mouse modifier")
        if (kind == 4) return InputSample(stream, seq, cursor, "mouse", kind = kind, modifiers = modifiers, dataBytes = 4)
        val buttons = b.int.toLong() and 0xffffffffL
        requireInput(buttons and 31L.inv() == 0L, "Unknown Mouse button")
        val x = b.short.toInt() and 65535
        val y = b.short.toInt() and 65535
        var unit = 0; var phase = 0; var dx = 0; var dy = 0
        if (kind == 2) {
            unit = b.get().toInt() and 255; phase = b.get().toInt() and 255
            dx = b.int; dy = b.int
            requireInput(unit in 0..1 && phase in 0..4, "Mouse wheel unit/phase")
        }
        return InputSample(stream, seq, cursor, "mouse", kind = kind, modifiers = modifiers, buttons = buttons,
            x = x, y = y, wheelUnit = unit, wheelPhase = phase, wheelX = dx, wheelY = dy, dataBytes = payload.size - 16)
    }
}
data class InputLimits(val types: Set<String>, val maxContacts: Int = 10, val maxDataBytes: Int = 4096,
    val buttonsMask: Long = 1, val modifierMask: Int = 0, val allowChords: Boolean = false,
    val wheelUnits: Set<Int> = setOf(0), val continuousScroll: Boolean = false)
class InputState(val contacts: Map<Int, Contact> = emptyMap(), val seqId: Long = 0, val cursor: ULong = 0u,
    val buttons: Long = 0, val modifiers: Int = 0, val scroll: Pair<Int, Int>? = null) {
    fun next(sample: InputSample, limits: InputLimits): InputState {
        requireInput(sample.type in limits.types && sample.dataBytes <= limits.maxDataBytes, "Unaccepted input type/data budget")
        requireInput(sample.seqId == seqId + 1 && sample.seqId <= 0xffffffffL, "Input sequence gap/duplicate")
        requireInput(sample.cursor >= cursor, "Input cursor backwards")
        val nextContacts = contacts.toMutableMap()
        var nextButtons = buttons; var nextModifiers = modifiers; var nextScroll = scroll
        if (sample.type == "touch") {
            requireInput(sample.contacts.size in 1..limits.maxContacts, "Touch count limit")
            val ids = sample.contacts.map { it.id }.toSet()
            requireInput(ids.size == sample.contacts.size, "Duplicate Touch ID")
            requireInput(contacts.keys.all { it in ids }, "Missing active Touch ID")
            sample.contacts.forEach {
                requireInput(it.id in 0..65535 && it.phase in 0..3 && it.x in 0..65535 && it.y in 0..65535, "Touch fields")
                if (it.phase == 0) requireInput(it.id !in contacts, "Touch Down already active")
                else requireInput(it.id in contacts, "Orphan Touch contact")
                if (it.phase >= 2) nextContacts.remove(it.id) else nextContacts[it.id] = it
            }
        } else if (sample.kind == 3) {
            nextButtons = 0; nextModifiers = 0; nextScroll = null
        } else {
            requireInput(sample.kind in 0..4 && sample.modifiers and limits.modifierMask.inv() == 0, "Unsupported Mouse modifier/kind")
            nextModifiers = sample.modifiers
            if (sample.kind != 4) {
                requireInput(sample.buttons and limits.buttonsMask.inv() == 0L, "Unsupported Mouse button")
                requireInput(limits.allowChords || sample.buttons and (sample.buttons - 1) == 0L, "Unsupported Mouse chord")
                requireInput(if (sample.kind == 1) sample.buttons != buttons else sample.buttons == buttons, "Implicit Mouse button change")
                requireInput(sample.x in 0..65535 && sample.y in 0..65535, "Mouse coordinates")
                nextButtons = sample.buttons
                if (sample.kind == 2) {
                    requireInput(sample.wheelUnit in limits.wheelUnits, "Unsupported Mouse wheel unit")
                    if (sample.wheelUnit == 0) {
                        requireInput(sample.wheelPhase == 0 && (sample.wheelX != 0 || sample.wheelY != 0) && scroll == null, "Invalid detent wheel")
                    } else {
                        requireInput(limits.continuousScroll && sample.wheelPhase in 1..4, "Unsupported continuous wheel")
                        if (sample.wheelPhase == 1) {
                            requireInput(scroll == null, "Overlapping wheel Began")
                            nextScroll = sample.x to sample.y
                        } else {
                            requireInput(scroll == sample.x to sample.y, "Wheel without Began or changed anchor")
                            if (sample.wheelPhase >= 3) {
                                requireInput(sample.wheelX == 0 && sample.wheelY == 0, "Wheel terminal delta")
                                nextScroll = null
                            }
                        }
                    }
                }
            }
        }
        return InputState(nextContacts, sample.seqId, sample.cursor, nextButtons, nextModifiers, nextScroll)
    }
}
