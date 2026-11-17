package io.github.rhczz.inkotp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.rhczz.inkotp.ui.model.SettingsUiState
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import io.github.rhczz.inkotp.ui.screens.ItemDetailScreen
import io.github.rhczz.inkotp.ui.screens.SettingsScreen
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
class M3ScreenInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun itemDetailsNeverExposeTheSeedOrOfferASeedRevealAction() {
        showScreen { detail(item()) }

        compose.onNodeWithText(TEST_SEED, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(TEST_SEED, substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithText("密钥").assertDoesNotExist()
        compose.onNodeWithText("显示").assertDoesNotExist()
    }

    @Test
    fun detailsShowTheActualEightDigitSha256Profile() {
        showScreen { detail(item()) }

        compose.onNodeWithText("1234 5678").assertIsDisplayed()
        compose.onNodeWithText("SHA256").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8 位").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun revealedCodeIsHiddenAgainWhenTheCountdownStartsANewPeriod() {
        val current = mutableStateOf(item().copy(hidden = true, remainingSeconds = 2))
        showScreen { detail(current.value) }
        compose.onNodeWithContentDescription("验证码已隐藏").assertIsDisplayed()

        compose.onNodeWithText("轻触显示本轮验证码").performClick()

        compose.onNodeWithText("1234 5678").assertIsDisplayed()
        compose.onNodeWithContentDescription("验证码已隐藏").assertDoesNotExist()

        // Keep the digits identical: even a rare repeated code must respect the new-period policy.
        compose.runOnIdle { current.value = current.value.copy(remainingSeconds = 30) }

        compose.onNodeWithContentDescription("验证码已隐藏").assertIsDisplayed()
        compose.onNodeWithText("1234 5678").assertDoesNotExist()
    }

    @Test
    fun hotpAccountKeepsItsDetailsButCannotCopyAnUnadvancedCounterCode() {
        var copies = 0
        showScreen {
            ItemDetailScreen(
                item = item().copy(type = "hotp"),
                secretBase32 = TEST_SEED,
                onBack = {},
                onDelete = {},
                onSave = { _, _ -> },
                onCopy = { copies++ },
            )
        }

        compose.onNodeWithText("此版本暂不生成 HOTP 验证码，账号信息会保留。")
            .assertIsDisplayed()
        compose.onNodeWithText("复制验证码").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, copies) }
    }

    @Test
    fun settingsExposeWorkingPasswordBackupAndLockActions() {
        val invoked = mutableListOf<String>()
        showScreen {
            settings(
                state = SettingsUiState(passwordSet = true),
                onPassword = { invoked += "password" },
                onExport = { invoked += "export" },
                onImport = { invoked += "import" },
                onLock = { invoked += "lock" },
            )
        }

        listOf("修改恢复口令", "导出备份", "导入备份", "立即锁定").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().performClick()
        }

        compose.runOnIdle { assertEquals(listOf("password", "export", "import", "lock"), invoked) }
        compose.onNodeWithText("M3").assertDoesNotExist()
    }

    @Test
    fun missingRecoveryPasswordDisablesBiometricsAndExplainsHowToEnableIt() {
        showScreen {
            settings(state = SettingsUiState(passwordSet = false, biometricEnabled = false))
        }

        compose.onAllNodes(isToggleable()).onFirst().assertIsNotEnabled()
        compose.onNodeWithText("先设置恢复口令，才能开启生物识别。").assertIsDisplayed()
        compose.onNodeWithText("设置恢复口令").assertIsDisplayed()
        compose.onNodeWithText("修改恢复口令").assertDoesNotExist()
    }

    @Test
    fun clipboardSettingCyclesThroughTimedImmediateAndDisabledPolicies() {
        val state = mutableStateOf(SettingsUiState(clipboardClearSeconds = 30))
        val selected = mutableListOf<Int>()
        showScreen {
            settings(
                state = state.value,
                onClipboardChange = {
                    selected += it
                    state.value = state.value.copy(clipboardClearSeconds = it)
                },
            )
        }

        repeat(3) {
            compose.onNodeWithText("剪贴板清理").performScrollTo().performClick()
        }

        compose.runOnIdle {
            assertEquals(listOf(1, 0, 30), selected)
            assertEquals(30, state.value.clipboardClearSeconds)
        }
    }

    private fun showScreen(content: @Composable () -> Unit) {
        compose.setContent {
            InkTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize(), content = content)
            }
        }
    }

    @Composable
    private fun detail(value: VaultItemUi) {
        ItemDetailScreen(
            item = value,
            secretBase32 = TEST_SEED,
            onBack = {},
            onDelete = {},
            onSave = { _, _ -> },
        )
    }

    @Composable
    private fun settings(
        state: SettingsUiState,
        onPassword: () -> Unit = {},
        onExport: () -> Unit = {},
        onImport: () -> Unit = {},
        onLock: () -> Unit = {},
        onClipboardChange: (Int) -> Unit = {},
    ) {
        SettingsScreen(
            state = state,
            onBack = {},
            onBiometricChange = {},
            onLockTimeoutChange = {},
            onHideCodesChange = {},
            onClipboardChange = onClipboardChange,
            onDiagnostics = {},
            onAbout = {},
            onOnboarding = {},
            onPassword = onPassword,
            onExport = onExport,
            onImport = onImport,
            onLock = onLock,
        )
    }

    private fun item() = VaultItemUi(
        id = "screen-test-item",
        issuer = "Example",
        accountName = "screen@example.test",
        code = "12345678",
        digits = 8,
        remainingSeconds = 20,
        period = 30,
        algorithm = "SHA256",
    )

    private companion object {
        const val TEST_SEED = "JBSWY3DPEHPK3PXP"
    }
}
