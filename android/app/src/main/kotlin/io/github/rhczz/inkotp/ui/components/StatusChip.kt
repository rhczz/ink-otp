package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalInkColors.current
    val shape = RoundedCornerShape(AppTokens.Radius.pill)
    Text(
        text = text,
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(
                AppTokens.Border.hairline,
                if (selected) colors.accent else colors.border,
                shape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = AppTokens.Spacing.m, vertical = AppTokens.Spacing.xs),
        style = AppType.label,
        color = if (selected) colors.accent else colors.inkSecondary,
        maxLines = 1,
    )
}
