package io.github.rhczz.inkotp.core.util

import java.security.MessageDigest

object Hash {
    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String {
        val digest = sha256(bytes)
        val chars = "0123456789abcdef"
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(chars[value ushr 4]).append(chars[value and 0x0F])
        }
        return out.toString()
    }
}
