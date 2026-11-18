package com.auditoryworks.nearcast.touchback

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray

/** Debug-only owned surface: hardware tests never inject into another application. */
class InputProbeActivity : Activity() {
    data class Receipt(val action: Int, val count: Int, val x: Float, val y: Float, val eventTime: Long)
    val receipts = CopyOnWriteArrayList<Receipt>()
    private val regressionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var regressionScroll: ScrollView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = object : View(this) {
            val paint = Paint().apply { color = Color.WHITE; textSize = 36f }
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(30, 45, 65))
                canvas.drawText("NearHub Cast owned input test", 40f, 100f, paint)
                canvas.drawText("Touch events: ${receipts.size}", 40f, 160f, paint)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                invalidate(); return true
            }
        }
        if (intent.getBooleanExtra("stationaryRegression", false) || intent.getBooleanExtra("mouseFilterRegression", false) ||
            intent.getBooleanExtra("wheelBurstRegression", false)) {
            val scroll = ScrollView(this)
            content.minimumHeight = resources.displayMetrics.heightPixels * 4
            scroll.addView(content, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                resources.displayMetrics.heightPixels * 4))
            regressionScroll = scroll; setContentView(scroll)
        } else setContentView(content)
        if (intent.getBooleanExtra("stationaryRegression", false)) regressionScope.launch { stationaryRegression() }
        else if (intent.getBooleanExtra("mouseFilterRegression", false) || intent.getBooleanExtra("wheelBurstRegression", false)) {
            regressionScope.launch { mouseFilterRegression(intent.getBooleanExtra("wheelBurstRegression", false)) }
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        receipts.add(Receipt(event.actionMasked, event.pointerCount, event.x, event.y, event.eventTime))
        android.util.Log.i("NearCastOwnedInput", "action=${event.actionMasked} pointers=${event.pointerCount} x=${event.x.toInt()} y=${event.y.toInt()}")
        return super.dispatchTouchEvent(event)
    }
    private suspend fun stationaryRegression() {
        var testOwner: InputOwner? = null
        var passed = false
        var failure: String? = null
        try {
            withTimeout(5000) { while (!hasWindowFocus() || !window.decorView.isLaidOut || !AccessibilityInput.capabilities().ready) delay(20) }
            // Window focus precedes completion of the Activity's input transition.
            delay(300)
            val geometry = NativeScreen.geometry(this, 1)
            val owner = InputOwner("owned-stationary-regression", 1, 1,
                InputBinding("owned-regression", "owned-test", JSONObject(), "owned-test", geometry))
            check(AccessibilityInput.prepare(owner)) { "Native prepare failed" }; testOwner = owner
            val planner = GesturePlanner()
            suspend fun touch(seq: Long, phase: Int, y: Int) {
                check(hasWindowFocus()) { "Owned window lost focus" }
                check(AccessibilityInput.apply(owner, planner.plan(InputSample(1, seq, seq.toULong(), "touch",
                    listOf(Contact(7, phase, 30000, y))), geometry))) { "Native gesture canceled at sequence $seq phase $phase" }
            }
            touch(1, 0, 40000)
            repeat(12) { touch(it + 2L, 1, 40000); delay(6) }
            for (i in 1..12) { touch(i + 13L, 1, 40000 - i * 1200); delay(6) }
            touch(26, 2, 25600)
            withTimeout(2000) { while (receipts.none { it.action == MotionEvent.ACTION_UP }) delay(10) }
            check(receipts.count { it.action == MotionEvent.ACTION_DOWN } == 1) { "Repeated native Down" }
            check(receipts.none { it.action == MotionEvent.ACTION_CANCEL }) { "Native sequence canceled" }
            check(receipts.any { it.action == MotionEvent.ACTION_MOVE }) { "Native Move missing" }
            check((regressionScroll?.scrollY ?: 0) > 100) { "Native gesture did not scroll the owned view" }
            passed = true
        } catch (problem: Exception) { failure = problem.message }
        finally {
            testOwner?.let { owner ->
                // Only on this owned test window: end any system sequence left by
                // a rejected stationary continuation before releasing the test owner.
                if (!passed && hasWindowFocus()) {
                    val point = owner.binding.geometry.point(30000, 40000)
                    runCatching { AccessibilityInput.apply(owner, GesturePlan(listOf(StrokePlan(99, point, point, false, true)), 1)) }
                }
                runCatching { AccessibilityInput.release(owner) }
            }
            android.util.Log.i("NearCastNativeRegression", JSONObject().put("case", "stationary-continuation")
                .put("passed", passed).put("failure", failure ?: JSONObject.NULL)
                .put("down", receipts.count { it.action == MotionEvent.ACTION_DOWN })
                .put("up", receipts.count { it.action == MotionEvent.ACTION_UP })
                .put("cancel", receipts.count { it.action == MotionEvent.ACTION_CANCEL })
                .put("scrollY", regressionScroll?.scrollY ?: 0).toString())
        }
    }
    private suspend fun mouseFilterRegression(burst: Boolean = false) {
        var engine: InputEngine? = null
        var passed = false
        var failure: String? = null
        var rightEvents = -1
        var leftScrollY = 0
        var wheelDelta = 0
        var wheelMerged = 0L
        var burstScrollDelta = 0
        var sequence = 0L
        try {
            withTimeout(5000) { while (!hasWindowFocus() || !window.decorView.isLaidOut || !AccessibilityInput.capabilities().ready) delay(20) }
            delay(300)
            val geometry = NativeScreen.geometry(this, 1)
            val binding = InputBinding("owned-mouse-regression", "owned-test", JSONObject(), "owned-test", geometry)
            // Actual OS operations retain their owner through Activity cancellation.
            val current = InputEngine(RemoteControlAccess.scope, AccessibilityInput)
            engine = current; current.bind(binding)
            val request = JSONObject().put("controlVersion", 1).put("streamKind", "touchback")
                .put("runtimeInstanceId", binding.runtimeInstanceId).put("target", binding.target)
                .put("sourceBindingId", binding.sourceBindingId).put("commandId", "owned-open")
                .put("format", AxtpContract.FORMAT).put("inputTypes", JSONArray(listOf("mouse")))
                .put("maxDataBytes", 4096).put("mouse", JSONObject().put("buttonsMask", 3)
                    .put("modifierMask", 0).put("allowChords", false)
                    .put("wheelUnits", JSONArray(listOf("detent120"))).put("continuousScroll", false))
            val response = current.open(request, binding.sessionId)
            check(response.getJSONObject("mouse").getInt("buttonsMask") == 3) { "RX gesture capability gate not met" }
            val stream = response.getLong("streamId")
            suspend fun mouse(kind: Int, buttons: Long, y: Int = 40000, wheel: Int = 0) {
                check(hasWindowFocus()) { "Owned window lost focus" }
                sequence++
                check(current.accept(InputSample(stream, sequence, sequence.toULong(), "mouse", kind = kind,
                    buttons = buttons, x = 30000, y = y, wheelY = wheel, dataBytes = if (kind == 2) 22 else 12)).await()) {
                    "Input failed: ${current.lastError}"
                }
            }
            mouse(1, 2); mouse(0, 2, 30000); mouse(1, 0)
            delay(100)
            rightEvents = receipts.size
            check(rightEvents == 0) { "Right click generated native touch events" }
            check(current.state == "active") { "Filtered right click closed the stream" }
            mouse(1, 1)
            for (i in 1..12) mouse(0, 1, 40000 - i * 1000)
            mouse(1, 0, 28000)
            withTimeout(2000) { while (receipts.none { it.action == MotionEvent.ACTION_UP }) delay(10) }
            leftScrollY = regressionScroll?.scrollY ?: 0
            check(leftScrollY > 100) { "Left drag did not scroll" }
            check(receipts.count { it.action == MotionEvent.ACTION_DOWN } == 1) { "Unexpected mouse Down" }
            val beforeWheel = regressionScroll?.scrollY ?: 0
            mouse(2, 0, 40000, -120)
            withTimeout(2000) { while (receipts.count { it.action == MotionEvent.ACTION_UP } < 2) delay(10) }
            wheelDelta = (regressionScroll?.scrollY ?: 0) - beforeWheel
            check(wheelDelta > 20) { "Wheel did not scroll" }
            val beforeRight = receipts.size
            mouse(1, 2); mouse(0, 2, 35000); mouse(1, 0)
            delay(100)
            check(receipts.size == beforeRight) { "Filtered right click generated an extra gesture" }
            check(receipts.none { it.action == MotionEvent.ACTION_CANCEL }) { "Native gesture canceled" }
            if (burst) {
                val beforeBurst = regressionScroll?.scrollY ?: 0
                val results = mutableListOf<Deferred<Boolean>>()
                repeat(40) { index ->
                    check(hasWindowFocus()) { "Owned window lost focus" }
                    sequence++
                    results += current.accept(InputSample(stream, sequence, sequence.toULong(), "mouse", kind = 2,
                        x = 30000, y = 40000, wheelY = -12, dataBytes = 22))
                    delay(if (index == 0) 6 else 30)
                }
                check(withTimeout(5000) { results.last().await() }) { "Wheel burst failed: ${current.lastError}" }
                delay(100)
                wheelMerged = current.coalescedWheelSamples
                burstScrollDelta = (regressionScroll?.scrollY ?: 0) - beforeBurst
                check(wheelMerged > 0) { "Wheel burst did not exercise batching" }
                check(burstScrollDelta > 0) { "Wheel burst did not scroll" }
                check(receipts.count { it.action == MotionEvent.ACTION_DOWN } == receipts.count { it.action == MotionEvent.ACTION_UP }) {
                    "Wheel burst left a pointer held"
                }
                check(receipts.none { it.action == MotionEvent.ACTION_CANCEL }) { "Wheel burst canceled a gesture" }
            }
            check(current.state == "active" && current.streamState(stream, binding.sessionId).getLong("lastSeqId") == sequence) {
                "Input stream did not consume the full sequence"
            }
            current.close(); passed = true
        } catch (problem: Exception) { failure = problem.message }
        finally {
            withContext(NonCancellable) {
                runCatching { engine?.close() }.onFailure { passed = false; failure = "Cleanup: ${it.message}" }
            }
            android.util.Log.i("NearCastNativeRegression", JSONObject().put("case", if (burst) "wheel-burst" else "mouse-right-filter")
                .put("passed", passed).put("failure", failure ?: JSONObject.NULL).put("rightEvents", rightEvents)
                .put("down", receipts.count { it.action == MotionEvent.ACTION_DOWN })
                .put("up", receipts.count { it.action == MotionEvent.ACTION_UP })
                .put("cancel", receipts.count { it.action == MotionEvent.ACTION_CANCEL })
                .put("leftScrollY", leftScrollY).put("wheelDelta", wheelDelta).put("lastSeq", sequence)
                .put("wheelMerged", wheelMerged).put("burstScrollDelta", burstScrollDelta).toString())
        }
    }
    override fun onDestroy() { regressionScope.cancel(); super.onDestroy() }
}
