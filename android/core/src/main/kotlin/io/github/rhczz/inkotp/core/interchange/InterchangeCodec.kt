package io.github.rhczz.inkotp.core.interchange

import io.github.rhczz.inkotp.core.backup.BackupCodec
import io.github.rhczz.inkotp.core.backup.BackupError
import io.github.rhczz.inkotp.core.backup.BackupException
import io.github.rhczz.inkotp.core.base32.Base32
import io.github.rhczz.inkotp.core.crypto.SecretBytes
import io.github.rhczz.inkotp.core.json.VaultJson
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpEngine
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.uri.OtpUriResult
import io.github.rhczz.inkotp.core.uri.OtpauthParser
import io.github.rhczz.inkotp.core.util.TextNormalizer
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** Stateless byte conversion. The caller owns returned secrets and must erase them after use. */
object InterchangeCodec {
    const val MAX_FILE_BYTES = 12 * 1024 * 1024
    private const val MAX_ENTRIES = 10000
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun detect(bytes: ByteArray): InterchangeFormat = guarded {
        bound(bytes)
        if (bytes.size >= 4 && bytes[0] == 0x32.toByte() && bytes[1] == 0x46.toByte() &&
            bytes[2] == 0x41.toByte() && bytes[3] == 0x42.toByte()) return@guarded InterchangeFormat.INKOTP_2FAB
        val text = text(bytes).trim()
        if (text.startsWith("otpauth://", ignoreCase = true)) return@guarded InterchangeFormat.OTPAUTH_TEXT
        val root = parse(text) as? JsonObject ?: fail(InterchangeError.UNKNOWN_FORMAT)
        when {
            "db" in root && "version" in root -> if (root["db"] is JsonObject) InterchangeFormat.AEGIS_PLAINTEXT else InterchangeFormat.AEGIS_ENCRYPTED
            "schemaVersion" in root && ("services" in root || "servicesEncrypted" in root) ->
                if (root.optionalText("servicesEncrypted").isNullOrBlank() && root.optionalText("reference").isNullOrBlank())
                    InterchangeFormat.TWOFAS_PLAINTEXT else InterchangeFormat.TWOFAS_ENCRYPTED
            else -> fail(InterchangeError.UNKNOWN_FORMAT)
        }
    }

    fun decode(bytes: ByteArray, password: CharArray? = null): ImportedVault = try {
        guarded {
            val format = detect(bytes)
            val warnings = mutableListOf<InterchangeWarning>()
            if (format.requiresPassword && password == null) fail(InterchangeError.PASSWORD_REQUIRED)
            val model = when (format) {
                InterchangeFormat.INKOTP_2FAB -> BackupCodec.open(bytes, password!!)
                InterchangeFormat.OTPAUTH_TEXT -> decodeUris(text(bytes), warnings)
                InterchangeFormat.AEGIS_ENCRYPTED, InterchangeFormat.AEGIS_PLAINTEXT -> {
                    val root = parse(text(bytes)).jsonObject
                    if (root.int("version") != 1) fail(InterchangeError.UNSUPPORTED_VERSION)
                    val db = if (format.requiresPassword) {
                        val plain = InterchangeCrypto.openAegis(root, password!!)
                        try { parse(text(plain)).jsonObject } finally { SecretBytes.wipe(plain) }
                    } else root.obj("db")
                    if (db.int("version") !in 1..3) fail(InterchangeError.UNSUPPORTED_VERSION)
                    if (db.keys.any { it !in setOf("version", "entries", "groups") } ||
                        (db["groups"] as? JsonArray)?.isNotEmpty() == true ||
                        root.keys.any { it !in setOf("version", "header", "db") }) metadata(warnings)
                    decodeEntries(db.array("entries"), warnings) { item, index -> aegisItem(item, index, warnings) }
                }
                InterchangeFormat.TWOFAS_ENCRYPTED, InterchangeFormat.TWOFAS_PLAINTEXT -> {
                    val root = parse(text(bytes)).jsonObject
                    val version = root.int("schemaVersion")
                    if (version !in 1..4) fail(InterchangeError.UNSUPPORTED_VERSION)
                    if (version <= 2) warnings += InterchangeWarning(InterchangeWarningCode.LEGACY_DEFAULTS)
                    if ((root["groups"] as? JsonArray)?.isNotEmpty() == true || root.keys.any {
                        it !in setOf("schemaVersion", "services", "groups", "updatedAt", "appVersionCode", "appVersionName", "appOrigin", "account", "servicesEncrypted", "reference")
                    }) metadata(warnings)
                    val entries = if (format.requiresPassword) {
                        if ((root["services"] as? JsonArray)?.isNotEmpty() == true) fail(InterchangeError.MALFORMED_FILE)
                        val plain = InterchangeCrypto.openTwoFas(root, password!!)
                        try { parse(text(plain)).jsonArray } finally { SecretBytes.wipe(plain) }
                    } else root.array("services")
                    decodeEntries(entries, warnings) { item, index -> twoFasItem(item, index, warnings) }
                }
            }
            if (format.plaintext) warnings += InterchangeWarning(InterchangeWarningCode.PLAINTEXT_SECRETS)
            if (format != InterchangeFormat.INKOTP_2FAB) warnings += InterchangeWarning(InterchangeWarningCode.LOCAL_SETTINGS_NOT_TRANSFERRED)
            ImportedVault(model, format, warnings.distinctBy { it.code })
        }
    } finally { password?.fill('\u0000') }

