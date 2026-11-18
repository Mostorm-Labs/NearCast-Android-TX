package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.discovery.*
import com.auditoryworks.nearcast.ui.components.*
import com.auditoryworks.nearcast.ui.theme.CastColors

/** Appears only for preparation, a required first-use trust check, or a recoverable error. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverConnectionSheet(action: ReceiverActionState, onConfirm: (Boolean) -> Unit,
    onTrust: () -> Unit, onCode: () -> Unit, onForget: () -> Unit,
    onLocalEntry: (ReceiverDevice) -> Unit, onManualEntry: () -> Unit, onDismiss: () -> Unit,
    manageDevice: Boolean = false) {
    val device = action.device ?: return
    val busy = action.phase in setOf(ReceiverPhase.CHECKING, ReceiverPhase.PREPARING, ReceiverPhase.SAVING, ReceiverPhase.SHOWING) ||
        action.phase == ReceiverPhase.SHOWN && !manageDevice
    var compared by remember(action.candidate) { mutableStateOf(false) }
    var options by remember(device.deviceId) { mutableStateOf(manageDevice) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF171C2B), tonalElevation = 0.dp) {
        CastDarkDialogBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.permission_close)) }
            }
            val status = when (action.phase) {
                ReceiverPhase.CHECKING -> R.string.discovery_checking
                ReceiverPhase.PREPARING -> R.string.discovery_preparing
                ReceiverPhase.COMPARE -> R.string.discovery_compare
                ReceiverPhase.SAVING -> R.string.discovery_saving
                ReceiverPhase.SHOWING -> R.string.discovery_showing
                ReceiverPhase.SHOWN -> R.string.discovery_shown
                ReceiverPhase.UNAVAILABLE -> R.string.discovery_unavailable
                ReceiverPhase.RATE_LIMITED -> R.string.discovery_rate_limited
                ReceiverPhase.UNSUPPORTED -> R.string.discovery_unsupported
                ReceiverPhase.OFFLINE -> R.string.discovery_offline
                ReceiverPhase.KEY_CHANGED -> R.string.discovery_key_changed
                ReceiverPhase.EXPIRED -> R.string.discovery_expired
                ReceiverPhase.UNTRUSTED -> R.string.discovery_untrusted
                ReceiverPhase.ERROR -> when (action.failure) {
                    ReceiverFailure.STORAGE -> R.string.discovery_storage_error
                    ReceiverFailure.PROTOCOL -> R.string.discovery_protocol_error
                    else -> R.string.discovery_network_error
                }
                else -> R.string.discovery_checking
            }
            Text(stringResource(status), color = CastColors.Muted, style = MaterialTheme.typography.bodyMedium)
            if (busy) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(24.dp), strokeWidth = 2.dp, color = CastColors.Accent)
            } else if (action.phase == ReceiverPhase.COMPARE) {
                if (action.previousFingerprint != null && action.previousFingerprint != action.fingerprint) {
                    Text(stringResource(R.string.discovery_replacing_key), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                CastInsetPanel {
                    Text(action.candidate!!.fingerprint.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") },
                        Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(compared, onCheckedChange = { compared = it })
                    Text(stringResource(R.string.discovery_compared), Modifier.weight(1f).clickable { compared = !compared },
                        style = MaterialTheme.typography.bodySmall)
                }
                CastPrimaryButton(onClick = { onConfirm(compared) }, enabled = compared, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discovery_confirm))
                }
            } else {
                val trusted = action.fingerprint != null && action.candidate == null &&
                    action.phase !in setOf(ReceiverPhase.KEY_CHANGED, ReceiverPhase.EXPIRED, ReceiverPhase.UNTRUSTED, ReceiverPhase.OFFLINE)
                if (trusted && device.localReady != false) {
                    CastPrimaryButton(onClick = { onLocalEntry(device) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ui_enter_existing_code))
                    }
                }
                if (trusted && device.supportsCodeDisplay) {
                    CastSecondaryButton(onClick = onCode, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_retry)) }
                } else if (device.supportsTrustDisplay && action.phase != ReceiverPhase.OFFLINE) {
                    CastPrimaryButton(onClick = onTrust, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.discovery_verify)) }
                }
                if (device.localReady == false) Text(stringResource(R.string.local_unavailable), color = MaterialTheme.colorScheme.error)
                if (action.fingerprint != null || action.previousFingerprint != null) {
                    TextButton(onClick = { options = !options }) { Text(stringResource(R.string.ui_device_options)) }
                    if (options) {
                        if (device.supportsTrustDisplay && action.phase != ReceiverPhase.OFFLINE) TextButton(onClick = onTrust) { Text(stringResource(R.string.discovery_verify)) }
                        TextButton(onClick = onForget) { Text(stringResource(R.string.discovery_forget), color = MaterialTheme.colorScheme.error) }
                    }
                }
                TextButton(onClick = onManualEntry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.local_switch_cloud))
                }
            }
        }
    }
}
