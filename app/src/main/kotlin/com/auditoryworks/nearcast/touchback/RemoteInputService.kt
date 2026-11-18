package com.auditoryworks.nearcast.touchback

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.*
import java.util.UUID

object NativeScreen {
    @Suppress("DEPRECATION")
    fun geometry(context: Context, generation: Long): ScreenGeometry {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("Default display unavailable")
        val metrics = DisplayMetrics(); display.getRealMetrics(metrics)
        return ScreenGeometry(metrics.widthPixels, metrics.heightPixels, display.rotation, UUID.randomUUID().toString(), generation)
    }
    fun usable(context: Context): Boolean = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
        !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    fun matches(context: Context, expected: ScreenGeometry): Boolean = runCatching {
        val actual = geometry(context, expected.captureGeneration)
        actual.width == expected.width && actual.height == expected.height && actual.rotation == expected.rotation
    }.getOrDefault(false)
}

class RemoteInputService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    @Volatile private var overlayToken: String? = null
    @Volatile private var laidOut = false
    private var expiry: Runnable? = null
    override fun onServiceConnected() { super.onServiceConnected(); AccessibilityInput.connect(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { hideMarkers(); AccessibilityInput.disconnect(this); return super.onUnbind(intent) }
    override fun onDestroy() { hideMarkers(); AccessibilityInput.disconnect(this); super.onDestroy() }
    internal fun markersReady(token: String) = overlayToken == token && laidOut
    @Suppress("DEPRECATION")
    internal fun showMarkers(token: String, geometry: ScreenGeometry): Boolean {
        hideMarkers()
        val view = object : View(this) {
            val paint = Paint().apply { isAntiAlias = false }
            val patterns = (0..3).map { DisplayMarker.bits(token, it) }
            override fun onDraw(canvas: Canvas) {
                for (corner in 0..3) {
                    val rect = DisplayMarker.rect(geometry.width, geometry.height, corner)
                    patterns[corner].forEachIndexed { index, bit ->
                        paint.color = if (bit) Color.WHITE else Color.BLACK
                        val x = rect.x + index % 12 * 8f; val y = rect.y + index / 12 * 8f
                        canvas.drawRect(x, y, x + 8, y + 8, paint)
                    }
                }
            }
        }.apply { systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            overlay = view; overlayToken = token
            view.post {
                val location = IntArray(2); view.getLocationOnScreen(location)
                if (overlay === view) laidOut = location[0] == 0 && location[1] == 0 && view.width == geometry.width && view.height == geometry.height
            }
            expiry = Runnable { hideMarkers(token) }.also { handler.postDelayed(it, 2000) }
            true
        } catch (_: RuntimeException) { hideMarkers(token); false }
    }
    internal fun hideMarkers(token: String? = null) {
        if (token != null && token != overlayToken) return
        expiry?.let(handler::removeCallbacks); expiry = null
        overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(it) } }
        overlay = null; overlayToken = null; laidOut = false
    }
}

