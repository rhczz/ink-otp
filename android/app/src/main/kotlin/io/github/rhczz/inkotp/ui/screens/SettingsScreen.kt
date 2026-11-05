package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.InkDivider
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.components.SectionHeader
import io.github.rhczz.inkotp.ui.components.SettingRow
import io.github.rhczz.inkotp.ui.components.SwitchRow
import io.github.rhczz.inkotp.ui.model.SettingsUiState
import io.github.rhczz.inkotp.ui.theme.AppTokens

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onBiometricChange: (Boolean) -> Unit,
    onLockTimeoutChange: (Int) -> Unit,
    onHideCodesChange: (Boolean) -> Unit,
    onClipboardChange: (Int) -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
    onOnboarding: () -> Unit,
) {
    val lockLabel = when (state.lockTimeoutSeconds) {
        0 -> "立即"
        60 -> "1 分钟"
        else -> "30 秒"
    }
    val clipboardLabel = when (state.clipboardClearSeconds) {
        0 -> "关闭"
        else -> "30 秒"
    }

    ScreenScaffold(title = "设置", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppTokens.Size.bottomContentPadding),
        ) {
            SectionHeader("安全")
            SwitchRow(
                icon = R.drawable.ic_fingerprint,
                title = "生物识别解锁",
                checked = state.biometricEnabled,
                onCheckedChange = onBiometricChange,
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_schedule,
                title = "自动锁定",
                value = lockLabel,
                onClick = {
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
                value = clipboardLabel,
                onClick = { onClipboardChange(if (state.clipboardClearSeconds == 0) 30 else 0) },
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_key,
                title = "修改恢复口令",
                value = if (state.passwordSet) null else "未设置",
                onClick = {},
            )

            SectionHeader("备份与恢复")
            SettingRow(
                icon = R.drawable.ic_upload,
                title = "导出备份",
                value = "M3",
                onClick = {},
            )
            InkDivider()
            SettingRow(
                icon = R.drawable.ic_download,
                title = "导入备份",
                value = "M3",
                onClick = {},
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
            SettingRow(
                icon = R.drawable.ic_info,
                title = "关于与开源许可",
                onClick = onAbout,
            )
        }
    }
}
