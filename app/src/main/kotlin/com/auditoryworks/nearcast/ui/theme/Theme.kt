package com.auditoryworks.nearcast.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Shared with Desktop TX's dist/styles.css. Keep branding independent of wallpaper colors. */
object CastColors {
    val Background = Color(0xFF10121C)
    val Panel = Color(0xFF1E212E)
    val Field = Color(0xFF0C1020)
    val Accent = Color(0xFF7ADFFF)
    val Primary = Color(0xFF438FFF)
    val PrimaryEnd = Color(0xFF6A62FF)
    val Text = Color(0xFFE8EDF8)
    val Muted = Color(0xFF9BA8BF)
    val Subtle = Color(0xFF78869E)
    val Border = Color(0x44B0B4C7)
    val Divider = Color(0x18FFFFFF)
    val Success = Color(0xFF72DEB2)
    val Warning = Color(0xFFFFD08A)
}

private val CastColorScheme = darkColorScheme(
    primary = CastColors.Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF203B62),
    onPrimaryContainer = Color(0xFFA8EFFF),
    secondary = CastColors.Accent,
    onSecondary = CastColors.Background,
    secondaryContainer = Color(0xFF203946),
    onSecondaryContainer = CastColors.Accent,
    tertiary = CastColors.Success,
    onTertiary = CastColors.Background,
    tertiaryContainer = Color(0xFF183D37),
    onTertiaryContainer = CastColors.Success,
    background = CastColors.Background,
    onBackground = Color.White,
    surface = CastColors.Panel,
    onSurface = CastColors.Text,
    surfaceVariant = Color(0xFF282E40),
    onSurfaceVariant = CastColors.Muted,
    surfaceContainer = Color(0xFF1B2030),
    surfaceContainerHigh = Color(0xFF232A3B),
    surfaceContainerHighest = Color(0xFF2B3347),
    surfaceContainerLow = Color(0xFF171C2B),
    outline = Color(0xFF48516A),
    outlineVariant = CastColors.Divider,
    error = Color(0xFFFFB6C1),
    onError = Color(0xFF441220),
    errorContainer = Color(0xFF572637),
    onErrorContainer = Color(0xFFFFD9DF)
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
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = CastColors.Background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = CastColors.Background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = CastColorScheme, typography = CastTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(7.dp), small = RoundedCornerShape(11.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(24.dp))) {
        CompositionLocalProvider(LocalContentColor provides CastColors.Text, content = content)
    }
}
