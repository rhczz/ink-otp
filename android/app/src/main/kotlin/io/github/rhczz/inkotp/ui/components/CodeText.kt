package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

/** `123 456` for 6 digits, `1234 5678` for 8 digits. */
fun groupCode(code: String, digits: Int): String {
    if (code.isEmpty()) return code
    return if (digits == 8 && code.length == 8) {
        "${code.substring(0, 4)} ${code.substring(4)}"
    } else if (code.length == 6) {
        "${code.substring(0, 3)} ${code.substring(3)}"
    } else {
        code
    }
}

fun hiddenCode(digits: Int): String = if (digits == 8) "•••• ••••" else "••• •••"

@Composable
fun CodeText(
    code: String,
    digits: Int,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = AppType.codeList,
    color: Color = LocalInkColors.current.ink,
) {
    val shown = if (hidden) hiddenCode(digits) else groupCode(code, digits)
    val spoken = if (hidden) "验证码已隐藏" else code.toCharArray().joinToString(" ")
    Text(
        text = shown,
        modifier = modifier.semantics { contentDescription = spoken },
        style = style,
        color = color,
        maxLines = 1,
    )
}

@Composable
fun CountdownRing(
    remainingSeconds: Int,
    period: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInkColors.current
    val warning = remainingSeconds <= 5
    val color = if (warning) colors.warning else colors.accent
    val progress = (remainingSeconds.toFloat() / period.coerceAtLeast(1)).coerceIn(0f, 1f)
    androidx.compose.foundation.Canvas(modifier = modifier.size(AppTokens.Size.countdownRing)) {
        val stroke = AppTokens.Border.focus.toPx()
        drawCircle(color = colors.border, radius = (size.minDimension - stroke) / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

@Composable
fun CountdownLabel(remainingSeconds: Int, period: Int = 30, modifier: Modifier = Modifier) {
    val colors = LocalInkColors.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CountdownRing(remainingSeconds = remainingSeconds, period = period)
        Spacer(Modifier.width(AppTokens.Spacing.xs))
        Text(
            text = "${remainingSeconds}s",
            style = AppType.caption.copy(fontFeatureSettings = "tnum"),
            color = if (remainingSeconds <= 5) colors.warning else colors.inkSecondary,
            fontWeight = FontWeight.Medium,
        )
    }
}
