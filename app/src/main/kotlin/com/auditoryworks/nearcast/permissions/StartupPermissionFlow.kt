package com.auditoryworks.nearcast.permissions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class StartupPermission { AUDIO, NOTIFICATIONS }
data class StartupPermissionState(val checked: Boolean = false, val showing: Boolean = false,
    val pending: StartupPermission? = null, val audioGrantedAtCheck: Boolean = false)

/** Startup checks never own a MediaProjection token or start a capture request. */
class StartupPermissionFlow {
    private val mutableState = MutableStateFlow(StartupPermissionState())
    val state: StateFlow<StartupPermissionState> = mutableState
    fun restore(checkpoint: StartupPermissionState) { mutableState.value = checkpoint }
    fun check(hasSeenSetup: Boolean, audioGranted: Boolean, sharingActive: Boolean) {
        val before = state.value
        val newlyRevoked = before.checked && before.audioGrantedAtCheck && !audioGranted
        if (before.checked && !newlyRevoked) return
        mutableState.value = StartupPermissionState(checked = true,
            showing = !sharingActive && (!hasSeenSetup || !audioGranted), audioGrantedAtCheck = audioGranted)
    }
    fun request(permission: StartupPermission): Boolean {
        val before = state.value
        if (!before.showing || before.pending != null) return false
        mutableState.value = before.copy(pending = permission)
        return true
    }
    fun returned() { mutableState.value = state.value.copy(pending = null) }
    fun finish(audioGranted: Boolean): Boolean {
        if (!audioGranted) return false
        return defer(audioGranted)
    }
    fun defer(audioGranted: Boolean): Boolean {
        val before = state.value
        if (!before.showing || before.pending != null) return false
        mutableState.value = before.copy(showing = false, audioGrantedAtCheck = audioGranted)
        return true
    }
    fun open(audioGranted: Boolean) {
        if (state.value.pending != null) return
        mutableState.value = StartupPermissionState(checked = true, showing = true, audioGrantedAtCheck = audioGranted)
    }
}

data class StartupPermissionSnapshot(val audioGranted: Boolean = false, val audioNeedsSettings: Boolean = false,
    val notificationsGranted: Boolean = false, val notificationsNeedSettings: Boolean = false,
    val notificationsSupported: Boolean = true)
