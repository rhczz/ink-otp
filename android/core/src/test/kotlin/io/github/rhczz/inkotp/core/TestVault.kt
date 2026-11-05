package io.github.rhczz.inkotp.core

import io.github.rhczz.inkotp.core.model.VaultIcon
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSettings
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpType

internal object TestVault {

    const val PASSWORD = "correct horse battery staple"
    const val VAULT_ID = "6f1c2a6e-6f4d-4c1b-9d4e-2a7f0f3f9b21"
    const val ITEM_1 = "0d9a3c1e-8f21-4d6a-9c3e-1234567890ab"
    const val ITEM_2 = "7c4b2a10-3d5e-4f6a-8b9c-0d1e2f3a4b5c"
    const val CREATED = "2026-11-04T10:00:00Z"
    const val UPDATED = "2026-11-04T10:05:12Z"

    fun item(
        id: String = ITEM_1,
        issuer: String? = "GitHub",
        accountName: String = "me@example.com",
        secret: ByteArray = "12345678901234567890".toByteArray(),
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
        digits: Int = 6,
        period: Int = 30,
        type: OtpType = OtpType.TOTP,
        counter: Long? = null,
        sortIndex: Int = 1000,
        origin: String? = "qr",
        tags: List<String> = emptyList(),
        icon: VaultIcon? = VaultIcon(kind = "letter", value = "G", colorHex = "#24292F"),
        website: String? = null,
    ): VaultItem = VaultItem(
        id = id,
        type = type,
        issuer = issuer,
        accountName = accountName,
        secret = secret,
        algorithm = algorithm,
        digits = digits,
        period = period,
        counter = counter,
        tags = tags,
        icon = icon,
        sortIndex = sortIndex,
        origin = origin,
        createdAt = CREATED,
        updatedAt = UPDATED,
        website = website,
    )

    fun model(vararg items: VaultItem = arrayOf(item())): VaultModel = VaultModel(
        schemaVersion = 1,
        vaultId = VAULT_ID,
        createdAt = CREATED,
        updatedAt = UPDATED,
        settings = VaultSettings(lockTimeoutSeconds = 30, hideCodesByDefault = false, clipboardClearSeconds = 30),
        items = items.toList(),
    )

    fun twoItemModel(): VaultModel = model(
        item(),
        item(
            id = ITEM_2,
            issuer = "Example Corp",
            accountName = "alice@example.com",
            secret = "12345678901234567890123456789012".toByteArray(),
            algorithm = OtpAlgorithm.SHA256,
            digits = 8,
            period = 60,
            sortIndex = 2000,
            origin = "manual",
            icon = VaultIcon(kind = "letter", value = "E", colorHex = "#1F6FEB"),
        ),
    )
}
