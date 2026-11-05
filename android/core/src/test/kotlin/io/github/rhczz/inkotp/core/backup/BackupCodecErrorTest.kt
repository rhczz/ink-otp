package io.github.rhczz.inkotp.core.backup

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.crypto.Aead
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.json.VaultJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackupCodecErrorTest {

    private val password = TestVault.PASSWORD.toCharArray()
    private val params = KdfParams(8_192, 1, 1)

    private fun sealed(): ByteArray = BackupCodec.seal(TestVault.model(), password, params)

    private fun mutate(bytes: ByteArray, offset: Int, value: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        value.copyInto(copy, offset)
        return copy
    }

    private fun code(block: () -> Unit): BackupError =
        assertThrows<BackupException> { block() }.code

    @Test
    fun headerErrors() {
        assertEquals(BackupError.BAK_E_KDF_ALGO, code { BackupCodec.parseHeader(mutate(sealed(), 5, byteArrayOf(2))) })
        assertEquals(BackupError.BAK_E_KDF_PARAMS, code { BackupCodec.parseHeader(mutate(sealed(), 6, ByteArray(4))) })
        assertEquals(
            BackupError.BAK_E_KDF_PARAMS,
            code { BackupCodec.parseHeader(mutate(sealed(), 6, ByteArray(4) { 0xFF.toByte() })) },
        )
        assertEquals(BackupError.BAK_E_CT_LEN, code { BackupCodec.parseHeader(mutate(sealed(), 46, ByteArray(4))) })
        assertEquals(
            BackupError.BAK_E_CT_LEN,
            code { BackupCodec.parseHeader(mutate(sealed(), 46, byteArrayOf(0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))) },
        )
        assertEquals(
            BackupError.BAK_E_FILE_TOO_LARGE,
            code { BackupCodec.parseHeader(ByteArray(12 * 1024 * 1024 + 1)) },
        )
    }

    @Test
    fun jsonAndSchemaFailuresAreMapped() {
        val bytes = sealed()
        assertEquals(
            BackupError.BAK_E_JSON,
            code { BackupCodec.open(bytes, password, aead = StaticAead("not json".toByteArray())) },
        )
        val future = VaultJson.encodeToString(TestVault.model())
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertEquals(
            BackupError.BAK_E_SCHEMA,
            code { BackupCodec.open(bytes, password, aead = StaticAead(future.toByteArray())) },
        )
    }

    private class StaticAead(private val plaintext: ByteArray) : Aead {
        override fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
            error("not used")

        override fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray =
            plaintext
    }
}
