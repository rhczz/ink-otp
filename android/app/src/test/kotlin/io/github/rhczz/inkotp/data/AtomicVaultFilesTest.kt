package io.github.rhczz.inkotp.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AtomicVaultFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun committedBytesSurviveReopeningTheRealFile() {
        val path = File(temporary.root, "vault.bin")
        val first = "encrypted fixture one".toByteArray()
        val second = "encrypted fixture two".toByteArray()
        AtomicVaultFiles(path).write(first)
        assertArrayEquals(first, AtomicVaultFiles(path).read())
        AtomicVaultFiles(path).write(second)
        assertArrayEquals(second, AtomicVaultFiles(path).read())
    }

    @Test fun silentFinishFailureThrowsAndPreservesThePreviousCommittedFile() {
        val path = File(temporary.root, "vault.bin")
        val original = "previous encrypted fixture".toByteArray()
        AtomicVaultFiles(path).write(original)
        // Reproduces AtomicFile.finishWrite logging an error without replacing
        // the base file. The repository must never adopt this as a commit.
        val abandoningWriter = AtomicVaultFiles(path) { _, output -> output.close() }
        try {
            abandoningWriter.write("replacement encrypted fixture".toByteArray())
            fail("silently abandoned write reported success")
        } catch (_: IOException) { }
        assertArrayEquals(original, AtomicVaultFiles(path).read())
    }

    @Test fun restorePreservesAnExactPrivateCopyBeforeReplacingTheActiveFile() {
        val path = File(temporary.root, "vault.bin")
        val original = "corrupted or unusable ciphertext".toByteArray()
        val restored = "restored ciphertext".toByteArray()
        val storage = AtomicVaultFiles(path)
        storage.write(original)
        storage.replaceKeepingOriginal(restored)

        assertArrayEquals(original, File(temporary.root, "vault.pre-restore.bin").readBytes())
        assertArrayEquals(restored, storage.read())
    }

    @Test fun failedRestoreWriteLeavesTheOriginalActiveAndItsPrivateCopyReadable() {
        val path = File(temporary.root, "vault.bin")
        val original = "unusable original ciphertext".toByteArray()
        AtomicVaultFiles(path).write(original)
        val abandoningWriter = AtomicVaultFiles(path) { _, output -> output.close() }

        assertThrows(IOException::class.java) {
            abandoningWriter.replaceKeepingOriginal("restored ciphertext".toByteArray())
        }
        assertArrayEquals(original, AtomicVaultFiles(path).read())
        assertArrayEquals(original, File(temporary.root, "vault.pre-restore.bin").readBytes())
    }

    @Test fun silentlyAbandonedFirstWriteDoesNotCreateACommittedVault() {
        val path = File(temporary.root, "vault.bin")
        val abandoningWriter = AtomicVaultFiles(path) { _, output -> output.close() }
        try {
            abandoningWriter.write("new encrypted fixture".toByteArray())
            fail("missing base file reported as committed")
        } catch (_: IOException) { }
        assertFalse(AtomicVaultFiles(path).exists())
    }
}
