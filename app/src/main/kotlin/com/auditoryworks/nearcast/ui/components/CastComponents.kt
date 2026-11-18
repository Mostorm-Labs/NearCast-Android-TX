package com.auditoryworks.nearcast.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.auditoryworks.nearcast.BuildConfig
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.ui.theme.CastColors

/** Material 3 1.3's sheet follows the system's light mode, while our brand theme stays dark. */
@Composable
fun CastDarkDialogBars() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
}

@Composable
fun CastScaffold(
    isLogUploadInProgress: Boolean,
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onUploadLogs: () -> Unit,
    onShowDownloadProgress: () -> Unit,
    appVersionName: String = BuildConfig.VERSION_NAME,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize().background(CastColors.Background)) {
        Image(painterResource(R.drawable.cast_background), null, Modifier.matchParentSize(),
            contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(
            Color(0xCC161A29), Color(0xAA171A28), Color(0xD9090B13)))))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.app_logo), null,
                    Modifier.size(36.dp).clip(RoundedCornerShape(7.dp)))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.app_name), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isUpdateDownloadInProgress && !isDownloadProgressVisible) {
                    IconButton(onClick = onShowDownloadProgress) {
                        Icon(Icons.Default.Download, stringResource(R.string.session_action_show_update),
                            tint = CastColors.Accent, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onUploadLogs, enabled = !isLogUploadInProgress) {
                    Icon(Icons.Default.CloudUpload, stringResource(R.string.session_action_upload_logs),
                        modifier = Modifier.size(20.dp))
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 665.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    content()
                    Text(stringResource(R.string.ui_version, appVersionName.removePrefix("v")),
                        color = CastColors.Subtle, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                }
            }
        }
    }
}

@Composable
fun CastPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth().shadow(24.dp, shape).clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xE81E212E), Color(0xCC1E212E))))
        .border(1.dp, CastColors.Border, shape).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
fun CastInsetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = Color(0x08FFFFFF), border = BorderStroke(1.dp, CastColors.Divider)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun CastEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.8.sp, color = CastColors.Accent)
}

@Composable
fun CastPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Button(onClick, modifier.defaultMinSize(minHeight = 48.dp).alpha(if (enabled) 1f else .45f)
        .clip(shape).background(Brush.linearGradient(listOf(CastColors.Primary, CastColors.PrimaryEnd))),
        enabled = enabled, shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, contentColor = Color.White,
            disabledContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), content = content)
}

@Composable
fun CastSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, CastColors.Divider),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x0CFFFFFF),
            contentColor = CastColors.Text),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), content = content)
}

@Composable
fun CastStatusDot(color: Color) {
    Box(Modifier.size(7.dp).background(color, CircleShape))
}

@Composable
fun CastStatusNotice(text: String, isError: Boolean = false) {
    if (text.isBlank()) return
    Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) Color(0x229B3348) else Color(0x0C7ADFFF),
        shape = RoundedCornerShape(9.dp)) {
        Text(text, Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else CastColors.Muted)
    }
}
