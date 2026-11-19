package io.github.rhczz.inkotp.core.interchange

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.backup.BackupCodec
import io.github.rhczz.inkotp.core.json.VaultJson
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SwiftContractTest {
    @Test
    fun swiftGeneratedBackupsOpenWithAndroidProductionCodecs() {
        val directory = System.getenv("INKOTP_IOS_CONTRACT_DIR")?.let(::File) ?: return
        val expected = PublicInterchangeContract.publicExample()
        val native = File(directory, "crosscheck")
        val backup = File(native, "swift-backup.2fab").readBytes()
        val nativeSource = VaultJson.decode(File(native, "swift-backup.expected.json").readBytes())
        assertTrue(nativeSource == expected, "iOS native backup input differs from the independent example")
        assertTrue(BackupCodec.open(backup, TestVault.PASSWORD.toCharArray()) == expected, "iOS native backup changed the vault")

        PublicInterchangeContract.assertSwiftExports(File(directory, "interoperability"), expected, platform = "iOS")
    }
}
