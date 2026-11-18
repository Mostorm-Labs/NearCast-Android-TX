package com.auditoryworks.nearcast.touchback

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Process-owned scope also retains timed-out operations until cleanup really completes. */
object RemoteControlAccess {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var preferences: SharedPreferences? = null
    private val mutableState = MutableStateFlow(RemoteControlState())
    val state: StateFlow<RemoteControlState> = mutableState.asStateFlow()
    private val retiring = mutableSetOf<InputEngine>()

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            preferences = context.getSharedPreferences("remote-control", Context.MODE_PRIVATE)
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.addAccessibilityStateChangeListener { refresh() }
        }
        refresh()
    }
    fun setEnabled(enabled: Boolean) { preferences?.edit()?.putBoolean("enabled", enabled)?.apply(); refresh() }
    fun refresh() {
        val context = appContext ?: return
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val permission = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == RemoteInputService::class.java.name
        }
        val enabled = preferences?.getBoolean("enabled", true) ?: true
        val supported = Build.VERSION.SDK_INT >= 26
        mutableState.value = RemoteControlState(enabled = enabled, supported = supported, permissionEnabled = permission,
            serviceConnected = AccessibilityInput.connected, cleanupPending = AccessibilityInput.cleanupBlocked,
            disabledReason = when { !enabled -> "LOCAL_INPUT_DISABLED"; !supported -> "ANDROID_VERSION_UNSUPPORTED"
                !permission || !AccessibilityInput.connected -> "NATIVE_PERMISSION_UNAVAILABLE"
                AccessibilityInput.cleanupBlocked -> "NATIVE_CLEANUP_PENDING"; else -> "CAPTURE_UNAVAILABLE" })
    }
    internal fun retire(engine: InputEngine) {
        retiring.add(engine)
        scope.launch { runCatching { engine.close() }; if (engine.state == "closed") retiring.remove(engine); refresh() }
    }
    fun retryCleanup() {
        scope.launch {
            retiring.toList().forEach { runCatching { it.close() }; if (it.state == "closed") retiring.remove(it) }
            refresh()
        }
    }
}
