package io.github.rhczz.inkotp.core.otp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OtpEngineExtraTest {

    private val secret = "12345678901234567890".toByteArray()

    @Test
    fun parameterOverloadsMatchPrimitiveCalls() {
        val parameters = OtpParameters(secret, OtpAlgorithm.SHA256, 8, 60)
        assertEquals(
            OtpEngine.hotp(secret, 7, OtpAlgorithm.SHA256, 8),
            OtpEngine.hotp(parameters, 7),
        )
        assertEquals(
            OtpEngine.totp(secret, 1_700_000_000, OtpAlgorithm.SHA256, 8, 60),
            OtpEngine.totp(parameters, 1_700_000_000),
        )
    }

    @Test
    fun validationHelpersReportExpectedCodes() {
        assertEquals(OtpError.SECRET_EMPTY, assertThrows<OtpException> { OtpEngine.validateSecret(ByteArray(0)) }.code)
        assertEquals(
            OtpError.SECRET_TOO_LONG,
            assertThrows<OtpException> { OtpEngine.validateSecret(ByteArray(256)) }.code,
        )
        assertEquals(OtpError.DIGITS_UNSUPPORTED, assertThrows<OtpException> { OtpEngine.validateDigits(7) }.code)
        assertEquals(OtpError.PERIOD_OUT_OF_RANGE, assertThrows<OtpException> { OtpEngine.validatePeriod(0) }.code)
        assertEquals(OtpError.PERIOD_OUT_OF_RANGE, assertThrows<OtpException> { OtpEngine.validatePeriod(301) }.code)
    }

    @Test
    fun errorPathsAreValidatedBeforeHmac() {
        assertEquals(
            OtpError.COUNTER_NEGATIVE,
            assertThrows<OtpException> { OtpEngine.hotp(secret, -1) }.code,
        )
        assertEquals(
            OtpError.TIME_OUT_OF_RANGE,
            assertThrows<OtpException> { OtpEngine.totp(secret, -1) }.code,
        )
        assertEquals(
            OtpError.DIGITS_UNSUPPORTED,
            assertThrows<OtpException> { OtpEngine.hotp(secret, 0, digits = 5) }.code,
        )
        assertEquals(
            OtpError.PERIOD_OUT_OF_RANGE,
            assertThrows<OtpException> { OtpEngine.totp(secret, 0, period = 0) }.code,
        )
    }

    @Test
    fun algorithmNamesAndDefaults() {
        assertEquals(OtpAlgorithm.SHA1, OtpAlgorithm.fromSpecName(null))
        assertEquals(OtpAlgorithm.SHA1, OtpAlgorithm.fromSpecName(""))
        assertEquals(OtpAlgorithm.SHA1, OtpAlgorithm.fromSpecName("sha1"))
        assertEquals(OtpAlgorithm.SHA256, OtpAlgorithm.fromSpecName("SHA256"))
        assertEquals(OtpAlgorithm.SHA512, OtpAlgorithm.fromSpecName("sha512"))
        assertEquals(null, OtpAlgorithm.fromSpecName("MD5"))
    }

    @Test
    fun parametersUseContentEquality() {
        val first = OtpParameters(secret.copyOf(), OtpAlgorithm.SHA1, 6, 30)
        val second = OtpParameters(secret.copyOf(), OtpAlgorithm.SHA1, 6, 30)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, second.copy(algorithm = OtpAlgorithm.SHA256))
    }
}
