package io.github.rhczz.inkotp.core.crypto

interface Aead {
    /** Returns ciphertext || tag (tag is the last 16 bytes). */
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** Accepts ciphertext || tag; throws [AeadException] on authentication failure. */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray
}

class AeadException(
    message: String,
) : IllegalArgumentException(message)

class JceAead : Aead {

    override fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        requireKeyAndNonce(key, nonce)
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray {
        requireKeyAndNonce(key, nonce)
        if (ciphertextAndTag.size < TAG_BYTES) throw AeadException("ciphertext is shorter than the tag")
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertextAndTag)
        } catch (_: java.security.GeneralSecurityException) {
            throw AeadException("authentication failed")
        }
    }

    private fun requireKeyAndNonce(key: ByteArray, nonce: ByteArray) {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(nonce.size == 12) { "GCM nonce must be 12 bytes" }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
    }
}
