package io.github.rhczz.inkotp.core.uri

import io.github.rhczz.inkotp.core.crypto.SecretBytes
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.util.TextNormalizer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

class GoogleMigrationException(message: String) : IllegalArgumentException(message)

@ConsistentCopyVisibility
data class GoogleMigrationPage internal constructor(
    val version: Int,
    val batchId: Int,
    val index: Int,
    val total: Int,
    val entries: List<OtpFields>,
) {
    fun clear() = entries.forEach { SecretBytes.wipe(it.secret) }
}

/** A batch owns its decoded seed arrays until the caller takes all entries or clears the batch. */
class GoogleMigrationBatch {
    data class Progress(val received: Int, val total: Int) {
        val complete get() = received == total
    }

    private val pages = sortedMapOf<Int, GoogleMigrationPage>()
    private var entryCount = 0
    val progress: Progress? get() = pages.values.firstOrNull()?.let { Progress(pages.size, it.total) }

    fun accept(page: GoogleMigrationPage): Progress = acceptAll(listOf(page))

    /** Validate all pages from a file before replacing the batch; consumes the supplied pages. */
    fun acceptAll(incoming: List<GoogleMigrationPage>): Progress {
        require(incoming.isNotEmpty())
        val accepted = pages.toSortedMap()
        var acceptedCount = entryCount
        try {
            for (page in incoming) {
                val first = accepted.values.firstOrNull()
                if (first != null && (first.version != page.version || first.batchId != page.batchId || first.total != page.total)) {
                    throw GoogleMigrationException("这张二维码属于另一批迁移，请继续扫描当前批次，或取消后重新开始。")
                }
                val duplicate = accepted[page.index]
                if (duplicate != null) {
                    if (duplicate != page) throw GoogleMigrationException("同一页二维码的账号内容不同，请核对来源后重试。")
                    continue
                }
                acceptedCount += page.entries.size
                if (acceptedCount > GoogleMigrationCodec.MAX_ENTRIES) {
                    throw GoogleMigrationException("这批迁移超过 500 个账号，请在来源应用分批导出。")
                }
                accepted[page.index] = page
            }
            pages.putAll(accepted)
            entryCount = acceptedCount
            return checkNotNull(progress)
        } finally {
            incoming.forEach { if (pages[it.index] !== it) it.clear() }
        }
    }

    fun takeEntries(): List<OtpFields> {
        check(progress?.complete == true)
        return pages.values.flatMap { it.entries }.also {
            pages.clear()
            entryCount = 0
        }
    }

    fun clear() {
        pages.values.forEach(GoogleMigrationPage::clear)
        pages.clear()
        entryCount = 0
    }
}

/** Version 1 offline migration QR protobuf. See spec/google-authenticator-transfer.md. */
object GoogleMigrationCodec {
    const val SCHEME = "otpauth-migration://"
    const val MAX_ENTRIES = 500
    private const val MAX_URI_INPUT = 16_384
    private const val MAX_QR_URI = 2_000
    private const val MAX_PAGE_ENTRIES = 10
    private const val PREFIX = "otpauth-migration://offline?data="
    private const val PAGE_METADATA_URI_BUDGET = 96

    fun decode(input: String): GoogleMigrationPage {
        if (input.length > MAX_URI_INPUT) invalid()
        val bytes = try {
            val uri = URI(input.trim())
            val query = uri.rawQuery ?: invalid()
            if (!uri.scheme.equals("otpauth-migration", ignoreCase = true) ||
                !uri.rawAuthority.equals("offline", ignoreCase = true) ||
                uri.rawPath !in setOf("", "/") || uri.rawFragment != null ||
                !query.startsWith("data=") || '&' in query) invalid()
            val data = URLDecoder.decode(query.removePrefix("data=").replace("+", "%2B"), "UTF-8")
            val base64 = if ('-' in data || '_' in data) Base64.getUrlDecoder() else Base64.getDecoder()
            base64.decode(data)
        } catch (_: Exception) {
            invalid()
        }
        val entries = mutableListOf<OtpFields>()
        var adopted = false
        try {
            val message = ProtoReader(bytes)
            val seen = mutableSetOf<Int>()
            var version = 0
            var total = 0
            var index = 0
            var batchId = 0
            while (!message.finished) {
                val tag = message.tag()
                if (tag != 10 && !seen.add(tag)) invalid()
                when (tag) {
                    10 -> {
                        if (entries.size == MAX_ENTRIES) invalid()
                        entries += decodeEntry(message.message())
                    }
                    16 -> version = message.int32()
                    24 -> total = message.int32()
                    32 -> index = message.int32()
                    40 -> batchId = message.int32()
                    else -> invalid()
                }
            }
            if (version !in 1..2) throw GoogleMigrationException("暂不支持这张 Google Authenticator 二维码的版本。")
            if (total !in 1..MAX_ENTRIES || index !in 0 until total || entries.isEmpty()) invalid()
            return GoogleMigrationPage(version, batchId, index, total, entries.toList()).also { adopted = true }
        } finally {
            SecretBytes.wipe(bytes)
            if (!adopted) entries.forEach { SecretBytes.wipe(it.secret) }
        }
    }

