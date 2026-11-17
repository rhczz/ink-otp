package io.github.rhczz.inkotp.core.vault

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.crypto.Argon2Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultSessionKeyTest {
    private val params = KdfParams(8192, 1, 1)
    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val dek = ByteArray(32) { (it + 3).toByte() }

    @Test
    fun sessionSavePreservesPasswordAndRefreshesBothNonces() {
        val password = TestVault.PASSWORD.toCharArray()
        val kek = Argon2Kdf().deriveKey(password, salt, params)
        val first = VaultCodec.sealWithKey(TestVault.model(), dek, kek, salt, params)
        val updated = TestVault.twoItemModel()
        val second = VaultCodec.sealWithKey(updated, dek, kek, salt, params)
        assertEquals(updated, VaultCodec.openWithPassword(second, password).model)
        assertArrayEquals(dek, VaultCodec.unwrapDekWithKey(second, kek))
        assertFalse(VaultCodec.parseHeader(first).dekNonce.contentEquals(VaultCodec.parseHeader(second).dekNonce))
        assertFalse(VaultCodec.parseHeader(first).vaultNonce.contentEquals(VaultCodec.parseHeader(second).vaultNonce))
        assertArrayEquals(salt, VaultCodec.parseHeader(second).salt)
    }

    @Test
    fun wrongSessionKeyCannotAuthenticatePasswordChannel() {
        val kek = ByteArray(32) { 5 }
        val bytes = VaultCodec.sealWithKey(TestVault.model(), dek, kek, salt, params)
        assertEquals(VaultError.VLT_E_WRAP_AUTH,
            assertThrows<VaultException> { VaultCodec.unwrapDekWithKey(bytes, ByteArray(32) { 9 }) }.code)
    }

    @Test
    fun passwordlessSessionKeepsV1Layout() {
        val bytes = VaultCodec.sealWithKey(TestVault.model(), dek)
        assertEquals(67, VaultCodec.parseHeader(bytes).ciphertextOffset)
        assertEquals(TestVault.model(), VaultCodec.openWithDek(bytes, dek))
    }
}
