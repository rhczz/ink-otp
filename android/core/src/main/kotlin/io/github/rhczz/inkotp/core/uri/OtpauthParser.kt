package io.github.rhczz.inkotp.core.uri

import io.github.rhczz.inkotp.core.base32.Base32
import io.github.rhczz.inkotp.core.base32.Base32Exception
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.util.TextNormalizer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets

enum class OtpauthError {
    SCHEME_UNSUPPORTED,
    HOST_UNSUPPORTED,
    URI_MALFORMED,
    LABEL_EMPTY,
    LABEL_ACCOUNT_EMPTY,
    LABEL_ISSUER_TOO_LONG,
    LABEL_ACCOUNT_TOO_LONG,
    ISSUER_TOO_LONG,
    SECRET_MISSING,
    SECRET_EMPTY,
    SECRET_INVALID_BASE32,
    SECRET_TOO_LONG,
    ALGORITHM_UNSUPPORTED,
    DIGITS_UNSUPPORTED,
    PERIOD_OUT_OF_RANGE,
    COUNTER_INVALID,
    COUNTER_NEGATIVE,
    COUNTER_OVERFLOW,
}

class OtpauthException(
    val code: OtpauthError,
) : IllegalArgumentException(code.name)

object OtpauthWarning {
    const val ISSUER_MISMATCH = "ISSUER_MISMATCH"
    const val CONTROL_CHARS_STRIPPED = "CONTROL_CHARS_STRIPPED"
}

data class OtpFields(
    val type: OtpType,
    val issuer: String?,
    val accountName: String?,
    val secret: ByteArray,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val period: Int?,
    val counter: Long?,
    val warnings: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtpFields) return false
        return type == other.type &&
            issuer == other.issuer &&
            accountName == other.accountName &&
            secret.contentEquals(other.secret) &&
            algorithm == other.algorithm &&
            digits == other.digits &&
            period == other.period &&
            counter == other.counter &&
            warnings == other.warnings
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (issuer?.hashCode() ?: 0)
        result = 31 * result + (accountName?.hashCode() ?: 0)
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + digits
        result = 31 * result + (period ?: 0)
        result = 31 * result + (counter?.hashCode() ?: 0)
        result = 31 * result + warnings.hashCode()
        return result
    }
}

sealed interface OtpauthResult

data class OtpUriResult(val fields: OtpFields) : OtpauthResult

data class BareSecretResult(val fields: OtpFields) : OtpauthResult {
    val needsUserInput: List<String> = listOf("issuer", "accountName")
}

data class MigrationResult(
    val host: String,
    val dataBase64: String,
) : OtpauthResult

/**
 * `otpauth://` parser per spec/otpauth-cases.json.
 *
 * The label is split on the first **literal** ':' before percent-decoding, so
 * `%3A` remains part of the field content. Query `issuer` wins over the label
 * prefix and mismatches are reported as a warning, not an error.
 */
object OtpauthParser {

    private const val MAX_ISSUER = 128
    private const val MAX_ACCOUNT = 256
    private const val MAX_SECRET = 255

    fun parse(input: String): OtpauthResult {
        if (!input.contains("://")) {
            return parseBareSecret(input)
        }

        val uri = try {
            URI(input)
        } catch (_: URISyntaxException) {
            throw OtpauthException(OtpauthError.URI_MALFORMED)
        }

        val scheme = uri.scheme?.lowercase() ?: throw OtpauthException(OtpauthError.SCHEME_UNSUPPORTED)
        if (scheme == "otpauth-migration") {
            val host = uri.host?.lowercase() ?: throw OtpauthException(OtpauthError.HOST_UNSUPPORTED)
            val query = parseQuery(uri.rawQuery)
            val data = query["data"] ?: throw OtpauthException(OtpauthError.URI_MALFORMED)
            if (data.isEmpty()) throw OtpauthException(OtpauthError.URI_MALFORMED)
            return MigrationResult(host = host, dataBase64 = data)
        }
        if (scheme != "otpauth") throw OtpauthException(OtpauthError.SCHEME_UNSUPPORTED)

        val host = uri.host?.lowercase() ?: throw OtpauthException(OtpauthError.HOST_UNSUPPORTED)
        val type = when (host) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> throw OtpauthException(OtpauthError.HOST_UNSUPPORTED)
        }

