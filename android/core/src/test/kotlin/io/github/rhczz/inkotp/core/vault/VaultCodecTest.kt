package io.github.rhczz.inkotp.core.vault

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.hexToBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultCodecTest {

    private val password = TestVault.PASSWORD.toCharArray()
    private val dek = ByteArray(32) { (it * 7 + 3).toByte() }
    private val params = KdfParams(mKiB = 8_192, tIter = 1, pLanes = 1)

    @Test
    fun readsIndependentWireExampleSharedWithSwift() {
        val spec = checkNotNull(javaClass.getResourceAsStream("/vault-format.md")) {
            "spec/vault-format.md is missing from the test classpath"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val marker = "fileHex =\n"
        check(spec.contains(marker)) { "Missing vault wire example" }
        val bytes = spec.substringAfter(marker)
            .substringBefore("\n```")
            .filterNot(Char::isWhitespace)
            .hexToBytes()

        val header = VaultCodec.parseHeader(bytes)
        assertEquals(115, header.ciphertextOffset)
        assertEquals(240, header.ctLen)
        val opened = VaultCodec.openWithPassword(bytes, "correct horse battery staple".toCharArray())
        assertEquals("6f1c2a6e-6f4d-4c1b-9d4e-2a7f0f3f9b21", opened.model.vaultId)
        assertTrue(opened.model.items.isEmpty())
        assertEquals(opened.model, VaultCodec.openWithDek(bytes, opened.dek))
        assertEquals(
            VaultError.VLT_E_WRAP_AUTH,
            assertThrows<VaultException> {
                VaultCodec.openWithPassword(bytes, "wrong fixture password".toCharArray())
            }.code,
        )
    }

    @Test
    fun passwordRoundTrip() {
        val model = TestVault.twoItemModel()
        val bytes = VaultCodec.seal(model, dek, password, params)
        val header = VaultCodec.parseHeader(bytes)
        assertTrue(header.hasPassword)
        assertEquals(115, header.ciphertextOffset)
        assertEquals(bytes.size, header.fileLen)
        assertEquals(model, VaultCodec.openWithPassword(bytes, password).model)
        assertArrayEquals(dek, VaultCodec.unwrapDek(bytes, password))
        assertArrayEquals(dek, VaultCodec.openWithPassword(bytes, password).dek)
        assertEquals(model, VaultCodec.openWithDek(bytes, dek))
    }

    @Test
    fun noPasswordRoundTrip() {
        val model = TestVault.twoItemModel()
        val bytes = VaultCodec.seal(model, dek, password = null)
        val header = VaultCodec.parseHeader(bytes)
        assertFalse(header.hasPassword)
        assertEquals(67, header.ciphertextOffset)
        assertEquals(bytes.size, header.fileLen)
        assertEquals(model, VaultCodec.openWithDek(bytes, dek))
        val exception = assertThrows<VaultException> { VaultCodec.unwrapDek(bytes, password) }
        assertEquals(VaultError.VLT_E_FLAGS, exception.code)
    }

    @Test
    fun wrongPasswordAndWrongDekFailClosed() {
        val bytes = VaultCodec.seal(TestVault.model(), dek, password, params)
        assertEquals(
            VaultError.VLT_E_WRAP_AUTH,
            assertThrows<VaultException> { VaultCodec.unwrapDek(bytes, "wrong".toCharArray()) }.code,
        )
        assertEquals(
            VaultError.VLT_E_VAULT_AUTH,
            assertThrows<VaultException> { VaultCodec.openWithDek(bytes, ByteArray(32) { 1 }) }.code,
        )
    }

    @Test
    fun everyHeaderByteTamperIsRejected() {
        val bytes = VaultCodec.seal(TestVault.model(), dek, password, params)
        val header = VaultCodec.parseHeader(bytes)
        for (index in 0 until header.ciphertextOffset) {
            val tampered = bytes.copyOf()
            tampered[index] = (tampered[index].toInt() xor 1).toByte()
            assertThrows<VaultException>("header byte $index accepted") {
                VaultCodec.openWithDek(tampered, dek)
            }
        }
    }

    @Test
    fun ciphertextAndTagTamperIsRejected() {
        val bytes = VaultCodec.seal(TestVault.model(), dek, password, params)
        val header = VaultCodec.parseHeader(bytes)
        for (index in intArrayOf(header.ciphertextOffset, header.ciphertextOffset + header.ctLen - 1, header.tagOffset)) {
            val tampered = bytes.copyOf()
            tampered[index] = (tampered[index].toInt() xor 1).toByte()
            assertEquals(
                VaultError.VLT_E_VAULT_AUTH,
                assertThrows<VaultException> { VaultCodec.openWithDek(tampered, dek) }.code,
                "index=$index",
            )
        }
    }

    @Test
    fun truncationAndTrailingDataAreRejected() {
        val bytes = VaultCodec.seal(TestVault.model(), dek, password, params)
        assertEquals(
            VaultError.VLT_E_FILE_TRUNCATED,
            assertThrows<VaultException> { VaultCodec.parseHeader(bytes.copyOf(bytes.size - 1)) }.code,
        )
        assertEquals(
            VaultError.VLT_E_TRAILING_DATA,
            assertThrows<VaultException> { VaultCodec.parseHeader(bytes + byteArrayOf(0)) }.code,
        )
    }

    @Test
    fun magicVersionAndFlagMutationsAreRejected() {
        val bytes = VaultCodec.seal(TestVault.model(), dek, password, params)
        val badMagic = bytes.copyOf().also { it[0] = 0 }
        assertEquals(
            VaultError.VLT_E_MAGIC,
            assertThrows<VaultException> { VaultCodec.parseHeader(badMagic) }.code,
        )
        val badVersion = bytes.copyOf().also { it[4] = 2 }
        assertEquals(
            VaultError.VLT_E_VERSION,
            assertThrows<VaultException> { VaultCodec.parseHeader(badVersion) }.code,
        )
        val badFlags = bytes.copyOf().also { it[5] = (it[5].toInt() or 2).toByte() }
        assertEquals(
            VaultError.VLT_E_FLAGS,
            assertThrows<VaultException> { VaultCodec.parseHeader(badFlags) }.code,
        )
    }

    @Test
    fun passwordChangeReencryptsWithTheSameDek() {
        val model = TestVault.model()
        val first = VaultCodec.seal(model, dek, "first".toCharArray(), params)
        val opened = VaultCodec.openWithPassword(first, "first".toCharArray())
        val second = VaultCodec.seal(opened.model, opened.dek, "second".toCharArray(), params)
        assertEquals(model, VaultCodec.openWithPassword(second, "second".toCharArray()).model)
        assertEquals(
            VaultError.VLT_E_WRAP_AUTH,
            assertThrows<VaultException> { VaultCodec.unwrapDek(second, "first".toCharArray()) }.code,
        )
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun sealsAreRandomized() {
        val model = TestVault.model()
        val first = VaultCodec.seal(model, dek, password, params)
        val second = VaultCodec.seal(model, dek, password, params)
        assertNotEquals(first.toList(), second.toList())
    }
}
