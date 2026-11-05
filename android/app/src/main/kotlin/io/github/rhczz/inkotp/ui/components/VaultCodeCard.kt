package io.github.rhczz.inkotp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultCodeCard(
    item: VaultItemUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    copied: Boolean = false,
) {
    val colors = LocalInkColors.current
    val shape = RoundedCornerShape(AppTokens.Radius.card)
    val background by animateColorAsState(
        targetValue = if (copied) colors.accentSoft else colors.surface,
        animationSpec = tween(AppTokens.Motion.HIGHLIGHT_MS),
        label = "code-card-background",
    )
    val codeColor = if (item.remainingSeconds <= 5) colors.inkSecondary else colors.ink

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(AppTokens.Border.hairline, colors.border, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = AppTokens.Size.codeCardHeight)
            .padding(horizontal = AppTokens.Spacing.l, vertical = AppTokens.Spacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        IssuerAvatar(issuer = item.issuer)
        Spacer(Modifier.width(AppTokens.Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.issuer ?: item.accountName,
                    style = AppType.bodyStrong,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CountdownLabel(remainingSeconds = item.remainingSeconds, period = item.period)
            }
            Text(
                text = item.accountName,
                style = AppType.caption,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(AppTokens.Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodeText(
                    code = item.code,
                    digits = item.digits,
                    hidden = item.hidden,
                    style = AppType.codeList,
                    color = codeColor,
                    modifier = Modifier.weight(1f),
                )
                InkIconButton(
                    icon = R.drawable.ic_more_vert,
                    contentDescription = "更多操作",
                    onClick = onMenu,
                    tint = colors.inkSecondary,
                )
            }
        }
    }
}
