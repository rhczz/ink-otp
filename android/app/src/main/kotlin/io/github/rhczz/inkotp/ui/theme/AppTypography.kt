package io.github.rhczz.inkotp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.rhczz.inkotp.R

/**
 * Inter for Latin/numerals; Android falls back to the platform font for CJK.
 * Only 400/500/600 are bundled (design doc §11.4).
 */
val InterFamily: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/**
 * The precise styles used by components. The seven size steps are fixed:
 * 12 / 14 / 16 / 20 / 24 / 32 / 56 sp.
 */
object AppType {

    val codeList = TextStyle(
        fontFamily = InterFamily,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        fontFeatureSettings = "tnum",
    )

    val codeDetail = TextStyle(
        fontFamily = InterFamily,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 3.sp,
        fontFeatureSettings = "tnum",
    )

    val title = TextStyle(
        fontFamily = InterFamily,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    )

    val section = TextStyle(
        fontFamily = InterFamily,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    )

    val body = TextStyle(
        fontFamily = InterFamily,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal,
    )

    val bodyStrong = body.copy(fontWeight = FontWeight.Medium)

    val label = TextStyle(
        fontFamily = InterFamily,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    )

    val caption = TextStyle(
        fontFamily = InterFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    )

    val button = label.copy(fontWeight = FontWeight.SemiBold)
}

/**
 * MaterialTheme mapping. Components should prefer AppType; this keeps
 * Material3 defaults inside the same seven-size ladder.
 */
val InkTypography = Typography(
    headlineLarge = AppType.codeDetail,
    headlineMedium = AppType.title,
    headlineSmall = AppType.section,
    titleLarge = AppType.section,
    titleMedium = AppType.bodyStrong,
    titleSmall = AppType.label,
    bodyLarge = AppType.body,
    bodyMedium = AppType.label,
    bodySmall = AppType.caption,
    labelLarge = AppType.button,
    labelMedium = AppType.label,
    labelSmall = AppType.caption,
)
