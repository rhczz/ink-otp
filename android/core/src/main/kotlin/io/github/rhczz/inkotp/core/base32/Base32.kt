package io.github.rhczz.inkotp.core.base32

enum class Base32Error {
    BASE32_INVALID_CHAR,
    BASE32_INVALID_LENGTH,
    BASE32_INVALID_PADDING,
    BASE32_INVALID_TRAILING_BITS,
}

class Base32Exception(
    val code: Base32Error,
) : IllegalArgumentException(code.name)

/**
 * RFC 4648 Base32, tolerant on decode, canonical on encode.
 *
 * Decode accepts lowercase, ASCII whitespace and hyphens, and omitted padding.
 * When padding is present it must be canonical; unused trailing bits must be 0.
 * Encode defaults to the RFC-padded form; use [encodeNoPadding] for the
 * canonical export form required by the design doc (§5.4).
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val VALUES: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(bytes: ByteArray, padding: Boolean = true): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        if (padding) {
            while (out.length % 8 != 0) out.append('=')
        }
        return out.toString()
    }

    fun encodeNoPadding(bytes: ByteArray): String = encode(bytes, padding = false)

    fun decode(input: String): ByteArray {
        // Tolerant normalization: drop ASCII whitespace and hyphens, uppercase.
        val cleaned = StringBuilder(input.length)
        for (c in input) {
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '-' -> Unit
                else -> cleaned.append(c.uppercaseChar())
            }
        }
        val text = cleaned.toString()
        if (text.isEmpty()) return ByteArray(0)

        var firstPad = -1
        for (i in text.indices) {
            val c = text[i]
            if (c == '=') {
                if (firstPad < 0) firstPad = i
                continue
            }
            if (firstPad >= 0) {
                // Padding already started; a data character after it is malformed.
                throw Base32Exception(Base32Error.BASE32_INVALID_PADDING)
            }
            if (c.code >= VALUES.size || VALUES[c.code] < 0) {
                throw Base32Exception(Base32Error.BASE32_INVALID_CHAR)
            }
        }

        val dataLength = if (firstPad >= 0) firstPad else text.length
        val padCount = text.length - dataLength
        if (padCount > 0) {
            if (dataLength == 0) throw Base32Exception(Base32Error.BASE32_INVALID_PADDING)
            val expectedPad = when (dataLength % 8) {
                2 -> 6
                4 -> 4
                5 -> 3
                7 -> 1
                else -> throw Base32Exception(Base32Error.BASE32_INVALID_PADDING)
            }
            if (padCount != expectedPad) {
                throw Base32Exception(Base32Error.BASE32_INVALID_PADDING)
            }
        } else {
            if (dataLength % 8 !in setOf(0, 2, 4, 5, 7)) {
                throw Base32Exception(Base32Error.BASE32_INVALID_LENGTH)
            }
        }

        val out = ByteArray(dataLength * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (i in 0 until dataLength) {
            buffer = (buffer shl 5) or VALUES[text[i].code]
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[index++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) {
            throw Base32Exception(Base32Error.BASE32_INVALID_TRAILING_BITS)
        }
        return out
    }
}
