package io.github.rhczz.inkotp.platform

import io.github.rhczz.inkotp.core.uri.GoogleMigrationCodec
import io.github.rhczz.inkotp.core.uri.GoogleMigrationException
import java.nio.charset.CharacterCodingException

/** Recognizes Google transfer pages in the existing, size-bounded document input. */
internal fun googleLinksFromBackup(bytes: ByteArray): List<String>? {
    var start = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() &&
        bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
    ) 3 else 0
    while (start < bytes.size && bytes[start].toInt() in ASCII_WHITESPACE) start++
    val scheme = GoogleMigrationCodec.SCHEME
    if (bytes.size - start >= scheme.length && scheme.indices.all {
            bytes[start + it].toInt().toChar().lowercaseChar() == scheme[it]
        }
    ) {
        val text = try {
            bytes.decodeToString(startIndex = start, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw GoogleMigrationException("迁移链接文件不是有效的 UTF-8 文本。")
        }
        return buildList {
            for ((index, raw) in text.lineSequence().withIndex()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (!line.startsWith(scheme, ignoreCase = true)) {
                    throw GoogleMigrationException("迁移文件第 ${index + 1} 行不是 Google Authenticator 迁移链接。")
                }
                if (size == GoogleMigrationCodec.MAX_ENTRIES) {
                    throw GoogleMigrationException("迁移文件最多可包含 500 行二维码链接。")
                }
                add(line)
            }
        }
    }

    val link = decodeQrImageIfImage(bytes) ?: return null
    if (!link.trimStart().startsWith(scheme, ignoreCase = true)) {
        throw GoogleMigrationException("图片中不是 Google Authenticator 迁移二维码。单账号请使用「添加验证码」。")
    }
    return listOf(link)
}

private val ASCII_WHITESPACE = setOf(9, 10, 11, 12, 13, 32)
