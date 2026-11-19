package io.github.rhczz.inkotp.core.interchange

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.json.VaultJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class InterchangeCodecTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/interoperability/$name")!!.use { it.readBytes() }
    private fun code(block: () -> Unit) = assertThrows<InterchangeException> { block() }.code

    @Test fun detectsContentsRatherThanFileExtensions() {
        assertEquals(InterchangeFormat.AEGIS_PLAINTEXT, InterchangeCodec.detect(fixture("aegis_plain.json")))
        assertEquals(InterchangeFormat.AEGIS_ENCRYPTED, InterchangeCodec.detect(fixture("aegis_encrypted.json")))
        assertEquals(InterchangeFormat.TWOFAS_PLAINTEXT, InterchangeCodec.detect(fixture("2fas_authenticator_plain.2fas")))
        assertEquals(InterchangeFormat.TWOFAS_ENCRYPTED, InterchangeCodec.detect(fixture("2fas_authenticator_encrypted_v4.2fas")))
        assertEquals(InterchangeFormat.OTPAUTH_TEXT, InterchangeCodec.detect(fixture("plain.txt")))
    }

    @Test fun officialTwoFasSchema2PlainAndEncryptedMatchTheIndependentSourceExport() {
        val plain = InterchangeCodec.decode(fixture("2fas_authenticator_plain.2fas"))
        val password = "test".toCharArray()
        val encrypted = InterchangeCodec.decode(fixture("2fas_authenticator_encrypted.2fas"), password)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(4, plain.model.items.size)
        assertEquals(listOf("Mason", "Elijah", "James", "Mason"), plain.model.items.map { it.accountName })
        assertEquals(listOf("SHA1", "SHA512", "SHA1", "SHA512"), plain.model.items.map { it.algorithm.name })
        assertEquals(listOf(30, 50, 30, 30), plain.model.items.map { it.period })
        assertEquals(profiles(plain.model), profiles(encrypted.model))
    }

    @Test fun officialLegacyTwoFasUsesDocumentedTotpDefaults() {
        val decoded = InterchangeCodec.decode(fixture("2fas_authenticator.json"))
        assertEquals(6, decoded.model.items.size)
        assertTrue(decoded.model.items.all { it.type == OtpType.TOTP && it.digits == 6 && it.period == 30 })
    }

    @Test fun mixedOfficialAegisFilesAreRejectedInsteadOfDroppingSevenDigitAndSteamEntries() {
        for (file in listOf("aegis_plain.json", "aegis_encrypted.json")) {
            val failure = assertThrows<InterchangeException> { InterchangeCodec.decode(fixture(file), "test".toCharArray()) }
            assertEquals(InterchangeError.UNSUPPORTED_ENTRIES, failure.code)
            assertEquals(listOf(2, 5, 7), failure.entryIndexes)
        }
    }

    @Test fun mixedOfficialUriFileRejectsUnsupportedProfilesAtTheirOriginalLines() {
        val failure = assertThrows<InterchangeException> { InterchangeCodec.decode(fixture("plain.txt")) }
        assertEquals(InterchangeError.UNSUPPORTED_ENTRIES, failure.code)
        assertEquals(listOf(2, 5, 7), failure.entryIndexes)
    }

    @Test fun officialTwoFasV3AndV4DecryptButRejectUnsupportedProfilesExplicitly() {
        for ((suffix, indexes) in listOf("v3" to listOf(2, 5), "v4" to listOf(3, 5))) {
            for (kind in listOf("plain", "encrypted")) {
                val failure = assertThrows<InterchangeException> {
                    InterchangeCodec.decode(fixture("2fas_authenticator_${kind}_$suffix.2fas"), "test".toCharArray())
                }
                assertEquals(InterchangeError.UNSUPPORTED_ENTRIES, failure.code)
                assertEquals(indexes, failure.entryIndexes)
            }
        }
    }

    @Test fun compatibleSubsetOfOfficialAegisPreservesHotpCounterAndProfiles() {
        val source = Json.parseToJsonElement(fixture("aegis_plain.json").decodeToString()).jsonObject
        val db = source.getValue("db").jsonObject
        val selected = listOf(0, 2, 3, 5).map { db.getValue("entries").jsonArray[it] }
        val bytes = JsonObject(source + ("db" to JsonObject(db + ("entries" to JsonArray(selected))))).toString().toByteArray()
        val imported = InterchangeCodec.decode(bytes)
        assertEquals(4, imported.model.items.size)
        assertEquals(listOf(null, null, 1L, 10300L), imported.model.items.map { it.counter })
        assertEquals(listOf("Deno", "Airbnb", "Issuu", "WWE"), imported.model.items.map { it.issuer })
    }

    @Test fun unknownVersionsAndExcessiveScryptAreNotMisreportedAsWrongPasswords() {
        val source = Json.parseToJsonElement(fixture("aegis_encrypted.json").decodeToString()).jsonObject
        val header = source.getValue("header").jsonObject
        val slot = header.getValue("slots").jsonArray[0].jsonObject
        val oversized = JsonObject(source + ("header" to JsonObject(header +
            ("slots" to JsonArray(listOf(JsonObject(slot + ("n" to JsonPrimitive(1 shl 25))))))))).toString().toByteArray()
        assertEquals(InterchangeError.UNSUPPORTED_KDF, code { InterchangeCodec.decode(oversized, "test".toCharArray()) })
        val future = JsonObject(source + ("version" to JsonPrimitive(999))).toString().toByteArray()
        assertEquals(InterchangeError.UNSUPPORTED_VERSION, code { InterchangeCodec.decode(future, "test".toCharArray()) })
    }

    @Test fun authenticationFailuresAndMissingPasswordsAreExplicit() {
        for (file in listOf("aegis_encrypted.json", "2fas_authenticator_encrypted.2fas")) {
            assertEquals(InterchangeError.PASSWORD_REQUIRED, code { InterchangeCodec.decode(fixture(file)) })
            assertEquals(InterchangeError.AUTHENTICATION_FAILED, code { InterchangeCodec.decode(fixture(file), "incorrect".toCharArray()) })
        }
    }

    @Test fun uriTextPreservesUnicodeColonPlusAndHotpCounter() {
        val model = TestVault.model(TestVault.item(issuer = "服务:+", accountName = "账户:plus+😀"),
            TestVault.item(id = TestVault.ITEM_2, accountName = "counter", type = OtpType.HOTP, counter = 42, sortIndex = 2000))
        val export = InterchangeCodec.encode(model, InterchangeFormat.OTPAUTH_TEXT)
        assertTrue(export.warnings.isNotEmpty())
        assertEquals(profiles(model), profiles(InterchangeCodec.decode(export.bytes).model))
        assertTrue(export.bytes.decodeToString().contains("%2B"))
    }

    @Test fun malformedUriLineRejectsTheWholeTextWithItsLineNumber() {
        val valid = InterchangeCodec.encode(TestVault.model(), InterchangeFormat.OTPAUTH_TEXT).bytes.decodeToString().trimEnd()
        for (invalid in listOf("not an otp URI", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")) {
            val failure = assertThrows<InterchangeException> { InterchangeCodec.decode((valid + "\n" + invalid + "\n").toByteArray()) }
            assertEquals(listOf(2), failure.entryIndexes)
        }
    }

    @Test fun uriErrorsReferToOriginalLineNumbersIncludingLeadingBlankLinesAndBom() {
        val valid = InterchangeCodec.encode(TestVault.model(), InterchangeFormat.OTPAUTH_TEXT).bytes.decodeToString().trimEnd()
        val failure = assertThrows<InterchangeException> {
            InterchangeCodec.decode(("\uFEFF\n\n" + valid + "\ninvalid line\n").toByteArray())
        }
        assertEquals(listOf(4), failure.entryIndexes)
    }

    @Test fun externalEncryptedPasswordsAreNotSilentlyNfcNormalized() {
        val supplied = "cafe\u0301-public-test"
        val nfc = "caf\u00e9-public-test"
        for (format in listOf(InterchangeFormat.AEGIS_ENCRYPTED, InterchangeFormat.TWOFAS_ENCRYPTED)) {
            val ciphertext = InterchangeCodec.encode(TestVault.model(), format, supplied.toCharArray()).bytes
            assertEquals(InterchangeError.AUTHENTICATION_FAILED, code { InterchangeCodec.decode(ciphertext, nfc.toCharArray()) })
            assertEquals(profiles(TestVault.model()), profiles(InterchangeCodec.decode(ciphertext, supplied.toCharArray()).model))
        }
    }

    @Test fun twoFasOrderPositionTakesPrecedenceOverJsonArrayOrder() {
        val source = Json.parseToJsonElement(fixture("2fas_authenticator_plain.2fas").decodeToString()).jsonObject
        val reordered = source.array("services").mapIndexed { index, value ->
            JsonObject(value.jsonObject + ("order" to buildJsonObject { put("position", 3 - index) }))
        }
        val bytes = JsonObject(source + ("services" to JsonArray(reordered))).toString().toByteArray()
        assertEquals(listOf("WWE", "Issuu", "Airbnb", "Deno"), InterchangeCodec.decode(bytes).model.items.map { it.issuer })
    }

    @Test fun filesAndJsonDepthAreBoundedBeforeParsing() {
        assertEquals(InterchangeError.FILE_TOO_LARGE, code { InterchangeCodec.detect(ByteArray(12 * 1024 * 1024 + 1)) })
        assertEquals(InterchangeError.MALFORMED_FILE, code { InterchangeCodec.detect(("[".repeat(40) + "]".repeat(40)).toByteArray()) })
        assertEquals(InterchangeError.MALFORMED_FILE, code { InterchangeCodec.detect(byteArrayOf(0xC3.toByte(), 0x28)) })
    }

    @Test fun twoFasRejectsCountersItsOfficialIntegerFieldCannotRepresent() {
        val model = TestVault.model(TestVault.item(type = OtpType.HOTP, counter = Int.MAX_VALUE.toLong() + 1))
        assertEquals(InterchangeError.UNSUPPORTED_ENTRIES, code { InterchangeCodec.encode(model, InterchangeFormat.TWOFAS_PLAINTEXT) })
        val source = Json.parseToJsonElement(fixture("2fas_authenticator_plain_v4.2fas").decodeToString()).jsonObject
        val entry = source.array("services")[1].jsonObject
        val changed = JsonObject(entry + ("otp" to JsonObject(entry.obj("otp") + ("counter" to JsonPrimitive(Int.MAX_VALUE.toLong() + 1)))))
        val bytes = JsonObject(source + ("services" to JsonArray(listOf(changed)))).toString().toByteArray()
        assertEquals(InterchangeError.UNSUPPORTED_ENTRIES, code { InterchangeCodec.decode(bytes) })
        // Aegis and standard URI retain the supported signed-Long counter range.
        val uri = InterchangeCodec.encode(model, InterchangeFormat.OTPAUTH_TEXT)
        assertEquals(model.items[0].counter, InterchangeCodec.decode(uri.bytes).model.items[0].counter)
    }

    @Test fun invalidBase64IsNotSilentlyDiscardedBeforeAuthentication() {
        val source = Json.parseToJsonElement(fixture("2fas_authenticator_encrypted.2fas").decodeToString()).jsonObject
        val value = source.text("servicesEncrypted")
        val bytes = JsonObject(source + ("servicesEncrypted" to JsonPrimitive("#" + value))).toString().toByteArray()
        assertEquals(InterchangeError.MALFORMED_FILE, code { InterchangeCodec.decode(bytes, "test".toCharArray()) })
    }

    @Test fun jsonContainerCountIsLimitedEvenWhenTheFileIsSmallAndShallow() {
        val text = "{\"schemaVersion\":4,\"services\":[],\"extra\":[" + List(100001) { "{}" }.joinToString(",") + "]}"
        assertEquals(InterchangeError.MALFORMED_FILE, code { InterchangeCodec.detect(text.toByteArray()) })
    }

    @Test fun flatJsonScalarBombIsRejectedBeforeBuildingTheJsonTree() {
        val text = "{\"schemaVersion\":4,\"services\":[],\"extra\":[" + "0,".repeat(500001) + "0]}"
        assertEquals(InterchangeError.MALFORMED_FILE, code { InterchangeCodec.detect(text.toByteArray()) })
    }

    @Test fun manyBlankUriLinesDoNotChangeEntryCountOrLosePhysicalErrorLocations() {
        val uri = InterchangeCodec.encode(TestVault.model(), InterchangeFormat.OTPAUTH_TEXT).bytes.decodeToString().trimEnd()
        val text = uri + "\n".repeat(200001) + "invalid line\n"
        val failure = assertThrows<InterchangeException> { InterchangeCodec.decode(text.toByteArray()) }
        assertEquals(listOf(200002), failure.entryIndexes)
    }

    @Test fun unknownOtpExtensionsFailClosedWhileUnknownMetadataIsReported() {
        val source = Json.parseToJsonElement(fixture("2fas_authenticator_plain.2fas").decodeToString()).jsonObject
        val item = source.array("services")[0].jsonObject
        val changed = JsonObject(item + ("otp" to JsonObject(item.obj("otp") + ("futureOtpMode" to JsonPrimitive(true)))))
        val bad = JsonObject(source + ("services" to JsonArray(listOf(changed)))).toString().toByteArray()
        assertEquals(InterchangeError.UNSUPPORTED_ENTRIES, code { InterchangeCodec.decode(bad) })
        val metadata = JsonObject(source + ("futureMetadata" to JsonPrimitive(true))).toString().toByteArray()
        assertTrue(InterchangeCodec.decode(metadata).warnings.any { it.code == InterchangeWarningCode.METADATA_NOT_TRANSFERRED })
    }

    @Test fun currentEncryptedExportsAreIndependentlyInspectableWithoutSensitiveLogs() {
        val base = TestVault.twoItemModel()
        val model = base.copy(items = base.items + TestVault.item(
            id = "00000000-0000-4000-8000-000000000003", issuer = "Public Counter", accountName = "sample",
            type = OtpType.HOTP, counter = 10300, sortIndex = 3000,
        ))
        val folder = File("build/interoperability").apply { mkdirs() }
        File(folder, "expected.json").writeBytes(VaultJson.encode(model))
        for (format in InterchangeFormat.entries) {
            val password = TestVault.PASSWORD.toCharArray()
            val result = InterchangeCodec.encode(model, format, password)
            assertTrue(password.all { it == '\u0000' })
            assertEquals(profiles(model), profiles(InterchangeCodec.decode(result.bytes, TestVault.PASSWORD.toCharArray()).model))
            File(folder, "kotlin-${format.name}.${format.extension}").writeBytes(result.bytes)
        }
    }

    private fun profiles(model: io.github.rhczz.inkotp.core.model.VaultModel) = model.items.map {
        listOf(it.type.name, it.issuer.orEmpty(), it.accountName, it.secret.toList().toString(), it.algorithm.name,
            it.digits.toString(), it.period.toString(), it.counter?.toString().orEmpty())
    }
}
