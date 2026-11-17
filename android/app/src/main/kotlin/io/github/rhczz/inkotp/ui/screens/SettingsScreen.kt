package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

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
    onPassword: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onLock: () -> Unit = {},
    onSystemAuthenticationChange: (Boolean) -> Unit = {},
) {
    val colors = LocalInkColors.current
    val lockLabel = when (state.lockTimeoutSeconds) {
        0 -> "立即"
        60 -> "1 分钟"
        else -> "30 秒"
    }
    val clipboardLabel = when (state.clipboardClearSeconds) {
        0 -> "关闭"
        1 -> "立即"
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
                icon = R.drawable.ic_lock,
                title = "每次进入时验证身份",
                checked = state.systemAuthenticationEnabled,
                onCheckedChange = onSystemAuthenticationChange,
                enabled = state.passwordSet,
            )
            Text(
                text = "使用系统允许的指纹、人脸或手机锁屏密码；开启前请先设置恢复口令。",
                style = AppType.caption,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = AppTokens.Spacing.l, vertical = AppTokens.Spacing.s),
            )
            InkDivider()
            SwitchRow(
                icon = R.drawable.ic_fingerprint,
                title = "生物识别解锁",
                checked = state.biometricEnabled,
                onCheckedChange = onBiometricChange,
                enabled = state.passwordSet && !state.systemAuthenticationEnabled,
            )
            if (!state.passwordSet) {
                Text(
                    text = "先设置恢复口令，才能开启生物识别。",
                    style = AppType.caption,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = AppTokens.Spacing.l, vertical = AppTokens.Spacing.s),
                )
            }
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
                value = clipboardLabel,
                onClick = {
                    val next = when (state.clipboardClearSeconds) {
                        30 -> 1
                        1 -> 0
                        else -> 30
                    }
                    onClipboardChange(next)
                },
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
}
