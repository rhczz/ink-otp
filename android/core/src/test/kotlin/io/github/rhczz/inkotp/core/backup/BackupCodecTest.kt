package io.github.rhczz.inkotp.core.backup

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.json.VaultJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class BackupCodecTest {

    private val password = TestVault.PASSWORD.toCharArray()

    @Test
    fun committedFixtureDecrypts() {
        val bytes = resource("/fixtures/backup-v1.2fab")
        val header = BackupCodec.inspect(bytes)
        assertEquals(KdfParams(65_536, 3, 2), header.kdfParams)
        assertEquals(1_410, header.ctLen)
        assertEquals(1_476, header.fileLen)

        val expected = VaultJson.decode(resource("/fixtures/backup-v1.expected.json"))
        val model = BackupCodec.open(bytes, password)
        assertEquals(expected, model)
        assertEquals(2, model.items.size)
        assertEquals("GitHub", model.items[0].issuer)
    }

    @Test
    fun wrongPasswordIsAuthenticationFailure() {
        val bytes = resource("/fixtures/backup-v1.2fab")
        val exception = assertThrows<BackupException> { BackupCodec.open(bytes, "wrong".toCharArray()) }
        assertEquals(BackupError.BAK_E_AUTH, exception.code)
    }

    @Test
    fun roundTripWithLowParameters() {
        val model = TestVault.twoItemModel()
        val bytes = BackupCodec.seal(model, password, KdfParams(8_192, 1, 1))
        assertEquals(model, BackupCodec.open(bytes, password))
    }

    @Test
    fun headerFailuresAreMapped() {
        val bytes = BackupCodec.seal(TestVault.model(), password, KdfParams(8_192, 1, 1))
        assertEquals(
            BackupError.BAK_E_MAGIC,
            assertThrows<BackupException> { BackupCodec.inspect(bytes.copyOf().also { it[0] = 0 }) }.code,
        )
        assertEquals(
            BackupError.BAK_E_VERSION,
            assertThrows<BackupException> { BackupCodec.inspect(bytes.copyOf().also { it[4] = 2 }) }.code,
        )
        assertEquals(
            BackupError.BAK_E_KDF_ALGO,
            assertThrows<BackupException> { BackupCodec.inspect(bytes.copyOf().also { it[5] = 2 }) }.code,
        )
        assertEquals(
            BackupError.BAK_E_FILE_TRUNCATED,
            assertThrows<BackupException> { BackupCodec.inspect(bytes.copyOf(bytes.size - 1)) }.code,
        )
        assertEquals(
            BackupError.BAK_E_TRAILING_DATA,
            assertThrows<BackupException> { BackupCodec.inspect(bytes + byteArrayOf(0)) }.code,
        )
    }

    @Test
    fun ciphertextTamperIsAuthenticationFailure() {
        val bytes = BackupCodec.seal(TestVault.model(), password, KdfParams(8_192, 1, 1))
        val tampered = bytes.copyOf()
        tampered[50] = (tampered[50].toInt() xor 1).toByte()
        assertEquals(
            BackupError.BAK_E_AUTH,
            assertThrows<BackupException> { BackupCodec.open(tampered, password) }.code,
        )
    }

    @Test
    fun kotlinBackupFixtureForPythonCrosscheck() {
        val model = TestVault.twoItemModel()
        val backup = BackupCodec.seal(model, password, KdfParams(8_192, 1, 1))
        val expectedJson = VaultJson.encodeToString(model)
        val directory = File("build/crosscheck")
        directory.mkdirs()
        File(directory, "kotlin-backup.2fab").writeBytes(backup)
        File(directory, "kotlin-backup.expected.json").writeText(expectedJson)
        assertTrue(File(directory, "kotlin-backup.2fab").length() > 0)
        assertTrue(File(directory, "kotlin-backup.expected.json").length() > 0)
    }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResource(path)) { "missing resource $path" }.readBytes()
}
