package io.github.rhczz.inkotp.core.crypto

import java.text.Normalizer

data class KdfParams(
    val mKiB: Int,
    val tIter: Int,
    val pLanes: Int,
) {
    init {
        require(isValid(mKiB, tIter, pLanes)) { "invalid Argon2id parameters" }
    }

    companion object {
        val V1 = KdfParams(mKiB = 65_536, tIter = 3, pLanes = 2)

        fun isValid(mKiB: Int, tIter: Int, pLanes: Int): Boolean =
            mKiB in 8_192..524_288 &&
                tIter in 1..10 &&
                pLanes in 1..8 &&
                mKiB >= 8 * pLanes
    }
}

interface Kdf {
    /**
     * Derives a 32-byte key from a passphrase. Passphrases are NFC-normalized
     * UTF-8 and are not trimmed or case-folded (backup/vault spec §3.2).
     */
    fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray
}

class Argon2Kdf : Kdf {

    override fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        require(salt.size == 16) { "Argon2id salt must be 16 bytes" }
        val normalized = Normalizer.normalize(String(password), Normalizer.Form.NFC)
        val passwordBytes = normalized.toByteArray(Charsets.UTF_8)
        val output = ByteArray(32)
        try {
            val parameters = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
                org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id,
            )
                .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(params.tIter)
                .withMemoryAsKB(params.mKiB)
                .withParallelism(params.pLanes)
                .withSalt(salt)
                .build()
            val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
            generator.init(parameters)
            generator.generateBytes(passwordBytes, output)
            return output
        } finally {
            SecretBytes.wipe(passwordBytes)
        }
    }
}

object SecretBytes {
    fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) array?.fill(0)
    }
}
