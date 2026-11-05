package io.github.rhczz.inkotp.core.uri

import io.github.rhczz.inkotp.core.SpecVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class OtpauthParserTest {

    @Test
    fun specCases() {
        val cases = run {
            val text = checkNotNull(javaClass.getResource("/otpauth-cases.json")) {
                "spec/otpauth-cases.json is missing from the test classpath"
            }.readText()
            SpecVectors.json.parseToJsonElement(text).jsonArray
        }
        assertEquals(40, cases.size)

        for (element in cases) {
            val case = element.jsonObject
            val name = case.str("name")!!
            val input = case.str("input")!!
            val accept = case["accept"]!!.jsonPrimitive.boolean
            val expectedError = case["error"]?.jsonPrimitive?.contentOrNull
            if (!accept) {
                val exception = assertThrows(OtpauthException::class.java) { OtpauthParser.parse(input) }
                assertEquals(expectedError, exception.code.name, "case=$name")
                continue
            }

            val expected = case["expected"]!!.jsonObject
            when (expected.str("kind")) {
                "migration" -> {
                    val result = OtpauthParser.parse(input)
                    assertTrue(result is MigrationResult, "case=$name")
                    result as MigrationResult
                    assertEquals(expected.str("host"), result.host, "case=$name")
                    assertEquals(expected.str("dataBase64"), result.dataBase64, "case=$name")
                }
                "bare-secret" -> {
                    val result = OtpauthParser.parse(input)
                    assertTrue(result is BareSecretResult, "case=$name")
                    result as BareSecretResult
                    assertEquals(
                        expected["needsUserInput"]!!.jsonArray.map { it.jsonPrimitive.content },
                        result.needsUserInput,
                        "case=$name",
                    )
                    assertFields(expected, result.fields, name)
                }
                else -> {
                    val result = OtpauthParser.parse(input)
                    assertTrue(result is OtpUriResult, "case=$name")
                    assertFields(expected, (result as OtpUriResult).fields, name)
                }
            }
        }
    }

    private fun assertFields(expected: JsonObject, fields: OtpFields, name: String) {
        assertEquals(expected.str("type"), fields.type.name.lowercase(), "type case=$name")
        assertEquals(expected.str("issuer"), fields.issuer, "issuer case=$name")
        assertEquals(expected.str("accountName"), fields.accountName, "account case=$name")
        assertEquals(
            expected.str("secretBase64"),
            Base64.getEncoder().encodeToString(fields.secret),
            "secret case=$name",
        )
        assertEquals(expected.str("algorithm"), fields.algorithm.name, "algorithm case=$name")
        assertEquals(expected.int("digits"), fields.digits, "digits case=$name")
        assertEquals(expected.int("period"), fields.period, "period case=$name")
        assertEquals(expected.long("counter"), fields.counter, "counter case=$name")
        assertEquals(expected.stringList("warnings"), fields.warnings, "warnings case=$name")
    }

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? = str(name)?.toIntOrNull()

    private fun JsonObject.long(name: String): Long? = str(name)?.toLongOrNull()

    private fun JsonObject.stringList(name: String): List<String> {
        val array = this[name] as? JsonArray ?: return emptyList()
        return array.map { it.jsonPrimitive.content }
    }
}
