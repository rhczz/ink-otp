package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

/**
 * The only card variant: surface + 1dp border + radius 18 + 16dp padding.
 */
@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalInkColors.current
    val shape = RoundedCornerShape(AppTokens.Radius.card)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.surface)
        .border(AppTokens.Border.hairline, colors.border, shape)
    Column(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
    ) {
        Column(modifier = Modifier.padding(AppTokens.Spacing.l), content = content)
    }
}
