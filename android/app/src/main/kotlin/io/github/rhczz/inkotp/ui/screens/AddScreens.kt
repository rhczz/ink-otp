package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.AppTextField
import io.github.rhczz.inkotp.ui.components.InkIcon
import io.github.rhczz.inkotp.ui.components.InkIconButton
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.components.SecretTextField
import io.github.rhczz.inkotp.ui.components.SectionHeader
import io.github.rhczz.inkotp.ui.components.SheetActionRow
import io.github.rhczz.inkotp.ui.components.SheetContent
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun AddSheetContent(
    onScan: () -> Unit,
    onGallery: () -> Unit,
    onManual: () -> Unit,
) {
    SheetContent(title = "添加验证码") {
        SheetActionRow(
            icon = R.drawable.ic_qr_code_scanner,
            title = "扫码添加",
            subtitle = "对准服务提供的二维码",
            onClick = onScan,
        )
        SheetActionRow(
            icon = R.drawable.ic_photo_library,
            title = "从相册导入",
            subtitle = "识别截图中的二维码",
            onClick = onGallery,
        )
        SheetActionRow(
            icon = R.drawable.ic_keyboard,
            title = "手动输入",
            subtitle = "输入 Base32 密钥或 otpauth:// 链接",
            onClick = onManual,
        )
    }
}

@Composable
fun AddSheetPreview() {
    val colors = LocalInkColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            color = colors.surface,
            shape = RoundedCornerShape(topStart = AppTokens.Radius.sheet, topEnd = AppTokens.Radius.sheet),
        ) {
            AddSheetContent(onScan = {}, onGallery = {}, onManual = {})
        }
    }
}

@Composable
fun ManualAddScreen(
    onBack: () -> Unit,
    onSave: (issuer: String, account: String, secret: String) -> Unit,
    initialInput: String = "",
) {
    val colors = LocalInkColors.current
    var issuer by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var secret by remember(initialInput) { mutableStateOf(initialInput) }
    var algorithm by remember { mutableStateOf("SHA1") }
    var digits by remember { mutableStateOf(6) }
    var period by remember { mutableStateOf("30") }
    var secretVisible by remember { mutableStateOf(false) }
    var secretError by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(title = "手动添加", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppTokens.Size.bottomContentPadding),
        ) {
            Spacer(Modifier.height(AppTokens.Spacing.l))
            AppTextField(value = issuer, onValueChange = { issuer = it }, label = "服务名（可选）")
            Spacer(Modifier.height(AppTokens.Spacing.l))
            AppTextField(value = account, onValueChange = { account = it }, label = "账号（链接可自动读取）")
            Spacer(Modifier.height(AppTokens.Spacing.l))
            SecretTextField(
                value = secret,
                onValueChange = {
                    secret = it
                    secretError = null
                },
                label = "密钥（Base32）或 otpauth 链接",
                visible = secretVisible,
                onToggleVisibility = { secretVisible = !secretVisible },
                error = secretError,
            )
            Text(
                text = "支持直接粘贴 otpauth:// 链接；密钥大小写、空格与连字符会被容错处理。",
                style = AppType.caption,
                color = colors.inkSecondary,
                modifier = Modifier.padding(top = AppTokens.Spacing.s),
            )

            SectionHeader("高级")
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = "算法", style = AppType.body, color = colors.inkSecondary, modifier = Modifier.weight(1f))
                InkTextButton(text = algorithm, onClick = { algorithm = when (algorithm) { "SHA1" -> "SHA256"; "SHA256" -> "SHA512"; else -> "SHA1" } })
            }
            Spacer(Modifier.height(AppTokens.Spacing.m))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = "位数", style = AppType.body, color = colors.inkSecondary, modifier = Modifier.weight(1f))
                InkTextButton(text = "$digits 位", onClick = { digits = if (digits == 6) 8 else 6 })
            }
            Spacer(Modifier.height(AppTokens.Spacing.m))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = "周期", style = AppType.body, color = colors.inkSecondary, modifier = Modifier.weight(1f))
                AppTextField(value = period, onValueChange = { period = it.filter(Char::isDigit).take(3) }, label = "秒（1–300）", modifier = Modifier.weight(1f), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            }

            Spacer(Modifier.height(AppTokens.Spacing.xxxl))
            InkPrimaryButton(
                text = "保存",
                onClick = {
                    if (secret.isBlank()) {
                        secretError = "请输入密钥"
                    } else {
                        val submitted = if (secret.contains("://")) secret else {
                            val p = period.toIntOrNull()
                            if (p == null || p !in 1..300) { secretError = "周期需要在 1 到 300 秒之间"; return@InkPrimaryButton }
                            if (account.isBlank()) { secretError = "请输入账号名称"; return@InkPrimaryButton }
                            "otpauth://totp/${android.net.Uri.encode(account)}?secret=${android.net.Uri.encode(secret)}&algorithm=$algorithm&digits=$digits&period=$p"
                        }
                        onSave(issuer, account, submitted)
                    }
                },
            )
            Spacer(Modifier.height(AppTokens.Spacing.s))
            InkTextButton(text = "取消", onClick = onBack, color = colors.inkSecondary)
        }
    }
}

@Composable
fun ScanPlaceholderScreen(
    onBack: () -> Unit,
    onManual: () -> Unit,
) {
    val background = AppTokens.Light.ink
    val foreground = AppTokens.Light.surface
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .safeDrawingPadding()
            .padding(AppTokens.Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InkIconButton(
                icon = R.drawable.ic_arrow_back,
                contentDescription = "返回",
                onClick = onBack,
                tint = foreground,
            )
            Spacer(Modifier.weight(1f))
            Text(text = "扫码添加", style = AppType.bodyStrong, color = foreground)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(AppTokens.Size.touchTarget))
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(AppTokens.Size.scanFrame)
                .border(
                    AppTokens.Border.focus,
                    foreground,
                    RoundedCornerShape(AppTokens.Radius.card),
                ),
            contentAlignment = Alignment.Center,
        ) {
            InkIcon(
                icon = R.drawable.ic_qr_code_scanner,
                contentDescription = null,
                size = AppTokens.IconSize.state,
                tint = foreground,
            )
        }
        Spacer(Modifier.height(AppTokens.Spacing.xxl))
        Text(
            text = "相机扫码在 M3 接入",
            style = AppType.body,
            color = foreground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "当前可先使用手动输入或相册导入。",
            style = AppType.caption,
            color = foreground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onManual,
            modifier = Modifier.fillMaxWidth().height(AppTokens.Size.buttonHeight),
            shape = RoundedCornerShape(AppTokens.Radius.control),
            colors = ButtonDefaults.buttonColors(containerColor = foreground, contentColor = background),
        ) {
            Text(text = "手动输入", style = AppType.button)
        }
    }
}