    fun encode(model: VaultModel, format: InterchangeFormat, password: CharArray? = null): ExportedVault = try {
        guarded {
            if (format.requiresPassword && password == null) fail(InterchangeError.PASSWORD_REQUIRED)
            val validated = VaultJson.encode(model)
            SecretBytes.wipe(validated)
            if (format == InterchangeFormat.TWOFAS_ENCRYPTED || format == InterchangeFormat.TWOFAS_PLAINTEXT) {
                val incompatible = model.items.sortedBy { it.sortIndex }.mapIndexedNotNull { i, item ->
                    (i + 1).takeIf { item.counter != null && item.counter > Int.MAX_VALUE }
                }
                if (incompatible.isNotEmpty()) throw InterchangeException(InterchangeError.UNSUPPORTED_ENTRIES, incompatible)
            }
            val warnings = if (format == InterchangeFormat.INKOTP_2FAB) emptyList() else buildList {
                add(InterchangeWarning(InterchangeWarningCode.METADATA_NOT_TRANSFERRED, model.items.size))
                add(InterchangeWarning(InterchangeWarningCode.LOCAL_SETTINGS_NOT_TRANSFERRED))
                if (format.plaintext) add(InterchangeWarning(InterchangeWarningCode.PLAINTEXT_SECRETS))
            }
            val bytes = when (format) {
                InterchangeFormat.INKOTP_2FAB -> BackupCodec.seal(model, password!!)
                InterchangeFormat.OTPAUTH_TEXT -> model.items.sortedBy { it.sortIndex }.joinToString("\n", postfix = if (model.items.isEmpty()) "" else "\n", transform = ::uri).toByteArray(StandardCharsets.UTF_8)
                InterchangeFormat.AEGIS_ENCRYPTED, InterchangeFormat.AEGIS_PLAINTEXT -> {
                    val db = aegisDb(model)
                    if (format.requiresPassword) {
                        val plain = db.toString().toByteArray(StandardCharsets.UTF_8)
                        try { InterchangeCrypto.sealAegis(plain, password!!).toString().toByteArray(StandardCharsets.UTF_8) }
                        finally { SecretBytes.wipe(plain) }
                    } else buildJsonObject {
                        put("version", 1); put("header", buildJsonObject { put("slots", JsonNull); put("params", JsonNull) }); put("db", db)
                    }.toString().toByteArray(StandardCharsets.UTF_8)
                }
                InterchangeFormat.TWOFAS_ENCRYPTED, InterchangeFormat.TWOFAS_PLAINTEXT -> {
                    val entries = twoFasEntries(model)
                    val encrypted = if (format.requiresPassword) {
                        val plain = entries.toString().toByteArray(StandardCharsets.UTF_8)
                        try { InterchangeCrypto.sealTwoFas(plain, password!!) } finally { SecretBytes.wipe(plain) }
                    } else null
                    buildJsonObject {
                        put("schemaVersion", 4); put("updatedAt", Instant.now().toEpochMilli())
                        put("appOrigin", "android"); put("appVersionCode", 0); put("appVersionName", "InkOTP interchange")
                        put("services", if (encrypted != null) JsonArray(emptyList()) else entries); put("groups", JsonArray(emptyList()))
                        if (encrypted != null) { put("servicesEncrypted", encrypted.first); put("reference", encrypted.second) }
                    }.toString().toByteArray(StandardCharsets.UTF_8)
                }
            }
            try { bound(bytes) } catch (failure: InterchangeException) { SecretBytes.wipe(bytes); throw failure }
            ExportedVault(bytes, format, warnings)
        }
    } finally { password?.fill('\u0000') }

