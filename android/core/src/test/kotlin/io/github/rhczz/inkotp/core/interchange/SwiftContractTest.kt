package io.github.rhczz.inkotp.core.interchange

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.backup.BackupCodec
import io.github.rhczz.inkotp.core.json.VaultJson
import io.github.rhczz.inkotp.core.model.VaultItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class SwiftContractTest {
    @Test
    fun swiftGeneratedBackupsOpenWithAndroidProductionCodecs() {
        val directory = System.getenv("INKOTP_IOS_CONTRACT_DIR")?.let(::File) ?: return
        val native = File(directory, "crosscheck")
        val backup = File(native, "swift-backup.2fab").readBytes()
        val expected = VaultJson.decode(File(native, "swift-backup.expected.json").readBytes())
        assertEquals(expected, BackupCodec.open(backup, TestVault.PASSWORD.toCharArray()))

        val interchange = File(directory, "interoperability")
        val source = VaultJson.decode(File(interchange, "expected.json").readBytes())
        for (format in InterchangeFormat.entries) {
            val data = File(interchange, "swift-${format.name}.${format.extension}").readBytes()
            assertEquals(format, InterchangeCodec.detect(data))
            val items = InterchangeCodec.decode(data, TestVault.PASSWORD.toCharArray()).model.items
            assertEquals(source.items.sortedBy { it.sortIndex }.map(::profile), items.sortedBy { it.sortIndex }.map(::profile), format.name)
        }
    }

    private fun profile(item: VaultItem) = listOf(
        item.type, item.issuer, item.accountName, item.secret.toList(), item.algorithm,
        item.digits, item.period.takeIf { item.counter == null }, item.counter,
    )
}
