package com.auditoryworks.nearcast.touchback

import android.content.Intent
import android.app.UiAutomation
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeInputDeviceTest {
    @Test fun actualServiceDeliversTapDragAndTwoFingerEventsToOwnedWindow() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<InputProbeActivity>(Intent(context, InputProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            // UiAutomation otherwise suppresses the very service this test exercises.
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            RemoteControlAccess.refresh()
            var probe: InputProbeActivity? = null
            scenario.onActivity { probe = it }
            // Android 17 may mark a previously bound service crashed when instrumentation
            // force-stops its package. Allow the existing authorization to be reconnected in
            // public settings, and never inject while that settings window has focus.
            val ready = withTimeoutOrNull(90000) {
                while (!RemoteControlAccess.state.value.serviceConnected ||
                    !withContext(Dispatchers.Main) { probe!!.hasWindowFocus() }) {
                    RemoteControlAccess.refresh(); delay(100)
                }
                true
            } == true
            assertTrue("Reconnect the already authorized service and return to the owned test window", ready)
            val geometry = withContext(Dispatchers.Main) { NativeScreen.geometry(context, 1) }
            val binding = InputBinding("device-test", "rx-test", JSONObject(), "owned-test", geometry)
            val owner = InputOwner("device-test-owner", 1, 1, binding)
            assertTrue(AccessibilityInput.prepare(owner))
            try {
                val planner = GesturePlanner()
                suspend fun touch(seq: Long, vararg contacts: Contact) {
                    assertTrue(AccessibilityInput.apply(owner, planner.plan(InputSample(1, seq, seq.toULong(), "touch", contacts.toList()), geometry)))
                }
                touch(1, Contact(1, 0, 24000, 26000))
                delay(700) // A stationary continuation must remain down for a long press.
                touch(2, Contact(1, 2, 24000, 26000))
                touch(3, Contact(2, 0, 20000, 30000))
                touch(4, Contact(2, 1, 35000, 34000))
                touch(5, Contact(2, 2, 40000, 36000))
                touch(6, Contact(3, 0, 22000, 30000))
                touch(7, Contact(3, 1, 22000, 30000), Contact(4, 0, 42000, 30000))
                touch(8, Contact(3, 1, 16000, 30000), Contact(4, 1, 48000, 30000))
                touch(9, Contact(3, 2, 16000, 30000), Contact(4, 1, 48000, 30000))
                touch(10, Contact(4, 2, 48000, 30000))
                withTimeout(3000) { while (probe!!.receipts.count { it.action == MotionEvent.ACTION_UP } < 3) delay(20) }
                val events = probe!!.receipts.toList()
                assertEquals(3, events.count { it.action == MotionEvent.ACTION_DOWN })
                assertEquals(3, events.count { it.action == MotionEvent.ACTION_UP })
                assertTrue(events.any { it.action == MotionEvent.ACTION_POINTER_DOWN && it.count == 2 })
                assertTrue(events.any { it.action == MotionEvent.ACTION_POINTER_UP && it.count == 2 })
                assertTrue(events.any { it.action == MotionEvent.ACTION_MOVE })
                val firstDown = events.first { it.action == MotionEvent.ACTION_DOWN }
                val firstUp = events.first { it.action == MotionEvent.ACTION_UP }
                assertTrue("Long press must preserve its original down time", firstUp.eventTime - firstDown.eventTime >= 650)
            } finally { assertTrue("Owned pointers must be released", AccessibilityInput.release(owner)) }
        } finally { scenario.close() }
    }
}