    private fun aegisItem(item: JsonObject, index: Int, warnings: MutableList<InterchangeWarning>): VaultItem {
        val info = item.obj("info")
        if (info.keys.any { it !in setOf("secret", "algo", "digits", "period", "counter") }) fail(InterchangeError.UNSUPPORTED_ENTRIES)
        if (item.keys.any { it !in setOf("type", "uuid", "name", "issuer", "info") }) metadata(warnings)
        return entry(item.text("type"), item.optionalText("issuer"), item.text("name"), info.text("secret"),
            info.optionalText("algo"), info.optInt("digits") ?: 6, info.optInt("period") ?: 30,
            info.optLong("counter"), index, warnings)
    }

    private fun twoFasItem(item: JsonObject, index: Int, warnings: MutableList<InterchangeWarning>): VaultItem {
        val otp = item.obj("otp")
        if (otp.keys.any { it !in setOf("link", "label", "account", "issuer", "digits", "period", "algorithm", "counter", "tokenType", "source") }) fail(InterchangeError.UNSUPPORTED_ENTRIES)
        if (item.keys.any { it !in setOf("name", "secret", "updatedAt", "otp", "order", "type", "serviceTypeID") }) metadata(warnings)
        val issuer = otp.optionalText("issuer")?.takeIf { it.isNotEmpty() } ?: item.optionalText("name")
        if (item.optionalText("name") != issuer) metadata(warnings)
        val account = otp.optionalText("account")?.takeIf { it.isNotEmpty() } ?: otp.optionalText("label")?.takeIf { it.isNotEmpty() } ?: item.text("name")
        if ((otp.optLong("counter") ?: 0) > Int.MAX_VALUE) fail(InterchangeError.UNSUPPORTED_ENTRIES)
        val position = (item["order"] as? JsonObject)?.optInt("position") ?: index
        if (position < 0) fail(InterchangeError.UNSUPPORTED_ENTRIES)
        return entry(otp.optionalText("tokenType") ?: "TOTP", issuer, account, item.text("secret"),
            otp.optionalText("algorithm"), otp.optInt("digits") ?: 6, otp.optInt("period") ?: 30,
            otp.optLong("counter"), index, warnings).copy(sortIndex = position)
    }

    private fun entry(type: String, issuer: String?, account: String, secret: String, algorithm: String?, digits: Int,
                      period: Int, counter: Long?, index: Int, warnings: MutableList<InterchangeWarning>): VaultItem {
        if (secret.length > 2048) fail(InterchangeError.UNSUPPORTED_ENTRIES)
        val bytes = Base32.decode(secret)
        var adopted = false
        try {
            val kind = when (type.lowercase()) { "totp" -> OtpType.TOTP; "hotp" -> OtpType.HOTP; else -> fail(InterchangeError.UNSUPPORTED_ENTRIES) }
            val algo = OtpAlgorithm.fromSpecName(algorithm) ?: fail(InterchangeError.UNSUPPORTED_ENTRIES)
            OtpEngine.validateSecret(bytes); OtpEngine.validateDigits(digits); OtpEngine.validatePeriod(period)
            if ((kind == OtpType.HOTP && (counter == null || counter < 0)) || (kind == OtpType.TOTP && counter != null)) fail(InterchangeError.UNSUPPORTED_ENTRIES)
            val normalizedIssuer = issuer?.let { normalize(it, 128, warnings) }?.takeIf { it.isNotEmpty() }
            val normalizedAccount = normalize(account, 256, warnings)
            if (normalizedAccount.isEmpty()) fail(InterchangeError.UNSUPPORTED_ENTRIES)
            val now = Instant.now().toString()
            return VaultItem(UUID.randomUUID().toString(), kind, normalizedIssuer, normalizedAccount, bytes,
                algo, digits, period, counter, sortIndex = (index + 1) * 1000, origin = "import",
                createdAt = now, updatedAt = now).also { adopted = true }
        } finally { if (!adopted) SecretBytes.wipe(bytes) }
    }