/** The process-wide native guard survives controller and DataChannel replacement. */
object AccessibilityInput : InputExecutor {
    @Volatile private var service: RemoteInputService? = null
    private var owner: InputOwner? = null
    private var ownerService: RemoteInputService? = null
    private data class Held(val stroke: GestureDescription.StrokeDescription, val end: PixelPoint)
    private val held = mutableMapOf<Int, Held>()
    private var pending: CompletableDeferred<Boolean>? = null
    private var uncertain = false
    val connected get() = service != null
    val cleanupBlocked get() = owner != null && uncertain
    internal fun connect(value: RemoteInputService) { service = value; RemoteControlAccess.refresh() }
    internal fun disconnect(value: RemoteInputService) {
        if (service !== value) return
        service = null
        if (ownerService === value && (held.isNotEmpty() || pending != null)) uncertain = true
        pending?.complete(false); pending = null
        RemoteControlAccess.refresh()
    }
    @Suppress("DEPRECATION")
    override fun capabilities(): NativeCapabilities {
        val current = service
        val access = RemoteControlAccess.state.value
        val ready = Build.VERSION.SDK_INT >= 26 && current != null && access.enabled && access.permissionEnabled &&
            NativeScreen.usable(current) && !(current.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled &&
            current.magnificationController.scale == 1f
        return NativeCapabilities(ready, minOf(10, GestureDescription.getMaxStrokeCount()), cleanupBlocked, owner?.id)
    }
    override suspend fun prepare(owner: InputOwner): Boolean = withContext(Dispatchers.Main.immediate) {
        if (this@AccessibilityInput.owner?.id == owner.id) return@withContext true
        val current = service
        if (this@AccessibilityInput.owner != null || current == null || !capabilities().ready || !NativeScreen.matches(current, owner.binding.geometry)) return@withContext false
        this@AccessibilityInput.owner = owner; ownerService = current; uncertain = false
        true
    }
    override suspend fun apply(owner: InputOwner, plan: GesturePlan): Boolean = withContext(Dispatchers.Main.immediate) {
        val current = service
        if (this@AccessibilityInput.owner?.id != owner.id || current == null || current !== ownerService ||
            !capabilities().ready || !NativeScreen.matches(current, owner.binding.geometry) || uncertain) return@withContext false
        if (plan.strokes.isEmpty()) return@withContext true
        dispatch(current, owner, plan)
    }
    override suspend fun release(owner: InputOwner): Boolean = withContext(Dispatchers.Main.immediate) {
        if (this@AccessibilityInput.owner == null) return@withContext true
        if (this@AccessibilityInput.owner?.id != owner.id || pending != null || uncertain) return@withContext false
        val current = service
        if (held.isNotEmpty()) {
            if (Build.VERSION.SDK_INT < 26 || current == null || current !== ownerService) return@withContext false
            val end = GesturePlan(held.map { (id, h) -> StrokePlan(id, h.end, h.end, true, true) }, 1)
            dispatch(current, owner, end)
        }
        val released = held.isEmpty() && pending == null && !uncertain
        if (released) { this@AccessibilityInput.owner = null; ownerService = null; RemoteControlAccess.refresh() }
        released
    }
    @android.annotation.TargetApi(26)
    private suspend fun dispatch(current: RemoteInputService, owner: InputOwner, plan: GesturePlan): Boolean {
        if (pending != null || this.owner?.id != owner.id) return false
        val builder = GestureDescription.Builder()
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(Display.DEFAULT_DISPLAY)
        val futureHeld = held.toMutableMap()
        val hasContinuation = plan.strokes.any { it.continuation }
        for (stroke in plan.strokes) {
            val path = Path().apply { moveTo(stroke.start.x, stroke.start.y); if (stroke.end != stroke.start) lineTo(stroke.end.x, stroke.end.y) }
            val descriptor = if (stroke.continuation) {
                val previous = held[stroke.id] ?: return false
                if (previous.end != stroke.start) return false
                previous.stroke.continueStroke(path, 0, plan.durationMs, !stroke.finish)
            } else GestureDescription.StrokeDescription(path, if (hasContinuation) 1 else 0, plan.durationMs, !stroke.finish)
            builder.addStroke(descriptor)
            if (stroke.finish) futureHeld.remove(stroke.id) else futureHeld[stroke.id] = Held(descriptor, stroke.end)
        }
        val operation = CompletableDeferred<Boolean>()
        pending = operation
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (pending === operation) pending = null
                operation.complete(this@AccessibilityInput.owner?.id == owner.id && !uncertain)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                // A system cancellation confirms that the injected sequence ended.
                android.util.Log.w("Touchback", "Native gesture canceled strokes=${plan.strokes.size} continuation=$hasContinuation durationMs=${plan.durationMs}")
                if (pending === operation) { held.clear(); pending = null; uncertain = false }
                operation.complete(false)
            }
        }
        val accepted = try { current.dispatchGesture(builder.build(), callback, Handler(Looper.getMainLooper())) } catch (_: RuntimeException) { false }
        if (!accepted) {
            android.util.Log.w("Touchback", "Native gesture dispatch rejected strokes=${plan.strokes.size} continuation=$hasContinuation durationMs=${plan.durationMs}")
            if (pending === operation) pending = null; return false
        }
        held.clear(); held.putAll(futureHeld)
        return operation.await()
    }
    internal fun showMarkers(token: String, geometry: ScreenGeometry) = service?.showMarkers(token, geometry) == true
    internal fun markersReady(token: String) = service?.markersReady(token) == true
    internal fun hideMarkers(token: String? = null) { service?.hideMarkers(token) }
}
