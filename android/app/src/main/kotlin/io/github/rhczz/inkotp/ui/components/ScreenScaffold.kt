package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalInkColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (onBack != null) AppTokens.Spacing.xs else AppTokens.Spacing.xl,
                    end = AppTokens.Spacing.m,
                    top = AppTokens.Spacing.xl,
                )
                .height(AppTokens.Size.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                InkIconButton(
                    icon = R.drawable.ic_arrow_back,
                    contentDescription = "返回",
                    onClick = onBack,
                    tint = colors.ink,
                )
            }
            Text(text = title, style = AppType.title, color = colors.ink)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .widthIn(max = AppTokens.Size.screenMaxWidth)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = AppTokens.Spacing.xl),
            content = content,
        )
    }
}
