package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.rhczz.inkotp.core.interchange.InterchangeFormat
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

fun InterchangeFormat.displayName(): String = when (this) {
    InterchangeFormat.AEGIS_ENCRYPTED -> "Aegis 加密备份"
    InterchangeFormat.AEGIS_PLAINTEXT -> "Aegis 明文 JSON"
    InterchangeFormat.TWOFAS_ENCRYPTED -> "2FAS 加密备份"
    InterchangeFormat.TWOFAS_PLAINTEXT -> "2FAS 明文 JSON"
    InterchangeFormat.OTPAUTH_TEXT -> "otpauth 迁移文本"
}

fun InterchangeFormat.documentMime(): String = when (this) {
    InterchangeFormat.OTPAUTH_TEXT -> "text/plain"
    InterchangeFormat.TWOFAS_ENCRYPTED, InterchangeFormat.TWOFAS_PLAINTEXT -> "application/octet-stream"
    else -> "application/json"
}

@Composable
fun BackupFormatDialog(
    onConfirm: (InterchangeFormat) -> Unit,
    onDismiss: () -> Unit,
    onQrExport: () -> Unit,
) {
    val colors = LocalInkColors.current
    var selected by remember { mutableStateOf<InterchangeFormat?>(InterchangeFormat.AEGIS_ENCRYPTED) }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(AppTokens.Radius.sheet),
        containerColor = colors.surface,
        title = { Text("选择备份或迁移格式", style = AppType.section) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("推荐使用可与 Aegis 互通的加密格式。跨应用迁移保留验证码参数，外观与本机安全设置不一定通用。",
                style = AppType.body, color = colors.inkSecondary)
            InterchangeFormat.entries.forEach { format ->
                Row(Modifier.clickable { selected = format }.padding(vertical = AppTokens.Spacing.s),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == format, onClick = { selected = format })
                    Column {
                        Text(format.displayName(), style = AppType.body)
                        Text(if (format.plaintext) "含明文账号密钥，仅用于主动迁移" else "使用当前应用恢复口令加密",
                            style = AppType.caption, color = if (format.plaintext) colors.danger else colors.inkSecondary)
                    }
                }
            }
            Row(Modifier.clickable { selected = null }.padding(vertical = AppTokens.Spacing.s),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == null, onClick = { selected = null })
                Column {
                    Text("迁移二维码（图片）", style = AppType.body)
                    Text("显示二维码或保存 PNG，图片包含明文账号密钥",
                        style = AppType.caption, color = colors.danger)
                }
            }
        } },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) ?: onQrExport() }) { Text("下一步", style = AppType.button) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", style = AppType.button) } },
    )
}
