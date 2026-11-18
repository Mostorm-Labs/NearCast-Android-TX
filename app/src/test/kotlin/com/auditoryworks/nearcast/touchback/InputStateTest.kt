package com.auditoryworks.nearcast.touchback

import org.junit.Assert.*
import org.junit.Test

class InputStateTest {
    @Test fun filteredRightClickAndMouseCancelLeaveAnExistingTouchHeld() {
        val planner = GesturePlanner()
        val geometry = ScreenGeometry(1080, 2400, 0, "right-filter", 1)
        planner.plan(InputSample(1, 1, 1u, "touch", listOf(Contact(7, 0, 30000, 40000))), geometry)
        listOf(
            InputSample(1, 2, 2u, "mouse", kind = 1, buttons = 2, x = 40000, y = 40000),
            InputSample(1, 3, 3u, "mouse", kind = 0, buttons = 2, x = 45000, y = 30000),
            InputSample(1, 4, 4u, "mouse", kind = 1, buttons = 0),
            InputSample(1, 5, 5u, "mouse", kind = 3)
        ).forEach { assertTrue(planner.plan(it, geometry).strokes.isEmpty()) }
        val move = planner.plan(InputSample(1, 6, 6u, "touch", listOf(Contact(7, 1, 30000, 30000))), geometry)
        assertTrue(move.strokes.single().continuation)
        assertEquals(geometry.point(30000, 40000), move.strokes.single().start)
        assertTrue(planner.plan(InputSample(1, 7, 7u, "touch", listOf(Contact(7, 2, 30000, 30000))), geometry).strokes.single().finish)
    }

    @Test fun holdingFilteredRightButtonStillAllowsTheWheel() {
        val planner = GesturePlanner()
        val geometry = ScreenGeometry(1080, 2400, 0, "right-filter", 1)
        assertTrue(planner.plan(InputSample(1, 1, 1u, "mouse", kind = 1, buttons = 2), geometry).strokes.isEmpty())
        val wheel = planner.plan(InputSample(1, 2, 2u, "mouse", kind = 2, buttons = 2,
            x = 30000, y = 40000, wheelY = 120), geometry)
        assertEquals(120L, wheel.durationMs)
        assertTrue(wheel.strokes.single().finish)
        assertTrue(planner.plan(InputSample(1, 3, 3u, "mouse", kind = 1, buttons = 0), geometry).strokes.isEmpty())
    }

    @org.junit.Test fun stationaryMultiTouchSkipsOnlyMovesAndKeepsPointerDownAndUp() {
        val geometry = ScreenGeometry(1080, 2400, 0, "stationary", 1)
        val planner = GesturePlanner()
        fun sample(seq: Long, vararg contacts: Contact) = planner.plan(InputSample(1, seq, seq.toULong(), "touch", contacts.toList()), geometry)
        sample(1, Contact(1, 0, 20000, 30000))
        assertEquals(2, sample(2, Contact(1, 1, 20000, 30000), Contact(2, 0, 40000, 30000)).strokes.size)
        assertTrue(sample(3, Contact(1, 1, 20000, 30000), Contact(2, 1, 40000, 30000)).strokes.isEmpty())
        val oneUp = sample(4, Contact(1, 2, 20000, 30000), Contact(2, 1, 40000, 30000))
        assertTrue(oneUp.strokes.first { it.id == 1 }.finish)
        assertFalse(oneUp.strokes.first { it.id == 2 }.finish)
        assertTrue(sample(5, Contact(2, 1, 40000, 30000)).strokes.isEmpty())
        assertTrue(sample(6, Contact(2, 2, 40000, 30000)).strokes.single().finish)
    }
    @org.junit.Test fun stationaryContinuationDoesNotCreateAnotherSystemGestureButUpStillReleases() {
        val geometry = ScreenGeometry(1080, 2400, 0, "stationary", 1)
        val touch = GesturePlanner()
        assertFalse(touch.plan(InputSample(1, 1, 1u, "touch", listOf(Contact(7, 0, 30000, 40000))), geometry).strokes.isEmpty())
        repeat(12) { index ->
            assertTrue("Unchanged touch Move must preserve the held system stroke", touch.plan(
                InputSample(1, index + 2L, index.toULong() + 2u, "touch", listOf(Contact(7, 1, 30000 + index % 2, 40000 + index % 2))), geometry).strokes.isEmpty())
        }
        val moved = touch.plan(InputSample(1, 14, 14u, "touch", listOf(Contact(7, 1, 30000, 35000))), geometry)
        assertTrue(moved.strokes.single().continuation)
        assertEquals(geometry.point(30000, 40000), moved.strokes.single().start)
        assertTrue(touch.plan(InputSample(1, 15, 15u, "touch", listOf(Contact(7, 2, 30000, 35000))), geometry).strokes.single().finish)

        val mouse = GesturePlanner()
        mouse.plan(InputSample(1, 1, 1u, "mouse", kind = 1, buttons = 1, x = 30000, y = 40000), geometry)
        assertTrue(mouse.plan(InputSample(1, 2, 2u, "mouse", kind = 0, buttons = 1, x = 30000, y = 40000), geometry).strokes.isEmpty())
        assertTrue(mouse.plan(InputSample(1, 3, 3u, "mouse", kind = 1, buttons = 0, x = 30000, y = 40000), geometry).strokes.single().finish)
    }
    private fun touch(seq: Long, vararg contacts: Contact) = InputSample(7, seq, seq.toULong(), "touch", contacts.toList())
    private val limits = InputLimits(setOf("touch", "mouse"), 10)

