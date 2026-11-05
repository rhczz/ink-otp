package io.github.rhczz.inkotp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The only source of visual tokens (Design Language v1.0, "Quiet Ink").
 *
 * Components must reference these tokens; hardcoded colors/sizes are rejected by
 * tools/check_design_tokens.py. See design doc §11.0.
 */
object AppTokens {

    object Light {
        val bg = Color(0xFFF6F7F9)
        val surface = Color(0xFFFFFFFF)
        val surfaceSunken = Color(0xFFEDEFF3)
        val ink = Color(0xFF0E1116)
        val inkSecondary = Color(0xFF56606E)
        val inkDisabled = Color(0xFF9AA3B0)
        val border = Color(0xFFE2E6EB)
        val borderStrong = Color(0xFFCDD3DB)
        val accent = Color(0xFF2B4EFF)
        val accentSoft = Color(0xFFE9EEFF)
        val success = Color(0xFF12B76A)
        val warning = Color(0xFFF79009)
        val danger = Color(0xFFD92D20)
    }

    object Dark {
        val bg = Color(0xFF090A0C)
        val surface = Color(0xFF121418)
        val surfaceSunken = Color(0xFF1A1D23)
        val ink = Color(0xFFF4F6F9)
        val inkSecondary = Color(0xFFA7AFBC)
        val inkDisabled = Color(0xFF5E6673)
        val border = Color(0xFF232830)
        val borderStrong = Color(0xFF333A45)
        val accent = Color(0xFF7C93FF)
        val accentSoft = Color(0xFF1B2140)
        val success = Color(0xFF3DDC97)
        val warning = Color(0xFFFDB022)
        val danger = Color(0xFFF97066)
    }

    /** Page margin / spacing scale (4dp grid; page values 12/16/20/24/32). */
    object Spacing {
        val none = 0.dp
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 20.dp
        val xxl = 24.dp
        val xxxl = 32.dp
    }

    /** The only five corner radii allowed by the design contract. */
    object Radius {
        val avatar = 10.dp
        val control = 14.dp
        val card = 18.dp
        val sheet = 28.dp
        val pill = 999.dp
    }

    /** Fixed component sizes from the design contract. */
    object Size {
        val screenMaxWidth = 560.dp
        val bottomContentPadding = 96.dp
        val touchTarget = 48.dp
        val buttonHeight = 52.dp
        val textButtonHeight = 48.dp
        val settingRowHeight = 56.dp
        val listRowHeight = 72.dp
        val avatar = 36.dp
        val unlockButton = 64.dp
        val countdownRing = 20.dp
        val searchHeight = 44.dp
        val codeCardHeight = 88.dp
        val sheetHandleWidth = 32.dp
        val sheetHandleHeight = 4.dp
        val dialogMaxWidth = 320.dp
        val scanFrame = 240.dp
        val settingValueMaxWidth = 180.dp
    }

    /** Eight low-saturation avatar colors (light theme). */
    object AvatarLight {
        val slate = AvatarColors(Color(0xFFE7EAF0), Color(0xFF3D4654))
        val cobalt = AvatarColors(Color(0xFFE6EBFF), Color(0xFF2B4EFF))
        val teal = AvatarColors(Color(0xFFDEF4F0), Color(0xFF0B6B5B))
        val amber = AvatarColors(Color(0xFFFAEEDC), Color(0xFF8A5A10))
        val rose = AvatarColors(Color(0xFFFBE7E9), Color(0xFFA32638))
        val violet = AvatarColors(Color(0xFFEEE8FB), Color(0xFF5B3FA6))
        val moss = AvatarColors(Color(0xFFE9F2DE), Color(0xFF4A6B22))
        val sky = AvatarColors(Color(0xFFE3F1FA), Color(0xFF1D5E86))
        val all = listOf(slate, cobalt, teal, amber, rose, violet, moss, sky)
    }

    /** Eight low-saturation avatar colors (dark theme). */
    object AvatarDark {
        val slate = AvatarColors(Color(0xFF252A33), Color(0xFFC6CEDA))
        val cobalt = AvatarColors(Color(0xFF1C2547), Color(0xFFA9B8FF))
        val teal = AvatarColors(Color(0xFF12312D), Color(0xFF7FE0CC))
        val amber = AvatarColors(Color(0xFF33270F), Color(0xFFF5C878))
        val rose = AvatarColors(Color(0xFF331A1F), Color(0xFFF5A3AC))
        val violet = AvatarColors(Color(0xFF251C3B), Color(0xFFC4B0F5))
        val moss = AvatarColors(Color(0xFF222C15), Color(0xFFBBD98C))
        val sky = AvatarColors(Color(0xFF16283A), Color(0xFF9FD0EC))
        val all = listOf(slate, cobalt, teal, amber, rose, violet, moss, sky)
    }

    /** The only four icon sizes allowed by the design contract. */
    object IconSize {
        val inline = 20.dp
        val control = 24.dp
        val primary = 28.dp
        val state = 48.dp
    }

    object Border {
        val hairline = 1.dp
        val focus = 2.dp
    }

    object Elevation {
        val flat = 0.dp
        val pressed = 1.dp
    }

    object Alpha {
        const val scrim = 0.55f
        const val pressedOverlay = 0.08f
    }

    /** The only six motion durations allowed by the design contract. */
    object Motion {
        const val PRESS_MS = 120
        const val SETTLE_MS = 160
        const val PAGE_MS = 220
        const val COUNTDOWN_STEP_MS = 250
        const val SHEET_OPEN_MS = 260
        const val HIGHLIGHT_MS = 300
        const val SHEET_CLOSE_MS = 240
    }
}
