package com.auditoryworks.nearcast.ui.screens

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
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.discovery.*
import com.auditoryworks.nearcast.ui.components.*
import com.auditoryworks.nearcast.ui.theme.CastColors

/** Automatic pairing runs inline; this sheet is for device options or recoverable errors only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverConnectionSheet(action: ReceiverActionState, onRetry: () -> Unit, onCode: () -> Unit,
    onForget: () -> Unit, onLocalEntry: (ReceiverDevice) -> Unit, onManualEntry: () -> Unit,
    onDismiss: () -> Unit, manageDevice: Boolean = false) {
    val device = action.device ?: return
    val busy = action.phase in setOf(ReceiverPhase.CHECKING, ReceiverPhase.PREPARING, ReceiverPhase.SAVING, ReceiverPhase.SHOWING) ||
        action.phase == ReceiverPhase.SHOWN && !manageDevice
    var options by remember(device.deviceId) { mutableStateOf(manageDevice) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CastColors.Panel, tonalElevation = 0.dp) {
        CastDialogBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.permission_close)) }
            }
            val status = when (action.phase) {
                ReceiverPhase.CHECKING, ReceiverPhase.PREPARING, ReceiverPhase.COMPARE -> R.string.discovery_checking
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
            } else {
                val paired = action.fingerprint != null && action.candidate == null &&
                    action.phase !in setOf(ReceiverPhase.KEY_CHANGED, ReceiverPhase.EXPIRED, ReceiverPhase.UNTRUSTED, ReceiverPhase.OFFLINE)
                if (paired && device.localReady != false) {
                    CastPrimaryButton(onClick = { onLocalEntry(device) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ui_enter_existing_code))
                    }
                }
                if (action.phase == ReceiverPhase.KEY_CHANGED) {
                    CastPrimaryButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.discovery_forget))
                    }
                } else if (action.phase != ReceiverPhase.OFFLINE && device.selectable) {
                    CastSecondaryButton(onClick = if (paired && device.supportsCodeDisplay) onCode else onRetry,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_retry)) }
                }
                if (device.localReady == false) Text(stringResource(R.string.local_unavailable), color = MaterialTheme.colorScheme.error)
                if (action.fingerprint != null || action.previousFingerprint != null) {
                    TextButton(onClick = { options = !options }) { Text(stringResource(R.string.ui_device_options)) }
                    if (options && action.phase != ReceiverPhase.KEY_CHANGED) {
                        if (action.phase != ReceiverPhase.OFFLINE) TextButton(onClick = onRetry) { Text(stringResource(R.string.discovery_verify)) }
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