    private fun decodeEntries(entries: JsonArray, warnings: MutableList<InterchangeWarning>,
                              convert: (JsonObject, Int) -> VaultItem): VaultModel {
        if (entries.size > MAX_ENTRIES) fail(InterchangeError.TOO_MANY_ENTRIES)
        val converted = mutableListOf<VaultItem>()
        val errors = mutableListOf<Int>()
        entries.forEachIndexed { index, value ->
            try { converted += convert(value.jsonObject, index) }
            catch (_: Exception) { errors += index + 1 }
        }
        if (errors.isNotEmpty()) {
            converted.forEach { SecretBytes.wipe(it.secret) }
            throw InterchangeException(InterchangeError.UNSUPPORTED_ENTRIES, errors)
        }
        return model(converted.sortedBy { it.sortIndex }.mapIndexed { index, item -> item.copy(sortIndex = (index + 1) * 1000) })
    }

    private fun decodeUris(text: String, warnings: MutableList<InterchangeWarning>): VaultModel {
        val entries = mutableListOf<VaultItem>()
        val errors = mutableListOf<Int>()
        var nonblank = 0
        // Do not materialize millions of empty lines into a List on a small heap.
        text.lineSequence().forEachIndexed { index, line ->
            if (line.isNotBlank()) {
                if (++nonblank > MAX_ENTRIES) {
                    entries.forEach { SecretBytes.wipe(it.secret) }
                    fail(InterchangeError.TOO_MANY_ENTRIES)
                }
                var secret: ByteArray? = null
                try {
                    if (line.length > 16384) fail(InterchangeError.UNSUPPORTED_ENTRIES)
                    val input = line.trim()
                    // Reject bare secrets before the parser allocates a secret array.
                    if (!input.startsWith("otpauth://", ignoreCase = true)) fail(InterchangeError.UNSUPPORTED_ENTRIES)
                    val parsed = OtpauthParser.parse(input) as? OtpUriResult ?: fail(InterchangeError.UNSUPPORTED_ENTRIES)
                    secret = parsed.fields.secret
                    val f = parsed.fields
                    if (f.warnings.isNotEmpty()) warnings += InterchangeWarning(InterchangeWarningCode.SOURCE_NORMALIZED)
                    entries += entry(f.type.name, f.issuer, f.accountName.orEmpty(), Base32.encodeNoPadding(f.secret),
                        f.algorithm.name, f.digits, f.period ?: 30, f.counter, entries.size, warnings)
                } catch (_: Exception) { errors += index + 1 }
                finally { SecretBytes.wipe(secret) }
            }
        }
        if (errors.isNotEmpty()) {
            entries.forEach { SecretBytes.wipe(it.secret) }
            throw InterchangeException(InterchangeError.UNSUPPORTED_ENTRIES, errors)
        }
        return model(entries)
    }

    private fun model(items: List<VaultItem>): VaultModel {
        val now = Instant.now().toString()
        return VaultModel(vaultId = UUID.randomUUID().toString(), createdAt = now, updatedAt = now, items = items)
    }

    private fun aegisDb(model: VaultModel) = buildJsonObject {
        put("version", 3); put("groups", JsonArray(emptyList()))
        put("entries", JsonArray(model.items.sortedBy { it.sortIndex }.map { item -> buildJsonObject {
            put("type", item.type.name.lowercase()); put("uuid", item.id); put("name", item.accountName); put("issuer", item.issuer.orEmpty())
            put("note", ""); put("favorite", false); put("icon", JsonNull); put("icon_mime", JsonNull); put("icon_hash", JsonNull)
            put("groups", JsonArray(emptyList())); put("info", buildJsonObject {
                put("secret", Base32.encodeNoPadding(item.secret)); put("algo", item.algorithm.name); put("digits", item.digits)
                if (item.type == OtpType.HOTP) put("counter", item.counter!!) else put("period", item.period)
            })
        } }))
    }

