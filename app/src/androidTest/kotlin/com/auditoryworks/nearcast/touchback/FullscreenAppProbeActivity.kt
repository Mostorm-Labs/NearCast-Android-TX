package com.auditoryworks.nearcast.touchback

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Separate test APK: MediaProjection excludes the capture app's own recent tasks. */
class FullscreenAppProbeActivity : Activity() {
    @Suppress("DEPRECATION", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        setContentView(TextView(this).apply {
            text = "NearCast owned fullscreen app\nSingle-app sharing must remain view only"
            textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(28, 44, 64))
        })
    }
}
