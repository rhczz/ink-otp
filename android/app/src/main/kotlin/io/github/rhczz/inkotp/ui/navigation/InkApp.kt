package io.github.rhczz.inkotp.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.rhczz.inkotp.ui.components.InkBottomSheet
import io.github.rhczz.inkotp.ui.demo.DemoData
import io.github.rhczz.inkotp.ui.model.SettingsUiState
import io.github.rhczz.inkotp.ui.model.VaultListUiState
import io.github.rhczz.inkotp.ui.screens.AboutScreen
import io.github.rhczz.inkotp.ui.screens.AddSheetContent
import io.github.rhczz.inkotp.ui.screens.DiagnosticsScreen
import io.github.rhczz.inkotp.ui.screens.ItemDetailScreen
import io.github.rhczz.inkotp.ui.screens.ManualAddScreen
import io.github.rhczz.inkotp.ui.screens.OnboardingScreen
import io.github.rhczz.inkotp.ui.screens.ScanPlaceholderScreen
import io.github.rhczz.inkotp.ui.screens.SettingsScreen
import io.github.rhczz.inkotp.ui.screens.UnlockScreen
import io.github.rhczz.inkotp.ui.screens.VaultListScreen
import io.github.rhczz.inkotp.ui.theme.AppTokens

sealed interface Screen {
    data object Unlock : Screen
    data object Onboarding : Screen
    data object List : Screen
    data object Detail : Screen
    data object ManualAdd : Screen
    data object Scan : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
    data object About : Screen
}

@Composable
fun InkApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Unlock) }
    val backStack = remember { mutableStateListOf<Screen>() }
    var onboardingPage by remember { mutableStateOf(0) }
    var listState by remember { mutableStateOf<VaultListUiState>(DemoData.listState) }
    var settingsState by remember { mutableStateOf<SettingsUiState>(DemoData.settingsState) }
    var showAddSheet by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf(DemoData.items.first()) }

    fun navigate(next: Screen) {
        backStack.add(screen)
        screen = next
    }

    fun back() {
        if (backStack.isNotEmpty()) {
            screen = backStack.removeAt(backStack.lastIndex)
        }
    }

    BackHandler(enabled = backStack.isNotEmpty()) { back() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(tween(AppTokens.Motion.PAGE_MS)) +
                    slideInVertically(tween(AppTokens.Motion.PAGE_MS)) { it / 24 })
                    .togetherWith(fadeOut(tween(AppTokens.Motion.PAGE_MS)))
            },
            label = "screen",
        ) { target ->
            when (target) {
                Screen.Unlock -> UnlockScreen(
                    state = DemoData.unlockState,
                    onUnlock = {
                        backStack.clear()
                        screen = Screen.List
                    },
                    onPasswordSubmit = {
                        backStack.clear()
                        screen = Screen.List
                    },
                )

                Screen.Onboarding -> OnboardingScreen(
                    page = onboardingPage,
                    onNext = {
                        if (onboardingPage < 3) onboardingPage++ else {
                            backStack.clear()
                            screen = Screen.List
                        }
                    },
                    onBack = {
                        if (onboardingPage > 0) onboardingPage-- else back()
                    },
                    onSkipPassword = { onboardingPage = 2 },
                    onSetPassword = { onboardingPage = 2 },
                    onEnableBiometric = { onboardingPage = 3 },
                    onFinish = {
                        backStack.clear()
                        screen = Screen.List
                    },
                )

                Screen.List -> VaultListScreen(
                    state = listState,
                    onQueryChange = { listState = listState.copy(query = it) },
                    onOpenItem = { selectedItem = it; navigate(Screen.Detail) },
                    onAdd = { showAddSheet = true },
                    onSettings = { navigate(Screen.Settings) },
                    onOpenDiagnostics = { navigate(Screen.Diagnostics) },
                )

                Screen.Detail -> ItemDetailScreen(
                    item = selectedItem,
                    secretBase32 = DemoData.detailState.secretBase32,
                    onBack = { back() },
                    onDelete = { back() },
                    onSave = { _, _ -> back() },
                )

                Screen.ManualAdd -> ManualAddScreen(
                    onBack = { back() },
                    onSave = { _, _, _ -> back() },
                )

                Screen.Scan -> ScanPlaceholderScreen(
                    onBack = { back() },
                    onManual = { navigate(Screen.ManualAdd) },
                )

                Screen.Settings -> SettingsScreen(
                    state = settingsState,
                    onBack = { back() },
                    onBiometricChange = { settingsState = settingsState.copy(biometricEnabled = it) },
                    onLockTimeoutChange = { settingsState = settingsState.copy(lockTimeoutSeconds = it) },
                    onHideCodesChange = { settingsState = settingsState.copy(hideCodesByDefault = it) },
                    onClipboardChange = { settingsState = settingsState.copy(clipboardClearSeconds = it) },
                    onDiagnostics = { navigate(Screen.Diagnostics) },
                    onAbout = { navigate(Screen.About) },
                    onOnboarding = {
                        onboardingPage = 0
                        navigate(Screen.Onboarding)
                    },
                )

                Screen.Diagnostics -> DiagnosticsScreen(
                    state = DemoData.diagnosticsState,
                    onBack = { back() },
                    onRefresh = {},
                )

                Screen.About -> AboutScreen(onBack = { back() })
            }
        }

        if (showAddSheet) {
            InkBottomSheet(onDismiss = { showAddSheet = false }) {
                AddSheetContent(
                    onScan = {
                        showAddSheet = false
                        navigate(Screen.Scan)
                    },
                    onGallery = {
                        showAddSheet = false
                        navigate(Screen.ManualAdd)
                    },
                    onManual = {
                        showAddSheet = false
                        navigate(Screen.ManualAdd)
                    },
                )
            }
        }
    }
}