    private fun decodeEntry(message: ProtoReader): OtpFields {
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = 0
        var digits = 0
        var type = 0
        var counter = 0L
        val seen = mutableSetOf<Int>()
        var adopted = false
        try {
            while (!message.finished) {
                val tag = message.tag()
                if (!seen.add(tag)) invalid()
                when (tag) {
                    10 -> secret = message.bytes(limit = 255)
                    18 -> name = message.text(limit = 1536)
                    26 -> issuer = message.text(limit = 512)
                    32 -> algorithm = message.int32()
                    40 -> digits = message.int32()
                    48 -> type = message.int32()
                    56 -> counter = message.varint()
                    66 -> message.text(limit = 256) // Google v2 unique ID is source metadata.
                    else -> unsupported()
                }
            }
            val seed = secret?.takeIf { it.isNotEmpty() } ?: invalid()
            val otpAlgorithm = when (algorithm) {
                0, 1 -> OtpAlgorithm.SHA1
                2 -> OtpAlgorithm.SHA256
                3 -> OtpAlgorithm.SHA512
                else -> unsupported()
            }
            val otpDigits = when (digits) {
                0, 1 -> 6
                2 -> 8
                else -> unsupported()
            }
            val otpType = when (type) {
                1 -> OtpType.HOTP
                0, 2 -> OtpType.TOTP
                else -> unsupported()
            }
            if (counter < 0 || (otpType == OtpType.TOTP && counter != 0L)) unsupported()
            val sourceIssuer = TextNormalizer.normalize(issuer).value
            val normalizedName = TextNormalizer.normalize(name).value
            val prefix = normalizedName.substringBefore(':', "")
            val normalizedIssuer = sourceIssuer.ifBlank { prefix.trim() }
            val account = if (prefix.isNotBlank() && prefix.trim() == normalizedIssuer) normalizedName.substringAfter(':').trim()
                else normalizedName
            if (account.isBlank() || TextNormalizer.codePointLength(account) > 256 ||
                TextNormalizer.codePointLength(normalizedIssuer) > 128) unsupported()
            return OtpFields(otpType, normalizedIssuer.ifBlank { null }, account, seed, otpAlgorithm,
                otpDigits, 30.takeIf { otpType == OtpType.TOTP }, counter.takeIf { otpType == OtpType.HOTP },
                listOfNotNull(OtpauthWarning.CONTROL_CHARS_STRIPPED.takeIf {
                    normalizedName != name || sourceIssuer != issuer
                })).also { adopted = true }
        } finally {
            if (!adopted) SecretBytes.wipe(secret)
        }
    }

    fun exportIssue(item: VaultItem): String? = when {
        item.algorithm != OtpAlgorithm.SHA1 -> "Google Authenticator 兼容导出需要 SHA1"
        item.digits != 6 -> "Google Authenticator 兼容导出需要 6 位"
        item.type == OtpType.TOTP && item.period != 30 -> "此二维码格式仅支持 30 秒 TOTP 周期"
        item.type == OtpType.HOTP && (item.counter == null || item.counter < 0) -> "HOTP 计数器无效"
        item.accountName.substringBefore(':', "").trim().takeIf { it.isNotEmpty() }
            ?.let { item.issuer.isNullOrBlank() || it == item.issuer } == true ->
            "账号名称与服务名前缀无法区分"
        item.secret.size !in 1..255 -> "密钥长度不受支持"
        wireEntry(item).use { !fits(it) } -> "账号信息超过二维码容量"
        else -> null
    }

    /** Returned, writable URI buffers must be cleared by the QR presentation owner. */
    fun encode(items: List<VaultItem>, batchId: Int): List<ByteArray> {
        if (items.isEmpty() || items.size > MAX_ENTRIES) {
            throw GoogleMigrationException("单次迁移需要 1 至 500 个兼容账号，请分批导出。")
        }
        if (items.any { exportIssue(it) != null }) unsupported()
        val batches = mutableListOf<WipeableBytes>()
        val result = mutableListOf<ByteArray>()
        var adopted = false
        try {
            var page = WipeableBytes().also(batches::add)
            var count = 0
            for (item in items.sortedBy { it.sortIndex }) {
                wireEntry(item).use { entry ->
                    val previousSize = page.size()
                    page.append(entry)
                    if (count == MAX_PAGE_ENTRIES || !fits(page)) {
                        page.truncate(previousSize)
                        page = WipeableBytes().also(batches::add)
                        page.append(entry)
                        count = 0
                    }
                    count++
                }
            }
            batches.forEachIndexed { index, body ->
                body.field(16, 1)
                body.field(24, batches.size.toLong())
                body.field(32, index.toLong())
                body.field(40, batchId.toLong())
                result += uri(body)
            }
            adopted = true
            return result
        } finally {
            batches.forEach(WipeableBytes::close)
            if (!adopted) result.forEach { SecretBytes.wipe(it) }
        }
    }

