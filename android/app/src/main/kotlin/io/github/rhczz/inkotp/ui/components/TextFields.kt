package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    sensitive: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalInkColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, style = AppType.label, color = colors.inkSecondary)
        Spacer(Modifier.height(AppTokens.Spacing.s))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().then(
                if (sensitive) Modifier.semantics { isSensitiveData = true } else Modifier,
            ),
            singleLine = singleLine,
            isError = error != null,
            placeholder = placeholder?.let { { Text(text = it, style = AppType.body, color = colors.inkDisabled) } },
            textStyle = AppType.body.copy(color = colors.ink),
            shape = RoundedCornerShape(AppTokens.Radius.control),
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.borderStrong,
                errorBorderColor = colors.danger,
                focusedContainerColor = colors.surfaceSunken,
                unfocusedContainerColor = colors.surfaceSunken,
                errorContainerColor = colors.surfaceSunken,
                cursorColor = colors.accent,
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
                errorTextColor = colors.ink,
            ),
        )
        if (error != null) {
            Text(
                text = error,
                style = AppType.label,
                color = colors.danger,
                modifier = Modifier.padding(top = AppTokens.Spacing.xs),
            )
        }
    }
}

@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val colors = LocalInkColors.current
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        error = error,
        keyboardType = KeyboardType.Password,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        sensitive = true,
        trailing = {
            InkIconButton(
                icon = if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                contentDescription = if (visible) "隐藏密钥" else "显示密钥",
                onClick = onToggleVisibility,
                tint = colors.inkSecondary,
            )
        },
    )
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClear: () -> Unit = { onValueChange("") },
) {
    val colors = LocalInkColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(AppTokens.Radius.pill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTokens.Size.searchHeight)
            .background(colors.surfaceSunken, shape)
            .border(
                if (focused) AppTokens.Border.focus else AppTokens.Border.hairline,
                if (focused) colors.accent else colors.border,
                shape,
            )
            .padding(horizontal = AppTokens.Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkIcon(
            icon = R.drawable.ic_search,
            contentDescription = null,
            size = AppTokens.IconSize.inline,
            tint = colors.inkSecondary,
        )
        Spacer(Modifier.width(AppTokens.Spacing.s))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(text = "搜索服务或账号", style = AppType.body, color = colors.inkDisabled)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = AppType.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            InkIconButton(
                icon = R.drawable.ic_close,
                contentDescription = "清除搜索",
                onClick = onClear,
                tint = colors.inkSecondary,
            )
        }
    }
}
