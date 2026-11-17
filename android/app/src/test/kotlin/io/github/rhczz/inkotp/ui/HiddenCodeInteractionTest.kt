package io.github.rhczz.inkotp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.rhczz.inkotp.ui.components.groupCode
import io.github.rhczz.inkotp.ui.components.hiddenCode
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import io.github.rhczz.inkotp.ui.model.VaultListUiState
import io.github.rhczz.inkotp.ui.screens.ItemDetailScreen
import io.github.rhczz.inkotp.ui.screens.VaultListScreen
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HiddenCodeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val current = mutableStateOf(Case(item()))
    private val copiedCodes = mutableListOf<String>()
    private var detailCopies = 0

    @Test fun everyHiddenListTapCopiesTheRealCodeWithoutShowingOrSpeakingIt() {
        show()
        listOf(false, true).forEach { dark ->
            listOf(6, 8).forEach { digits ->
                val account = item(digits)
                compose.runOnIdle { current.value = Case(account, dark = dark); copiedCodes.clear() }
                assertHidden(account)
                repeat(2) { index ->
                    compose.onNodeWithContentDescription("验证码已隐藏").performClick()
                    compose.runOnIdle { assertEquals(List(index + 1) { account.code }, copiedCodes) }
                    assertHidden(account)
                }
            }
        }
    }

    @Test fun detailTouchesCopyAndReturningToTheListKeepCodesHidden() {
        val account = item(8)
        current.value = Case(account, detail = true)
        show()
        assertHidden(account)
        compose.onNodeWithContentDescription("验证码已隐藏").performTouchInput { click() }
        assertHidden(account)
        compose.runOnIdle { assertEquals(0, detailCopies) }

        repeat(2) { index ->
            compose.onNodeWithText("复制验证码").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(index + 1, detailCopies) }
            assertHidden(account)
        }
        compose.onNodeWithContentDescription("返回").performClick()
        assertHidden(account)
        compose.onNodeWithContentDescription("更多操作").performClick()
        assertHidden(account)
        compose.onNodeWithText("轻触显示本轮验证码").assertDoesNotExist()
        compose.onNodeWithText("本轮验证码已显示，更新后自动隐藏").assertDoesNotExist()
    }

    @Test fun countdownAndSettingChangesNeverInheritATemporaryReveal() {
        val account = item()
        current.value = Case(account.copy(hidden = false))
        show()
        compose.onNodeWithText(groupCode(account.code, account.digits)).performClick()
        compose.runOnIdle { current.value = current.value.copy(item = account) }
        assertHidden(account)

        listOf(6, 5, 1, 30).forEach { remaining ->
            compose.runOnIdle { current.value = current.value.copy(item = account.copy(remainingSeconds = remaining)) }
            compose.onNodeWithContentDescription("验证码已隐藏").performClick()
            assertHidden(account)
        }
        val next = account.copy(code = "654321", remainingSeconds = 29)
        compose.runOnIdle { current.value = current.value.copy(item = next) }
        compose.onNodeWithContentDescription("验证码已隐藏").performClick()
        assertHidden(next)
        compose.runOnIdle { assertEquals(listOf(account.code, account.code, account.code, account.code, account.code, next.code), copiedCodes) }

        compose.runOnIdle { current.value = current.value.copy(item = next.copy(hidden = false)) }
        compose.onNodeWithText(groupCode(next.code, next.digits)).assertIsDisplayed()
        compose.onNodeWithContentDescription(next.code.toCharArray().joinToString(" ")).assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(item = next) }
        assertHidden(next)
    }

    @Test fun hotpTapsCannotCopyAPlaceholder() {
        current.value = Case(item().copy(type = "hotp", code = "—", remainingSeconds = 0))
        show()
        compose.onNodeWithContentDescription("验证码已隐藏").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), copiedCodes) }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("复制验证码").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, detailCopies) }
    }

    private fun assertHidden(account: VaultItemUi) {
        listOf(false, true).forEach { unmerged ->
            compose.onNodeWithText(hiddenCode(account.digits), useUnmergedTree = unmerged).assertExists()
            compose.onNodeWithContentDescription("验证码已隐藏", useUnmergedTree = unmerged).assertExists()
            compose.onNodeWithText(account.code, substring = true, useUnmergedTree = unmerged).assertDoesNotExist()
            compose.onNodeWithText(groupCode(account.code, account.digits), substring = true, useUnmergedTree = unmerged).assertDoesNotExist()
            compose.onNodeWithContentDescription(account.code.toCharArray().joinToString(" "), substring = true, useUnmergedTree = unmerged).assertDoesNotExist()
        }
    }

    private fun show() {
        compose.setContent {
            val state = current.value
            InkTheme(darkTheme = state.dark) {
                Surface(Modifier.fillMaxSize()) {
                    if (state.detail) ItemDetailScreen(
                        item = state.item, secretBase32 = "",
                        onBack = { current.value = current.value.copy(detail = false) },
                        onDelete = {}, onSave = { _, _ -> }, onCopy = { detailCopies++ },
                    ) else VaultListScreen(
                        state = VaultListUiState(items = listOf(state.item), backupReminder = false),
                        onQueryChange = {}, onAdd = {}, onSettings = {}, onOpenDiagnostics = {},
                        onOpenItem = { current.value = current.value.copy(detail = true) },
                        onCopy = { copiedCodes += it.code },
                    )
                }
            }
        }
    }

    private fun item(digits: Int = 6) = VaultItemUi(
        id = "hidden-copy-$digits", issuer = "Example", accountName = "hidden@example.test",
        code = if (digits == 8) "12345678" else "123456", digits = digits, hidden = true,
    )

    private data class Case(val item: VaultItemUi, val dark: Boolean = false, val detail: Boolean = false)
}
