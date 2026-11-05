package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.InkIcon
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.components.SectionHeader
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val colors = LocalInkColors.current
    ScreenScaffold(title = "关于与开源许可", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppTokens.Size.bottomContentPadding),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(AppTokens.Spacing.xxl))
            InkIcon(
                icon = R.drawable.ic_shield,
                contentDescription = null,
                size = AppTokens.IconSize.state,
                tint = colors.ink,
                modifier = Modifier,
            )
            Spacer(Modifier.height(AppTokens.Spacing.m))
            Text(text = "InkOTP", style = AppType.title, color = colors.ink)
            Text(text = "0.1.0 · M2", style = AppType.caption, color = colors.inkSecondary)
            Spacer(Modifier.height(AppTokens.Spacing.l))
            Text(
                text = "完全离线的两步验证器。不申请网络权限，不收集数据；种子只以密文落盘。",
                style = AppType.body,
                color = colors.inkSecondary,
            )

            SectionHeader("许可证")
            Text(text = "GPL-3.0", style = AppType.body, color = colors.ink)
            Text(
                text = "完整许可证文本随仓库提供。",
                style = AppType.caption,
                color = colors.inkSecondary,
            )

            SectionHeader("第三方组件")
            LicenseLine("AndroidX / Jetpack Compose", "Apache-2.0")
            LicenseLine("BouncyCastle", "MIT-style")
            LicenseLine("ZXing", "Apache-2.0")
            LicenseLine("Inter 字体", "SIL Open Font License 1.1")
            LicenseLine("Material Symbols", "Apache-2.0")
            LicenseLine("Robolectric / Roborazzi（仅测试）", "Apache-2.0")

            SectionHeader("隐私")
            Text(
                text = "无网络权限、无分析 SDK、无崩溃上报。生物识别由 Android 系统完成，App 只能收到成功/失败结果。",
                style = AppType.body,
                color = colors.inkSecondary,
            )
        }
    }
}

@Composable
private fun LicenseLine(name: String, license: String) {
    val colors = LocalInkColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTokens.Spacing.xs),
    ) {
        Text(text = name, style = AppType.body, color = colors.ink)
        Text(text = license, style = AppType.caption, color = colors.inkSecondary)
    }
}
