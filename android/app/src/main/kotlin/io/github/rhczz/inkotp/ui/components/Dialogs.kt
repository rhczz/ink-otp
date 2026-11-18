package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun InkConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = LocalInkColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = AppTokens.Size.dialogMaxWidth),
        shape = RoundedCornerShape(AppTokens.Radius.sheet),
        containerColor = colors.surface,
        title = { Text(text = title, style = AppType.section, color = colors.ink) },
        text = {
            Text(
                text = message,
                modifier = Modifier.verticalScroll(rememberScrollState()),
                style = AppType.body,
                color = colors.inkSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    style = AppType.button,
                    color = if (destructive) colors.danger else colors.ink,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = AppType.button, color = colors.inkSecondary)
            }
        },
    )
}
