package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.session.*
import com.auditoryworks.nearcast.touchback.RemoteControlState
import com.auditoryworks.nearcast.ui.components.*
import com.auditoryworks.nearcast.ui.theme.CastColors

@Composable
fun SessionScreen(
    statusText: String,
    sessionState: CastSessionState,
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onStartCast: () -> Unit,
    onStopCast: () -> Unit,
    onLeave: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowDownloadProgress: () -> Unit,
    remoteControlState: RemoteControlState,
    onRemoteSettings: () -> Unit,
    onRemoteRetry: () -> Unit,
    onScan: () -> Unit = {}
) {
    val sharing = sessionState.capture == CaptureState.ACTIVE || sessionState.capture == CaptureState.PAUSED_HIDDEN
    val live = sessionState.isP2PReady && sessionState.capture == CaptureState.ACTIVE
    val reselect = sessionState.capture == CaptureState.AWAITING_RESELECTION
    var details by rememberSaveable { mutableStateOf(false) }
    val title = when {
        !sessionState.isP2PReady -> connectionStatusText(sessionState)
        reselect -> stringResource(R.string.ui_reselect_title)
        sessionState.capture == CaptureState.PAUSED_HIDDEN -> stringResource(R.string.ui_share_paused)
        live -> stringResource(R.string.session_capture_active)
        sessionState.isCaptureBusy -> captureStatusText(sessionState)
        else -> stringResource(R.string.ui_ready_to_share)
    }
    CastScaffold(isUpdateDownloadInProgress, isDownloadProgressVisible, onOpenSettings, onShowDownloadProgress,
        onTabSelected = { when (it) { AppTab.SCAN -> onScan(); AppTab.SETTINGS -> onOpenSettings(); else -> Unit } }) {
        CastPanel {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Icon(if (live) Icons.Default.CastConnected else Icons.Default.Cast, null, Modifier.size(28.dp), tint = CastColors.Accent)
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = CastColors.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(when {
                reselect || sessionState.capture == CaptureState.PAUSED_HIDDEN || sessionState.capture == CaptureState.ERROR -> captureStatusText(sessionState)
                !sessionState.isP2PReady -> stringResource(R.string.ui_connection_wait)
                live -> stringResource(R.string.ui_sharing_hint)
                else -> stringResource(R.string.ui_start_hint)
            }, color = CastColors.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            if (sharing) {
                Button(onClick = onStopCast, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CastColors.Primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.session_action_stop))
                }
            } else {
                CastPrimaryButton(onStartCast, Modifier.fillMaxWidth(), enabled = sessionState.isP2PReady && !sessionState.isCaptureBusy) {
                    if (sessionState.isCaptureBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (reselect) R.string.session_action_reselect else R.string.session_action_start))
                }
            }
            if (reselect) TextButton(onClick = onStopCast, enabled = !sessionState.isCaptureBusy) { Text(stringResource(R.string.session_action_stop)) }
            TextButton(onClick = onLeave, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.session_action_leave)) }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = CastColors.Divider)
            Spacer(Modifier.height(10.dp))
            RemoteControlSummary(remoteControlState, onOpenSettings, onRemoteSettings, onRemoteRetry)
        }
        TextButton(onClick = { details = !details }) {
            Text(stringResource(if (details) R.string.ui_hide_details else R.string.ui_connection_details))
        }
        if (details) CastInsetPanel {
            StatusRow(stringResource(R.string.session_connection_title), connectionStatusText(sessionState))
            StatusRow(stringResource(R.string.session_capture_title), captureStatusText(sessionState))
            if (statusText.isNotBlank()) Text(statusText, style = MaterialTheme.typography.bodySmall, color = CastColors.Muted)
        }
        if (!details && (sessionState.capture == CaptureState.ERROR || sessionState.transport == TransportState.FAILED)) {
            CastStatusNotice(sessionState.errorMessage ?: statusText, isError = true)
        }
    }
}

@Composable
private fun StatusRow(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = CastColors.Subtle, style = MaterialTheme.typography.labelSmall)
        Text(value, color = CastColors.Text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun connectionStatusText(state: CastSessionState): String = when (state.transport) {
    TransportState.IDLE -> stringResource(R.string.session_connection_idle)
    TransportState.CONNECTING -> stringResource(R.string.session_connection_connecting)
    TransportState.CONNECTED -> stringResource(R.string.session_connection_connected)
    TransportState.DISCONNECTED -> stringResource(R.string.session_connection_disconnected)
    TransportState.FAILED -> stringResource(R.string.session_connection_failed)
    TransportState.CLOSED -> stringResource(R.string.session_connection_closed)
}

@Composable
private fun captureStatusText(state: CastSessionState): String = when (state.capture) {
    CaptureState.IDLE -> stringResource(R.string.session_capture_idle)
    CaptureState.REQUESTING_PERMISSION -> stringResource(R.string.session_capture_requesting)
    CaptureState.STARTING -> stringResource(R.string.session_capture_starting)
    CaptureState.ACTIVE -> stringResource(R.string.session_capture_active)
    CaptureState.PAUSED_HIDDEN -> stringResource(R.string.session_capture_hidden)
    CaptureState.STOPPING -> stringResource(R.string.session_capture_stopping)
    CaptureState.ERROR -> stringResource(R.string.session_capture_error)
    CaptureState.AWAITING_RESELECTION -> when (state.stopReason) {
        ProjectionStopReason.SCREEN_LOCKED -> stringResource(R.string.session_capture_locked)
        ProjectionStopReason.PERMISSION_DENIED ->
            stringResource(R.string.session_capture_permission_denied)
        ProjectionStopReason.CAPTURE_ERROR -> stringResource(R.string.session_capture_error)
        else -> stringResource(R.string.session_capture_stopped)
    }
}
