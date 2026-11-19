package io.github.rhczz.inkotp.core

import io.github.rhczz.inkotp.core.backup.BackupCodec
import io.github.rhczz.inkotp.core.backup.BackupException
import io.github.rhczz.inkotp.core.interchange.InterchangeCodec
import io.github.rhczz.inkotp.core.interchange.InterchangeException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SharedConformanceTest {
    private fun fixture(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$path")) { "Missing public fixture $path" }.use { it.readBytes() }

    private val contract by lazy {
        SpecVectors.json.parseToJsonElement(fixture("conformance-cases.json").decodeToString()).jsonObject.also {
            assertEquals(1, it.getValue("version").jsonPrimitive.int)
        }
    }

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull

    @Test fun publicInterchangeConformance() {
        val cases = contract.getValue("interchange").jsonArray.map { it.jsonObject }
        val manifest = SpecVectors.json.parseToJsonElement(
            fixture("fixtures/interoperability/sources.json").decodeToString(),
        ).jsonObject.getValue("fixtures").jsonArray
        assertEquals(manifest.map { it.jsonObject.text("file") }.toSet(), cases.map { it.text("file") }.toSet())
        assertEquals(manifest.size, cases.size)
        for (row in cases) {
            val file = checkNotNull(row.text("file"))
            val bytes = fixture("fixtures/interoperability/$file")
            val expectedFormat = row.text("format")
            val password = row.text("password")
            assertEquals(expectedFormat, InterchangeCodec.detect(bytes).name, file)
            if (password != null) {
                assertEquals("PASSWORD_REQUIRED", assertThrows<InterchangeException> {
                    InterchangeCodec.decode(bytes)
                }.code.name, file)
                assertEquals("AUTHENTICATION_FAILED", assertThrows<InterchangeException> {
                    InterchangeCodec.decode(bytes, "incorrect-public-test".toCharArray())
                }.code.name, file)
            }
            val error = row.text("error")
            if (error != null) {
                val indexes = row.getValue("indexes").jsonArray.map { it.jsonPrimitive.int }
                val failure = assertThrows<InterchangeException> {
                    InterchangeCodec.decode(bytes, password?.toCharArray())
                }
                assertEquals(error, failure.code.name, file)
                assertEquals(indexes, failure.entryIndexes, file)
            } else {
                val decoded = InterchangeCodec.decode(bytes, password?.toCharArray())
                val warnings = row["warnings"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                assertEquals(expectedFormat, decoded.format.name, file)
                assertEquals(row.getValue("count").jsonPrimitive.int, decoded.model.items.size, file)
                assertTrue(decoded.warnings.map { it.code.name }.containsAll(warnings), file)
            }
        }
    }

    @Test fun publicLegacyBackupMutations() {
        val baseline = fixture("fixtures/backup-v1.2fab")
        for (value in contract.getValue("legacyBackupMutations").jsonArray) {
            val row = value.jsonObject
            val position = row.getValue("offset").jsonPrimitive.int
            val offset = if (position >= 0) position else baseline.size + position
            val mask = row.getValue("xor").jsonPrimitive.int
            val changed = baseline.copyOf()
            changed[offset] = (changed[offset].toInt() xor mask).toByte()
            val failure = assertThrows<BackupException> {
                BackupCodec.open(changed, TestVault.PASSWORD.toCharArray())
            }
            assertEquals(row.text("error"), failure.code.name, "mutation at $position")
        }
    }
}
