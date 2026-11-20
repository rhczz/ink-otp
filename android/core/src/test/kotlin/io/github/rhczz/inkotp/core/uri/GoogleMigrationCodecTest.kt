package io.github.rhczz.inkotp.core.uri

import io.github.rhczz.inkotp.core.SpecVectors
import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.Base64
import org.junit.jupiter.api.assertThrows

class GoogleMigrationCodecTest {
    @TestFactory fun independentTransferVectors() =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/google-authenticator-v1.json")).bufferedReader().use {
            SpecVectors.json.parseToJsonElement(it.readText()).jsonObject.getValue("cases").jsonArray
        }.map { row ->
            val case = row.jsonObject
            dynamicTest(case.getValue("name").jsonPrimitive.content) {
                val input = case.getValue("uri").jsonPrimitive.content
                val expected = case.getValue("expected")
                if (expected == JsonNull) {
                    assertThrows<GoogleMigrationException> { GoogleMigrationCodec.decode(input) }
                } else {
                    val fields = expected.jsonObject
                    val page = GoogleMigrationCodec.decode(input)
                    try {
                        assertEquals(fields.getValue("version").jsonPrimitive.int, page.version)
                        assertEquals(fields.getValue("batchId").jsonPrimitive.int, page.batchId)
                        assertEquals(fields.getValue("batchSize").jsonPrimitive.int, page.total)
                        assertEquals(fields.getValue("batchIndex").jsonPrimitive.int, page.index)
                        val entries = fields.getValue("entries").jsonArray
                        assertEquals(entries.size, page.entries.size)
                        page.entries.zip(entries).forEach { (entry, raw) ->
                            val item = raw.jsonObject
                            assertEquals(item.getValue("issuer").jsonPrimitive.contentOrNull, entry.issuer)
                            assertEquals(item.getValue("accountName").jsonPrimitive.content, entry.accountName)
                            assertArrayEquals(Base64.getDecoder().decode(item.getValue("secretBase64").jsonPrimitive.content), entry.secret)
                            assertEquals(item.getValue("algorithm").jsonPrimitive.content, entry.algorithm.name)
                            assertEquals(item.getValue("digits").jsonPrimitive.int, entry.digits)
                            assertEquals(item.getValue("period").jsonPrimitive.intOrNull, entry.period)
                            assertEquals(item.getValue("counter").jsonPrimitive.longOrNull, entry.counter)
                            assertEquals(item.getValue("type").jsonPrimitive.content, entry.type.name)
                        }
                    } finally { page.clear() }
                }
            }
        }

    @Test fun pagesCanArriveOutOfOrderAndRepeat() {
        val items = (1..21).map { index ->
            TestVault.item(accountName = "sample-$index", sortIndex = index)
        }
        val uris = GoogleMigrationCodec.encode(items, batchId = -17)
        assertEquals(3, uris.size)
        assertTrue(uris.all { it.size <= 2000 })
        val batch = GoogleMigrationBatch()
        for (index in listOf(2, 0, 0)) {
            val status = batch.accept(GoogleMigrationCodec.decode(uris[index].decodeToString()))
            assertFalse(status.complete)
        }
        val status = batch.accept(GoogleMigrationCodec.decode(uris[1].decodeToString()))
        assertTrue(status.complete)
        assertEquals(items.map { it.accountName }, batch.takeEntries().map { it.accountName })
        assertEquals(null, batch.progress)
    }

    @Test fun mixedBatchAndDamagedInputDoNotAffectAlreadyScannedPages() {
        val items = (1..11).map { index -> TestVault.item(accountName = "sample-$index", sortIndex = index) }
        val primary = GoogleMigrationCodec.encode(items, batchId = 7)
        val other = GoogleMigrationCodec.encode(items, batchId = 9)
        val batch = GoogleMigrationBatch()
        batch.accept(GoogleMigrationCodec.decode(primary.first().decodeToString()))
        assertThrows<GoogleMigrationException> {
            batch.accept(GoogleMigrationCodec.decode(other.last().decodeToString()))
        }
        assertThrows<GoogleMigrationException> { GoogleMigrationCodec.decode("otpauth-migration://offline?data=CA") }
        assertEquals(1, batch.progress?.received)
        batch.clear()
        assertEquals(null, batch.progress)
    }

    @Test fun exportRequiresExactGoogleCompatibleParameters() {
        val compatible = TestVault.item(accountName = "counter", type = OtpType.HOTP, counter = 0L)
        val incompatible = TestVault.item(accountName = "eight", algorithm = OtpAlgorithm.SHA256, digits = 8)
        assertThrows<GoogleMigrationException> { GoogleMigrationCodec.encode(listOf(compatible, incompatible), 5) }
        assertEquals(null, GoogleMigrationCodec.exportIssue(compatible))
        val decoded = GoogleMigrationCodec.decode(GoogleMigrationCodec.encode(listOf(compatible), 5).single().decodeToString())
        assertEquals(OtpType.HOTP, decoded.entries.single().type)
        assertEquals(0L, decoded.entries.single().counter)
    }

    @Test fun conflictingPagesAreWipedAndDoNotReplaceTheOriginal() {
        val original = TestVault.item(accountName = "original")
        val replacement = TestVault.item(accountName = "replacement")
        val first = GoogleMigrationCodec.decode(GoogleMigrationCodec.encode(listOf(original), 7).single().decodeToString())
        val conflicting = GoogleMigrationCodec.decode(GoogleMigrationCodec.encode(listOf(replacement), 7).single().decodeToString())
        val batch = GoogleMigrationBatch()
        batch.accept(first)
        assertThrows<GoogleMigrationException> { batch.accept(conflicting) }
        assertTrue(conflicting.entries.single().secret.all { it == 0.toByte() })
        assertEquals("original", batch.takeEntries().single().accountName)
    }

    @Test fun exportedPagesSplitOnUriSizeAndRejectUnrepresentableNames() {
        val items = (1..10).map { index ->
            TestVault.item(
                issuer = "部署".repeat(60),
                accountName = "公开账号".repeat(48) + "$index",
                sortIndex = index,
            )
        }
        val pages = GoogleMigrationCodec.encode(items, Int.MAX_VALUE)
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.size <= 2000 })
        assertEquals(10, pages.sumOf { GoogleMigrationCodec.decode(it.decodeToString()).entries.size })
        assertTrue(GoogleMigrationCodec.exportIssue(TestVault.item(accountName = "GitHub:a")) != null)
        assertTrue(GoogleMigrationCodec.exportIssue(TestVault.item(issuer = null, accountName = "label:a")) != null)
    }

}
