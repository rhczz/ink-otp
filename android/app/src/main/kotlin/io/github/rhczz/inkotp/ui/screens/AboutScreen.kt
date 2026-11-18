package io.github.rhczz.inkotp.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.InkIcon
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.components.SectionHeader
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun AboutScreen(onBack: () -> Unit, onPrivacy: (() -> Unit)? = null) {
    val colors = LocalInkColors.current
    val context = LocalContext.current
    var repositoryOpenFailed by remember { mutableStateOf(false) }
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
                icon = R.drawable.ic_launcher_foreground,
                contentDescription = null,
                size = AppTokens.IconSize.state,
                tint = colors.ink,
                modifier = Modifier,
            )
            Spacer(Modifier.height(AppTokens.Spacing.m))
            Text(text = "InkOTP", style = AppType.title, color = colors.ink)
            Text(text = io.github.rhczz.inkotp.BuildConfig.VERSION_NAME, style = AppType.caption, color = colors.inkSecondary)
            Spacer(Modifier.height(AppTokens.Spacing.l))
            Text(
                text = "离线两步验证器。验证码密钥由应用在本机处理，保存在加密库中。",
                style = AppType.body,
                color = colors.inkSecondary,
            )

            SectionHeader("许可证")
            Text(text = "GPL-3.0", style = AppType.body, color = colors.ink)
            Text(
                text = "源代码与完整许可证文本",
                style = AppType.caption,
                color = colors.inkSecondary,
            )
            InkTextButton(
                text = REPOSITORY_ADDRESS,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://$REPOSITORY_ADDRESS"))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                    repositoryOpenFailed = try {
                        context.startActivity(intent)
                        false
                    } catch (_: ActivityNotFoundException) {
                        true
                    }
                },
            )
            if (repositoryOpenFailed) {
                Text(
                    text = "请使用浏览器访问上述地址。",
                    style = AppType.caption,
                    color = colors.inkSecondary,
                )
            }

            SectionHeader("第三方组件")
            LicenseLine("AndroidX / Jetpack Compose", "Apache-2.0")
            LicenseLine("BouncyCastle", "MIT-style")
            LicenseLine("ZXing", "Apache-2.0")
            LicenseLine("Inter 字体", "SIL Open Font License 1.1")
            LicenseLine("Material Symbols", "Apache-2.0")
            LicenseLine("Robolectric / Roborazzi（仅测试）", "Apache-2.0")

            if (onPrivacy != null) {
                SectionHeader("隐私")
                InkTextButton("查看完整隐私声明", onClick = onPrivacy)
            }
        }
    }
}

private const val REPOSITORY_ADDRESS = "github.com/rhczz/ink-otp"

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
