package io.github.rhczz.inkotp.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalInkDarkTheme = staticCompositionLocalOf { false }

data class AvatarColors(
    val container: Color,
    val content: Color,
)

/**
 * Eight low-saturation avatar colors; the issuer hash picks one stably so the
 * same service always gets the same color (design doc §11.3).
 */
object AvatarPalette {

    private val light = AppTokens.AvatarLight.all
    private val dark = AppTokens.AvatarDark.all

    fun forIssuer(issuer: String?, darkTheme: Boolean): AvatarColors {
        val key = issuer?.lowercase()?.takeIf { it.isNotBlank() } ?: "?"
        val index = Math.floorMod(key.hashCode(), 8)
        return if (darkTheme) dark[index] else light[index]
    }
}
