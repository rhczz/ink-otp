package io.github.rhczz.inkotp.core.backup

import io.github.rhczz.inkotp.core.TestVault
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupMergeTest {

    private val secretA = "12345678901234567890".toByteArray()
    private val secretB = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toByteArray()
    private val secretC = "ZYXWVUTSRQPONMLKJIHGFEDCBA654321".toByteArray()

    private fun item(
        id: String,
        issuer: String,
        account: String,
        secret: ByteArray,
        sortIndex: Int,
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    ): VaultItem = TestVault.item(
        id = id,
        issuer = issuer,
        accountName = account,
        secret = secret,
        sortIndex = sortIndex,
        algorithm = algorithm,
    )

    private val localA = item(TestVault.ITEM_1, "GitHub", "alice", secretA, 1000)
    private val localB = item(TestVault.ITEM_2, "GitLab", "bob", secretB, 2000)
    private val remoteA = item("11111111-1111-4111-8111-111111111111", "github", "ALICE", secretA, 500, OtpAlgorithm.SHA256)
    private val remoteB = item("22222222-2222-4222-8222-222222222222", "gitlab", "BOB", secretB, 600)
    private val remoteC = item("33333333-3333-4333-8333-333333333333", "Example", "carol", secretC, 700)

    @Test
    fun keepLocalKeepsExistingAndAppendsNew() {
        val result = BackupMerge.merge(listOf(localA, localB), listOf(remoteA, remoteB, remoteC))
        assertEquals(2, result.conflicts.size)
        assertEquals(3, result.items.size)
        assertEquals(localA, result.items[0])
        assertEquals(localB, result.items[1])
        assertEquals(remoteC.copy(sortIndex = 3000), result.items[2])
        assertEquals(listOf(1000, 2000, 3000), result.items.map { it.sortIndex })
    }

    @Test
    fun overwriteLocalUsesRemoteFields() {
        val result = BackupMerge.merge(
            listOf(localA, localB),
            listOf(remoteA, remoteB),
            MergeConflictPolicy.OVERWRITE_LOCAL,
        )
        assertEquals(2, result.items.size)
        assertEquals("ALICE", result.items[0].accountName)
        assertEquals(OtpAlgorithm.SHA256, result.items[0].algorithm)
        assertEquals("BOB", result.items[1].accountName)
        assertEquals(2, result.conflicts.size)
    }

    @Test
    fun keepBothRenamesRemoteAccount() {
        val result = BackupMerge.merge(
            listOf(localA, localB),
            listOf(remoteA, remoteB, remoteC),
            MergeConflictPolicy.KEEP_BOTH,
        )
        assertEquals(5, result.items.size)
        assertEquals(2, result.conflicts.size)
        assertEquals(5, result.items.map { it.accountName }.toSet().size)
        assertTrue(result.items.any { it.accountName == "ALICE (2)" })
        assertTrue(result.items.any { it.accountName == "BOB (2)" })
        assertTrue(result.items.none { it.accountName == "ALICE" })
    }

    @Test
    fun dedupeKeyIsCaseInsensitiveButSecretSensitive() {
        val sameKey = item("44444444-4444-4444-8444-444444444444", "GITHUB", "Alice", secretA, 1)
        assertEquals(BackupMerge.keyOf(localA), BackupMerge.keyOf(sameKey))
        val differentSecret = sameKey.copy(secret = "different".toByteArray())
        assertTrue(BackupMerge.keyOf(localA) != BackupMerge.keyOf(differentSecret))
    }

    @Test
    fun emptyRemoteIsANoOp() {
        val result = BackupMerge.merge(listOf(localA), emptyList())
        assertEquals(listOf(localA.copy(sortIndex = 1000)), result.items)
        assertTrue(result.conflicts.isEmpty())
    }
}
