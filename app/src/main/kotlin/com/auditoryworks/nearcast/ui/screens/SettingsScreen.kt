package com.auditoryworks.nearcast.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.touchback.RemoteControlState
import com.auditoryworks.nearcast.ui.components.*
import com.auditoryworks.nearcast.ui.theme.CastColors

@Composable
fun SettingsScreen(
    remoteControlState: RemoteControlState,
    onRemoteEnabled: (Boolean) -> Unit,
    onRemoteSettings: () -> Unit,
    onRemoteHelp: () -> Unit,
    onRemoteRetry: () -> Unit,
    isLogUploadInProgress: Boolean,
    onUploadLogs: () -> Unit,
    isCheckingUpdate: Boolean,
    availableVersion: String?,
    updateMessage: String,
    onCheckUpdate: () -> Unit,
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onShowDownloadProgress: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    CastScaffold(isUpdateDownloadInProgress, isDownloadProgressVisible, {}, onShowDownloadProgress,
        onBack = onBack, showVersion = true) {
        RemoteControlCard(remoteControlState, onRemoteEnabled, onRemoteSettings, onRemoteHelp, onRemoteRetry)
        CastInsetPanel {
            Text(stringResource(R.string.ui_help_updates), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.SystemUpdate, null, tint = CastColors.Accent)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_check_update), style = MaterialTheme.typography.bodyMedium)
                    if (availableVersion != null) Text(stringResource(R.string.ui_update_available, availableVersion),
                        style = MaterialTheme.typography.bodySmall, color = CastColors.Accent)
                }
                TextButton(onClick = onCheckUpdate, enabled = !isCheckingUpdate && !isUpdateDownloadInProgress) {
                    if (isCheckingUpdate) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(if (availableVersion != null) R.string.ui_view_update else R.string.ui_check))
                }
            }
            if (updateMessage.isNotBlank()) Text(updateMessage, style = MaterialTheme.typography.bodySmall, color = CastColors.Muted)
            HorizontalDivider(color = CastColors.Divider)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.BugReport, null, tint = CastColors.Accent)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ui_logs_hint), style = MaterialTheme.typography.bodySmall, color = CastColors.Muted)
                }
                TextButton(onClick = onUploadLogs, enabled = !isLogUploadInProgress) {
                    if (isLogUploadInProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.ui_open))
                }
            }
        }
    }
}