    private fun wireEntry(item: VaultItem): WipeableBytes = WipeableBytes().apply {
        WipeableBytes().use { entry ->
            entry.field(10, item.secret)
            entry.field(18, item.accountName.encodeToByteArray())
            item.issuer?.takeIf { it.isNotBlank() }?.let { entry.field(26, it.encodeToByteArray()) }
            entry.field(32, 1)
            entry.field(40, 1)
            entry.field(48, if (item.type == OtpType.HOTP) 1 else 2)
            if (item.type == OtpType.HOTP) entry.field(56, item.counter!!)
            varint(10)
            varint(entry.size().toLong())
            append(entry)
        }
    }

    // Appending the four batch fields only changes the final Base64 quantum and its tail.
    private fun fits(body: WipeableBytes): Boolean = uri(body).let { uri ->
        try { uri.size + PAGE_METADATA_URI_BUDGET <= MAX_QR_URI }
        finally { SecretBytes.wipe(uri) }
    }

    private fun uri(body: WipeableBytes): ByteArray {
        val bytes = body.toByteArray()
        val data = try { Base64.getEncoder().encode(bytes) } finally { SecretBytes.wipe(bytes) }
        try {
            return WipeableBytes().use { result ->
                result.write(PREFIX.encodeToByteArray())
                val hex = "0123456789ABCDEF"
                for (byte in data) {
                    val value = byte.toInt()
                    if (value == '+'.code || value == '/'.code || value == '='.code) {
                        result.write('%'.code)
                        result.write(hex[value shr 4].code)
                        result.write(hex[value and 15].code)
                    } else result.write(value)
                }
                result.toByteArray()
            }
        } finally { SecretBytes.wipe(data) }
    }

    private class ProtoReader(private val bytes: ByteArray, private var position: Int = 0, private val end: Int = bytes.size) {
        val finished get() = position == end

        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (finished) invalid()
                val byte = bytes[position++].toInt() and 255
                if (shift == 63 && byte > 1) invalid()
                value = value or ((byte and 127).toLong() shl shift)
                if (byte < 128) return value
            }
            invalid()
        }

        fun int32(): Int {
            val value = varint()
            if (value !in Int.MIN_VALUE.toLong()..0xffff_ffffL) invalid()
            return value.toInt()
        }

        fun tag(): Int = int32().also { if (it <= 0) invalid() }

        private fun boundedEnd(limit: Int): Int {
            val length = varint()
            if (length < 0 || length > limit || length > end - position) invalid()
            return position + length.toInt()
        }

        fun message(): ProtoReader {
            val next = boundedEnd(MAX_URI_INPUT)
            return ProtoReader(bytes, position, next).also { position = next }
        }

        fun bytes(limit: Int): ByteArray {
            val next = boundedEnd(limit)
            return bytes.copyOfRange(position, next).also { position = next }
        }

        fun text(limit: Int): String {
            val next = boundedEnd(limit)
            return try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, position, next - position)).toString()
                    .also { position = next }
            } catch (_: Exception) { invalid() }
        }
    }

    private class WipeableBytes : ByteArrayOutputStream() {
        fun append(other: WipeableBytes) = write(other.buf, 0, other.count)
        fun truncate(length: Int) {
            buf.fill(0, length, count)
            count = length
        }

        fun field(tag: Long, value: Long) {
            varint(tag)
            varint(value)
        }

        fun field(tag: Long, value: ByteArray) {
            varint(tag)
            varint(value.size.toLong())
            write(value)
        }

        fun varint(value: Long) {
            var rest = value
            while (rest and -128L != 0L) {
                write(((rest and 127) or 128).toInt())
                rest = rest ushr 7
            }
            write(rest.toInt())
        }

        override fun close() {
            buf.fill(0)
            reset()
        }
    }

    private fun invalid(): Nothing = throw GoogleMigrationException("Google Authenticator 迁移二维码无效或已损坏。")

    private fun unsupported(): Nothing = throw GoogleMigrationException("迁移包含不支持的账号参数，尚未导入或导出任何账号。")
}
