package io.github.rhczz.inkotp.core.otp

import io.github.rhczz.inkotp.core.SpecVectors
import io.github.rhczz.inkotp.core.base32.Base32
import io.github.rhczz.inkotp.core.hexToBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OtpEngineTest {

    @Test
    fun rfc6238TotpVectors() {
        val vectors = SpecVectors.array("totp")
        assertEquals(18, vectors.size)
        for (element in vectors) {
            val vector = element.jsonObject
            val time = vector.getValue("time").jsonPrimitive.content.toLong()
            val algorithm = OtpAlgorithm.valueOf(vector.getValue("algorithm").jsonPrimitive.content)
            val digits = vector.getValue("digits").jsonPrimitive.content.toInt()
            val secret = Base32.decode(vector.getValue("secretBase32").jsonPrimitive.content)
            val period = vector["period"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30
            val expected = vector.getValue("expected").jsonPrimitive.content
            assertEquals(expected, OtpEngine.totp(secret, time, algorithm, digits, period).value, "T=$time ${algorithm.name}")
        }
    }

    @Test
    fun rfc4226HotpVectors() {
        val vectors = SpecVectors.array("hotp")
        assertEquals(10, vectors.size)
        for (element in vectors) {
            val vector = element.jsonObject
            val counter = vector.getValue("counter").jsonPrimitive.content.toLong()
            val algorithm = OtpAlgorithm.valueOf(vector.getValue("algorithm").jsonPrimitive.content)
            val digits = vector.getValue("digits").jsonPrimitive.content.toInt()
            val secret = Base32.decode(vector.getValue("secretBase32").jsonPrimitive.content)
            val expected = vector.getValue("expected").jsonPrimitive.content
            assertEquals(expected, OtpEngine.hotp(secret, counter, algorithm, digits), "counter=$counter")
        }
    }

    @Test
    fun randomTotpVectors() {
        for (element in SpecVectors.array("random_totp")) {
            val vector = element.jsonObject
            val secret = Base32.decode(vector.getValue("secretBase32").jsonPrimitive.content)
            val time = vector.getValue("time").jsonPrimitive.content.toLong()
            val algorithm = OtpAlgorithm.valueOf(vector.getValue("algorithm").jsonPrimitive.content)
            val digits = vector.getValue("digits").jsonPrimitive.content.toInt()
            val period = vector.getValue("period").jsonPrimitive.content.toInt()
            val expected = vector.getValue("expected").jsonPrimitive.content
            assertEquals(
                expected,
                OtpEngine.totp(secret, time, algorithm, digits, period).value,
                "random T=$time ${algorithm.name} d=$digits p=$period",
            )
        }
    }

    @Test
    fun boundaryVectors() {
        for (element in SpecVectors.array("boundary")) {
            val vector = element.jsonObject
            val expectedError = vector.getValue("expectedError").jsonPrimitive.content
            val request = vector.getValue("request").jsonObject
            val exception = assertThrows(IllegalArgumentException::class.java) {
                evaluateBoundaryRequest(request)
            }
            assertEquals(expectedError, (exception as? OtpException)?.code?.name, request.toString())
        }
    }

    @Test
    fun remainingSecondsUsesWholeWindow() {
        val secret = ByteArray(20) { it.toByte() }
        assertEquals(30, OtpEngine.totp(secret, 0).remainingSeconds)
        assertEquals(29, OtpEngine.totp(secret, 1).remainingSeconds)
        assertEquals(1, OtpEngine.totp(secret, 29).remainingSeconds)
        assertEquals(30, OtpEngine.totp(secret, 30).remainingSeconds)
    }

    @Test
    fun hotpIsDeterministicAndSixDigits() {
        val secret = "12345678901234567890".toByteArray()
        val low = OtpEngine.hotp(secret, 0)
        val high = OtpEngine.hotp(secret, 1L shl 32)
        assertEquals(6, low.length)
        assertEquals(6, high.length)
        assertEquals(low, OtpEngine.hotp(secret, 0))
    }

    private fun evaluateBoundaryRequest(request: JsonObject) {
        val kind = request.getValue("kind").jsonPrimitive.content
        val secret = request.getValue("secretHex").jsonPrimitive.content.hexToBytes()
        val algorithmName = request.getValue("algorithm").jsonPrimitive.content
        val algorithm = OtpAlgorithm.fromSpecName(algorithmName)
            ?: throw OtpException(OtpError.ALGORITHM_UNSUPPORTED)
        val digits = request.getValue("digits").jsonPrimitive.content.toInt()
        when (kind) {
            "totp" -> {
                val timeText = request.getValue("time").jsonPrimitive.content
                val time = timeText.toLongOrNull() ?: throw OtpException(OtpError.TIME_OUT_OF_RANGE)
                val period = request.getValue("period").jsonPrimitive.content.toInt()
                OtpEngine.totp(secret, time, algorithm, digits, period)
            }
            "hotp" -> {
                val counterText = request.getValue("counter").jsonPrimitive.content
                if (counterText.startsWith('-')) throw OtpException(OtpError.COUNTER_NEGATIVE)
                if (counterText.any { !it.isDigit() }) throw OtpException(OtpError.COUNTER_OVERFLOW)
                val counter = counterText.toLongOrNull() ?: throw OtpException(OtpError.COUNTER_OVERFLOW)
                OtpEngine.hotp(secret, counter, algorithm, digits)
            }
            else -> throw OtpException(OtpError.KIND_UNSUPPORTED)
        }
    }
}
