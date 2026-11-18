package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.discovery.*
import com.auditoryworks.nearcast.ui.components.*
import com.auditoryworks.nearcast.ui.theme.CastColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiscoveryBottomSheet(snapshot: DiscoverySnapshot, action: ReceiverActionState,
    onSelect: (ReceiverDevice) -> Unit, onConfirm: (Boolean) -> Unit, onTrust: () -> Unit,
    onCode: () -> Unit, onForget: () -> Unit, onBack: () -> Unit, onRefresh: () -> Unit,
    onSettings: () -> Unit, onLocalEntry: (ReceiverDevice) -> Unit, onManualEntry: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF171C2B), tonalElevation = 0.dp) {
        CastDarkDialogBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CastEyebrow(stringResource(R.string.ui_local_eyebrow))
                    Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.discovery_refresh)) }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.permission_close)) }
            }
            if (action.device == null) {
                Text(stringResource(R.string.discovery_hint), style = MaterialTheme.typography.bodySmall, color = CastColors.Muted)
                snapshot.issue?.let {
                    Text(stringResource(if (it == DiscoveryIssue.PERMISSION) R.string.discovery_permission_error else R.string.discovery_scan_error), color = MaterialTheme.colorScheme.error)
                    if (it == DiscoveryIssue.PERMISSION) TextButton(onClick = onSettings) { Text(stringResource(R.string.permission_app_settings)) }
                }
                if (snapshot.devices.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (snapshot.scanning) CircularProgressIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.discovery_empty))
                    }
                } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snapshot.devices, key = { it.instance }) { device ->
                        CastInsetPanel(Modifier.clickable(enabled = device.selectable) { onSelect(device) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tv, null, tint = CastColors.Accent); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                                    Text(device.addresses.firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, color = CastColors.Muted)
                                    if (device.model.isNotBlank() || device.version.isNotBlank()) Text(listOf(device.model, device.version).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = CastColors.Muted)
                                    Text(stringResource(if (device.selectable) R.string.discovery_select_hint else R.string.discovery_not_compatible), style = MaterialTheme.typography.bodySmall, color = if (device.selectable) CastColors.Accent else CastColors.Subtle)
                                }
                            }
                        }
                    }
                }
            } else {
                val device = action.device
                val busy = action.phase in setOf(ReceiverPhase.CHECKING, ReceiverPhase.PREPARING, ReceiverPhase.SAVING, ReceiverPhase.SHOWING)
                var compared by remember(action.candidate) { mutableStateOf(false) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.discovery_back)) }
                    Text(device.name, style = MaterialTheme.typography.headlineSmall)
                    Text(device.addresses.joinToString(", "), style = MaterialTheme.typography.bodySmall)
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
                        else -> R.string.discovery_hint
                    }
                    Text(stringResource(status), style = MaterialTheme.typography.bodyLarge)
                    if (busy) CircularProgressIndicator(Modifier.size(28.dp))
                    if (action.phase == ReceiverPhase.COMPARE) {
                        if (action.previousFingerprint != null && action.previousFingerprint != action.fingerprint) Text(stringResource(R.string.discovery_replacing_key), color = MaterialTheme.colorScheme.error)
                        CastInsetPanel {
                            Text(action.candidate!!.fingerprint.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = compared, onCheckedChange = { compared = it })
                            Text(stringResource(R.string.discovery_compared), Modifier.clickable { compared = !compared })
                        }
                        CastPrimaryButton(onClick = { onConfirm(compared) }, enabled = compared, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.discovery_confirm)) }
                    } else if (!busy && action.phase != ReceiverPhase.OFFLINE) {
                        if (action.fingerprint != null && action.phase != ReceiverPhase.KEY_CHANGED && action.phase != ReceiverPhase.EXPIRED && action.phase != ReceiverPhase.UNTRUSTED) {
                            CastPrimaryButton(onClick = onCode, enabled = device.supportsCodeDisplay, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.discovery_show_again)) }
                        }
                        if (device.supportsTrustDisplay) CastSecondaryButton(onClick = onTrust, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.discovery_verify)) }
                    }
                    if (!busy && (action.previousFingerprint != null || (action.candidate == null && action.fingerprint != null))) {
                        TextButton(onClick = onForget) { Text(stringResource(R.string.discovery_forget)) }
                    }
                    if (!busy && action.candidate == null && action.fingerprint != null && action.phase !in setOf(ReceiverPhase.KEY_CHANGED, ReceiverPhase.EXPIRED, ReceiverPhase.UNTRUSTED, ReceiverPhase.OFFLINE)) {
                        Text(stringResource(if (device.codeSelection != null) R.string.local_selected_code_hint else R.string.local_legacy_code_hint))
                        CastPrimaryButton(onClick = { onLocalEntry(device) }, enabled = device.localReady != false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.local_use_receiver)) }
                        if (device.localReady == false) Text(stringResource(R.string.local_unavailable), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            CastSecondaryButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.local_switch_cloud)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
