package io.github.rhczz.inkotp.ui.model

data class VaultItemUi(
    val id: String,
    val issuer: String?,
    val accountName: String,
    val code: String,
    val digits: Int = 6,
    val remainingSeconds: Int = 30,
    val period: Int = 30,
    val hidden: Boolean = false,
    val type: String = "totp",
)

data class VaultListUiState(
    val items: List<VaultItemUi> = emptyList(),
    val query: String = "",
    val searching: Boolean = false,
    val backupReminder: Boolean = true,
    val clockWarning: Boolean = false,
    val passwordWarning: Boolean = false,
)

data class UnlockUiState(
    val error: String? = null,
    val biometricAvailable: Boolean = true,
    val degraded: Boolean = false,
)

data class ItemDetailUiState(
    val item: VaultItemUi,
    val editing: Boolean = false,
    val showSecret: Boolean = false,
    val secretBase32: String = "",
)

data class SettingsUiState(
    val biometricEnabled: Boolean = true,
    val lockTimeoutSeconds: Int = 30,
    val hideCodesByDefault: Boolean = false,
    val clipboardClearSeconds: Int = 30,
    val themeMode: String = "system",
    val passwordSet: Boolean = true,
    val lastBackupAt: String? = "11-05 09:30",
)

data class DiagnosticsUiState(
    val deviceTime: String = "2026-11-05 16:38:12",
    val autoTime: Boolean = true,
    val driftDetected: Boolean = false,
)
