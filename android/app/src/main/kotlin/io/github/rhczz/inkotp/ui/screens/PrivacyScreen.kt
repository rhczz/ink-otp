package io.github.rhczz.inkotp.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rhczz.inkotp.platform.PrivacyPolicy
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.InkSecondaryButton
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors
import java.io.ByteArrayInputStream

@Composable
internal fun PrivacyScreen(policy: PrivacyPolicy, onBack: () -> Unit) {
    val colors = LocalInkColors.current
    var linkError by remember { mutableStateOf(false) }
    ScreenScaffold(title = "隐私声明", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            if (linkError) {
                Text(
                    "此设备没有可打开链接的应用。联系方式仍可在声明正文中查看。",
                    style = AppType.body,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(vertical = AppTokens.Spacing.s),
                )
            }
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        webViewClient = PrivacyWebClient { uri ->
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
                        loadDataWithBaseURL(POLICY_PAGE, policy.document, "text/html", "UTF-8", null)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

private const val POLICY_PAGE = "https://appassets.androidplatform.net/privacy/"

private class PrivacyWebClient(private val openExternal: (Uri) -> Unit) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.fragment != null && uri.buildUpon().fragment(null).build().toString() == POLICY_PAGE) return false
        if (request.isForMainFrame && request.hasGesture() &&
            ((uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.host != "appassets.androidplatform.net") ||
                (uri.scheme == "mailto" && !uri.schemeSpecificPart.isNullOrBlank()))
        ) {
            openExternal(uri)
        }
        return true
    }
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
