package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.touchback.CalibrationState
import com.auditoryworks.nearcast.touchback.RemoteControlState
import com.auditoryworks.nearcast.ui.components.CastInsetPanel
import com.auditoryworks.nearcast.ui.components.CastDialogBars
import com.auditoryworks.nearcast.ui.components.CastStatusDot
import com.auditoryworks.nearcast.ui.theme.CastColors

private fun remoteControlStatus(state: RemoteControlState): Int {
    val receiverStatus = if (state.sourceReady) state.receiverFeedback?.let { feedback ->
        when (feedback.diagnosticReason) {
            "RECEIVER_INPUT_DISABLED" -> R.string.remote_rx_master_off
            "RECEIVER_OPT_IN_REQUIRED" -> R.string.remote_rx_permission
            "DESKTOP_INPUT_ONLY", "CAPTURE_CHANGED", "NATIVE_INPUT_UNSUPPORTED" -> R.string.remote_rx_source
            "INPUT_WINDOW_INACTIVE" -> R.string.remote_rx_window
            "INPUT_PERMISSION_PENDING" -> R.string.remote_rx_pending
            "INPUT_MODE_UNSUPPORTED" -> R.string.remote_rx_input_mode
            "INPUT_OPEN_REJECTED", "INPUT_PERMISSION_DENIED", "INPUT_PERMISSION_TIMEOUT" -> R.string.remote_rx_rejected
            else -> when {
                !feedback.receiverOptIn -> R.string.remote_rx_permission
                !feedback.controllerRequested -> R.string.remote_rx_not_requested
                else -> null
            }
        }
    } else null
    return when {
        !state.enabled -> R.string.remote_status_off
        !state.supported -> R.string.remote_status_unsupported
        !state.permissionEnabled -> R.string.remote_status_permission
        !state.serviceConnected -> R.string.remote_status_service
        state.cleanupPending -> R.string.remote_status_cleanup
        state.compatibilityFailure == "TARGET_CONTRACT_MISMATCH" -> R.string.remote_status_contract_mismatch
        state.compatibilityFailure == "TARGET_DESCRIPTOR_INVALID" -> R.string.remote_status_target_invalid
        state.calibration == CalibrationState.RUNNING -> R.string.remote_status_calibrating
        state.calibration == CalibrationState.FAILED -> R.string.remote_status_mapping
        receiverStatus != null -> receiverStatus
        state.inputState == "active" -> R.string.remote_status_active
        state.disabledReason == "INPUT_SESSION_FAULT" -> R.string.remote_status_fault
        state.disabledReason == "NATIVE_INPUT_UNAVAILABLE" || state.disabledReason == "SCREEN_UNAVAILABLE" -> R.string.remote_status_system
        state.sourceReady -> R.string.remote_status_receiver
        !state.channelReady -> R.string.remote_status_ready
        !state.appReady -> R.string.remote_status_connecting
        else -> R.string.remote_status_ready
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteControlCard(state: RemoteControlState, onEnabled: (Boolean) -> Unit,
    onOpenSettings: () -> Unit, onHelp: () -> Unit, onRetry: () -> Unit) {
    val status = remoteControlStatus(state)
    CastInsetPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.TouchApp, null, Modifier.size(20.dp), tint = CastColors.Accent)
            Text(stringResource(R.string.remote_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = state.enabled, onCheckedChange = onEnabled, enabled = state.supported,
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = CastColors.Primary, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant))
        }
        Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CastStatusDot(when {
                state.inputState == "active" && state.enabled -> CastColors.Success
                state.enabled && (!state.permissionEnabled || !state.serviceConnected || state.cleanupPending) -> CastColors.Warning
                else -> CastColors.Accent
            })
            Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = CastColors.Text)
        }
        Text(stringResource(R.string.remote_card_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.supported && state.enabled) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.permissionEnabled || !state.serviceConnected) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.remote_open_settings)) }
                } else if (state.cleanupPending || state.calibration == CalibrationState.FAILED || state.disabledReason == "INPUT_SESSION_FAULT") {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(if (state.cleanupPending) R.string.remote_retry_cleanup else R.string.remote_retry))
                    }
                }
                TextButton(onClick = onHelp) { Text(stringResource(R.string.remote_help)) }
            }
        }
    }
}

