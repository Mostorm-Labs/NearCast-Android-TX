package com.auditoryworks.nearcast.touchback

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.auditoryworks.nearcast.service.ScreenCaptureService
import com.auditoryworks.nearcast.webrtc.*
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Debug-only localhost receiver fixture. Uses the production capture/controller unchanged. */
class CaptureProbeActivity : ComponentActivity() {
    private var manager: WebRtcManager? = null
    private lateinit var status: TextView
    private var request = 0L
    private var receipts = 0
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val pending = ++request
            ScreenCaptureService.start(this)
            Handler(Looper.getMainLooper()).postDelayed({
                if (pending == request && !isFinishing) runCatching { manager?.startScreenCapture(result.data!!) }
                    .onFailure { Log.e("NearCastProbe", "capture start", it) }
            }, 800)
        } else manager?.markCapturePermissionDenied()
    }
    private val audioConsent = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(28, 44, 64)) }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; text = "Local device test; waiting for receiver" }
        layout.addView(status)
        val row = LinearLayout(this)
        fun button(title: String, action: () -> Unit) { row.addView(Button(this).apply { text = title; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, 140, 1f)) }
        button("Share") { startCapture() }
        button("Stop") { request++; manager?.stopScreenCapture() }
        button("Rotate") { rotate() }
        button("Retry") { manager?.retryRemoteControl() }
        layout.addView(row)
        layout.addView(object : View(this) {
            val paint = Paint().apply { color = Color.WHITE; textSize = 42f; isAntiAlias = true }
            override fun onDraw(canvas: Canvas) {
                canvas.drawText("NearHub Cast owned capture / input surface", 30f, 90f, paint)
                canvas.drawText("Received touch events: $receipts", 30f, 155f, paint)
                paint.color = Color.CYAN
                canvas.drawCircle(width * .3f, height * .6f, 55f, paint)
                canvas.drawCircle(width * .7f, height * .6f, 55f, paint)
                paint.color = Color.WHITE
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                receipts++
                Log.i("NearCastProbe", JSONObject().put("event", "touch").put("action", event.actionMasked)
                    .put("points", event.pointerCount).put("receipts", receipts).toString())
                invalidate(); return true
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        val client = LocalDirectSignalingClient("ws://127.0.0.1:18888") { message -> runOnUiThread { status.text = message } }
        val rtc = WebRtcManager(applicationContext, client) { message -> runOnUiThread { status.text = message } }
        manager = rtc
        lifecycleScope.launch {
            rtc.remoteControlState.collect { state ->
                status.text = "capture=${rtc.sessionState.value.capture} input=${state.inputState}\ncalibration=${state.calibration} source=${state.sourceReady}\nreason=${state.disabledReason} error=${state.error}"
                Log.i("NearCastProbe", JSONObject().put("event", "state").put("capture", rtc.sessionState.value.capture.name)
                    .put("input", state.inputState).put("calibration", state.calibration.name).put("source", state.sourceReady)
                    .put("reason", state.disabledReason).put("submitted", state.submittedSamples).put("error", state.error).toString())
            }
        }
        client.connect()
    }
    private fun startCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioConsent.launch(Manifest.permission.RECORD_AUDIO); return
        }
        manager?.markCapturePermissionRequested()
        captureConsent.launch((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
    }
    private fun rotate() { requestedOrientation = if (resources.configuration.orientation == 1) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.getStringExtra("action")) {
            "share" -> startCapture()
            "stop" -> { request++; manager?.stopScreenCapture() }
            "rotate" -> rotate()
            "fullscreen" -> {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            "retry" -> manager?.retryRemoteControl()
            "disable" -> RemoteControlAccess.setEnabled(false)
            "enable" -> RemoteControlAccess.setEnabled(true)
            "finish" -> finish()
        }
    }
    override fun onDestroy() { request++; manager?.stop(); manager = null; super.onDestroy() }
}
