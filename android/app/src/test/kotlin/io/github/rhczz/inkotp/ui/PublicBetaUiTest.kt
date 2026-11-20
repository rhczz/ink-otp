package io.github.rhczz.inkotp.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.ui.model.DiagnosticsUiState
import io.github.rhczz.inkotp.ui.screens.AddSheetContent
import io.github.rhczz.inkotp.ui.screens.DiagnosticsScreen
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class PublicBetaUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun diagnosticsOpensAndroidDateAndTimeSettings() {
        compose.setContent {
            InkTheme(darkTheme = false) {
                DiagnosticsScreen(
                    state = DiagnosticsUiState(autoTime = true),
                    onBack = {},
                    onRecalculate = {},
                    onRefreshResources = {},
                )
            }
        }

        compose.onNodeWithText("自动设置时间：已开启").assertIsDisplayed()
        compose.onNodeWithText("打开系统日期与时间设置").performClick()

        val launched = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Settings.ACTION_DATE_SETTINGS, launched.action)
    }

    @Test fun addMenuScrollsAndWrapsItsLongestDescriptionAtLargeFontSize() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                InkTheme(darkTheme = false) {
                    Box(Modifier.size(width = 320.dp, height = 270.dp)) {
                        AddSheetContent(onScan = {}, onGallery = {}, onManual = {})
                    }
                }
            }
        }

        compose.onNodeWithText("从 Google Authenticator 导入").assertDoesNotExist()
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("输入 Base32 密钥，或粘贴设置或迁移链接", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertFalse(layout.single().hasVisualOverflow)
    }
}
