package io.github.rhczz.inkotp.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import io.github.rhczz.inkotp.platform.PrivacyBlock
import io.github.rhczz.inkotp.platform.PrivacyBlockKind
import io.github.rhczz.inkotp.platform.PrivacyPolicy
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.InkSecondaryButton
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
internal fun PrivacyScreen(policy: PrivacyPolicy, onBack: () -> Unit) {
    val colors = LocalInkColors.current
    val context = LocalContext.current
    val title = policy.blocks.first { it.kind == PrivacyBlockKind.TITLE }.text
    val body = remember(policy) { policy.blocks.filterNot { it.kind == PrivacyBlockKind.TITLE } }
    var linkError by remember { mutableStateOf(false) }

    ScreenScaffold(title = title, onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            if (linkError) {
                Text(
                    "此设备没有可打开链接的应用。联系方式仍可在声明正文中查看。",
                    style = AppType.body,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(vertical = AppTokens.Spacing.s),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = AppTokens.Spacing.s, bottom = AppTokens.Spacing.xl),
            ) {
                items(body) { block ->
                    PrivacyParagraph(block) { url ->
                        val uri = Uri.parse(url)
                        val intent = if (uri.scheme == "mailto") Intent(Intent.ACTION_SENDTO, uri)
                        else Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                        linkError = try {
                            context.startActivity(intent)
                            false
                        } catch (_: ActivityNotFoundException) {
                            true
                        } catch (_: SecurityException) {
                            true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyParagraph(block: PrivacyBlock, onLink: (String) -> Unit) {
    val colors = LocalInkColors.current
    val kind = block.kind
    val style = when (kind) {
        PrivacyBlockKind.HEADING -> AppType.section
        PrivacyBlockKind.TERM -> AppType.bodyStrong
        PrivacyBlockKind.META -> AppType.caption
        else -> AppType.body
    }
    val top = when (kind) {
        PrivacyBlockKind.HEADING -> AppTokens.Spacing.xl
        PrivacyBlockKind.DETAIL -> AppTokens.Spacing.xs
        else -> AppTokens.Spacing.m
    }
    val color = if (kind == PrivacyBlockKind.META || kind == PrivacyBlockKind.DETAIL) colors.inkSecondary else colors.ink
    val modifier = Modifier.padding(top = top)
    val text = buildAnnotatedString {
        if (kind == PrivacyBlockKind.BULLET) append("• ")
        val offset = length
        append(block.text)
        val linkStyles = TextLinkStyles(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline))
        for (link in block.links) {
            addLink(LinkAnnotation.Url(link.url, linkStyles) { onLink(link.url) }, offset + link.start, offset + link.end)
        }
    }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = if (kind == PrivacyBlockKind.HEADING) modifier.semantics { heading() } else modifier,
    )
}

@Composable
internal fun PrivacyNoticeScreen(
    declined: Boolean,
    saveFailed: Boolean,
    onRead: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onReconsider: () -> Unit,
) {
    val colors = LocalInkColors.current
    ScreenScaffold(title = "InkOTP 隐私说明") {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(vertical = AppTokens.Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.l),
        ) {
            Text(
                "验证码账号和密钥在本机处理并加密保存。InkOTP 无需联网。相机只用于你选择的扫码功能，系统会单独申请权限。",
                style = AppType.body,
                color = colors.ink,
            )
            InkTextButton("查看完整隐私声明", onClick = onRead)
            if (declined) {
                Text(
                    "你可以退出，或随时重新选择。在同意前，InkOTP 不会打开或创建加密库。",
                    style = AppType.body,
                    color = colors.inkSecondary,
                )
                InkPrimaryButton("重新选择", onClick = onReconsider)
            } else {
                Text(
                    "请阅读完整隐私声明，再决定是否同意 InkOTP 在本机处理验证码数据。",
                    style = AppType.body,
                    color = colors.inkSecondary,
                )
                InkPrimaryButton("同意并继续", onClick = onAccept)
                InkSecondaryButton("不同意", onClick = onDecline)
            }
            if (saveFailed) {
                Text("无法保存隐私选择，请稍后重试。", style = AppType.body, color = colors.ink)
            }
        }
    }
}
