package com.auditoryworks.nearcast.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Semantic colors resolve inside the current system-selected Material theme. */
object CastColors {
    val Background: Color @Composable get() = MaterialTheme.colorScheme.background
    val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
    val Field: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
    val Accent: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val Primary: Color @Composable get() = MaterialTheme.colorScheme.primary
    val Text: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Subtle: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Border: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val Divider: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val Success: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val Warning: Color @Composable get() = MaterialTheme.colorScheme.onSurface
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF171717), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAEAEA), onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF171717), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAEAEA), onSecondaryContainer = Color(0xFF171717),
    tertiary = Color(0xFF404040), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAEAEA), onTertiaryContainer = Color(0xFF171717),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF171717),
    surface = Color.White, onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF616161),
    surfaceContainer = Color(0xFFF4F4F4), surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainerLowest = Color.White, surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE8E8E8), surfaceTint = Color.Transparent,
    outline = Color(0xFF858585), outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFF242424), onError = Color.White,
    errorContainer = Color(0xFFEAEAEA), onErrorContainer = Color(0xFF171717),
    inverseSurface = Color(0xFF242424), inverseOnSurface = Color(0xFFFAFAFA), inversePrimary = Color.White
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F5), onPrimary = Color(0xFF171717),
    primaryContainer = Color(0xFF303030), onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFF5F5F5), onSecondary = Color(0xFF171717),
    secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color(0xFFF5F5F5),
    tertiary = Color(0xFFD4D4D4), onTertiary = Color(0xFF171717),
    tertiaryContainer = Color(0xFF303030), onTertiaryContainer = Color(0xFFF5F5F5),
    background = Color(0xFF101010), onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF191919), onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFB5B5B5),
    surfaceContainer = Color(0xFF202020), surfaceContainerLow = Color(0xFF222222),
    surfaceContainerLowest = Color(0xFF101010), surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF333333), surfaceTint = Color.Transparent,
    outline = Color(0xFF858585), outlineVariant = Color(0xFF353535),
    error = Color(0xFFE5E5E5), onError = Color(0xFF171717),
    errorContainer = Color(0xFF303030), onErrorContainer = Color(0xFFF5F5F5),
    inverseSurface = Color(0xFFE5E5E5), inverseOnSurface = Color(0xFF171717), inversePrimary = Color(0xFF171717)
)

private val CastTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)

@Composable
fun ScreenCastTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = colors.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = if (Build.VERSION.SDK_INT < 26) android.graphics.Color.BLACK else colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark && Build.VERSION.SDK_INT >= 26
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = CastTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp))) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}
