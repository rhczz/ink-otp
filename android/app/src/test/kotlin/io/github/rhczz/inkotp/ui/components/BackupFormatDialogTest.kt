package io.github.rhczz.inkotp.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.rhczz.inkotp.core.interchange.InterchangeFormat
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupFormatDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allSixFormatsAreReachableAndAllThreePlaintextOptionsWarnAboutSecrets() {
        showDialog()

        InterchangeFormat.entries.forEach { format ->
            compose.onNodeWithText(format.displayName()).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithText("含明文账号密钥，仅用于主动迁移").assertCountEquals(3)
        compose.onNodeWithText("用于兼容旧版 InkOTP，不是通用格式").assertExists()
    }

    @Test fun confirmingWithoutChangingSelectionUsesEncryptedAegisByDefault() {
        var chosen: InterchangeFormat? = null
        showDialog(onConfirm = { chosen = it })

        compose.onNodeWithText("下一步").performClick()

        compose.runOnIdle { assertEquals(InterchangeFormat.AEGIS_ENCRYPTED, chosen) }
    }

    @Test fun selectingMigrationTextReportsTheExplicitlyChosenPlaintextFormat() {
        var chosen: InterchangeFormat? = null
        showDialog(onConfirm = { chosen = it })

        compose.onNodeWithText("otpauth 迁移文本").performScrollTo().performClick()
        compose.onNodeWithText("下一步").performClick()

        compose.runOnIdle {
            assertEquals(InterchangeFormat.OTPAUTH_TEXT, chosen)
            assertTrue(chosen!!.plaintext)
        }
    }

    @Test fun cancelDismissesWithoutStartingAnyExport() {
        var dismissed = 0
        var exports = 0
        showDialog(onConfirm = { exports++ }, onDismiss = { dismissed++ })

        compose.onNodeWithText("取消").performClick()

        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, exports)
        }
    }

    private fun showDialog(onConfirm: (InterchangeFormat) -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent { InkTheme(darkTheme = false) { BackupFormatDialog(onConfirm, onDismiss) } }
    }
}
