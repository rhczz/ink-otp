package io.github.rhczz.inkotp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.rhczz.inkotp.ui.model.SettingsUiState
import io.github.rhczz.inkotp.ui.model.UnlockUiState
import io.github.rhczz.inkotp.ui.screens.SettingsScreen
import io.github.rhczz.inkotp.ui.screens.UnlockScreen
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
class SystemAuthenticationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun systemAuthenticationStartsFromTheSystemButtonWithoutCollectingAPhonePassword() {
        var unlocks = 0
        val appPasswords = mutableListOf<String>()
        showScreen {
            UnlockScreen(
                state = UnlockUiState(systemAuthenticationRequired = true, biometricAvailable = false),
                onUnlock = { unlocks++ },
                onPasswordSubmit = { appPasswords += it },
            )
        }

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("请输入手机密码", substring = true).assertDoesNotExist()
        compose.onNodeWithText("使用系统验证").assertIsDisplayed().performClick()
        compose.onNodeWithText("轻触进行系统验证").assertDoesNotExist()

        compose.runOnIdle {
            assertEquals(1, unlocks)
            assertTrue(appPasswords.isEmpty())
        }
    }

    @Test fun recoveryStateImmediatelyOffersTheAppRecoveryPhraseAndClearsItAfterSubmission() {
        val state = mutableStateOf(UnlockUiState(systemAuthenticationRequired = true, biometricAvailable = false))
        val submitted = mutableListOf<String>()
        showScreen {
            UnlockScreen(state = state.value, onUnlock = {}, onPasswordSubmit = { submitted += it })
        }

        compose.runOnIdle { state.value = state.value.copy(systemRecoveryRequired = true) }

        compose.onNodeWithText("应用恢复口令（随后需系统验证）").assertIsDisplayed()
        compose.onNodeWithText("验证并重新绑定").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("a test application recovery phrase")
        compose.onNodeWithText("验证并重新绑定").performClick()

        compose.runOnIdle { assertEquals(listOf("a test application recovery phrase"), submitted) }
        compose.onNode(hasSetTextAction()).assertTextEquals("")
    }

    @Test fun negativeSystemButtonShowsTheRecoveryFormAndRetryCallsTheSystemImmediately() {
        val state = mutableStateOf(UnlockUiState(biometricAvailable = true))
        var unlocks = 0
        showScreen {
            UnlockScreen(state = state.value, onUnlock = { unlocks++ }, onPasswordSubmit = {})
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        compose.runOnIdle { state.value = state.value.copy(recoveryPasswordRequested = true) }

        compose.onNodeWithText("恢复口令", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("recovery phrase stays visible")
        compose.runOnIdle { state.value = state.value.copy(recoveryPasswordRequested = false) }
        compose.onNodeWithContentDescription("显示密钥").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("recovery phrase stays visible")
        compose.onNodeWithText("使用系统验证").performClick()

        compose.runOnIdle { assertEquals(1, unlocks) }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun settingsRequireARecoveryPhraseAndSystemPolicyPreventsChangingTheOldLockGrace() {
        val state = mutableStateOf(SettingsUiState(passwordSet = false, biometricEnabled = false, lockTimeoutSeconds = 60))
        val selected = mutableListOf<Boolean>()
        val lockChanges = mutableListOf<Int>()
        showScreen {
            SettingsScreen(
                state = state.value,
                onBack = {},
                onBiometricChange = {},
                onLockTimeoutChange = { lockChanges += it },
                onHideCodesChange = {},
                onClipboardChange = {},
                onDiagnostics = {},
                onAbout = {},
                onOnboarding = {},
                onSystemAuthenticationChange = {
                    selected += it
                    state.value = state.value.copy(systemAuthenticationEnabled = it)
                },
            )
        }

        compose.onAllNodes(isToggleable()).onFirst().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(passwordSet = true) }
        compose.onAllNodes(isToggleable()).onFirst().assertIsEnabled().performClick()

        compose.onNodeWithText("每次进入验证").assertIsDisplayed()
        compose.onNodeWithText("自动锁定").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(listOf(true), selected)
            assertTrue(lockChanges.isEmpty())
            assertEquals(60, state.value.lockTimeoutSeconds)
        }
    }

    private fun showScreen(content: @Composable () -> Unit) {
        compose.setContent {
            InkTheme(darkTheme = false) { Surface(modifier = Modifier.fillMaxSize(), content = content) }
        }
    }
}
