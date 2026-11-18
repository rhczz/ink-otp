package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.SecretTextField
import io.github.rhczz.inkotp.ui.components.InkIcon
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.model.UnlockUiState
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun UnlockScreen(
    state: UnlockUiState,
    onUnlock: () -> Unit,
    onPasswordSubmit: (String) -> Unit,
    onRestoreBackup: (() -> Unit)? = null,
) {
    val colors = LocalInkColors.current
    var passwordMode by remember(state.biometricAvailable, state.degraded, state.deviceUnlockAvailable, state.systemAuthenticationRequired, state.systemRecoveryRequired) {
        mutableStateOf(state.systemRecoveryRequired ||
            (!state.systemAuthenticationRequired && !state.biometricAvailable && !state.degraded && !state.deviceUnlockAvailable))
    }
    var passwordVisible by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(state.recoveryPasswordRequested) {
        if (state.recoveryPasswordRequested) passwordMode = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = AppTokens.Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.weight(0.7f))
        InkIcon(
            icon = R.drawable.ic_lock,
            contentDescription = null,
            size = AppTokens.IconSize.state,
            tint = colors.inkSecondary,
        )
        Spacer(Modifier.height(AppTokens.Spacing.xxl))
        Text(text = if (state.systemRecoveryRequired) "重新绑定手机验证" else "解锁以查看验证码", style = AppType.section, color = colors.ink)
        Spacer(Modifier.height(AppTokens.Spacing.s))
        Text(
            text = when {
                state.systemRecoveryRequired -> "先输入应用恢复口令，再完成手机系统验证"
                state.systemAuthenticationRequired -> "使用手机的指纹、人脸或锁屏密码验证身份"
                state.degraded -> "设备绑定模式"
                state.biometricAvailable -> "完成一次生物识别以更新本机解锁方式"
                state.deviceUnlockAvailable -> "点击解锁，继续查看验证码"
                else -> "请输入本机恢复口令以修复或更新解锁方式"
            },
            style = AppType.label,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AppTokens.Spacing.xxxl))

        if (passwordMode) {
            SecretTextField(
                value = password,
                onValueChange = { password = it },
                label = if (state.systemAuthenticationRequired) "应用恢复口令（随后需系统验证）" else "恢复口令",
                modifier = Modifier.fillMaxWidth(),
                visible = passwordVisible,
                onToggleVisibility = { passwordVisible = !passwordVisible },
            )
            Spacer(Modifier.height(AppTokens.Spacing.l))
            InkPrimaryButton(text = if (state.systemAuthenticationRequired) "验证并重新绑定" else "解锁", onClick = { val submitted = password; password = ""; onPasswordSubmit(submitted) })
            Spacer(Modifier.height(AppTokens.Spacing.s))
            if (state.biometricAvailable || state.systemAuthenticationRequired) InkTextButton(text = "使用系统验证", onClick = { passwordMode = false; onUnlock() }, color = colors.inkSecondary)
        } else {
            InkPrimaryButton(
                text = if (state.systemAuthenticationRequired || state.biometricAvailable) "使用系统验证" else "解锁",
                onClick = onUnlock,
            )
            Spacer(Modifier.height(AppTokens.Spacing.l))
            if (state.biometricAvailable && !state.systemAuthenticationRequired) {
                InkTextButton(text = "使用恢复口令", onClick = { passwordMode = true }, color = colors.inkSecondary)
            }
        }

        if (onRestoreBackup != null) {
            Spacer(Modifier.height(AppTokens.Spacing.m))
            InkTextButton(text = "从备份恢复", onClick = onRestoreBackup, color = colors.inkSecondary)
        }
        if (state.error != null) {
            Spacer(Modifier.height(AppTokens.Spacing.m))
            Text(text = state.error, style = AppType.label, color = colors.danger, textAlign = TextAlign.Center)
        }
        if (state.degraded) {
            Spacer(Modifier.height(AppTokens.Spacing.xxl))
            Text(
                text = "当前为设备绑定模式：未设置恢复口令，换机或重装将丢失数据。",
                style = AppType.caption,
                color = colors.warning,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