        val rawPath = uri.rawPath ?: throw OtpauthException(OtpauthError.LABEL_EMPTY)
        val rawLabel = rawPath.removePrefix("/")
        if (rawLabel.isEmpty()) throw OtpauthException(OtpauthError.LABEL_EMPTY)
        val colon = rawLabel.indexOf(':')
        val rawIssuer = if (colon >= 0) rawLabel.substring(0, colon) else null
        val rawAccount = if (colon >= 0) rawLabel.substring(colon + 1) else rawLabel
        if (rawAccount.isEmpty()) throw OtpauthException(OtpauthError.LABEL_ACCOUNT_EMPTY)

        var controlStripped = false
        val labelIssuer = rawIssuer?.takeIf { it.isNotEmpty() }?.let {
            val decoded = decodePercent(it, plusAsSpace = false)
            val normalized = TextNormalizer.normalize(decoded)
            controlStripped = controlStripped || normalized.strippedControls
            if (TextNormalizer.codePointLength(normalized.value) > MAX_ISSUER) {
                throw OtpauthException(OtpauthError.LABEL_ISSUER_TOO_LONG)
            }
            normalized.value
        }
        val account = decodePercent(rawAccount, plusAsSpace = false).let {
            val normalized = TextNormalizer.normalize(it)
            controlStripped = controlStripped || normalized.strippedControls
            if (normalized.value.isEmpty()) throw OtpauthException(OtpauthError.LABEL_ACCOUNT_EMPTY)
            if (TextNormalizer.codePointLength(normalized.value) > MAX_ACCOUNT) {
                throw OtpauthException(OtpauthError.LABEL_ACCOUNT_TOO_LONG)
            }
            normalized.value
        }

        val query = parseQuery(uri.rawQuery)
        val secretText = query["secret"] ?: throw OtpauthException(OtpauthError.SECRET_MISSING)
        if (secretText.isEmpty()) throw OtpauthException(OtpauthError.SECRET_EMPTY)
        val secret = try {
            Base32.decode(secretText)
        } catch (_: Base32Exception) {
            throw OtpauthException(OtpauthError.SECRET_INVALID_BASE32)
        }
        if (secret.isEmpty()) throw OtpauthException(OtpauthError.SECRET_EMPTY)
        if (secret.size > MAX_SECRET) throw OtpauthException(OtpauthError.SECRET_TOO_LONG)

        val algorithm = query["algorithm"]?.takeIf { it.isNotEmpty() }?.let {
            OtpAlgorithm.fromSpecName(it) ?: throw OtpauthException(OtpauthError.ALGORITHM_UNSUPPORTED)
        } ?: OtpAlgorithm.SHA1

        val digits = query["digits"]?.let { raw ->
            val parsed = raw.toIntOrNull() ?: throw OtpauthException(OtpauthError.DIGITS_UNSUPPORTED)
            if (parsed != 6 && parsed != 8) throw OtpauthException(OtpauthError.DIGITS_UNSUPPORTED)
            parsed
        } ?: 6

        val warnings = mutableListOf<String>()
        val queryIssuer = query["issuer"]?.takeIf { it.isNotEmpty() }?.let {
            val normalized = TextNormalizer.normalize(it)
            controlStripped = controlStripped || normalized.strippedControls
            if (TextNormalizer.codePointLength(normalized.value) > MAX_ISSUER) {
                throw OtpauthException(OtpauthError.ISSUER_TOO_LONG)
            }
            normalized.value
        }
        val issuer = when {
            queryIssuer != null -> {
                if (!labelIssuer.isNullOrEmpty() && labelIssuer != queryIssuer) {
                    warnings += OtpauthWarning.ISSUER_MISMATCH
                }
                queryIssuer
            }
            !labelIssuer.isNullOrEmpty() -> labelIssuer
            else -> null
        }

