package com.auditoryworks.nearcast.touchback

import kotlin.math.abs
import kotlin.math.roundToInt

data class PixelPoint(val x: Float, val y: Float)
data class ScreenGeometry(val width: Int, val height: Int, val rotation: Int, val revision: String, val captureGeneration: Long) {
    init { require(width >= 320 && height >= 240 && rotation in 0..3 && revision.isNotBlank()) }
    fun point(x: Int, y: Int): PixelPoint {
        requireInput(x in 0..65535 && y in 0..65535, "Normalized coordinate range")
        return PixelPoint((x.toDouble() * (width - 1) / 65535).roundToInt().toFloat(),
            (y.toDouble() * (height - 1) / 65535).roundToInt().toFloat())
    }
}
data class StrokePlan(val id: Int, val start: PixelPoint, val end: PixelPoint, val continuation: Boolean, val finish: Boolean)
data class GesturePlan(val strokes: List<StrokePlan>, val durationMs: Long = 16)
class GesturePlanner {
    private val points = mutableMapOf<Int, PixelPoint>()
    private var domain: String? = null
    private fun gesture(strokes: List<StrokePlan>): GesturePlan {
        // A stationary continuation produces no MotionEvents in Android's injector,
        // which reports cancellation. Keep the existing held strokes instead. Test
        // the whole snapshot: stationary peers must remain when another finger moves
        // or a pointer is added/released.
        val stationary = strokes.all { it.continuation && !it.finish && it.start == it.end }
        return GesturePlan(if (stationary) emptyList() else strokes)
    }
    fun plan(sample: InputSample, geometry: ScreenGeometry): GesturePlan {
        if (sample.type == "touch") {
            requireInput(domain == null || domain == "touch", "Native touch domain busy", AxtpContract.Error.BUSY)
            val strokes = sample.contacts.map { contact ->
                val end = geometry.point(contact.x, contact.y)
                val start = points[contact.id]
                requireInput(if (contact.phase == 0) start == null else start != null, "Gesture contact lifecycle")
                if (contact.phase >= 2) points.remove(contact.id) else points[contact.id] = end
                StrokePlan(contact.id, start ?: end, end, start != null, contact.phase >= 2)
            }
            domain = if (points.isEmpty()) null else "touch"
            return gesture(strokes)
        }
        if (sample.kind == 4) return GesturePlan(emptyList())
        if (sample.kind == 3) {
            // A filtered mouse sequence must never cancel a native Touch sequence.
            if (domain != "mouse") return GesturePlan(emptyList())
            val strokes = points.map { (id, point) -> StrokePlan(id, point, point, true, true) }
            points.clear(); domain = null
            return GesturePlan(strokes, 1)
        }
        requireInput(sample.buttons and AndroidMousePolicy.ACCEPTED_BUTTONS_MASK.inv() == 0L && sample.modifiers == 0, "Mouse features")
        // Validate/advance the original wire state in InputState; filter only the
        // native plan, so consuming a right click never creates a sequence gap.
        val buttons = sample.buttons and AndroidMousePolicy.INJECTED_BUTTONS_MASK
        val heldPoint = points[65536]
        if (sample.kind != 2 && buttons == 0L && heldPoint == null) return GesturePlan(emptyList())
        requireInput(domain == null || domain == "mouse", "Native touch domain busy", AxtpContract.Error.BUSY)
        val point = geometry.point(sample.x, sample.y)
        if (sample.kind == 2) {
            requireInput(points.isEmpty() && buttons == 0L && sample.wheelUnit == 0 && sample.wheelPhase == 0, "Wheel domain/features")
            val distance = minOf(geometry.width, geometry.height) * 0.1
            val start = PixelPoint(point.x.coerceIn(1f, geometry.width - 2f), point.y.coerceIn(1f, geometry.height - 2f))
            val end = PixelPoint((start.x - sample.wheelX / 120.0 * distance).coerceIn(1.0, geometry.width - 2.0).toFloat(),
                (start.y + sample.wheelY / 120.0 * distance).coerceIn(1.0, geometry.height - 2.0).toFloat())
            return GesturePlan(if (end == start) emptyList() else listOf(StrokePlan(65537, start, end, false, true)), 120)
        }
        val finish = buttons == 0L
        if (finish) points.remove(65536) else points[65536] = point
        domain = if (points.isEmpty()) null else "mouse"
        return gesture(listOf(StrokePlan(65536, heldPoint ?: point, point, heldPoint != null, finish)))
    }
}
data class MarkerRect(val x: Int, val y: Int)
object DisplayMarker {
    const val SIZE = 96
    fun rect(width: Int, height: Int, corner: Int) = MarkerRect(if (corner % 2 == 0) 16 else width - 112, if (corner < 2) 16 else height - 112)
    fun bits(token: String, corner: Int): List<Boolean> {
        require(token.matches(Regex("[0-9a-f]{16}")) && corner in 0..3)
        val data = (7 downTo 0).map { (0xa7 shr it) and 1 }.toMutableList()
        data.add((corner shr 1) and 1); data.add(corner and 1)
        token.forEach { ch -> for (bit in 3 downTo 0) data.add((ch.digitToInt(16) shr bit) and 1) }
        while (data.size < 100) data.add(data.size % 2)
        return (0 until 144).map { index ->
            val x = index % 12; val y = index / 12
            (if (x == 0 || y == 0 || x == 11 || y == 11) (x + y) % 2 else data[(y - 1) * 10 + x - 1]) == 1
        }
    }
}
class DisplayChallenge(val token: String, val width: Int, val height: Int, val generation: Long, val startedAt: Long) {
    private val patterns = (0..3).map { DisplayMarker.bits(token, it) }
    private var consecutive = 0
    private var lastFrameAt = -1L
    fun observe(frameWidth: Int, frameHeight: Int, captureGeneration: Long, now: Long, luma: (Int, Int) -> Int): Boolean {
        if (captureGeneration != generation || now < startedAt || now - startedAt > 2000 || now <= lastFrameAt ||
            frameWidth < 320 || frameHeight < 240 || abs(frameWidth.toLong() * height - frameHeight.toLong() * width) > 2L * maxOf(width, height)) {
            consecutive = 0; return false
        }
        lastFrameAt = now
        val match = (0..3).all { corner ->
            val rect = DisplayMarker.rect(width, height, corner)
            patterns[corner].withIndex().all { (index, light) ->
                val x = ((rect.x + (index % 12) * 8 + 4.0) * frameWidth / width).roundToInt().coerceIn(1, frameWidth - 2)
                val y = ((rect.y + (index / 12) * 8 + 4.0) * frameHeight / height).roundToInt().coerceIn(1, frameHeight - 2)
                var average = 0
                for (dy in -1..1) for (dx in -1..1) average += luma(x + dx, y + dy)
                if (light) average >= 190 * 9 else average <= 65 * 9
            }
        }
        consecutive = if (match) consecutive + 1 else 0
        return consecutive >= 2
    }
}
