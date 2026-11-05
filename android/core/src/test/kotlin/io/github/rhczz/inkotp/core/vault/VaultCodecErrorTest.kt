package io.github.rhczz.inkotp.core.vault

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.crypto.Aead
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.json.VaultJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultCodecErrorTest {

    private val password = TestVault.PASSWORD.toCharArray()
    private val dek = ByteArray(32) { it.toByte() }
    private val params = KdfParams(8_192, 1, 1)

    private fun sealed(): ByteArray = VaultCodec.seal(TestVault.model(), dek, password, params)

    private fun mutate(bytes: ByteArray, offset: Int, value: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        value.copyInto(copy, offset)
        return copy
    }

    private fun code(block: () -> Unit): VaultError =
        assertThrows<VaultException> { block() }.code

    @Test
    fun kdfAndWrappedLengthErrors() {
        assertEquals(VaultError.VLT_E_KDF_ALGO, code { VaultCodec.parseHeader(mutate(sealed(), 6, byteArrayOf(0))) })
        assertEquals(VaultError.VLT_E_WRAPPED_LEN, code { VaultCodec.parseHeader(mutate(sealed(), 59, ByteArray(4))) })
        assertEquals(
            VaultError.VLT_E_WRAPPED_LEN,
            code { VaultCodec.parseHeader(mutate(sealed(), 59, ByteArray(4) { 0xFF.toByte() })) },
        )
        assertEquals(
            VaultError.VLT_E_KDF_PARAMS,
            code { VaultCodec.parseHeader(mutate(sealed(), 7, ByteArray(4) { 0xFF.toByte() })) },
        )
        assertEquals(
            VaultError.VLT_E_KDF_PARAMS,
            code { VaultCodec.parseHeader(mutate(sealed(), 7, ByteArray(4))) },
        )
    }

    @Test
    fun ciphertextLengthErrors() {
        assertEquals(VaultError.VLT_E_CT_LEN, code { VaultCodec.parseHeader(mutate(sealed(), 111, ByteArray(4))) })
        assertEquals(
            VaultError.VLT_E_CT_LEN,
            code { VaultCodec.parseHeader(mutate(sealed(), 111, byteArrayOf(0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))) },
        )
    }

    @Test
    fun fileTooLarge() {
        assertEquals(
            VaultError.VLT_E_FILE_TOO_LARGE,
            code { VaultCodec.parseHeader(ByteArray(12 * 1024 * 1024 + 1)) },
        )
    }

    @Test
    fun noPasswordHeaderConsistencyErrors() {
        val bytes = VaultCodec.seal(TestVault.model(), dek, password = null)
        assertEquals(VaultError.VLT_E_KDF_ALGO, code { VaultCodec.parseHeader(mutate(bytes, 6, byteArrayOf(1))) })
        assertEquals(VaultError.VLT_E_WRAPPED_LEN, code { VaultCodec.parseHeader(mutate(bytes, 59, byteArrayOf(0, 0, 0, 32))) })
        assertEquals(VaultError.VLT_E_KDF_PARAMS, code { VaultCodec.parseHeader(mutate(bytes, 7, byteArrayOf(0, 0, 0, 1))) })
        assertEquals(VaultError.VLT_E_FLAGS, code { VaultCodec.parseHeader(mutate(bytes, 19, byteArrayOf(1))) })
        assertEquals(VaultError.VLT_E_FLAGS, code { VaultCodec.parseHeader(mutate(bytes, 35, byteArrayOf(1))) })
    }

    @Test
    fun jsonAndSchemaFailuresAreMapped() {
        val bytes = sealed()
        val jsonFailure = code {
            VaultCodec.openWithDek(bytes, dek, StaticAead("not json".toByteArray()))
        }
        assertEquals(VaultError.VLT_E_JSON, jsonFailure)
        val future = VaultJson.encodeToString(TestVault.model())
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        val schemaFailure = code {
            VaultCodec.openWithDek(bytes, dek, StaticAead(future.toByteArray()))
        }
        assertEquals(VaultError.VLT_E_SCHEMA, schemaFailure)
    }

    private class StaticAead(private val plaintext: ByteArray) : Aead {
        override fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
            error("not used")

        override fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray =
            plaintext
    }
}
