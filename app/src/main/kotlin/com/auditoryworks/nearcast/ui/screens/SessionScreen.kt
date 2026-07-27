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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun SessionScreen(
    statusText: String,
    isCasting: Boolean,
    isP2PReady: Boolean,
    isLogUploadInProgress: Boolean,
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onStartCast: () -> Unit,
    onStopCast: () -> Unit,
    onLeave: () -> Unit,
    onUploadLogs: () -> Unit,
    onShowDownloadProgress: () -> Unit
) {
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
                            contentDescription = "Show update progress",
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
                        contentDescription = "Upload Logs",
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
                // Status icon
                Card(
                    modifier = Modifier.size(120.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isCasting -> MaterialTheme.colorScheme.primaryContainer
                            isP2PReady -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isCasting) Icons.Default.CastConnected
                            else Icons.Default.Cast,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = when {
                                isCasting -> MaterialTheme.colorScheme.primary
                                isP2PReady -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = when {
                        isCasting -> "Screen is being shared"
                        isP2PReady -> "Connected, ready to cast"
                        else -> "Establishing connection..."
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                if (!isCasting) {
                    Button(
                        onClick = onStartCast,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = isP2PReady,
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
                        Text("Start Screen Cast", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
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
                        Text("Stop Casting", style = MaterialTheme.typography.titleMedium)
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
                        Text("Leave Room")
                    }
                }
            }
        }
    }
}
