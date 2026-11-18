package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.discovery.*
import com.auditoryworks.nearcast.ui.components.*
import com.auditoryworks.nearcast.ui.theme.CastColors

enum class ConnectionTab { CODE, DEVICES }

@Composable
fun HomeScreen(
    pairCode: String,
    statusText: String,
    tab: ConnectionTab,
    onTabSelected: (ConnectionTab) -> Unit,
    snapshot: DiscoverySnapshot,
    onSelectReceiver: (ReceiverDevice) -> Unit,
    onRefresh: () -> Unit,
    onNetworkSettings: () -> Unit,
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onPairCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowDownloadProgress: () -> Unit,
    localReceiverName: String? = null,
    isJoining: Boolean = false,
    onUseCloud: () -> Unit = {},
    onCancelJoin: () -> Unit = {},
    preparingReceiver: String? = null,
    onCancelPreparation: () -> Unit = {},
    onReceiverOptions: (ReceiverDevice) -> Unit = {}
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(tab, localReceiverName) {
        if (tab == ConnectionTab.CODE && localReceiverName != null) {
            codeFocus.requestFocus()
            keyboard?.show()
        }
    }
    CastScaffold(isUpdateDownloadInProgress, isDownloadProgressVisible, onOpenSettings, onShowDownloadProgress) {
        CastPanel {
            Row(Modifier.fillMaxWidth().background(CastColors.Field.copy(alpha = .6f), RoundedCornerShape(12.dp))
                .border(1.dp, CastColors.Divider, RoundedCornerShape(12.dp)).padding(4.dp).selectableGroup()) {
                for (item in ConnectionTab.entries) {
                    val selected = tab == item
                    Box(Modifier.weight(1f).clipTab(selected).selectable(selected, enabled = !isJoining, role = Role.Tab,
                        onClick = { focus.clearFocus(); keyboard?.hide(); onTabSelected(item) })
                        .defaultMinSize(minHeight = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(if (item == ConnectionTab.CODE) R.string.ui_code_tab else R.string.ui_devices_tab),
                            color = if (selected) CastColors.Accent else CastColors.Muted,
                            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (tab == ConnectionTab.CODE) {
                Text(stringResource(R.string.ui_code_title), style = MaterialTheme.typography.headlineSmall,
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(if (localReceiverName != null) R.string.ui_local_code_hint else R.string.ui_code_hint),
                    style = MaterialTheme.typography.bodyMedium, color = CastColors.Muted, textAlign = TextAlign.Center)
                if (localReceiverName != null) {
                    Spacer(Modifier.height(16.dp))
                    Surface(shape = RoundedCornerShape(9.dp), color = Color(0x127ADFFF)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tv, null, Modifier.size(18.dp), tint = CastColors.Accent)
                            Spacer(Modifier.width(8.dp))
                            Text(localReceiverName, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = CastColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = onUseCloud, enabled = !isJoining) {
                                Icon(Icons.Default.Close, stringResource(R.string.ui_clear_receiver), Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                PairCodeInput(pairCode, onPairCodeChange, !isJoining, onJoin, codeFocus)
                Spacer(Modifier.height(24.dp))
                CastPrimaryButton(onClick = { keyboard?.hide(); onJoin() }, modifier = Modifier.fillMaxWidth(),
                    enabled = pairCode.length == 4 && !isJoining) {
                    if (isJoining) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (isJoining) R.string.connection_connecting else R.string.connection_join))
                }
                if (isJoining) TextButton(onClick = onCancelJoin) { Text(stringResource(R.string.connection_cancel)) }
            } else {
                ReceiverList(snapshot, enabled = !isJoining, onSelect = { focus.clearFocus(); keyboard?.hide(); onSelectReceiver(it) },
                    onRefresh = onRefresh, onNetworkSettings = onNetworkSettings, preparingReceiver = preparingReceiver,
                    onOptions = onReceiverOptions)
                if (preparingReceiver != null) TextButton(onClick = onCancelPreparation) {
                    Text(stringResource(R.string.connection_cancel))
                }
            }
            if (statusText.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                CastStatusNotice(statusText, isError = true)
            }
        }
    }
}

private fun Modifier.clipTab(selected: Boolean): Modifier =
    background(if (selected) Color(0x30438FFF) else Color.Transparent, RoundedCornerShape(8.dp))

@Composable
private fun ReceiverList(snapshot: DiscoverySnapshot, enabled: Boolean, onSelect: (ReceiverDevice) -> Unit,
    onRefresh: () -> Unit, onNetworkSettings: () -> Unit, preparingReceiver: String?, onOptions: (ReceiverDevice) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ui_nearby_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onRefresh, enabled = enabled) {
                Icon(Icons.Default.Refresh, stringResource(R.string.discovery_refresh), tint = CastColors.Accent)
            }
        }
        Text(stringResource(R.string.ui_nearby_hint), color = CastColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (snapshot.issue != null) {
            CastStatusNotice(stringResource(if (snapshot.issue == DiscoveryIssue.PERMISSION) R.string.discovery_permission_error else R.string.discovery_scan_error), true)
            if (snapshot.issue == DiscoveryIssue.PERMISSION) TextButton(onClick = onNetworkSettings) {
                Text(stringResource(R.string.permission_app_settings))
            }
        }
        if (snapshot.devices.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (snapshot.scanning) CircularProgressIndicator(Modifier.size(24.dp), color = CastColors.Accent, strokeWidth = 2.dp)
                Text(stringResource(R.string.discovery_empty), color = CastColors.Muted,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
        snapshot.devices.forEach { device ->
            Surface(Modifier.fillMaxWidth().clickable(enabled = enabled && device.selectable && preparingReceiver != device.instance) { onSelect(device) },
                shape = RoundedCornerShape(11.dp), color = Color(0x08FFFFFF),
                border = androidx.compose.foundation.BorderStroke(1.dp, CastColors.Divider)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Tv, null, Modifier.size(22.dp), tint = CastColors.Accent)
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (preparingReceiver == device.instance) stringResource(R.string.discovery_checking)
                            else if (device.selectable) device.addresses.firstOrNull().orEmpty() else stringResource(R.string.discovery_not_compatible),
                            style = MaterialTheme.typography.bodySmall, color = CastColors.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (preparingReceiver == device.instance) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CastColors.Accent)
                    else if (device.selectable) IconButton(onClick = { onOptions(device) }, enabled = enabled) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.ui_device_options), Modifier.size(18.dp), tint = CastColors.Muted)
                    }
                }
            }
        }
    }
}

/** One real field supports typing, pasting, selection, deletion and assistive technology. */
@Composable
private fun PairCodeInput(code: String, onCodeChange: (String) -> Unit, enabled: Boolean,
    onDone: () -> Unit, focusRequester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val description = stringResource(R.string.connection_pair_code)
    BasicTextField(value = code, onValueChange = { onCodeChange(it.uppercase().take(4)) }, enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled && code.length == 4) { keyboard?.hide(); onDone() } }),
        textStyle = MaterialTheme.typography.headlineMedium.copy(color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent),
        decorationBox = { inner ->
            Box {
                Box(Modifier.matchParentSize().alpha(0f)) { inner() }
                Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { index ->
                        val selected = focused && index == code.length.coerceAtMost(3)
                        Box(Modifier.weight(1f).height(56.dp).background(CastColors.Field, RoundedCornerShape(11.dp))
                            .border(if (selected) 2.dp else 1.dp, if (selected) CastColors.Accent else Color(0x34FFFFFF), RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center) {
                            Text(code.getOrNull(index)?.toString().orEmpty(), color = Color.White,
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        })
}
