package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun InkPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = LocalInkColors.current
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(AppTokens.Size.buttonHeight),
        shape = RoundedCornerShape(AppTokens.Radius.control),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.ink,
            contentColor = colors.surface,
            disabledContainerColor = colors.inkDisabled,
            disabledContentColor = colors.surface,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(AppTokens.Spacing.l),
                strokeWidth = AppTokens.Border.focus,
                color = colors.surface,
            )
            Spacer(Modifier.width(AppTokens.Spacing.s))
        }
        Text(text = text, style = AppType.button)
    }
}

@Composable
fun InkSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalInkColors.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(AppTokens.Size.buttonHeight),
        shape = RoundedCornerShape(AppTokens.Radius.control),
        border = BorderStroke(AppTokens.Border.hairline, colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.ink),
    ) {
        Text(text = text, style = AppType.button)
    }
}

@Composable
fun InkTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = LocalInkColors.current.accent,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(AppTokens.Size.textButtonHeight),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text = text, style = AppType.button)
    }
}

@Composable
fun InkIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalInkColors.current.ink,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(AppTokens.Size.touchTarget),
    ) {
        InkIcon(icon = icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
