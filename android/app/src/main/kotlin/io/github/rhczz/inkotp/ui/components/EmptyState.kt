package io.github.rhczz.inkotp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryText: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = LocalInkColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppTokens.Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InkIcon(
            icon = icon,
            contentDescription = null,
            size = AppTokens.IconSize.state,
            tint = colors.inkSecondary,
        )
        Spacer(Modifier.height(AppTokens.Spacing.l))
        Text(text = title, style = AppType.section, color = colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(AppTokens.Spacing.s))
        Text(
            text = body,
            style = AppType.body,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        if (primaryText != null && onPrimary != null) {
            Spacer(Modifier.height(AppTokens.Spacing.xxl))
            InkPrimaryButton(text = primaryText, onClick = onPrimary)
        }
        if (secondaryText != null && onSecondary != null) {
            Spacer(Modifier.height(AppTokens.Spacing.s))
            InkTextButton(text = secondaryText, onClick = onSecondary)
        }
    }
}
