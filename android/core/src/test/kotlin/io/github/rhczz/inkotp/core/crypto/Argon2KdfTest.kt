package io.github.rhczz.inkotp.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.Normalizer

class Argon2KdfTest {

    private val kdf = Argon2Kdf()
    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun lowCostVectorMatchesPythonReference() {
        val key = kdf.deriveKey("password".toCharArray(), salt, KdfParams(8_192, 1, 1))
        assertArrayEquals(
            "6708f1faf7314dfc009386f5258b181f70f6190926363475fadec630a1bde67a".hexToBytes(),
            key,
        )
    }

    @Test
    fun productionParametersMatchPythonReference() {
        val key = kdf.deriveKey("password".toCharArray(), salt, KdfParams(65_536, 3, 2))
        assertArrayEquals(
            "f013896e493f7ad348426eaca43653765a0a902c79cb0068036d4483ee05c166".hexToBytes(),
            key,
        )
    }

    @Test
    fun passwordIsNfcNormalized() {
        val composed = "caf\u00e9".toCharArray()
        val decomposed = Normalizer.normalize("caf\u00e9", Normalizer.Form.NFD).toCharArray()
        assertArrayEquals(
            kdf.deriveKey(composed, salt, KdfParams(8_192, 1, 1)),
            kdf.deriveKey(decomposed, salt, KdfParams(8_192, 1, 1)),
        )
    }

    @Test
    fun parameterRangesAreEnforced() {
        for ((m, t, p) in listOf(
            Triple(8_191, 1, 1),
            Triple(524_289, 1, 1),
            Triple(8_192, 0, 1),
            Triple(8_192, 11, 1),
            Triple(8_192, 1, 0),
            Triple(8_192, 1, 9),
            Triple(64, 1, 8),
        )) {
            assertFalse(KdfParams.isValid(m, t, p), "m=$m t=$t p=$p")
        }
        assertTrue(KdfParams.isValid(8_192, 1, 1))
        assertTrue(KdfParams.isValid(65_536, 3, 2))
        assertThrows<IllegalArgumentException> { KdfParams(1_024, 1, 1) }
    }

    @Test
    fun saltLengthIsEnforced() {
        assertThrows<IllegalArgumentException> {
            kdf.deriveKey("password".toCharArray(), ByteArray(8), KdfParams(8_192, 1, 1))
        }
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }
}
