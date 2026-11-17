package io.github.rhczz.inkotp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import io.github.rhczz.inkotp.ui.components.VaultCodeCard
import io.github.rhczz.inkotp.ui.components.groupCode
import io.github.rhczz.inkotp.ui.components.hiddenCode
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import io.github.rhczz.inkotp.ui.screens.ItemDetailScreen
import io.github.rhczz.inkotp.ui.theme.AppTokens
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
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class CountdownCodeColorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun listDigitsUseWarningForTheFinalFiveSecondsAndResetInBothThemes() =
        verifyCountdownColors(detail = false)

    @Test
    fun detailDigitsUseWarningForTheFinalFiveSecondsAndResetInBothThemes() =
        verifyCountdownColors(detail = true)

    @Test
    fun hiddenCodesKeepTheExpiryColorWithoutRevealingDigits() {
        val current = mutableStateOf(Case(item = item(8).copy(hidden = true, remainingSeconds = 6)))
        show { current.value }

        listOf(false, true).forEach { detail ->
            compose.runOnIdle { current.value = current.value.copy(detail = detail) }
            assertTextColor(hiddenCode(8), AppTokens.Light.ink)
            compose.runOnIdle { current.value = current.value.copy(item = current.value.item.copy(remainingSeconds = 5)) }
            assertTextColor(hiddenCode(8), AppTokens.Light.warning)
            compose.onNodeWithText("1234 5678").assertDoesNotExist()
            if (detail) {
                compose.onNodeWithText(hiddenCode(8)).performTouchInput { click() }
                assertTextColor(hiddenCode(8), AppTokens.Light.warning)
                compose.onNodeWithText("1234 5678").assertDoesNotExist()
            }
            compose.runOnIdle { current.value = current.value.copy(item = current.value.item.copy(remainingSeconds = 30)) }
            assertTextColor(hiddenCode(8), AppTokens.Light.ink)
            compose.onNodeWithText("1234 5678").assertDoesNotExist()
            compose.runOnIdle { current.value = current.value.copy(item = current.value.item.copy(remainingSeconds = 6)) }
        }
    }

    @Test
    fun hotpPlaceholderDoesNotBecomeAnExpiringCode() {
        val current = mutableStateOf(Case(item = item(6).copy(type = "hotp", code = "—", remainingSeconds = 0)))
        show { current.value }

        listOf(false, true).forEach { dark ->
            compose.runOnIdle { current.value = current.value.copy(dark = dark) }
            assertTextColor("—", if (dark) AppTokens.Dark.ink else AppTokens.Light.ink)
        }
        compose.runOnIdle { current.value = current.value.copy(detail = true) }
        compose.onNodeWithText("—").assertDoesNotExist()
        compose.onNodeWithText("0s").assertDoesNotExist()
        compose.onNodeWithText("此版本暂不生成 HOTP 验证码，账号信息会保留。").assertIsDisplayed()
        compose.onNodeWithText("复制验证码").performScrollTo().assertIsNotEnabled()
    }

    private fun verifyCountdownColors(detail: Boolean) {
        val current = mutableStateOf(Case(item = item(6), detail = detail))
        show { current.value }

        listOf(false, true).forEach { dark ->
            listOf(6, 8).forEach { digits ->
                val code = item(digits)
                val ink = if (dark) AppTokens.Dark.ink else AppTokens.Light.ink
                val warning = if (dark) AppTokens.Dark.warning else AppTokens.Light.warning
                compose.runOnIdle { current.value = Case(item = code, dark = dark, detail = detail) }
                listOf(6, 5, 1, 30).forEach { remaining ->
                    compose.runOnIdle { current.value = current.value.copy(item = code.copy(remainingSeconds = remaining)) }
                    assertTextColor(groupCode(code.code, digits), if (remaining <= 5) warning else ink)
                    compose.onNodeWithText("${remaining}s").assertIsDisplayed()
                    if (remaining <= 5) assertTextColor("${remaining}s", warning)
                }
            }
        }
    }

    private fun assertTextColor(text: String, expected: Color) {
        compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue("Text layout should be available for $text", action(layouts))
            assertEquals("Rendered text color for $text", expected, layouts.single().layoutInput.style.color)
        }
    }

    private fun show(current: () -> Case) {
        compose.setContent {
            val case = current()
            InkTheme(darkTheme = case.dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (case.detail) Detail(case.item)
                    else VaultCodeCard(item = case.item, onClick = {}, onLongClick = {}, onMenu = {})
                }
            }
        }
    }

    @Composable
    private fun Detail(item: VaultItemUi) {
        ItemDetailScreen(item = item, secretBase32 = "", onBack = {}, onDelete = {}, onSave = { _, _ -> })
    }

    private fun item(digits: Int) = VaultItemUi(
        id = "countdown-test", issuer = "Example", accountName = "countdown@example.test",
        code = if (digits == 8) "12345678" else "123456", digits = digits,
        remainingSeconds = 6, period = 30,
    )

    private data class Case(val item: VaultItemUi, val dark: Boolean = false, val detail: Boolean = false)
}