@Composable
fun RemoteControlSummary(state: RemoteControlState, onSettings: () -> Unit,
    onPermission: () -> Unit, onRetry: () -> Unit) {
    val needsPermission = state.supported && state.enabled && (!state.permissionEnabled || !state.serviceConnected)
    val needsRetry = state.enabled && (state.cleanupPending || state.calibration == CalibrationState.FAILED || state.disabledReason == "INPUT_SESSION_FAULT")
    val status = remoteControlStatus(state)
    val text = when (status) {
        R.string.remote_status_off -> R.string.ui_remote_off
        R.string.remote_status_permission, R.string.remote_status_service -> R.string.ui_remote_permission
        R.string.remote_status_active -> R.string.ui_remote_active
        R.string.remote_status_calibrating -> R.string.ui_remote_calibrating
        R.string.remote_status_ready -> R.string.ui_remote_ready
        R.string.remote_status_receiver, R.string.remote_rx_permission, R.string.remote_rx_not_requested,
        R.string.remote_rx_master_off -> R.string.ui_remote_wait_receiver
        else -> R.string.ui_remote_attention
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.TouchApp, null, Modifier.size(20.dp), tint = CastColors.Accent)
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(text), style = MaterialTheme.typography.bodySmall,
                color = if (status == R.string.remote_status_active) CastColors.Success else CastColors.Muted)
        }
        TextButton(onClick = when { needsPermission -> onPermission; needsRetry -> onRetry; else -> onSettings }) {
            Text(stringResource(when { needsPermission -> R.string.ui_enable; needsRetry -> R.string.ui_retry; else -> R.string.ui_settings }))
        }
    }
}

enum class PermissionDialog { REMOTE_EXPLANATION, REMOTE_NOT_GRANTED, REMOTE_HELP, SETTINGS_UNAVAILABLE, AUDIO_EXPLANATION, AUDIO_DENIED, AUDIO_PERMANENT }

@Composable
fun CapturePermissionDialog(kind: PermissionDialog, onDismiss: () -> Unit, onOpenAccessibility: () -> Unit,
    onSkipRemote: () -> Unit, onOpenAppSettings: () -> Unit, onRequestAudio: () -> Unit,
    sharingPending: Boolean = false) {
    val title = when (kind) {
        PermissionDialog.REMOTE_EXPLANATION -> R.string.remote_intro_title
        PermissionDialog.REMOTE_NOT_GRANTED -> R.string.remote_not_granted_title
        PermissionDialog.REMOTE_HELP -> R.string.remote_help_title
        PermissionDialog.SETTINGS_UNAVAILABLE -> R.string.permission_settings_unavailable_title
        PermissionDialog.AUDIO_EXPLANATION -> R.string.audio_permission_title
        else -> R.string.audio_permission_denied_title
    }
    val body = when (kind) {
        PermissionDialog.REMOTE_EXPLANATION -> R.string.remote_intro_body
        PermissionDialog.REMOTE_NOT_GRANTED -> R.string.remote_not_granted_body
        PermissionDialog.REMOTE_HELP -> R.string.remote_help_body
        PermissionDialog.SETTINGS_UNAVAILABLE -> R.string.permission_settings_unavailable_body
        PermissionDialog.AUDIO_EXPLANATION -> R.string.audio_permission_body
        PermissionDialog.AUDIO_DENIED -> R.string.audio_permission_denied_body
        PermissionDialog.AUDIO_PERMANENT -> R.string.audio_permission_permanent_body
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) },
        text = {
            CastDialogBars()
            Text(stringResource(body), Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            when (kind) {
                PermissionDialog.REMOTE_EXPLANATION, PermissionDialog.REMOTE_NOT_GRANTED ->
                    TextButton(onClick = onOpenAccessibility) { Text(stringResource(R.string.remote_open_settings)) }
                PermissionDialog.REMOTE_HELP -> TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.permission_app_settings)) }
                PermissionDialog.AUDIO_EXPLANATION, PermissionDialog.AUDIO_DENIED ->
                    TextButton(onClick = onRequestAudio) { Text(stringResource(R.string.permission_request)) }
                PermissionDialog.AUDIO_PERMANENT -> TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.permission_app_settings)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.permission_understood)) }
            }
        }, dismissButton = {
            if (kind in setOf(PermissionDialog.REMOTE_EXPLANATION, PermissionDialog.REMOTE_NOT_GRANTED)) {
                TextButton(onClick = onSkipRemote) { Text(stringResource(if (sharingPending) R.string.remote_cast_only else R.string.ui_not_now)) }
            } else TextButton(onClick = onDismiss) { Text(stringResource(R.string.permission_close)) }
        })
}
