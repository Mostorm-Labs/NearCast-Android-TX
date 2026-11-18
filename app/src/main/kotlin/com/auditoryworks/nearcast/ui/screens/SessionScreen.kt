package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.session.CaptureState
import com.auditoryworks.nearcast.session.CastSessionState
import com.auditoryworks.nearcast.session.ProjectionStopReason
import com.auditoryworks.nearcast.session.TransportState
import com.auditoryworks.nearcast.touchback.RemoteControlState
import com.auditoryworks.nearcast.ui.components.*
import com.auditoryworks.nearcast.ui.theme.CastColors

@Composable
fun SessionScreen(
    statusText: String,
    sessionState: CastSessionState,
    isLogUploadInProgress: Boolean,
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onStartCast: () -> Unit,
    onStopCast: () -> Unit,
    onLeave: () -> Unit,
    onUploadLogs: () -> Unit,
    onShowDownloadProgress: () -> Unit,
    remoteControlState: RemoteControlState,
    onRemoteEnabled: (Boolean) -> Unit,
    onRemoteSettings: () -> Unit,
    onRemoteHelp: () -> Unit,
    onRemoteRetry: () -> Unit
) {
    val isLiveMediaPath = sessionState.transport == TransportState.CONNECTED &&
        sessionState.capture == CaptureState.ACTIVE
    val isAwaitingReselection = sessionState.capture == CaptureState.AWAITING_RESELECTION
    val canStart = sessionState.isP2PReady && !sessionState.isCaptureBusy
    val stateColor = when {
        isLiveMediaPath -> CastColors.Accent
        sessionState.isP2PReady -> CastColors.Success
        sessionState.transport == TransportState.FAILED -> MaterialTheme.colorScheme.error
        else -> CastColors.Muted
    }
    CastScaffold(isLogUploadInProgress, isUpdateDownloadInProgress, isDownloadProgressVisible,
        onUploadLogs, onShowDownloadProgress) {
        CastPanel {
            Box(Modifier.size(72.dp).background(stateColor.copy(alpha = .09f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center) {
                Icon(if (isLiveMediaPath) Icons.Default.CastConnected else Icons.Default.Cast,
                    null, Modifier.size(36.dp), tint = stateColor)
            }
            Spacer(Modifier.height(20.dp))
            CastEyebrow(stringResource(R.string.ui_session_eyebrow))
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    isAwaitingReselection -> captureStatusText(sessionState)
                    sessionState.capture == CaptureState.PAUSED_HIDDEN -> stringResource(R.string.session_capture_hidden)
                    sessionState.capture == CaptureState.ACTIVE && !sessionState.isP2PReady -> stringResource(R.string.session_connection_disconnected)
                    sessionState.capture == CaptureState.ACTIVE -> stringResource(R.string.session_capture_active)
                    sessionState.capture == CaptureState.REQUESTING_PERMISSION -> stringResource(R.string.session_capture_requesting)
                    sessionState.capture == CaptureState.STARTING -> stringResource(R.string.session_capture_starting)
                    sessionState.capture == CaptureState.STOPPING -> stringResource(R.string.session_capture_stopping)
                    else -> connectionStatusText(sessionState)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            CastInsetPanel {
                StatusRow(stringResource(R.string.session_connection_title), connectionStatusText(sessionState), sessionState.isP2PReady)
                HorizontalDivider(color = CastColors.Divider)
                StatusRow(stringResource(R.string.session_capture_title), captureStatusText(sessionState), sessionState.capture == CaptureState.ACTIVE)
            }
            if (statusText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                CastStatusNotice(statusText, isError = statusText.contains("failed", true) || statusText.contains("error", true))
            }
            Spacer(Modifier.height(24.dp))
            when {
                sessionState.capture == CaptureState.ACTIVE || sessionState.capture == CaptureState.PAUSED_HIDDEN -> {
                    Button(onClick = onStopCast, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA8394D), contentColor = Color.White)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.session_action_stop))
                    }
                }
                else -> {
                    CastPrimaryButton(onClick = onStartCast, modifier = Modifier.fillMaxWidth(), enabled = canStart) {
                        if (sessionState.isCaptureBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (isAwaitingReselection) R.string.session_action_reselect else R.string.session_action_start))
                    }
                    if (isAwaitingReselection) {
                        Spacer(Modifier.height(10.dp))
                        CastSecondaryButton(onClick = onStopCast, modifier = Modifier.fillMaxWidth(), enabled = !sessionState.isCaptureBusy) {
                            Text(stringResource(R.string.session_action_stop))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            CastSecondaryButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.session_action_leave))
            }
        }
        RemoteControlCard(remoteControlState, onRemoteEnabled, onRemoteSettings, onRemoteHelp, onRemoteRetry)
    }
}

@Composable
private fun StatusRow(title: String, value: String, isHealthy: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(.4f), style = MaterialTheme.typography.bodySmall, color = CastColors.Muted)
        Row(Modifier.weight(.6f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            CastStatusDot(if (isHealthy) CastColors.Success else CastColors.Subtle)
            Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                color = if (isHealthy) CastColors.Accent else CastColors.Muted, textAlign = TextAlign.End)
        }
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
