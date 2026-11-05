package io.github.rhczz.inkotp.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class AeadTest {

    private val aead = JceAead()
    private val key = ByteArray(32) { (it * 3 + 1).toByte() }
    private val nonce = ByteArray(12) { it.toByte() }
    private val aad = "header".toByteArray()
    private val plaintext = "secret vault json".toByteArray()

    @Test
    fun roundTrip() {
        val ciphertext = aead.encrypt(key, nonce, plaintext, aad)
        assertNotEquals(plaintext.toList(), ciphertext.toList())
        assertArrayEquals(plaintext, aead.decrypt(key, nonce, ciphertext, aad))
    }

    @Test
    fun wrongKeyNonceOrAadFails() {
        val ciphertext = aead.encrypt(key, nonce, plaintext, aad)
        assertThrows<AeadException> { aead.decrypt(ByteArray(32) { 9 }, nonce, ciphertext, aad) }
        assertThrows<AeadException> { aead.decrypt(key, ByteArray(12) { 9 }, ciphertext, aad) }
        assertThrows<AeadException> { aead.decrypt(key, nonce, ciphertext, "other".toByteArray()) }
    }

    @Test
    fun tamperedCiphertextOrShortInputFails() {
        val ciphertext = aead.encrypt(key, nonce, plaintext, aad)
        val tampered = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows<AeadException> { aead.decrypt(key, nonce, tampered, aad) }
        assertThrows<AeadException> { aead.decrypt(key, nonce, ciphertext.copyOf(4), aad) }
    }

    @Test
    fun keyAndNonceLengthsAreEnforced() {
        assertThrows<IllegalArgumentException> { aead.encrypt(ByteArray(16), nonce, plaintext, aad) }
        assertThrows<IllegalArgumentException> { aead.encrypt(key, ByteArray(8), plaintext, aad) }
    }

    @Test
    fun randomSourceProducesRequestedLength() {
        val random = SecureRandomSource()
        val first = random.bytes(16)
        val second = random.bytes(16)
        assertNotEquals(first.toList(), second.toList())
    }
}