        if (controlStripped) warnings += OtpauthWarning.CONTROL_CHARS_STRIPPED

        return when (type) {
            OtpType.TOTP -> {
                val period = query["period"]?.let { raw ->
                    raw.toIntOrNull() ?: throw OtpauthException(OtpauthError.PERIOD_OUT_OF_RANGE)
                } ?: 30
                if (period !in 1..300) throw OtpauthException(OtpauthError.PERIOD_OUT_OF_RANGE)
                OtpUriResult(
                    OtpFields(
                        type = type,
                        issuer = issuer,
                        accountName = account,
                        secret = secret,
                        algorithm = algorithm,
                        digits = digits,
                        period = period,
                        counter = null,
                        warnings = warnings,
                    ),
                )
            }
            OtpType.HOTP -> {
                val counter = parseCounter(query["counter"])
                OtpUriResult(
                    OtpFields(
                        type = type,
                        issuer = issuer,
                        accountName = account,
                        secret = secret,
                        algorithm = algorithm,
                        digits = digits,
                        period = null,
                        counter = counter,
                        warnings = warnings,
                    ),
                )
            }
        }
    }

    private fun parseBareSecret(input: String): OtpauthResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw OtpauthException(OtpauthError.URI_MALFORMED)
        val secret = try {
            Base32.decode(trimmed)
        } catch (_: Base32Exception) {
            throw OtpauthException(OtpauthError.URI_MALFORMED)
        }
        if (secret.isEmpty()) throw OtpauthException(OtpauthError.SECRET_EMPTY)
        if (secret.size > MAX_SECRET) throw OtpauthException(OtpauthError.SECRET_TOO_LONG)
        return BareSecretResult(
            OtpFields(
                type = OtpType.TOTP,
                issuer = null,
                accountName = null,
                secret = secret,
                algorithm = OtpAlgorithm.SHA1,
                digits = 6,
                period = 30,
                counter = null,
                warnings = emptyList(),
            ),
        )
    }

    private fun parseCounter(raw: String?): Long {
        if (raw == null) return 0L
        if (raw.isEmpty()) throw OtpauthException(OtpauthError.COUNTER_INVALID)
        if (raw.startsWith('-')) throw OtpauthException(OtpauthError.COUNTER_NEGATIVE)
        if (raw.any { !it.isDigit() }) throw OtpauthException(OtpauthError.COUNTER_INVALID)
        return raw.toLongOrNull() ?: throw OtpauthException(OtpauthError.COUNTER_OVERFLOW)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) continue
            val separator = part.indexOf('=')
            val rawKey = if (separator >= 0) part.substring(0, separator) else part
            val rawValue = if (separator >= 0) part.substring(separator + 1) else ""
            val key = decodePercent(rawKey, plusAsSpace = true)
            if (key.isEmpty()) continue
            val value = decodePercent(rawValue, plusAsSpace = true)
            result.putIfAbsent(key, value)
        }
        return result
    }

    private fun decodePercent(raw: String, plusAsSpace: Boolean): String {
        val bytes = ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            when {
                c == '%' -> {
                    if (index + 2 >= raw.length) throw OtpauthException(OtpauthError.URI_MALFORMED)
                    val high = hexValue(raw[index + 1])
                    val low = hexValue(raw[index + 2])
                    bytes.write((high shl 4) or low)
                    index += 3
                }
                plusAsSpace && c == '+' -> {
                    bytes.write(' '.code)
                    index++
                }
                else -> {
                    val end = if (Character.isHighSurrogate(c) && index + 1 < raw.length) index + 2 else index + 1
                    bytes.write(raw.substring(index, end).toByteArray(StandardCharsets.UTF_8))
                    index = end
                }
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw OtpauthException(OtpauthError.URI_MALFORMED)
    }
}

