package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import com.auditoryworks.nearcast.R

@Composable
fun HomeScreen(
    pairCode: String,
    statusText: String,
    isLogUploadInProgress: Boolean,
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onPairCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
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
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter the pair code shown on the receiver",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(40.dp))

                OutlinedTextField(
                    value = pairCode,
                    onValueChange = { if (it.length <= 8) onPairCodeChange(it.uppercase()) },
                    label = { Text("Pair Code") },
                    placeholder = { Text("e.g. SRJS") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        textAlign = TextAlign.Center
                    )
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onJoin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = pairCode.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Join Room", style = MaterialTheme.typography.titleMedium)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (statusText.contains("failed", ignoreCase = true) ||
                        statusText.contains("error", ignoreCase = true))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
