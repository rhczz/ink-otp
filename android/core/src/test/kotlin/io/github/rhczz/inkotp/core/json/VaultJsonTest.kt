package io.github.rhczz.inkotp.core.json

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSchemaException
import io.github.rhczz.inkotp.core.model.VaultSettings
import io.github.rhczz.inkotp.core.otp.OtpType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultJsonTest {

    @Test
    fun roundTrip() {
        val model = TestVault.twoItemModel()
        val encoded = VaultJson.encode(model)
        val decoded = VaultJson.decode(encoded)
        assertEquals(model, decoded)
        assertEquals(model, VaultJson.decode(VaultJson.encodeToString(model)))
    }

    @Test
    fun fixtureFromSpecDecodes() {
        val text = resource("/fixtures/backup-v1.expected.json")
        val model = VaultJson.decode(text)
        assertEquals(1, model.schemaVersion)
        assertEquals(TestVault.VAULT_ID, model.vaultId)
        assertEquals(2, model.items.size)
        assertEquals("GitHub", model.items[0].issuer)
        assertEquals("me@example.com", model.items[0].accountName)
        assertEquals(TestVault.item().secret.toList(), model.items[0].secret.toList())
        assertEquals("SHA256", model.items[1].algorithm.name)
        assertEquals(8, model.items[1].digits)
        assertEquals(60, model.items[1].period)
        assertEquals(2000, model.items[1].sortIndex)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = resource("/fixtures/backup-v1.expected.json")
            .replaceFirst("{", "{\"futureField\":42,")
        val model = VaultJson.decode(text)
        assertEquals(2, model.items.size)
    }

    @Test
    fun futureSchemaVersionIsRejected() {
        val text = resource("/fixtures/backup-v1.expected.json")
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertThrows<VaultSchemaException> { VaultJson.decode(text) }
    }

    @Test
    fun invalidSecretBase64IsRejected() {
        val text = resource("/fixtures/backup-v1.expected.json")
            .replace("\"secretB64\": \"MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=\"", "\"secretB64\": \"!!!\"")
        assertThrows<VaultSchemaException> { VaultJson.decode(text) }
    }

    @Test
    fun totpCounterIsRejected() {
        val item = TestVault.item(counter = 0)
        assertThrows<VaultSchemaException> { VaultJson.encode(TestVault.model(item)) }
    }

    @Test
    fun hotpRequiresCounter() {
        val item = TestVault.item(type = OtpType.HOTP, counter = null)
        assertThrows<VaultSchemaException> { VaultJson.encode(TestVault.model(item)) }
        val valid = TestVault.item(type = OtpType.HOTP, counter = 42)
        val decoded = VaultJson.decode(VaultJson.encode(TestVault.model(valid)))
        assertEquals(42L, decoded.items.single().counter)
        assertEquals(OtpType.HOTP, decoded.items.single().type)
    }

    @Test
    fun duplicateIdsAreRejected() {
        val model = TestVault.model(TestVault.item(), TestVault.item())
        assertThrows<VaultSchemaException> { VaultJson.encode(model) }
    }

    @Test
    fun invalidUuidAndTimestampsAreRejected() {
        assertThrows<VaultSchemaException> {
            VaultJson.encode(TestVault.model(TestVault.item(id = "not-a-uuid")))
        }
        assertThrows<VaultSchemaException> {
            VaultJson.encode(
                TestVault.model().copy(createdAt = "yesterday"),
            )
        }
    }

    @Test
    fun settingsRangesAreValidated() {
        assertThrows<VaultSchemaException> {
            VaultJson.encode(TestVault.model().copy(settings = VaultSettings(lockTimeoutSeconds = -1)))
        }
        assertThrows<VaultSchemaException> {
            VaultJson.encode(TestVault.model().copy(settings = VaultSettings(clipboardClearSeconds = 301)))
        }
    }

    @Test
    fun textIsNormalizedAndControlCharactersStripped() {
        val item = TestVault.item(
            id = TestVault.ITEM_1,
            issuer = "Git\u0000Hub",
            accountName = "me@example.com",
            secret = "12345678901234567890".toByteArray(),
        )
        val decoded = VaultJson.decode(VaultJson.encode(TestVault.model(item)))
        assertEquals("GitHub", decoded.items.single().issuer)
    }

    @Test
    fun encodedJsonUsesUppercaseEnumsAndLowercaseType() {
        val encoded = VaultJson.encodeToString(TestVault.model())
        assertTrue(encoded.contains("\"type\":\"totp\""), encoded)
        assertTrue(encoded.contains("\"algorithm\":\"SHA1\""), encoded)
        assertTrue(encoded.contains("\"schemaVersion\":1"), encoded)
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResource(path)) { "missing resource $path" }.readText()
}
