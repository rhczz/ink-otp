package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.AppTextField
import io.github.rhczz.inkotp.ui.components.InkIcon
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.InkSecondaryButton
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.components.SecretTextField
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun OnboardingScreen(
    page: Int,
    onNext: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSkipPassword: () -> Unit,
    onSetPassword: (String) -> Unit,
    onEnableBiometric: () -> Unit,
    onFinish: () -> Unit,
    onScan: () -> Unit = onFinish,
    onManual: () -> Unit = onFinish,
    passwordSet: Boolean = true,
    biometricAvailable: Boolean = true,
) {
    val colors = LocalInkColors.current
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    val valid = password.length >= 8 && password == confirm && acknowledged

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = AppTokens.Spacing.xxl, vertical = AppTokens.Spacing.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                InkTextButton(text = "返回", onClick = onBack, color = colors.inkSecondary)
            }
            Spacer(Modifier.weight(1f))
            Text(text = "${page + 1} / 4", style = AppType.caption, color = colors.inkSecondary)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(AppTokens.Spacing.xxxl))
            when (page) {
                0 -> {
                    InkIcon(R.drawable.ic_shield, size = AppTokens.IconSize.state, tint = colors.ink)
                    Spacer(Modifier.height(AppTokens.Spacing.xxl))
                    Text(
                        text = "你的验证码只在这台设备上",
                        style = AppType.title,
                        color = colors.ink,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(AppTokens.Spacing.m))
                    Bullet(text = "不联网，不上传任何数据")
                    Bullet(text = "库文件整库加密，密钥交给系统安全硬件")
                    Bullet(text = "随时可导出加密备份，换机也能恢复")
                }
                1 -> {
                    InkIcon(R.drawable.ic_key, size = AppTokens.IconSize.state, tint = colors.ink)
                    Spacer(Modifier.height(AppTokens.Spacing.xxl))
                    Text(text = "设置恢复口令", style = AppType.title, color = colors.ink)
                    Spacer(Modifier.height(AppTokens.Spacing.s))
                    Text(
                        text = "口令用于备份与换机恢复。忘记将无法恢复。",
                        style = AppType.body,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(AppTokens.Spacing.xxl))
                    SecretTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "恢复口令（至少 8 位）",
                        visible = passwordVisible,
                        onToggleVisibility = { passwordVisible = !passwordVisible },
                    )
                    Spacer(Modifier.height(AppTokens.Spacing.l))
                    SecretTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = "再次输入",
                        visible = confirmVisible,
                        onToggleVisibility = { confirmVisible = !confirmVisible },
                        error = if (confirm.isNotEmpty() && password != confirm) "两次输入不一致" else null,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text("我已了解：遗忘恢复口令将无法恢复备份", style = AppType.caption, color = colors.inkSecondary)
                    }
                }
                2 -> {
                    InkIcon(R.drawable.ic_fingerprint, size = AppTokens.IconSize.state, tint = colors.ink)
                    Spacer(Modifier.height(AppTokens.Spacing.xxl))
                    Text(text = "开启生物识别", style = AppType.title, color = colors.ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(AppTokens.Spacing.s))
                    Text(
                        text = if (!passwordSet) "请先设置恢复口令，才能开启生物识别。" else if (!biometricAvailable) "设备没有可用的强生物识别，可继续使用恢复口令。" else "日常解锁更快。生物识别失败或变更时，仍可用恢复口令。",
                        style = AppType.body,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> {
                    InkIcon(R.drawable.ic_add, size = AppTokens.IconSize.state, tint = colors.ink)
                    Spacer(Modifier.height(AppTokens.Spacing.xxl))
                    Text(text = "添加第一个账号", style = AppType.title, color = colors.ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(AppTokens.Spacing.s))
                    Text(
                        text = "扫描服务提供的二维码，或手动输入 Base32 密钥。",
                        style = AppType.body,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(AppTokens.Spacing.xl))
        when (page) {
            0 -> InkPrimaryButton(text = "继续", onClick = onNext)
            1 -> {
                InkPrimaryButton(text = "设置恢复口令", onClick = { val submitted = password; password = ""; confirm = ""; onSetPassword(submitted) }, enabled = valid)
                Spacer(Modifier.height(AppTokens.Spacing.s))
                InkTextButton(text = "稍后设置（安全性降低）", onClick = onSkipPassword, color = colors.inkSecondary)
            }
            2 -> {
                InkPrimaryButton(text = "开启", onClick = onEnableBiometric, enabled = passwordSet && biometricAvailable)
                Spacer(Modifier.height(AppTokens.Spacing.s))
                InkTextButton(text = "只使用口令", onClick = onNext, color = colors.inkSecondary)
            }
            else -> {
                InkPrimaryButton(text = "扫描二维码", onClick = onScan)
                Spacer(Modifier.height(AppTokens.Spacing.s))
                InkSecondaryButton(text = "手动添加", onClick = onManual)
                Spacer(Modifier.height(AppTokens.Spacing.s))
                InkTextButton(text = "稍后添加", onClick = onFinish, color = colors.inkSecondary)
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val colors = LocalInkColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = AppTokens.Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        InkIcon(R.drawable.ic_check, contentDescription = null, size = AppTokens.IconSize.inline, tint = colors.success)
        Spacer(Modifier.width(AppTokens.Spacing.m))
        Text(text = text, style = AppType.body, color = colors.inkSecondary)
    }
}
