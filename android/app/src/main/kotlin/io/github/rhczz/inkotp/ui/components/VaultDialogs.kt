package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun VaultPasswordDialog(title: String, message: String, requireCurrent: Boolean = false,
    newPassword: Boolean = false, busy: Boolean = false,
    onConfirm: (current: String, password: String) -> Unit, onDismiss: () -> Unit,
    onCancelBusy: (() -> Unit)? = null,
    cancelling: Boolean = false,
    confirmText: String = "确认",
) {
    val colors = LocalInkColors.current
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    val cancel = if (busy) onCancelBusy else onDismiss
    AlertDialog(onDismissRequest = { cancel?.invoke() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(AppTokens.Radius.sheet), containerColor = colors.surface,
        title = { Text(title, style = AppType.section) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(message, style = AppType.body, color = colors.inkSecondary)
            Spacer(Modifier.height(AppTokens.Spacing.l))
            if (requireCurrent) {
                SecretTextField(current, { current = it }, "当前恢复口令", visible, { visible = !visible })
                Spacer(Modifier.height(AppTokens.Spacing.m))
            }
            SecretTextField(password, { password = it }, if (newPassword) "新恢复口令" else "恢复口令", visible, { visible = !visible })
            if (newPassword) {
                Spacer(Modifier.height(AppTokens.Spacing.m))
                SecretTextField(confirm, { confirm = it }, "再次输入新口令", visible, { visible = !visible },
                    error = if (confirm.isNotEmpty() && confirm != password) "两次输入不一致" else null)
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("我已了解：忘记口令将无法恢复备份", style = AppType.caption)
                }
            }
        } },
        confirmButton = { TextButton(enabled = !busy && password.isNotEmpty() && (!newPassword || (password == confirm && acknowledged)) && (!requireCurrent || current.isNotEmpty()),
            onClick = { val old = current; val supplied = password; current = ""; password = ""; confirm = ""; onConfirm(old, supplied) }
        ) { Text(if (cancelling) "正在取消…" else if (busy) "处理中…" else confirmText, style = AppType.button) } },
        dismissButton = { TextButton(enabled = cancel != null, onClick = { cancel?.invoke() }) { Text("取消", style = AppType.button) } },
    )
}