    @Test fun completeSamplingRejectsMissingContactAndDuplicateOrOrphanPhases() {
        val initial = InputState().next(touch(1, Contact(7, 0, 10, 20), Contact(8, 0, 30, 40)), limits)
        assertThrows(ProtocolFailure::class.java) { initial.next(touch(2, Contact(7, 1, 11, 21)), limits) }
        assertThrows(ProtocolFailure::class.java) { initial.next(touch(2, Contact(7, 0, 10, 20), Contact(8, 1, 30, 40)), limits) }
        assertThrows(ProtocolFailure::class.java) { InputState().next(touch(1, Contact(9, 1, 1, 1)), limits) }
        val ended = initial.next(touch(2, Contact(7, 2, 10, 20), Contact(8, 3, 30, 40)), limits)
        assertTrue(ended.contacts.isEmpty())
        assertEquals(2L, ended.seqId)
    }

    @Test fun rejectsSequenceGapBackwardsCursorAndUnnegotiatedMouseFeatures() {
        assertThrows(ProtocolFailure::class.java) { InputState().next(touch(2, Contact(1, 0, 0, 0)), limits) }
        val active = InputState().next(touch(1, Contact(1, 0, 0, 0)), limits)
        assertThrows(ProtocolFailure::class.java) { active.next(touch(2, Contact(1, 1, 1, 1)).copy(cursor = 0u), limits) }
        assertThrows(ProtocolFailure::class.java) { InputState().next(InputSample(7, 1, 1u, "mouse", kind = 1, buttons = 2), limits) }
        assertThrows(ProtocolFailure::class.java) { InputState().next(InputSample(7, 1, 1u, "mouse", kind = 0, buttons = 1), limits) }
    }

    @Test fun mapsAllFourCornersAndKeepsMouseHoverOutOfTheTouchDomain() {
        val geometry = ScreenGeometry(1080, 2400, 0, "g1", 1)
        assertEquals(PixelPoint(0f, 0f), geometry.point(0, 0))
        assertEquals(PixelPoint(1079f, 2399f), geometry.point(65535, 65535))
        val planner = GesturePlanner()
        assertTrue(planner.plan(InputSample(7, 1, 1u, "mouse", kind = 0, x = 32000, y = 32000), geometry).strokes.isEmpty())
        val down = planner.plan(InputSample(7, 2, 2u, "mouse", kind = 1, buttons = 1, x = 65535, y = 65535), geometry)
        assertEquals(PixelPoint(1079f, 2399f), down.strokes.single().end)
        assertFalse(down.strokes.single().finish)
        val up = planner.plan(InputSample(7, 3, 3u, "mouse", kind = 1, buttons = 0, x = 65535, y = 65535), geometry)
        assertTrue(up.strokes.single().finish)
    }

    @Test fun wheelProducesBoundedSwipeAndTouchAndMouseCannotOverlap() {
        val planner = GesturePlanner()
        val geometry = ScreenGeometry(1080, 2400, 0, "g1", 1)
        val wheel = planner.plan(InputSample(7, 1, 1u, "mouse", kind = 2, x = 32768, y = 32768, wheelY = 120), geometry)
        assertEquals(120L, wheel.durationMs)
        assertEquals(108f, wheel.strokes.single().end.y - wheel.strokes.single().start.y, 0.01f)
        planner.plan(touch(2, Contact(1, 0, 100, 100)), geometry)
        assertThrows(ProtocolFailure::class.java) { planner.plan(InputSample(7, 3, 3u, "mouse", kind = 1, buttons = 1), geometry) }
    }
}
