package io.github.rhczz.inkotp.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.LocalInkColors
import kotlinx.coroutines.delay

/** Blocks immediately, but only slow operations need visible feedback. Never dims the page. */
@Composable
fun OperationBusyOverlay(
    busy: Boolean,
    operationKey: Any?,
    authenticationInProgress: Boolean,
) {
    // Register unconditionally so this handler keeps priority over page navigation.
    BackHandler(enabled = busy) {}
    if (!busy) return

    var progressVisible by remember(operationKey, authenticationInProgress) { mutableStateOf(false) }
    LaunchedEffect(operationKey, authenticationInProgress) {
        if (!authenticationInProgress) {
            delay(300L)
            progressVisible = true
        }
    }
    val colors = LocalInkColors.current
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (progressVisible && !authenticationInProgress) {
            Surface(shape = RoundedCornerShape(AppTokens.Radius.control), color = colors.surface) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(AppTokens.Spacing.l).semantics { contentDescription = "正在处理" },
                    color = colors.accent,
                )
            }
        }
    }
}
