package io.github.rhczz.inkotp.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h568dp-xxhdpi")
class ImportReviewDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactWindowWithLargeTextKeepsActionsSeparatedAndReplacementReachable() {
        var mergePolicy: MergeConflictPolicy? = null
        var replacementRequests = 0
        var dismissals = 0
        showReview(
            onImport = { mergePolicy = it },
            onReplace = { replacementRequests++ },
            onDismiss = { dismissals++ },
        )

        val merge = compose.onNodeWithText("合并导入").assertIsDisplayed()
        val cancel = compose.onNodeWithText("取消").assertIsDisplayed()
        val mergeBounds = merge.fetchSemanticsNode().boundsInRoot
        val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
        val gap = maxOf(
            mergeBounds.left - cancelBounds.right, cancelBounds.left - mergeBounds.right,
            mergeBounds.top - cancelBounds.bottom, cancelBounds.top - mergeBounds.bottom,
        )
        assertTrue("Merge and cancel must have a visible gap", gap >= with(compose.density) { 4.dp.toPx() })

        compose.onNodeWithText("替换本机全部账号…").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, replacementRequests)
            assertEquals(null, mergePolicy)
        }
        merge.performClick()
        compose.runOnIdle { assertEquals(MergeConflictPolicy.KEEP_LOCAL, mergePolicy) }
        cancel.performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun committingDisablesTheFooterActions() {
        showReview(busy = true)
        compose.onNodeWithText("合并导入").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w568dp-h320dp-land-xxhdpi")
    fun landscapeWithLargeTextKeepsTheFooterAndScrollsToReplacement() {
        showReview(darkTheme = true)
        compose.onNodeWithText("合并导入").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("替换本机全部账号…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("合并导入").assertIsDisplayed()
    }

    private fun showReview(
        busy: Boolean = false,
        darkTheme: Boolean = false,
        onImport: (MergeConflictPolicy) -> Unit = {},
        onReplace: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                InkTheme(darkTheme = darkTheme) {
                    ImportReviewDialog(
                        accounts = (1..18).map { "测试账号 $it · alice@example.test" },
                        conflicts = 2,
                        source = "Aegis 加密备份",
                        warnings = listOf("导入会保留验证码参数。此文件中的标签和外观设置可能无法恢复。"),
                        busy = busy,
                        onImport = onImport,
                        onReplace = onReplace,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}
