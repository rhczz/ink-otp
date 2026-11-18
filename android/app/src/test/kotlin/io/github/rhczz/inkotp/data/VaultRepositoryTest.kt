package io.github.rhczz.inkotp.data

import io.github.rhczz.inkotp.core.backup.BackupCodec
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.crypto.Kdf
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.vault.VaultCodec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class VaultRepositoryTest {
    private val files = MemoryFiles()
    private val envelopes = MemoryEnvelopes()
    private val keys = TestKeys()
    private val params = KdfParams(8192, 1, 1)
    private fun repository() = VaultRepository(files, envelopes, keys, kdfParams = params)
    private fun password() = "correct horse battery staple".toCharArray()
    private fun item() = VaultItem(UUID.randomUUID().toString(), OtpType.TOTP, "Example", "test@example.com",
        "12345678901234567890".toByteArray(), sortIndex = 1000,
        createdAt = "2026-11-17T00:00:00Z", updatedAt = "2026-11-17T00:00:00Z")

    @Test fun saveReopensWithOriginalPasswordAndLockErasesLiveSecrets() = runBlocking {
        val repo = repository()
        val supplied = password()
        repo.initialize(supplied)
        assertEquals(0, repo.model!!.settings.lockTimeoutSeconds)
        assertTrue(supplied.all { it == '\u0000' })
        repo.save(repo.model!!.copy(items = listOf(item())))
        val first = files.bytes!!.copyOf()
        val live = repo.model!!.items.first().secret
        repo.save(repo.model!!.copy(updatedAt = "2026-11-17T00:00:01Z"))
        assertTrue(live.all { it == 0.toByte() })
        assertTrue(repo.model!!.items.first().secret.any { it != 0.toByte() })
        assertFalse(first.contentEquals(files.bytes!!))
        val retained = repo.model!!.items.first().secret
        repo.lock()
        assertNull(repo.model)
        assertTrue(retained.all { it == 0.toByte() })
        assertEquals(1, repository().unlockPassword(password()).items.size)
    }

    @Test fun failedSaveAndWrongPasswordPreserveCommittedFile() = runBlocking {
        val repo = repository()
        repo.initialize(password())
        val committed = files.bytes!!.copyOf()
        files.failWrite = true
        try { repo.save(repo.model!!.copy(items = listOf(item()))); fail("save succeeded") }
        catch (_: VaultAccessException) { }
        assertArrayEquals(committed, files.bytes)
        assertTrue(repo.model!!.items.isEmpty())
        repo.lock()
        try { repo.unlockPassword("incorrect password".toCharArray()); fail("unlock succeeded") }
        catch (_: Exception) { }
        assertNull(repo.model)
        assertArrayEquals(committed, files.bytes)
    }

    @Test fun upgradingDeviceModePreservesDeviceAccessAndRejectsOldEnvelope() = runBlocking {
        val repo = repository()
        repo.initialize(null)
        assertEquals("device-bound", repo.wrapMode())
        repo.lock()
        repo.unlockDevice()
        val staleEnvelope = envelopes.envelope
        repo.setPassword(null, password())
        assertEquals("device-local", repo.wrapMode())
        assertNotNull(repository().unlockDevice())
        envelopes.envelope = staleEnvelope // interrupted cleanup cannot bypass a password header
        repo.lock()
        try { repo.unlockDevice(); fail("device unlock bypassed password") }
        catch (_: VaultAccessException) { }
        assertEquals(0, repo.unlockPassword(password()).items.size)
        assertNotNull(repository().unlockDevice())
    }

    @Test fun importRotatesDekAndIdsButKeepsLocalPasswordAndSettings() = runBlocking {
        val repo = repository()
        repo.initialize(password())
        val existing = item()
        repo.save(repo.model!!.copy(items = listOf(existing)))
        val oldDek = VaultCodec.unwrapDek(files.bytes!!, password())
        val remoteItem = existing.copy(secret = ByteArray(20) { 7 }, accountName = "other")
        val remote = repo.model!!.copy(items = listOf(remoteItem))
        val result = repo.importBackup(remote, MergeConflictPolicy.KEEP_LOCAL, replace = false)
        assertEquals(2, result.items.size)
        assertEquals(2, result.items.map { it.id }.distinct().size)
        assertFalse(oldDek.contentEquals(VaultCodec.unwrapDek(files.bytes!!, password())))
        repo.lock()
        assertEquals(2, repository().unlockDevice().items.size)
    }

    @Test fun deviceEnvelopeWorksAcrossWritesAndRejectsAnOldSalt() = runBlocking {
        val repo = repository()
        repo.initialize(password())
        val stale = envelopes.envelope
        repo.lock()
        repo.unlockDevice()
        repo.save(repo.model!!.copy(items = listOf(item())))
        assertEquals(1, VaultCodec.openWithPassword(files.bytes!!, password()).model.items.size)
        repo.setPassword(password(), "another strong password".toCharArray())
        assertEquals(1, repository().unlockDevice().items.size)
        envelopes.envelope = stale
        repo.lock()
        try { repo.unlockDevice(); fail("stale local keys accepted") }
        catch (_: VaultAccessException) { }
        assertNull(repo.model)
        assertNotNull(repo.unlockPassword("another strong password".toCharArray()))
        assertEquals(1, repository().unlockDevice().items.size)
    }

    @Test fun wrongBackupPasswordNeverChangesCurrentVault() = runBlocking {
        val repo = repository()
        repo.initialize(password())
        val committed = files.bytes!!.copyOf()
        val backup = BackupCodec.seal(repo.model!!, password(), params)
        try { repo.decodeBackup(backup, "wrong".toCharArray()); fail("decoded") }
        catch (_: Exception) { }
        assertArrayEquals(committed, files.bytes)
    }

    @Test fun cancelledKeyDerivationCannotCreateVaultOrSession() {
        val operation = Job()
        val cancellingKdf = object : Kdf {
            override fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
                operation.cancel()
                return ByteArray(32) { 7 }
            }
        }
        val repo = VaultRepository(files, envelopes, keys, kdf = cancellingKdf, kdfParams = params)
        val supplied = password()
        val failure = runCatching { runBlocking(operation) { repo.initialize(supplied) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertNull(files.bytes)
        assertNull(repo.model)
        assertNull(envelopes.envelope)
        assertTrue(supplied.all { it == '\u0000' })
    }

    @Test fun failedImportKeepsCurrentSessionAndPassword() = runBlocking {
        val repo = repository()
        repo.initialize(password())
        val committed = files.bytes!!.copyOf()
        val remote = repo.model!!.copy(items = listOf(item()))
        files.failWrite = true
        try { repo.importBackup(remote, MergeConflictPolicy.KEEP_LOCAL, true); fail("import succeeded") }
        catch (_: VaultAccessException) { }
        assertTrue(repo.model!!.items.isEmpty())
        assertArrayEquals(committed, files.bytes)
        assertEquals(repo.model, VaultCodec.openWithPassword(files.bytes!!, password()).model)
    }

    @Test fun passwordOnlyUpgradeRequiresOneSuccessfulRecoveryBeforeSilentReopen() = runBlocking {
        val repo = repository()
        repo.initialize(password())
        repo.lock()
        envelopes.envelope = null  // A pre-upgrade password-only vault has no local wrapper.
        val committed = files.bytes!!.copyOf()
        val reopened = repository()
        assertEquals("password-only", reopened.wrapMode())
        try { reopened.unlockPassword("wrong phrase".toCharArray()); fail("accepted wrong recovery phrase") }
        catch (_: Exception) { }
        assertNull(reopened.model)
        assertNull(envelopes.envelope)
        reopened.unlockPassword(password())
        assertEquals("device-local", reopened.wrapMode())
        assertArrayEquals(committed, files.bytes)
        assertNotNull(repository().unlockDevice())
    }

    @Test fun committedPasswordChangeSucceedsEvenWhenReplacementWrapperWriteFails() = runBlocking {
        val repo = repository()
        repo.initialize(null)
        envelopes.failWrite = true
        repo.setPassword(null, password())
        assertEquals("password-only", repo.wrapMode())
        repo.lock()
        // Also check a fresh repository: stale device ciphertext cannot bypass
        // the newly committed password header after a process restart.
        val reopened = repository()
        try { reopened.unlockDevice(); fail("stale device wrapper accepted") }
        catch (_: VaultAccessException) { }
        assertNotNull(reopened.unlockPassword(password()))
    }

    @Test fun committedImportSucceedsWhenWrapperWriteFailsAndStaleEnvelopeCannotUnlock() = runBlocking {
        val repo = repository()
        repo.initialize(password())
        envelopes.failWrite = true
        val imported = repo.importBackup(repo.model!!.copy(items = listOf(item())), MergeConflictPolicy.KEEP_LOCAL, true)
        assertEquals(1, imported.items.size)
        repo.lock()
        val reopened = repository()
        try { reopened.unlockDevice(); fail("old DEK accepted") }
        catch (_: Exception) { }
        assertEquals(1, reopened.unlockPassword(password()).items.size)
    }

    private class MemoryFiles : VaultFiles {
        var bytes: ByteArray? = null
        var failWrite = false
        override fun exists() = bytes != null
        override fun read() = bytes!!.copyOf()
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("injected failure")
            this.bytes = bytes.copyOf()
        }
    }
    private class MemoryEnvelopes : EnvelopeStore {
        var envelope: DeviceEnvelope? = null
        var failWrite = false
        override suspend fun read() = envelope
        override suspend fun write(value: DeviceEnvelope?) {
            if (failWrite) throw IOException("injected wrapper write failure")
            envelope = value
        }
    }
    private class TestKeys : DeviceKeys {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encryptCipher(mode: String) = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        override fun decryptCipher(mode: String, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        override fun delete(mode: String) = Unit
    }
}
