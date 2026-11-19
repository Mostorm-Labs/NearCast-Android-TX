package com.auditoryworks.nearcast.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

enum class AppTab { SCAN, CODE, SETTINGS }

@Composable
fun CastDialogBars() {
    val view = LocalView.current
    val light = CastColors.Background.luminance() > .5f
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            // Android 7 cannot draw dark navigation buttons on a light background.
            if (Build.VERSION.SDK_INT < 26) {
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.BLACK
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light && Build.VERSION.SDK_INT >= 26
            }
        }
    }
}

@Composable
fun CastScaffold(
    isUpdateDownloadInProgress: Boolean,
    isDownloadProgressVisible: Boolean,
    onOpenSettings: () -> Unit,
    onShowDownloadProgress: () -> Unit,
    appVersionName: String = BuildConfig.VERSION_NAME,
    showVersion: Boolean = false,
    selectedTab: AppTab = AppTab.CODE,
    onTabSelected: (AppTab) -> Unit = { if (it == AppTab.SETTINGS) onOpenSettings() },
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(CastColors.Background)
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_brand_mark), null, Modifier.size(32.dp), tint = CastColors.Text)
            Text(stringResource(R.string.app_name), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isUpdateDownloadInProgress && !isDownloadProgressVisible) IconButton(onClick = onShowDownloadProgress) {
                Icon(Icons.Default.Download, stringResource(R.string.session_action_show_update))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                content()
                if (showVersion) Text(stringResource(R.string.ui_version, appVersionName.removePrefix("v")),
                    color = CastColors.Subtle, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        HorizontalDivider(color = CastColors.Divider)
        NavigationBar(containerColor = CastColors.Background, tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)) {
            AppTab.entries.forEach { tab ->
                val label = stringResource(when (tab) {
                    AppTab.SCAN -> R.string.ui_devices_tab
                    AppTab.CODE -> R.string.ui_code_tab
                    AppTab.SETTINGS -> R.string.ui_settings
                })
                NavigationBarItem(selected = selectedTab == tab, onClick = { onTabSelected(tab) },
                    icon = { Icon(when (tab) {
                        AppTab.SCAN -> Icons.Default.Radar
                        AppTab.CODE -> Icons.Default.Dialpad
                        AppTab.SETTINGS -> Icons.Default.Settings
                    }, null) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CastColors.Text, selectedTextColor = CastColors.Text,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        unselectedIconColor = CastColors.Muted, unselectedTextColor = CastColors.Muted))
            }
        }
    }
}

@Composable
fun CastPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = CastColors.Panel, border = BorderStroke(1.dp, CastColors.Border)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
fun CastInsetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = CastColors.Panel, border = BorderStroke(1.dp, CastColors.Divider)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun CastEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.8.sp, color = CastColors.Muted)
}

@Composable
fun CastPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.defaultMinSize(minHeight = 52.dp), enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CastColors.Primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f),
            disabledContentColor = CastColors.Muted),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), content = content)
}

@Composable
fun CastSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier.defaultMinSize(minHeight = 52.dp), enabled = enabled,
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CastColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CastColors.Text),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), content = content)
}

@Composable
fun CastStatusDot(color: Color) { Box(Modifier.size(7.dp).background(color, CircleShape)) }

@Composable
fun CastStatusNotice(text: String, isError: Boolean = false) {
    if (text.isBlank()) return
    Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else CastColors.Muted)
    }
}
