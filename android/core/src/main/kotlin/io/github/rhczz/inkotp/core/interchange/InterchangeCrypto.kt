package io.github.rhczz.inkotp.core.interchange

import io.github.rhczz.inkotp.core.crypto.AeadException
import io.github.rhczz.inkotp.core.crypto.JceAead
import io.github.rhczz.inkotp.core.crypto.SecretBytes
import io.github.rhczz.inkotp.core.crypto.SecureRandomSource
import kotlinx.serialization.json.*
import org.bouncycastle.crypto.generators.SCrypt
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object InterchangeCrypto {
    private val aead = JceAead()
    private val random = SecureRandomSource()
    private val empty = ByteArray(0)
    const val TWO_FAS_REFERENCE = "tRViSsLKzd86Hprh4ceC2OP7xazn4rrt4xhfEUbOjxLX8Rc3mkISXE0lWbmnWfggogbBJhtYgpK6fMl1D6mtsy92R3HkdGfwuXbzLebqVFJsR7IZ2w58t938iymwG4824igYy1wi6n2WDpO1Q1P69zwJGs2F5a1qP4MyIiDSD7NCV2OvidXQCBnDlGfmz0f1BQySRkkt4ryiJeCjD2o4QsveJ9uDBUn8ELyOrESv5R5DMDkD4iAF8TXU7KyoJujd"

    private data class Slot(val n: Int, val r: Int, val p: Int, val salt: ByteArray,
                            val ciphertext: ByteArray, val nonce: ByteArray)

    fun openAegis(root: JsonObject, password: CharArray): ByteArray {
        val header = root.obj("header")
        val slots = header.array("slots")
        if (slots.size > 32) fail(InterchangeError.UNSUPPORTED_KDF)
        val passwordSlots = slots.map { it.jsonObject }.filter { it.int("type") == 1 }
        if (passwordSlots.isEmpty()) fail(InterchangeError.UNSUPPORTED_ENCRYPTION)
        if (passwordSlots.size > 4) fail(InterchangeError.UNSUPPORTED_KDF)
        // Validate every advertised cost before running any KDF.
        val checked = passwordSlots.map { slot ->
            val n = slot.int("n"); val r = slot.int("r"); val p = slot.int("p")
            if (n < 2 || n and (n - 1) != 0 || r !in 1..32 || p !in 1..4 ||
                128L * n * r > 64L * 1024 * 1024 || n.toLong() * r * p > 524288) fail(InterchangeError.UNSUPPORTED_KDF)
            val params = slot.obj("key_params")
            Slot(n, r, p, hex(slot.text("salt"), 32),
                hex(slot.text("key"), 32) + hex(params.text("tag"), 16), hex(params.text("nonce"), 12))
        }
        val pw = utf8Password(password)
        var master: ByteArray? = null
        try {
            for (slot in checked) {
                val key = SCrypt.generate(pw, slot.salt, slot.n, slot.r, slot.p, 32)
                try {
                    master = try { aead.decrypt(key, slot.nonce, slot.ciphertext, empty) }
                    catch (_: AeadException) { null }
                    if (master != null) break
                } finally { SecretBytes.wipe(key) }
            }
            val actual = master ?: fail(InterchangeError.AUTHENTICATION_FAILED)
            if (actual.size != 32) fail(InterchangeError.MALFORMED_FILE)
            val params = header.obj("params")
            return decrypt(actual, hex(params.text("nonce"), 12),
                b64(root.text("db")) + hex(params.text("tag"), 16))
        } finally { SecretBytes.wipe(pw, master) }
    }

    fun sealAegis(db: ByteArray, password: CharArray): JsonObject {
        val salt = random.bytes(32)
        val pw = utf8Password(password)
        var master: ByteArray? = null
        var key: ByteArray? = null
        try {
            master = random.bytes(32)
            key = SCrypt.generate(pw, salt, 32768, 8, 1, 32)
            val nonce = random.bytes(12)
            val wrapped = aead.encrypt(key, nonce, master, empty)
            val dbNonce = random.bytes(12)
            val encrypted = aead.encrypt(master, dbNonce, db, empty)
            val slot = buildJsonObject {
                put("type", 1); put("uuid", UUID.randomUUID().toString()); put("key", toHex(wrapped.copyOfRange(0, 32)))
                put("key_params", params(nonce, wrapped.takeLast(16).toByteArray()))
                put("n", 32768); put("r", 8); put("p", 1); put("salt", toHex(salt))
            }
            return buildJsonObject {
                put("version", 1)
                put("header", buildJsonObject { put("slots", JsonArray(listOf(slot))); put("params", params(dbNonce, encrypted.takeLast(16).toByteArray())) })
                put("db", Base64.getEncoder().encodeToString(encrypted.copyOfRange(0, encrypted.size - 16)))
            }
        } finally { SecretBytes.wipe(pw, key, master) }
    }

    fun openTwoFas(root: JsonObject, password: CharArray): ByteArray {
        root.optionalText("reference")?.takeIf { it.isNotEmpty() }?.let {
            val reference = openTwoFasValue(it, password)
            try {
                if (!reference.contentEquals(TWO_FAS_REFERENCE.toByteArray(StandardCharsets.UTF_8))) fail(InterchangeError.MALFORMED_FILE)
            } finally { SecretBytes.wipe(reference) }
        }
        return openTwoFasValue(root.text("servicesEncrypted"), password)
    }

    private fun openTwoFasValue(value: String, password: CharArray): ByteArray {
        val parts = value.split(':')
        if (parts.size != 3) fail(InterchangeError.MALFORMED_FILE)
        val ciphertext = b64(parts[0])
        val salt = b64(parts[1])
        val iv = b64(parts[2])
        if (salt.size !in 16..512 || iv.size != 12 || ciphertext.size < 16) fail(InterchangeError.MALFORMED_FILE)
        val key = pbkdf(password, salt)
        return try { decrypt(key, iv, ciphertext) } finally { SecretBytes.wipe(key) }
    }

    fun sealTwoFas(services: ByteArray, password: CharArray): Pair<String, String> {
        val salt = random.bytes(256)
        val key = pbkdf(password, salt)
        return try {
            sealTwoFasValue(services, key, salt) to sealTwoFasValue(TWO_FAS_REFERENCE.toByteArray(StandardCharsets.UTF_8), key, salt)
        } finally { SecretBytes.wipe(key) }
    }

    private fun sealTwoFasValue(data: ByteArray, key: ByteArray, salt: ByteArray): String {
        val nonce = random.bytes(12)
        val encoder = Base64.getEncoder()
        return listOf(aead.encrypt(key, nonce, data, empty), salt, nonce).joinToString(":") { encoder.encodeToString(it) }
    }

    private fun pbkdf(password: CharArray, salt: ByteArray): ByteArray {
        requirePassword(password)
        val spec = PBEKeySpec(password, salt, 10000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun utf8Password(password: CharArray): ByteArray {
        requirePassword(password)
        // External passwords are used as supplied: do not NFC-normalize them.
        val buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        return try { ByteArray(buffer.remaining()).also { buffer.get(it) } }
        finally { if (buffer.hasArray()) buffer.array().fill(0) }
    }

    private fun requirePassword(password: CharArray) {
        if (password.isEmpty() || password.size > 1024) fail(InterchangeError.PASSWORD_REQUIRED)
    }
    private fun decrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray): ByteArray = try { aead.decrypt(key, nonce, ct, empty) }
    catch (_: AeadException) { fail(InterchangeError.AUTHENTICATION_FAILED) }
    private fun params(nonce: ByteArray, tag: ByteArray) = buildJsonObject { put("nonce", toHex(nonce)); put("tag", toHex(tag)) }
    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hex(value: String, size: Int): ByteArray {
        if (value.length != size * 2 || value.any { it.digitToIntOrNull(16) == null }) fail(InterchangeError.MALFORMED_FILE)
        return ByteArray(size) { ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte() }
    }
    private fun b64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' })
    }
    catch (_: IllegalArgumentException) { fail(InterchangeError.MALFORMED_FILE) }
}

internal fun fail(code: InterchangeError): Nothing = throw InterchangeException(code)
internal fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.takeIf { it.isString }?.content ?: fail(InterchangeError.MALFORMED_FILE)
internal fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.let { it.jsonPrimitive.takeIf { p -> p.isString }?.content ?: fail(InterchangeError.MALFORMED_FILE) }
internal fun JsonObject.obj(key: String): JsonObject = get(key)?.jsonObject ?: fail(InterchangeError.MALFORMED_FILE)
internal fun JsonObject.array(key: String): JsonArray = get(key)?.jsonArray ?: fail(InterchangeError.MALFORMED_FILE)
internal fun JsonObject.int(key: String): Int = get(key)?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: fail(InterchangeError.MALFORMED_FILE)
