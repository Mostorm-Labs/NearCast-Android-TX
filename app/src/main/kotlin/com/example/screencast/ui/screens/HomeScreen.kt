package com.example.screencast.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.screencast.discovery.DiscoveredService

@Composable
fun HomeScreen(
    serverUrl: String,
    pairCode: String,
    statusText: String,
    onServerUrlChange: (String) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    isDiscovering: Boolean = false,
    discoveredDevices: List<DiscoveredService> = emptyList(),
    onScanDevices: () -> Unit = {},
    onDeviceSelected: (DiscoveredService) -> Unit = {},
    onDismissDeviceSheet: () -> Unit = {},
    showDeviceSheet: Boolean = false,
    showPairCodeDialog: Boolean = false,
    pendingDeviceName: String = "",
    onPairCodeConfirm: (String) -> Unit = {},
    onPairCodeDismiss: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "NearHub ScreenCast",
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

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onScanDevices,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDiscovering
            ) {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isDiscovering) "Scanning..." else "Scan for Devices")
            }

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

    if (showDeviceSheet) {
        DeviceDiscoveryBottomSheet(
            devices = discoveredDevices,
            isScanning = isDiscovering,
            error = null,
            onDeviceSelected = onDeviceSelected,
            onRefresh = onScanDevices,
            onDismiss = onDismissDeviceSheet
        )
    }

    if (showPairCodeDialog) {
        PairCodeInputDialog(
            deviceName = pendingDeviceName,
            onConfirm = onPairCodeConfirm,
            onDismiss = onPairCodeDismiss
        )
    }
}

@Composable
fun PairCodeInputDialog(
    deviceName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pairCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Pair Code") },
        text = {
            Column {
                Text("Device: $deviceName")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = pairCode,
                    onValueChange = { if (it.length <= 8) pairCode = it.uppercase() },
                    label = { Text("Pair Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pairCode) },
                enabled = pairCode.isNotBlank()
            ) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
