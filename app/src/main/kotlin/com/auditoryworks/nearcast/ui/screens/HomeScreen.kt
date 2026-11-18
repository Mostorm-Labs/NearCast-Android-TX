package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.touchback.RemoteControlState
import com.auditoryworks.nearcast.ui.components.*
import com.auditoryworks.nearcast.ui.theme.CastColors

@Composable
fun HomeScreen(
    pairCode: String,
    statusText: String,
    appVersionName: String,
    isLogUploadInProgress: Boolean,
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onPairCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onUploadLogs: () -> Unit,
    onShowDownloadProgress: () -> Unit,
    remoteControlState: RemoteControlState,
    onRemoteEnabled: (Boolean) -> Unit,
    onRemoteSettings: () -> Unit,
    onRemoteHelp: () -> Unit,
    onRemoteRetry: () -> Unit,
    onDiscoverDevices: () -> Unit,
    localReceiverName: String? = null,
    localReceiverAddress: String? = null,
    isJoining: Boolean = false,
    onUseCloud: () -> Unit = {},
    onCancelJoin: () -> Unit = {}
) {
    CastScaffold(isLogUploadInProgress, isUpdateDownloadInProgress, isDownloadProgressVisible,
        onUploadLogs, onShowDownloadProgress, appVersionName) {
        CastPanel {
            CastEyebrow(stringResource(R.string.ui_sender_eyebrow))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.ui_welcome_title), style = MaterialTheme.typography.headlineLarge,
                color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(if (localReceiverName != null) R.string.local_enter_code else R.string.cloud_enter_code),
                style = MaterialTheme.typography.bodyMedium, color = CastColors.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(if (localReceiverName != null) Icons.Default.Wifi else Icons.Default.Cloud,
                    null, Modifier.size(16.dp), tint = CastColors.Accent)
                Text(if (localReceiverName != null) stringResource(R.string.local_target, localReceiverName)
                    else stringResource(R.string.cloud_target), style = MaterialTheme.typography.labelMedium,
                    color = CastColors.Accent, modifier = Modifier.weight(1f, fill = false))
            }
            if (localReceiverName != null) {
                Spacer(Modifier.height(5.dp))
                Text(localReceiverAddress ?: stringResource(R.string.local_waiting_discovery),
                    style = MaterialTheme.typography.bodySmall, color = CastColors.Muted, textAlign = TextAlign.Center)
                TextButton(onClick = onUseCloud, enabled = !isJoining) { Text(stringResource(R.string.local_switch_cloud)) }
            } else Spacer(Modifier.height(16.dp))
            PairCodeInput(pairCode, onPairCodeChange, enabled = !isJoining, onDone = onJoin)
            Spacer(Modifier.height(9.dp))
            Text(stringResource(R.string.connection_pair_code), style = MaterialTheme.typography.bodySmall,
                color = CastColors.Subtle)
            Spacer(Modifier.height(22.dp))
            CastPrimaryButton(onClick = onJoin, Modifier.fillMaxWidth(), enabled = pairCode.isNotBlank() && !isJoining) {
                if (isJoining) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Default.Cast, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (isJoining) R.string.connection_connecting else R.string.connection_join))
            }
            if (isJoining) TextButton(onClick = onCancelJoin) { Text(stringResource(R.string.connection_cancel)) }
            if (statusText.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                CastStatusNotice(statusText, isError = statusText.contains("failed", true) || statusText.contains("error", true))
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = CastColors.Divider)
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CastEyebrow(stringResource(R.string.ui_local_eyebrow))
                Text(stringResource(R.string.ui_receivers_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ui_receivers_hint), style = MaterialTheme.typography.bodySmall,
                    color = CastColors.Muted)
            }
            Spacer(Modifier.height(14.dp))
            CastSecondaryButton(onClick = onDiscoverDevices, modifier = Modifier.fillMaxWidth(), enabled = !isJoining) {
                Icon(Icons.Default.Devices, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.discovery_title), Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = CastColors.Accent)
            }
        }
        RemoteControlCard(remoteControlState, onRemoteEnabled, onRemoteSettings, onRemoteHelp, onRemoteRetry)
    }
}

/** One real editable field keeps paste, selection, backspace and accessibility behavior intact. */
@Composable
private fun PairCodeInput(code: String, onCodeChange: (String) -> Unit, enabled: Boolean, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val description = stringResource(R.string.connection_pair_code)
    BasicTextField(value = code, onValueChange = { if (it.length <= 8) onCodeChange(it.uppercase()) },
        enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled && code.isNotBlank()) { keyboard?.hide(); onDone() } }),
        textStyle = MaterialTheme.typography.headlineMedium.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        decorationBox = { innerTextField ->
            Box {
                Box(Modifier.matchParentSize().alpha(0f)) { innerTextField() }
                val cells = maxOf(4, code.length)
                Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(cells) { index ->
                        val selected = focused && index == code.length.coerceAtMost(cells - 1)
                        Box(Modifier.weight(1f).height(64.dp)
                            .background(CastColors.Field.copy(alpha = .8f), RoundedCornerShape(14.dp))
                            .border(if (selected) 2.dp else 1.dp,
                                if (selected) CastColors.Accent else Color(0x34FFFFFF), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center) {
                            Text(code.getOrNull(index)?.toString() ?: "", color = Color.White,
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        })
}
