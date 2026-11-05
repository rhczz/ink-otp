package io.github.rhczz.inkotp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun InkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = AppTokens.Dark.ink,
            onPrimary = AppTokens.Dark.bg,
            secondary = AppTokens.Dark.inkSecondary,
            background = AppTokens.Dark.bg,
            onBackground = AppTokens.Dark.ink,
            surface = AppTokens.Dark.surface,
            onSurface = AppTokens.Dark.ink,
            surfaceVariant = AppTokens.Dark.surfaceSunken,
            onSurfaceVariant = AppTokens.Dark.inkSecondary,
            outline = AppTokens.Dark.border,
            outlineVariant = AppTokens.Dark.borderStrong,
            error = AppTokens.Dark.danger,
            onError = AppTokens.Dark.bg,
        )
    } else {
        lightColorScheme(
            primary = AppTokens.Light.ink,
            onPrimary = AppTokens.Light.surface,
            secondary = AppTokens.Light.inkSecondary,
            background = AppTokens.Light.bg,
            onBackground = AppTokens.Light.ink,
            surface = AppTokens.Light.surface,
            onSurface = AppTokens.Light.ink,
            surfaceVariant = AppTokens.Light.surfaceSunken,
            onSurfaceVariant = AppTokens.Light.inkSecondary,
            outline = AppTokens.Light.border,
            outlineVariant = AppTokens.Light.borderStrong,
            error = AppTokens.Light.danger,
            onError = AppTokens.Light.surface,
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
