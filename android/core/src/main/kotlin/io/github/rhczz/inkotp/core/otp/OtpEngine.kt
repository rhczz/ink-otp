package io.github.rhczz.inkotp.core.otp

enum class OtpAlgorithm(val macName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512"),
    ;

    companion object {
        fun fromSpecName(value: String?): OtpAlgorithm? = when (value?.uppercase()) {
            null, "" -> SHA1
            "SHA1" -> SHA1
            "SHA256" -> SHA256
            "SHA512" -> SHA512
            else -> null
        }
    }
}

enum class OtpError {
    DIGITS_UNSUPPORTED,
    PERIOD_OUT_OF_RANGE,
    ALGORITHM_UNSUPPORTED,
    SECRET_EMPTY,
    SECRET_TOO_LONG,
    COUNTER_NEGATIVE,
    COUNTER_OVERFLOW,
    TIME_OUT_OF_RANGE,
    KIND_UNSUPPORTED,
}

class OtpException(
    val code: OtpError,
) : IllegalArgumentException(code.name)

data class OtpParameters(
    val secret: ByteArray,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtpParameters) return false
        return secret.contentEquals(other.secret) &&
            algorithm == other.algorithm &&
            digits == other.digits &&
            period == other.period
    }

    override fun hashCode(): Int {
        var result = secret.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + digits
        result = 31 * result + period
        return result
    }
}

data class OtpCode(
    val value: String,
    val remainingSeconds: Int,
    val counter: Long,
)

/**
 * RFC 4226 HOTP / RFC 6238 TOTP.
 *
 * Inputs are validated before any HMAC is computed. The counter is encoded as
 * an unsigned 64-bit big-endian value; the supported Kotlin range is
 * 0..Long.MAX_VALUE (the spec's u64 arithmetic is covered by the boundary
 * tests via the test helper).
 */
object OtpEngine {

    private val POWERS_OF_TEN = longArrayOf(
        1L,
        10L,
        100L,
        1_000L,
        10_000L,
        100_000L,
        1_000_000L,
        10_000_000L,
        100_000_000L,
    )

    fun hotp(
        secret: ByteArray,
        counter: Long,
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
        digits: Int = 6,
    ): String {
        validateSecret(secret)
        validateDigits(digits)
        if (counter < 0) throw OtpException(OtpError.COUNTER_NEGATIVE)

        val message = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            message[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }

        val mac = javax.crypto.Mac.getInstance(algorithm.macName)
        mac.init(javax.crypto.spec.SecretKeySpec(secret, algorithm.macName))
        val digest = mac.doFinal(message)

        val offset = digest[digest.size - 1].toInt() and 0x0F
        val binary = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        val code = binary.toLong() % POWERS_OF_TEN[digits]
        return code.toString().padStart(digits, '0')
    }

    fun totp(
        secret: ByteArray,
        epochSeconds: Long,
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
        digits: Int = 6,
        period: Int = 30,
    ): OtpCode {
        validateSecret(secret)
        validateDigits(digits)
        validatePeriod(period)
        if (epochSeconds < 0) throw OtpException(OtpError.TIME_OUT_OF_RANGE)

        val counter = epochSeconds / period
        val remaining = (period - (epochSeconds % period)).toInt()
        return OtpCode(
            value = hotp(secret, counter, algorithm, digits),
            remainingSeconds = if (remaining == 0) period else remaining,
            counter = counter,
        )
    }

    fun hotp(parameters: OtpParameters, counter: Long): String =
        hotp(parameters.secret, counter, parameters.algorithm, parameters.digits)

    fun totp(parameters: OtpParameters, epochSeconds: Long): OtpCode =
        totp(parameters.secret, epochSeconds, parameters.algorithm, parameters.digits, parameters.period)

    fun validateSecret(secret: ByteArray) {
        if (secret.isEmpty()) throw OtpException(OtpError.SECRET_EMPTY)
        if (secret.size > 255) throw OtpException(OtpError.SECRET_TOO_LONG)
    }

    fun validateDigits(digits: Int) {
        if (digits != 6 && digits != 8) throw OtpException(OtpError.DIGITS_UNSUPPORTED)
    }

    fun validatePeriod(period: Int) {
        if (period < 1 || period > 300) throw OtpException(OtpError.PERIOD_OUT_OF_RANGE)
    }
}
