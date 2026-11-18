package io.github.rhczz.inkotp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.rhczz.inkotp.ui.demo.DemoData
import io.github.rhczz.inkotp.ui.model.VaultListUiState
import io.github.rhczz.inkotp.ui.screens.AboutScreen
import io.github.rhczz.inkotp.ui.screens.AddSheetPreview
import io.github.rhczz.inkotp.ui.screens.DiagnosticsScreen
import io.github.rhczz.inkotp.ui.screens.ItemDetailScreen
import io.github.rhczz.inkotp.ui.screens.ManualAddScreen
import io.github.rhczz.inkotp.ui.screens.OnboardingScreen
import io.github.rhczz.inkotp.ui.screens.ScanPlaceholderScreen
import io.github.rhczz.inkotp.ui.screens.SettingsScreen
import io.github.rhczz.inkotp.ui.screens.UnlockScreen
import io.github.rhczz.inkotp.ui.screens.VaultListScreen
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lightScreens() = captureAll(dark = false, scale = 1.0f, suffix = "light")

    @Test
    fun darkScreens() = captureAll(dark = true, scale = 1.0f, suffix = "dark")

    @Test
    fun largeFontScreens() = captureAll(dark = false, scale = 2.0f, suffix = "font200")

    private fun captureAll(dark: Boolean, scale: Float, suffix: String) {
        val cases = screenshotCases()
        val index = mutableStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = scale),
            ) {
                InkTheme(darkTheme = dark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        cases[index.value].content()
                    }
                }
            }
        }
        cases.forEachIndexed { i, case ->
            index.value = i
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/roborazzi/m2-${case.name}-$suffix.png")
        }
    }

    private fun screenshotCases(): List<ScreenshotCase> = listOf(
        ScreenshotCase("unlock") {
            UnlockScreen(
                state = DemoData.unlockState,
                onUnlock = {},
                onPasswordSubmit = {},
            )
        },
        ScreenshotCase("unlock-system-auth") {
            UnlockScreen(
                state = DemoData.unlockState.copy(systemAuthenticationRequired = true),
                onUnlock = {}, onPasswordSubmit = {},
            )
        },
        ScreenshotCase("unlock-system-recovery") {
            UnlockScreen(
                state = DemoData.unlockState.copy(systemAuthenticationRequired = true, systemRecoveryRequired = true),
                onUnlock = {}, onPasswordSubmit = {},
            )
        },
        ScreenshotCase("onboarding-0") {
            OnboardingScreen(0, {}, null, {}, {}, {}, {})
        },
        ScreenshotCase("onboarding-1") {
            OnboardingScreen(1, {}, null, {}, {}, {}, {})
        },
        ScreenshotCase("onboarding-2") {
            OnboardingScreen(2, {}, null, {}, {}, {}, {})
        },
        ScreenshotCase("onboarding-3") {
            OnboardingScreen(3, {}, null, {}, {}, {}, {})
        },
        ScreenshotCase("vault-list") {
            VaultListScreen(
                state = DemoData.listState,
                onQueryChange = {},
                onOpenItem = {},
                onAdd = {},
                onSettings = {},
                onOpenDiagnostics = {},
            )
        },
        ScreenshotCase("vault-list-empty") {
            VaultListScreen(
                state = VaultListUiState(items = emptyList(), backupReminder = false),
                onQueryChange = {},
                onOpenItem = {},
                onAdd = {},
                onSettings = {},
                onOpenDiagnostics = {},
            )
        },
        ScreenshotCase("detail") {
            ItemDetailScreen(
                item = DemoData.items.first(),
                secretBase32 = DemoData.detailState.secretBase32,
                onBack = {},
                onDelete = {},
                onSave = { _, _ -> },
            )
        },
        ScreenshotCase("add-sheet") {
            AddSheetPreview()
        },
        ScreenshotCase("manual-add") {
            ManualAddScreen(onBack = {}, onSave = { _, _, _ -> })
        },
        ScreenshotCase("scan") {
            ScanPlaceholderScreen(onBack = {}, onManual = {})
        },
        ScreenshotCase("settings") {
            SettingsScreen(
                state = DemoData.settingsState,
                onBack = {},
                onLockTimeoutChange = {},
                onHideCodesChange = {},
                onClipboardChange = {},
                onDiagnostics = {},
                onAbout = {},
                onOnboarding = {},
            )
        },
        ScreenshotCase("diagnostics") {
            DiagnosticsScreen(state = DemoData.diagnosticsState, onBack = {}, onRecalculate = {}, onRefreshResources = {})
        },
        ScreenshotCase("diagnostics-resources") {
            DiagnosticsScreen(state = DemoData.diagnosticsState.copy(resources = listOf(
                "进程内存 PSS" to "54.00 MiB", "Java 堆使用" to "12.00 MiB / 256.00 MiB",
                "原生堆" to "8.00 MiB", "私有文件逻辑大小" to "0.02 MiB",
                "本次采样" to "8 ms / 12 个目录项", "最近操作（含验证等待）" to "导入 · 350 ms",
            )), onBack = {}, onRecalculate = {}, onRefreshResources = {})
        },
        ScreenshotCase("about") {
            AboutScreen(onBack = {})
        },
    )

    private data class ScreenshotCase(
        val name: String,
        val content: @Composable () -> Unit,
    )
}
