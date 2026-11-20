package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.BarcodeFormat
import io.github.rhczz.inkotp.app.GoogleExportReview
import io.github.rhczz.inkotp.core.uri.GoogleMigrationBatch
import io.github.rhczz.inkotp.core.uri.GoogleMigrationCodec
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.InkSecondaryButton
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
internal fun GoogleImportScreen(progress: GoogleMigrationBatch.Progress?, busy: Boolean, onBack: () -> Unit,
    onScan: () -> Unit, onGallery: () -> Unit, onPaste: () -> Unit,
) {
    ScreenScaffold(title = "Google Authenticator 导入", onBack = onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = AppTokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.m),
        ) {
            Text(
                progress?.let { "已收到 ${it.received} / ${it.total} 张二维码。请继续添加当前批次。" }
                    ?: "使用 Google Authenticator 的「转移验证码」导出。收齐二维码后可以核对并合并账号。",
                style = AppType.body,
            )
            InkPrimaryButton("扫描二维码", onScan, enabled = !busy)
            InkSecondaryButton("从图片读取", onGallery, enabled = !busy)
            InkTextButton("粘贴迁移链接", onPaste, enabled = !busy)
            InkTextButton("取消迁移", onBack, enabled = !busy)
        }
    }
}

@Composable
internal fun GoogleExportReviewDialog(review: GoogleExportReview, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val excluded = review.total - review.supported
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(AppTokens.Radius.sheet),
        containerColor = LocalInkColors.current.surface,
        title = { Text("迁移到 Google Authenticator", style = AppType.section) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.s)) {
                Text("二维码会明文展示账号密钥。请只在可信设备之间面对面扫码。", style = AppType.body)
                Text("兼容账号 ${review.supported} 个，共 ${review.total} 个。", style = AppType.body)
                if (excluded > 0) {
                    Text("以下账号无法无损迁移，可改用 Aegis 加密备份。", style = AppType.caption)
                    review.issues.forEach { Text(it, style = AppType.caption) }
                    if (excluded > review.issues.size) Text("另有 ${excluded - review.issues.size} 个账号不兼容。", style = AppType.caption)
                }
                if (review.supported > GoogleMigrationCodec.MAX_ENTRIES) {
                    Text("兼容账号超过 500 个，请使用 Aegis 加密备份。", style = AppType.caption)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = review.supported in 1..GoogleMigrationCodec.MAX_ENTRIES,
                onClick = onContinue,
            ) { Text(if (excluded > 0) "仅迁移兼容账号" else "继续验证身份") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun GoogleExportQrDialog(pageCount: Int, pageAt: (Int) -> ByteArray?, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val matrix = remember(index, pageCount) {
        pageAt(index)?.let { uri ->
            try {
                QRCodeWriter().encode(uri.decodeToString(), BarcodeFormat.QR_CODE, 1, 1,
                    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
            } catch (_: Exception) { null }
            finally { uri.fill(0) }
        }
    }
    DisposableEffect(matrix) { onDispose { matrix?.clear() } }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(AppTokens.Radius.sheet),
        containerColor = LocalInkColors.current.surface,
        title = { Text("迁移二维码 ${index + 1} / $pageCount", style = AppType.section) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.m),
            ) {
                Text("在目标设备选择导入验证码，按页扫描。", style = AppType.body)
                if (matrix == null) {
                    Text("这页二维码无法显示，请关闭后重试。", style = AppType.caption)
                } else {
                    Canvas(
                        Modifier.size(AppTokens.Size.scanFrame).background(Color.White)
                            .semantics { contentDescription = "Google Authenticator 迁移二维码，第 ${index + 1} 页" },
                    ) {
                        val module = (size.minDimension / matrix.width).toInt().coerceAtLeast(1).toFloat()
                        val left = (size.width - module * matrix.width) / 2
                        val top = (size.height - module * matrix.height) / 2
                        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                            if (matrix.get(x, y)) drawRect(Color.Black, Offset(left + x * module, top + y * module), Size(module, module))
                        }
                    }
                }
                if (pageCount > 1) Row(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.m)) {
                    InkTextButton("上一页", { index-- }, enabled = index > 0)
                    InkTextButton("下一页", { index++ }, enabled = index + 1 < pageCount)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}
