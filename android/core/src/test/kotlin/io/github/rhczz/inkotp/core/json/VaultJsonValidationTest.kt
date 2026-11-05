package io.github.rhczz.inkotp.core.json

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultSchemaException
import io.github.rhczz.inkotp.core.model.VaultSettings
import io.github.rhczz.inkotp.core.otp.OtpType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultJsonValidationTest {

    private fun assertSchemaRejected(item: VaultItem) {
        assertThrows<VaultSchemaException> { VaultJson.encode(TestVault.model(item)) }
    }

    @Test
    fun textFieldLimitsAreEnforced() {
        assertSchemaRejected(TestVault.item(accountName = ""))
        assertSchemaRejected(TestVault.item(accountName = "a".repeat(257)))
        assertSchemaRejected(TestVault.item(issuer = "i".repeat(129)))
        assertSchemaRejected(TestVault.item(tags = List(21) { "tag" }))
        assertSchemaRejected(TestVault.item(tags = listOf("")))
        assertSchemaRejected(TestVault.item(tags = listOf("t".repeat(33))))
        assertSchemaRejected(TestVault.item(origin = "o".repeat(33)))
        assertSchemaRejected(TestVault.item(website = "w".repeat(257)))
    }

    @Test
    fun iconLimitsAreEnforced() {
        assertSchemaRejected(TestVault.item(icon = io.github.rhczz.inkotp.core.model.VaultIcon("", "G")))
        assertSchemaRejected(TestVault.item(icon = io.github.rhczz.inkotp.core.model.VaultIcon("k".repeat(33), "G")))
        assertSchemaRejected(TestVault.item(icon = io.github.rhczz.inkotp.core.model.VaultIcon("letter", "")))
        assertSchemaRejected(TestVault.item(icon = io.github.rhczz.inkotp.core.model.VaultIcon("letter", "ABC")))
        assertSchemaRejected(TestVault.item(icon = io.github.rhczz.inkotp.core.model.VaultIcon("letter", "G", "red")))
    }

    @Test
    fun otpParameterLimitsAreEnforced() {
        assertSchemaRejected(TestVault.item(secret = ByteArray(0)))
        assertSchemaRejected(TestVault.item(secret = ByteArray(256)))
        assertSchemaRejected(TestVault.item(digits = 5))
        assertSchemaRejected(TestVault.item(period = 0))
        assertSchemaRejected(TestVault.item(type = OtpType.HOTP, counter = -1))
        assertSchemaRejected(TestVault.item(type = OtpType.HOTP, counter = 1, period = 301))
    }

    @Test
    fun unknownTypeAndAlgorithmAreRejected() {
        val encoded = VaultJson.encodeToString(TestVault.model())
        assertThrows<VaultSchemaException> {
            VaultJson.decode(encoded.replace("\"type\":\"totp\"", "\"type\":\"motp\""))
        }
        assertThrows<VaultSchemaException> {
            VaultJson.decode(encoded.replace("\"algorithm\":\"SHA1\"", "\"algorithm\":\"MD5\""))
        }
    }

    @Test
    fun malformedUtf8AndJsonAreRejected() {
        assertThrows<VaultJsonException> { VaultJson.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) }
        assertThrows<VaultJsonException> { VaultJson.decode("not json") }
        assertThrows<VaultJsonException> {
            VaultJson.decode(
                "{\"schemaVersion\":1,\"vaultId\":\"${TestVault.VAULT_ID}\"," +
                    "\"createdAt\":\"${TestVault.CREATED}\",\"updatedAt\":\"${TestVault.UPDATED}\"}",
            )
        }
    }

    @Test
    fun settingsDefaultsAndBounds() {
        val decoded = VaultJson.decode(VaultJson.encode(TestVault.model()))
        assertEquals(VaultSettings(), decoded.settings)
        assertThrows<VaultSchemaException> {
            VaultJson.encode(TestVault.model().copy(settings = VaultSettings(lockTimeoutSeconds = 3_601)))
        }
    }
}
