package io.github.rhczz.inkotp.ui.demo

import io.github.rhczz.inkotp.ui.model.DiagnosticsUiState
import io.github.rhczz.inkotp.ui.model.ItemDetailUiState
import io.github.rhczz.inkotp.ui.model.SettingsUiState
import io.github.rhczz.inkotp.ui.model.UnlockUiState
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import io.github.rhczz.inkotp.ui.model.VaultListUiState

/**
 * Deterministic demo data for M2 screens and screenshot baselines.
 * Never used as a source of truth for real secrets.
 */
object DemoData {

    val items: List<VaultItemUi> = listOf(
        VaultItemUi(
            id = "demo-github",
            issuer = "GitHub",
            accountName = "me@example.com",
            code = "482913",
            digits = 6,
            remainingSeconds = 28,
            period = 30,
        ),
        VaultItemUi(
            id = "demo-google",
            issuer = "Google",
            accountName = "alice@gmail.com",
            code = "170245",
            digits = 6,
            remainingSeconds = 12,
            period = 30,
        ),
        VaultItemUi(
            id = "demo-aws",
            issuer = "AWS",
            accountName = "ops@example.com",
            code = "903114",
            digits = 6,
            remainingSeconds = 4,
            period = 30,
        ),
        VaultItemUi(
            id = "demo-gitlab",
            issuer = "GitLab",
            accountName = "alice",
            code = "23456789",
            digits = 8,
            remainingSeconds = 22,
            period = 30,
        ),
        VaultItemUi(
            id = "demo-cloudflare",
            issuer = "Cloudflare",
            accountName = "security@example.com",
            code = "671028",
            digits = 6,
            remainingSeconds = 16,
            period = 30,
        ),
        VaultItemUi(
            id = "demo-notion",
            issuer = "Notion",
            accountName = "alice@example.com",
            code = "000000",
            digits = 6,
            remainingSeconds = 9,
            period = 30,
            hidden = true,
        ),
    )

    val listState = VaultListUiState(
        items = items,
        backupReminder = true,
        clockWarning = false,
        passwordWarning = false,
    )

    val unlockState = UnlockUiState()
    val settingsState = SettingsUiState()
    val diagnosticsState = DiagnosticsUiState()
    val detailState = ItemDetailUiState(
        item = items.first(),
        secretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
    )
}
