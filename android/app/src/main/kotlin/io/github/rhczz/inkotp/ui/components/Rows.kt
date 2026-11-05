package io.github.rhczz.inkotp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(top = AppTokens.Spacing.xxl, bottom = AppTokens.Spacing.s),
        style = AppType.section,
        color = LocalInkColors.current.ink,
    )
}

@Composable
fun SettingRow(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = LocalInkColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppTokens.Size.settingRowHeight)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = AppTokens.Spacing.l, vertical = AppTokens.Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkIcon(
            icon = icon,
            contentDescription = null,
            size = AppTokens.IconSize.control,
            tint = if (enabled) colors.inkSecondary else colors.inkDisabled,
        )
        Spacer(Modifier.width(AppTokens.Spacing.m))
        Text(
            text = title,
            style = AppType.body,
            color = if (enabled) colors.ink else colors.inkDisabled,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        if (value != null) {
            Spacer(Modifier.width(AppTokens.Spacing.m))
            Text(
                text = value,
                style = AppType.caption,
                color = colors.inkSecondary,
                modifier = Modifier.widthIn(max = AppTokens.Size.settingValueMaxWidth),
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Spacer(Modifier.width(AppTokens.Spacing.s))
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            InkIcon(
                icon = io.github.rhczz.inkotp.R.drawable.ic_chevron_right,
                contentDescription = null,
                size = AppTokens.IconSize.inline,
                tint = colors.inkDisabled,
            )
        }
    }
}

@Composable
fun SwitchRow(
    @DrawableRes icon: Int,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalInkColors.current
    SettingRow(
        icon = icon,
        title = title,
        modifier = modifier,
        enabled = enabled,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.surface,
                    checkedTrackColor = colors.ink,
                    uncheckedThumbColor = colors.surface,
                    uncheckedTrackColor = colors.borderStrong,
                    uncheckedBorderColor = colors.borderStrong,
                ),
            )
        },
    )
}

@Composable
fun InkDivider(modifier: Modifier = Modifier, startInset: Boolean = true) {
    HorizontalDivider(
        modifier = modifier.padding(
            start = if (startInset) AppTokens.Size.settingRowHeight else AppTokens.Spacing.none,
        ),
        thickness = AppTokens.Border.hairline,
        color = LocalInkColors.current.border,
    )
}

@Composable
fun TextActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalInkColors.current.ink,
) {
    Text(
        text = title,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppTokens.Spacing.m),
        style = AppType.body,
        color = color,
    )
}
