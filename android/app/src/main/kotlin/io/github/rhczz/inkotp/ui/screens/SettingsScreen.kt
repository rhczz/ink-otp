package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.InkDivider
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.components.SectionHeader
import io.github.rhczz.inkotp.ui.components.SettingRow
import io.github.rhczz.inkotp.ui.components.SwitchRow
import io.github.rhczz.inkotp.ui.model.SettingsUiState
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onLockTimeoutChange: (Int) -> Unit,
    onHideCodesChange: (Boolean) -> Unit,
    onClipboardChange: (Int) -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
    onOnboarding: () -> Unit,
    onPassword: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onLock: () -> Unit = {},
    onSystemAuthenticationChange: (Boolean) -> Unit = {},
    onPrivacy: (() -> Unit)? = null,
    onWithdrawPrivacy: (() -> Unit)? = null,
) {
    val colors = LocalInkColors.current
    val lockLabel = when (state.lockTimeoutSeconds) {
        0 -> "立即"
        60 -> "1 分钟"
        else -> "30 秒"
    }
    var choosingClipboardClear by remember { mutableStateOf(false) }

    ScreenScaffold(title = "设置", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppTokens.Size.bottomContentPadding),
        ) {
            SectionHeader("安全")
            SwitchRow(
                icon = R.drawable.ic_lock,
                title = "每次进入时验证身份",
                checked = state.systemAuthenticationEnabled,
                onCheckedChange = onSystemAuthenticationChange,
                enabled = state.passwordSet,
            )
            Text(
                text = if (state.passwordSet) "开启后使用手机的指纹、人脸或锁屏密码。关闭时可直接进入。"
                    else "先设置恢复口令，才能开启每次进入验证。",
                style = AppType.caption,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = AppTokens.Spacing.l, vertical = AppTokens.Spacing.s),
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_schedule,
                title = "自动锁定",
                value = if (state.systemAuthenticationEnabled) "每次进入验证" else lockLabel,
                onClick = {
                    if (state.systemAuthenticationEnabled) return@SettingRow
                    val next = when (state.lockTimeoutSeconds) {
                        0 -> 30
                        30 -> 60
                        else -> 0
                    }
                    onLockTimeoutChange(next)
                },
            )
            InkDivider()
            SwitchRow(
                icon = R.drawable.ic_visibility_off,
                title = "隐藏验证码",
                checked = state.hideCodesByDefault,
                onCheckedChange = onHideCodesChange,
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_content_copy,
                title = "剪贴板清理",
                value = clipboardClearLabel(state.clipboardClearSeconds),
                onClick = { choosingClipboardClear = true },
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_key,
                title = if (state.passwordSet) "修改恢复口令" else "设置恢复口令",
                value = if (state.passwordSet) null else "未设置",
                onClick = onPassword,
            )

            SectionHeader("备份与恢复")
            SettingRow(
                icon = R.drawable.ic_upload,
                title = "导出备份",
                onClick = onExport,
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_download,
                title = "导入备份",
                onClick = onImport,
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_backup,
                title = "上次备份",
                value = state.lastBackupAt ?: "从未",
            )

            SectionHeader("帮助")
            SettingRow(
                icon = R.drawable.ic_help,
                title = "验证码对不上？",
                value = "诊断",
                onClick = onDiagnostics,
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_restore,
                title = "首次启动引导",
                onClick = onOnboarding,
            )
            InkDivider()
            if (onPrivacy != null) {
                SettingRow(icon = R.drawable.ic_shield, title = "隐私声明", onClick = onPrivacy)
                InkDivider()
            }
            if (onWithdrawPrivacy != null) {
                SettingRow(icon = R.drawable.ic_lock, title = "撤回隐私同意", onClick = onWithdrawPrivacy)
                InkDivider()
            }
            SettingRow(
                icon = R.drawable.ic_info,
                title = "关于与开源许可",
                onClick = onAbout,
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_lock,
                title = "立即锁定",
                onClick = onLock,
            )
        }
    }

    if (choosingClipboardClear) {
        ClipboardClearDialog(
            selectedSeconds = state.clipboardClearSeconds,
            onSelect = { seconds ->
                choosingClipboardClear = false
                if (seconds != state.clipboardClearSeconds) onClipboardChange(seconds)
            },
            onDismiss = { choosingClipboardClear = false },
        )
    }
}

private val clipboardClearChoices = listOf(0, 1, 15, 30, 60)

private fun clipboardClearLabel(seconds: Int): String = when (seconds) {
    0 -> "关闭"
    1 -> "立即"
    else -> "$seconds 秒"
}

@Composable
private fun ClipboardClearDialog(selectedSeconds: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalInkColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = AppTokens.Size.dialogMaxWidth),
        shape = RoundedCornerShape(AppTokens.Radius.sheet),
        containerColor = colors.surface,
        title = { Text("选择清理时间", style = AppType.section, color = colors.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("仅对从 InkOTP 复制的验证码生效。", style = AppType.body, color = colors.inkSecondary)
                Column(Modifier.selectableGroup()) {
                    clipboardClearChoices.forEach { seconds ->
                        val selected = seconds == selectedSeconds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = AppTokens.Size.touchTarget)
                                .selectable(selected, role = Role.RadioButton, onClick = { onSelect(seconds) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(AppTokens.Spacing.m))
                            Text(clipboardClearLabel(seconds), style = AppType.body, color = colors.ink)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", style = AppType.button) } },
    )
}
