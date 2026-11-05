package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun InkSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInkColors.current
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.padding(AppTokens.Spacing.l),
    ) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(AppTokens.Radius.control),
            containerColor = colors.ink,
            contentColor = colors.surface,
            actionColor = colors.accentSoft,
            dismissActionContentColor = colors.inkSecondary,
        )
    }
}