    private fun twoFasEntries(model: VaultModel) = JsonArray(model.items.sortedBy { it.sortIndex }.mapIndexed { index, item -> buildJsonObject {
        put("name", item.issuer ?: item.accountName); put("secret", Base32.encodeNoPadding(item.secret)); put("updatedAt", Instant.parse(item.updatedAt).toEpochMilli())
        put("serviceTypeID", JsonNull); put("groupId", JsonNull); put("badge", JsonNull); put("icon", JsonNull)
        put("order", buildJsonObject { put("position", index) }); put("otp", buildJsonObject {
            put("link", uri(item)); put("label", item.accountName); put("account", item.accountName)
            put("issuer", item.issuer?.let(::JsonPrimitive) ?: JsonNull); put("digits", item.digits); put("period", item.period)
            put("algorithm", item.algorithm.name); put("counter", item.counter?.let(::JsonPrimitive) ?: JsonNull)
            put("tokenType", item.type.name); put("source", "Manual")
        })
    } })

    private fun uri(item: VaultItem): String {
        val issuer = item.issuer?.takeIf { it.isNotEmpty() }
        val label = if (issuer != null) component(issuer) + ":" + component(item.accountName) else component(item.accountName)
        val query = linkedMapOf("secret" to Base32.encodeNoPadding(item.secret), "algorithm" to item.algorithm.name, "digits" to item.digits.toString())
        if (issuer != null) query["issuer"] = issuer
        if (item.type == OtpType.HOTP) query["counter"] = item.counter!!.toString() else query["period"] = item.period.toString()
        return "otpauth://${item.type.name.lowercase()}/$label?" + query.entries.joinToString("&") { component(it.key) + "=" + component(it.value) }
    }

    private fun component(value: String): String = buildString {
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val c = byte.toInt() and 255
            if (c in 65..90 || c in 97..122 || c in 48..57 || c in listOf(45, 46, 95, 126)) append(c.toChar())
            else { append('%'); append("0123456789ABCDEF"[c ushr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }

    private fun normalize(value: String, max: Int, warnings: MutableList<InterchangeWarning>): String {
        val result = TextNormalizer.normalize(value).value
        if (result != value) warnings += InterchangeWarning(InterchangeWarningCode.SOURCE_NORMALIZED)
        if (TextNormalizer.codePointLength(result) > max) fail(InterchangeError.UNSUPPORTED_ENTRIES)
        return result
    }
    private fun metadata(warnings: MutableList<InterchangeWarning>) { warnings += InterchangeWarning(InterchangeWarningCode.METADATA_NOT_TRANSFERRED) }
    private fun JsonObject.optInt(key: String): Int? = get(key)?.takeUnless { it is JsonNull }?.let { if (it.jsonPrimitive.isString) fail(InterchangeError.MALFORMED_FILE); it.jsonPrimitive.intOrNull ?: fail(InterchangeError.MALFORMED_FILE) }
    private fun JsonObject.optLong(key: String): Long? = get(key)?.takeUnless { it is JsonNull }?.let { if (it.jsonPrimitive.isString) fail(InterchangeError.MALFORMED_FILE); it.jsonPrimitive.longOrNull ?: fail(InterchangeError.MALFORMED_FILE) }
    private fun bound(bytes: ByteArray) { if (bytes.size > MAX_FILE_BYTES) fail(InterchangeError.FILE_TOO_LARGE) }
    private fun text(bytes: ByteArray): String {
        bound(bytes)
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }
    private fun parse(text: String): JsonElement {
        var depth = 0; var containers = 0; var separators = 0; var string = false; var escape = false
        for (c in text) {
            if (string) { if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') string = false }
            else when (c) {
                '"' -> string = true
                '{', '[' -> if (++depth > 32 || ++containers > 100000) fail(InterchangeError.MALFORMED_FILE)
                '}', ']' -> depth--
                ',', ':' -> if (++separators > 500000) fail(InterchangeError.MALFORMED_FILE)
            }
        }
        return json.parseToJsonElement(text)
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (failure: InterchangeException) { throw failure }
    catch (failure: BackupException) {
        fail(when (failure.code) {
            BackupError.BAK_E_AUTH -> InterchangeError.AUTHENTICATION_FAILED
            BackupError.BAK_E_VERSION -> InterchangeError.UNSUPPORTED_VERSION
            BackupError.BAK_E_KDF_PARAMS, BackupError.BAK_E_KDF_ALGO -> InterchangeError.UNSUPPORTED_KDF
            else -> InterchangeError.MALFORMED_FILE
        })
    } catch (_: Exception) { fail(InterchangeError.MALFORMED_FILE) }
}
