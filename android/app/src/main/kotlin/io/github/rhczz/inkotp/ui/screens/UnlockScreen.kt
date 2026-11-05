package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.AppTextField
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
) {
    val colors = LocalInkColors.current
    var passwordMode by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

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
        Text(text = "解锁以查看验证码", style = AppType.section, color = colors.ink)
        Spacer(Modifier.height(AppTokens.Spacing.s))
        Text(
            text = "生物识别不会上传任何数据",
            style = AppType.label,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AppTokens.Spacing.xxxl))

        if (passwordMode) {
            AppTextField(
                value = password,
                onValueChange = { password = it },
                label = "恢复口令",
                modifier = Modifier.fillMaxWidth(),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            )
            Spacer(Modifier.height(AppTokens.Spacing.l))
            InkPrimaryButton(text = "解锁", onClick = { onPasswordSubmit(password) })
            Spacer(Modifier.height(AppTokens.Spacing.s))
            InkTextButton(text = "改用生物识别", onClick = { passwordMode = false }, color = colors.inkSecondary)
        } else {
            Box(
                modifier = Modifier
                    .size(AppTokens.Size.unlockButton)
                    .clip(CircleShape)
                    .border(AppTokens.Border.focus, colors.ink, CircleShape)
                    .clickable(onClick = onUnlock),
                contentAlignment = Alignment.Center,
            ) {
                InkIcon(
                    icon = R.drawable.ic_fingerprint,
                    contentDescription = "解锁，需要生物识别",
                    size = AppTokens.IconSize.primary,
                    tint = colors.ink,
                )
            }
            Spacer(Modifier.height(AppTokens.Spacing.m))
            Text(text = "轻触解锁", style = AppType.label, color = colors.inkSecondary)
            Spacer(Modifier.height(AppTokens.Spacing.l))
            InkTextButton(text = "使用恢复口令", onClick = { passwordMode = true })
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
