package io.github.rhczz.inkotp.core.base32

import io.github.rhczz.inkotp.core.SpecVectors
import io.github.rhczz.inkotp.core.hexToBytes
import io.github.rhczz.inkotp.core.toHex
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Base32Test {

    @Test
    fun specVectors() {
        for (element in SpecVectors.array("base32")) {
            val vector = element.jsonObject
            val input = vector.getValue("input").jsonPrimitive.content
            val error = vector["expectedError"]?.jsonPrimitive?.content
            if (error != null) {
                val exception = assertThrows(Base32Exception::class.java) { Base32.decode(input) }
                assertEquals(error, exception.code.name, "input='$input'")
                continue
            }
            val expectedHex = vector.getValue("expectedHex").jsonPrimitive.content
            val expectedBase32 = vector.getValue("expectedBase32").jsonPrimitive.content
            val decoded = Base32.decode(input)
            assertArrayEquals(expectedHex.hexToBytes(), decoded, "decode '$input'")
            assertEquals(expectedBase32, Base32.encode(decoded), "encode '$input'")
            assertEquals(
                expectedBase32.trimEnd('='),
                Base32.encodeNoPadding(decoded),
                "encodeNoPadding '$input'",
            )
        }
    }

    @Test
    fun canonicalAlphabetAndRoundTrip() {
        val bytes = ByteArray(64) { (it * 37 + 11).toByte() }
        val padded = Base32.encode(bytes)
        assertEquals(0, padded.length % 8)
        assertArrayEquals(bytes, Base32.decode(padded))
        assertArrayEquals(bytes, Base32.decode(Base32.encodeNoPadding(bytes)))
        assertEquals(padded.trimEnd('='), Base32.encodeNoPadding(bytes))
    }

    @Test
    fun emptyInputIsEmptyBytes() {
        assertArrayEquals(ByteArray(0), Base32.decode(""))
        assertEquals("", Base32.encode(ByteArray(0)))
    }

    @Test
    fun tolerantInputProducesCanonicalOutput() {
        val bytes = Base32.decode("  mzxw-6ytb\toi  ")
        assertEquals("666f6f626172", bytes.toHex())
        assertEquals("MZXW6YTBOI======", Base32.encode(bytes))
    }

    @Test
    fun invalidCharactersAreReportedBeforeLength() {
        val exception = assertThrows(Base32Exception::class.java) { Base32.decode("MZXW6YTB!") }
        assertEquals(Base32Error.BASE32_INVALID_CHAR, exception.code)
    }

    @Test
    fun nonZeroTrailingBitsAreRejected() {
        for (input in listOf("MZXW6YT", "MZXW6YT=", "MZXW6YR")) {
            val exception = assertThrows(Base32Exception::class.java) { Base32.decode(input) }
            assertEquals(Base32Error.BASE32_INVALID_TRAILING_BITS, exception.code, input)
        }
    }

    @Test
    fun invalidPaddingIsRejected() {
        for (input in listOf("MZXW6YTB=", "MZXW6YT==", "MZXW6YQ==", "MZXW6YTB====", "========", "MZ=XW6YTB")) {
            val exception = assertThrows(Base32Exception::class.java) { Base32.decode(input) }
            assertEquals(Base32Error.BASE32_INVALID_PADDING, exception.code, input)
        }
    }

    @Test
    fun invalidLengthIsRejected() {
        for (input in listOf("A", "MZXW6Y")) {
            val exception = assertThrows(Base32Exception::class.java) { Base32.decode(input) }
            assertEquals(Base32Error.BASE32_INVALID_LENGTH, exception.code, input)
        }
    }
}
