package io.github.rhczz.inkotp.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.ui.screens.ScanScreen
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScanScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun deniedCameraPermissionKeepsGalleryManualAndSettingsAvailable() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.CAMERA)
        var galleries = 0
        var manuals = 0
        compose.setContent {
            InkTheme {
                ScanScreen(onBack = {}, onDetected = {}, onGallery = { galleries++ }, onManual = { manuals++ })
            }
        }

        compose.onNodeWithText("授予相机权限").assertExists()
        compose.onNodeWithText("重试相机权限").assertDoesNotExist()
        compose.onNodeWithText("前往系统设置").assertExists()
        compose.onNodeWithText("从图片导入").performScrollTo().performClick()
        compose.onNodeWithText("手动输入").performScrollTo().performClick()
        assertEquals(1, galleries)
        assertEquals(1, manuals)
    }
}
