package io.github.rhczz.inkotp.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.BuildConfig
import io.github.rhczz.inkotp.ui.screens.AboutScreen
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun licensePageOpensTheProjectRepositoryInAnExternalApp() {
        showLicenses()

        compose.onNodeWithText(BuildConfig.VERSION_NAME).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GPL-3.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("github.com/rhczz/ink-otp").performScrollTo().performClick()

        val intent = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/rhczz/ink-otp", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun licensePageKeepsTheAddressVisibleWhenThereIsNoHandler() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
        showLicenses()

        compose.onNodeWithText("github.com/rhczz/ink-otp").performScrollTo().performClick().assertIsDisplayed()
        compose.onNodeWithText("请使用浏览器访问上述地址。").assertIsDisplayed()
    }

    private fun showLicenses() {
        compose.setContent {
            InkTheme(darkTheme = false) { AboutScreen(onBack = {}) }
        }
    }
}
