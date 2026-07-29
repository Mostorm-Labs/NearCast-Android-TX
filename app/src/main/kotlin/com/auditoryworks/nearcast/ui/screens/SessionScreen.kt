package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.session.CaptureState
import com.auditoryworks.nearcast.session.CastSessionState
import com.auditoryworks.nearcast.session.ProjectionStopReason
import com.auditoryworks.nearcast.session.TransportState

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
    onShowDownloadProgress: () -> Unit
) {
    val isLiveMediaPath = sessionState.transport == TransportState.CONNECTED &&
        sessionState.capture == CaptureState.ACTIVE
    val isAwaitingReselection = sessionState.capture == CaptureState.AWAITING_RESELECTION
    val canStart = sessionState.isP2PReady && !sessionState.isCaptureBusy

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
            ) {
                if (isUpdateDownloadInProgress && !isDownloadProgressVisible) {
                    IconButton(onClick = onShowDownloadProgress) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(R.string.session_action_show_update),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onUploadLogs,
                    enabled = !isLogUploadInProgress
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = stringResource(R.string.session_action_upload_logs),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Card(
                    modifier = Modifier.size(120.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isLiveMediaPath -> MaterialTheme.colorScheme.primaryContainer
                            sessionState.isP2PReady -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLiveMediaPath) Icons.Default.CastConnected
                            else Icons.Default.Cast,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = when {
                                isLiveMediaPath -> MaterialTheme.colorScheme.primary
                                sessionState.isP2PReady -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = when {
                        isAwaitingReselection -> captureStatusText(sessionState)
                        sessionState.capture == CaptureState.PAUSED_HIDDEN ->
                            stringResource(R.string.session_capture_hidden)
                        sessionState.capture == CaptureState.ACTIVE && !sessionState.isP2PReady ->
                            stringResource(R.string.session_connection_disconnected)
                        sessionState.capture == CaptureState.ACTIVE ->
                            stringResource(R.string.session_capture_active)
                        sessionState.capture == CaptureState.REQUESTING_PERMISSION ->
                            stringResource(R.string.session_capture_requesting)
                        sessionState.capture == CaptureState.STARTING ->
                            stringResource(R.string.session_capture_starting)
                        sessionState.capture == CaptureState.STOPPING ->
                            stringResource(R.string.session_capture_stopping)
                        else -> connectionStatusText(sessionState)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                StatusRow(
                    title = stringResource(R.string.session_connection_title),
                    value = connectionStatusText(sessionState),
                    isHealthy = sessionState.isP2PReady
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatusRow(
                    title = stringResource(R.string.session_capture_title),
                    value = captureStatusText(sessionState),
                    isHealthy = sessionState.capture == CaptureState.ACTIVE
                )

                if (statusText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                when {
                    sessionState.capture == CaptureState.ACTIVE ||
                        sessionState.capture == CaptureState.PAUSED_HIDDEN -> {
                        Button(
                            onClick = onStopCast,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.session_action_stop),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    else -> {
                        Button(
                            onClick = onStartCast,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = canStart,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isAwaitingReselection) {
                                    stringResource(R.string.session_action_reselect)
                                } else {
                                    stringResource(R.string.session_action_start)
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        if (isAwaitingReselection) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onStopCast,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                enabled = !sessionState.isCaptureBusy
                            ) {
                                Text(stringResource(R.string.session_action_stop))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onLeave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.session_action_leave))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, value: String, isHealthy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isHealthy) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            },
            textAlign = TextAlign.End
        )
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
