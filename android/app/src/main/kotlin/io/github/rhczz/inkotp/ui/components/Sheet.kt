package io.github.rhczz.inkotp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun SheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = AppTokens.Spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(AppTokens.Size.sheetHandleWidth)
                .height(AppTokens.Size.sheetHandleHeight)
                .clip(RoundedCornerShape(AppTokens.Radius.pill))
                .background(LocalInkColors.current.borderStrong),
        )
    }
}

@Composable
fun SheetContent(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalInkColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppTokens.Spacing.xl, vertical = AppTokens.Spacing.l),
    ) {
        Text(text = title, style = AppType.section, color = colors.ink)
        Spacer(Modifier.height(AppTokens.Spacing.s))
        content()
    }
}

@Composable
fun SheetActionRow(
    @DrawableRes icon: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = LocalInkColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTokens.Size.settingRowHeight)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkIcon(icon = icon, contentDescription = null, tint = colors.ink)
        Spacer(Modifier.width(AppTokens.Spacing.l))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = AppType.body, color = colors.ink)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = AppType.caption,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        InkIcon(
            icon = R.drawable.ic_chevron_right,
            contentDescription = null,
            size = AppTokens.IconSize.inline,
            tint = colors.inkDisabled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalInkColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = AppTokens.Radius.sheet, topEnd = AppTokens.Radius.sheet),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppTokens.Spacing.xl, end = AppTokens.Spacing.xl, bottom = AppTokens.Spacing.xxxl),
            content = content,
        )
    }
}
