package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.InkCard
import io.github.rhczz.inkotp.ui.components.InkIcon
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.components.SectionHeader
import io.github.rhczz.inkotp.ui.model.DiagnosticsUiState
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onRecalculate: () -> Unit,
    onRefreshResources: () -> Unit,
) {
    val colors = LocalInkColors.current
    ScreenScaffold(title = "诊断", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppTokens.Size.bottomContentPadding),
        ) {
            Spacer(Modifier.height(AppTokens.Spacing.l))
            InkCard {
                Text(text = "当前设备时间", style = AppType.caption, color = colors.inkSecondary)
                Text(
                    text = state.deviceTime,
                    style = AppType.codeList,
                    color = colors.ink,
                    modifier = Modifier.padding(top = AppTokens.Spacing.xs),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = AppTokens.Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InkIcon(
                        icon = if (state.autoTime) R.drawable.ic_check_circle else R.drawable.ic_warning,
                        contentDescription = null,
                        size = AppTokens.IconSize.inline,
                        tint = if (state.autoTime) colors.success else colors.warning,
                    )
                    Spacer(Modifier.width(AppTokens.Spacing.xs))
                    Text(
                        text = if (state.autoTime) "自动设置时间：已开启" else "自动设置时间：未开启",
                        style = AppType.label,
                        color = colors.inkSecondary,
                    )
                }
            }

            if (state.driftDetected) {
                Spacer(Modifier.height(AppTokens.Spacing.m))
                InkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InkIcon(
                            icon = R.drawable.ic_warning,
                            contentDescription = null,
                            size = AppTokens.IconSize.control,
                            tint = colors.warning,
                        )
                        Spacer(Modifier.width(AppTokens.Spacing.s))
                        Text(
                            text = "检测到设备时间被修改。请确认时间正确后重新计算。",
                            style = AppType.label,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }

            SectionHeader("验证码对不上？")
            Text(
                text = "按顺序检查以下项目",
                style = AppType.caption,
                color = colors.inkSecondary,
            )
            ChecklistItem("设备时间是否准确（含时区）", state.autoTime)
            ChecklistItem("是否选对了账号/服务", true)
            ChecklistItem("服务是否要求使用备用码而非 TOTP", true)
            ChecklistItem("密钥是否手动输入错误", true)

            Spacer(Modifier.height(AppTokens.Spacing.xxl))
            InkPrimaryButton(text = "重新计算全部验证码", onClick = onRecalculate)

            SectionHeader("性能与资源")
            Text(
                text = "仅在你点击刷新时采样，不在后台监控。PSS、堆和文件大小是不同统计口径，不能直接相加。",
                style = AppType.caption,
                color = colors.inkSecondary,
            )
            state.resources.forEach { (label, value) ->
                Spacer(Modifier.height(AppTokens.Spacing.m))
                Text(label, style = AppType.caption, color = colors.inkSecondary)
                Text(value, style = AppType.body, color = colors.ink)
            }
            Spacer(Modifier.height(AppTokens.Spacing.l))
            InkPrimaryButton(
                text = if (state.resourceSampling) "正在采样…" else "刷新资源统计",
                onClick = onRefreshResources,
                enabled = !state.resourceSampling,
            )
        }
    }
}

@Composable
private fun ChecklistItem(text: String, ok: Boolean) {
    val colors = LocalInkColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppTokens.Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkIcon(
            icon = if (ok) R.drawable.ic_check_circle else R.drawable.ic_help,
            contentDescription = null,
            size = AppTokens.IconSize.inline,
            tint = if (ok) colors.success else colors.inkSecondary,
        )
        Spacer(Modifier.width(AppTokens.Spacing.m))
        Text(text = text, style = AppType.body, color = colors.ink)
    }
}
