package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.permissions.*
import com.auditoryworks.nearcast.touchback.RemoteControlState
import com.auditoryworks.nearcast.ui.components.CastDialogBars
import com.auditoryworks.nearcast.ui.theme.CastColors

@Composable
fun StartupPermissionDialog(state: StartupPermissionState, snapshot: StartupPermissionSnapshot,
    remote: RemoteControlState, projectionActive: Boolean, onAudio: () -> Unit,
    onNotifications: () -> Unit, onRemote: () -> Unit, onContinue: () -> Unit, onDefer: () -> Unit) {
    val pending = state.pending != null
    AlertDialog(onDismissRequest = { if (!pending) onDefer() },
        title = { Text(stringResource(R.string.startup_permission_title)) },
        text = {
            CastDialogBars()
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.startup_permission_body), style = MaterialTheme.typography.bodySmall,
                    color = CastColors.Muted)
                StartupPermissionRow(Icons.Default.Cast, stringResource(R.string.startup_screen_title),
                    stringResource(R.string.startup_screen_detail), required = true, granted = projectionActive,
                    status = stringResource(if (projectionActive) R.string.startup_current_capture else R.string.startup_per_capture))
                HorizontalDivider()
                StartupPermissionRow(Icons.Default.VolumeUp, stringResource(R.string.startup_audio_title),
                    stringResource(R.string.startup_audio_detail), required = true, granted = snapshot.audioGranted,
                    status = stringResource(R.string.startup_granted), enabled = !pending,
                    busy = state.pending == StartupPermission.AUDIO,
                    action = stringResource(if (snapshot.audioNeedsSettings) R.string.startup_app_settings else R.string.startup_grant), onGrant = onAudio)
                if (snapshot.notificationsSupported) {
                    HorizontalDivider()
                    StartupPermissionRow(Icons.Default.Notifications, stringResource(R.string.startup_notifications_title),
                        stringResource(R.string.startup_notifications_detail), required = false, granted = snapshot.notificationsGranted,
                        status = stringResource(R.string.startup_granted), enabled = !pending,
                        busy = state.pending == StartupPermission.NOTIFICATIONS,
                        action = stringResource(if (snapshot.notificationsNeedSettings) R.string.startup_app_settings else R.string.startup_grant), onGrant = onNotifications)
                }
                HorizontalDivider()
                StartupPermissionRow(Icons.Default.TouchApp, stringResource(R.string.remote_title),
                    stringResource(R.string.startup_remote_detail), required = false,
                    granted = remote.permissionEnabled && remote.serviceConnected,
                    status = stringResource(if (remote.supported) R.string.startup_granted else R.string.remote_status_unsupported),
                    enabled = !pending, action = stringResource(R.string.ui_enable),
                    onGrant = if (remote.supported) onRemote else null)
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue, enabled = snapshot.audioGranted && !pending) {
                Text(stringResource(R.string.startup_continue))
            }
        }, dismissButton = {
            TextButton(onClick = onDefer, enabled = !pending) { Text(stringResource(R.string.startup_defer)) }
        })
}

@Composable
private fun StartupPermissionRow(icon: ImageVector, title: String, detail: String, required: Boolean,
    granted: Boolean, status: String, enabled: Boolean = true, busy: Boolean = false,
    action: String = "", onGrant: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = CastColors.Accent)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (required) R.string.startup_required else R.string.startup_optional),
                style = MaterialTheme.typography.labelSmall, color = if (required) CastColors.Accent else CastColors.Muted)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = CastColors.Muted)
        if (onGrant != null && !granted) {
            TextButton(onClick = onGrant, enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(action)
            }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (granted) Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = CastColors.Success)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (granted) CastColors.Success else CastColors.Accent)
        }
    }
}
