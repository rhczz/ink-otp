package io.github.rhczz.inkotp.core.interchange

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.json.VaultJson
import io.github.rhczz.inkotp.core.model.VaultIcon
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSettings
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PeerInterchangeTest {
    @Test
    fun productionInterchangeAgreement() {
        val expected = publicExample()
        val peerPath = System.getenv("INKOTP_TEST_PEER_EXPORTS")
        if (peerPath == null) {
            check(System.getenv("INKOTP_TEST_PEER_REQUIRED") != "1") { "macOS exports are required" }
            writeAndroidExports(expected)
        } else {
            readMacOSExports(File(peerPath), expected)
        }
    }

    private fun publicExample(): VaultModel {
        val baseline = requireNotNull(javaClass.getResourceAsStream("/fixtures/backup-v1.expected.json"))
            .use { VaultJson.decode(it.readBytes()) }
        val counter = TestVault.item(
            id = "00000000-0000-4000-8000-000000000003",
            issuer = "Public Counter",
            accountName = "sample",
            secret = baseline.items.first().secret.copyOf(),
            type = OtpType.HOTP,
            counter = 10300,
            sortIndex = 3000,
            origin = "import",
            tags = listOf("public-fixture", "示例"),
            icon = VaultIcon("letter", "P", "#123456"),
            website = "https://example.com/inkotp-fixture",
        ).copy(createdAt = baseline.createdAt, updatedAt = baseline.updatedAt)
        return baseline.copy(
            settings = VaultSettings(lockTimeoutSeconds = 120, hideCodesByDefault = true, clipboardClearSeconds = 45),
            items = baseline.items + counter,
        )
    }

    private fun writeAndroidExports(expected: VaultModel) {
        val directory = File("build/interoperability")
        check(directory.isDirectory || directory.mkdirs()) { "Android export directory is unavailable" }
        File(directory, "expected.json").writeBytes(VaultJson.encode(expected))
        for (format in InterchangeFormat.entries) {
            val password = TestVault.PASSWORD.toCharArray()
            val exported = InterchangeCodec.encode(expected, format, password)
            assertTrue(password.all { it == '\u0000' }, "Exporter must consume its password")
            assertMatches(format, exported.bytes, expected)
            File(directory, filename("kotlin", format)).writeBytes(exported.bytes)
        }
    }

    private fun readMacOSExports(directory: File, expected: VaultModel) {
        val filenames = InterchangeFormat.entries.associateWith { filename("swift", it) }
        val entries = checkNotNull(directory.list()) { "macOS export directory is unavailable" }
        assertEquals(filenames.values.toSet() + "expected.json", entries.toSet(), "macOS export inventory")
        val claimedInput = VaultJson.decode(File(directory, "expected.json").readBytes())
        assertTrue(claimedInput == expected, "macOS producer input must match the independent public example")
        for ((format, filename) in filenames) {
            assertMatches(format, File(directory, filename).readBytes(), expected)
        }
    }

    private fun assertMatches(format: InterchangeFormat, bytes: ByteArray, expected: VaultModel) {
        assertEquals(format, InterchangeCodec.detect(bytes))
        val password = TestVault.PASSWORD.toCharArray().takeIf { format.requiresPassword }
        val decoded = InterchangeCodec.decode(bytes, password)
        assertEquals(format, decoded.format)
        if (format == InterchangeFormat.INKOTP_2FAB) {
            assertTrue(decoded.model == expected, "${format.name}: complete vault JSON changed")
        } else {
            val expectedProfiles = expected.items.map(::profile)
            val decodedProfiles = decoded.model.items.map(::profile)
            assertTrue(decodedProfiles == expectedProfiles, "${format.name}: OTP profiles or order changed")
        }
    }

    private fun filename(prefix: String, format: InterchangeFormat) = "$prefix-${format.name}.${format.extension}"

    private data class Profile(
        val type: OtpType,
        val issuer: String?,
        val account: String,
        val secret: List<Byte>,
        val algorithm: OtpAlgorithm,
        val digits: Int,
        val period: Int?,
        val counter: Long?,
    )

    private fun profile(item: VaultItem) = Profile(
        item.type, item.issuer, item.accountName, item.secret.toList(), item.algorithm,
        item.digits, item.period.takeIf { item.type == OtpType.TOTP }, item.counter,
    )
}
