package io.github.rhczz.inkotp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class InkColors(
    val bg: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkDisabled: Color,
    val border: Color,
    val borderStrong: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val scrim: Color,
)

private val LightInkColors = InkColors(
    bg = AppTokens.Light.bg,
    surface = AppTokens.Light.surface,
    surfaceSunken = AppTokens.Light.surfaceSunken,
    ink = AppTokens.Light.ink,
    inkSecondary = AppTokens.Light.inkSecondary,
    inkDisabled = AppTokens.Light.inkDisabled,
    border = AppTokens.Light.border,
    borderStrong = AppTokens.Light.borderStrong,
    accent = AppTokens.Light.accent,
    accentSoft = AppTokens.Light.accentSoft,
    success = AppTokens.Light.success,
    warning = AppTokens.Light.warning,
    danger = AppTokens.Light.danger,
    scrim = Color.Black.copy(alpha = AppTokens.Alpha.scrim),
)

private val DarkInkColors = InkColors(
    bg = AppTokens.Dark.bg,
    surface = AppTokens.Dark.surface,
    surfaceSunken = AppTokens.Dark.surfaceSunken,
    ink = AppTokens.Dark.ink,
    inkSecondary = AppTokens.Dark.inkSecondary,
    inkDisabled = AppTokens.Dark.inkDisabled,
    border = AppTokens.Dark.border,
    borderStrong = AppTokens.Dark.borderStrong,
    accent = AppTokens.Dark.accent,
    accentSoft = AppTokens.Dark.accentSoft,
    success = AppTokens.Dark.success,
    warning = AppTokens.Dark.warning,
    danger = AppTokens.Dark.danger,
    scrim = Color.Black.copy(alpha = AppTokens.Alpha.scrim),
)

val LocalInkColors = staticCompositionLocalOf { LightInkColors }

@Composable
fun InkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkInkColors else LightInkColors
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.ink,
            onPrimary = colors.bg,
            secondary = colors.inkSecondary,
            onSecondary = colors.bg,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceSunken,
            onSurfaceVariant = colors.inkSecondary,
            outline = colors.border,
            outlineVariant = colors.borderStrong,
            error = colors.danger,
            onError = colors.bg,
            scrim = colors.scrim,
        )
    } else {
        lightColorScheme(
            primary = colors.ink,
            onPrimary = colors.surface,
            secondary = colors.inkSecondary,
            onSecondary = colors.surface,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceSunken,
            onSurfaceVariant = colors.inkSecondary,
            outline = colors.border,
            outlineVariant = colors.borderStrong,
            error = colors.danger,
            onError = colors.surface,
            scrim = colors.scrim,
        )
    }

    CompositionLocalProvider(
        LocalInkColors provides colors,
        LocalInkDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = InkTypography,
            shapes = InkShapes,
            content = content,
        )
    }
}
