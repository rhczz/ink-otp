package io.github.rhczz.inkotp.core.util

import java.text.Normalizer

data class NormalizedText(
    val value: String,
    val strippedControls: Boolean,
)

object TextNormalizer {

    /**
     * Unicode NFC, then drop C0 (U+0000..U+001F) and C1 (U+007F..U+009F) controls.
     * The caller decides whether stripping is a warning or an error.
     */
    fun normalize(input: String): NormalizedText {
        val nfc = Normalizer.normalize(input, Normalizer.Form.NFC)
        val out = StringBuilder(nfc.length)
        var stripped = false
        var index = 0
        while (index < nfc.length) {
            val codePoint = nfc.codePointAt(index)
            if (codePoint in 0x00..0x1F || codePoint in 0x7F..0x9F) {
                stripped = true
            } else {
                out.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return NormalizedText(out.toString(), stripped)
    }

    fun codePointLength(value: String): Int = value.codePointCount(0, value.length)
}
