package io.github.rhczz.inkotp.data

import android.os.SystemClock
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.platform.SystemAuthProof
import io.github.rhczz.inkotp.platform.SystemAuthRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Hardware authorization is simulated; these tests cover the app's mandatory policy boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemAuthenticationRepositoryTest {
    private val files = MemoryFiles()
    private val envelopes = MemoryEnvelopes()
    private val keys = TestKeys()
    private fun repo(sdk: Int = 36) = VaultRepository(files, envelopes, keys,
        kdfParams = KdfParams(8192, 1, 1), sdkInt = sdk)
    private fun password() = "correct horse battery staple".toCharArray()
    private fun authorized(request: SystemAuthRequest) = SystemAuthProof(request, request.cipher)

    @Test fun enablingRequiresARecoveryPasswordAndFreshSystemProof() = runBlocking {
        val repository = repo()
        repository.initialize(null)
        assertAccess(VaultAccessError.PASSWORD_REQUIRED) { repository.prepareSystemEnable() }
        repository.setPassword(null, password())
        val request = repository.prepareSystemEnable()
        repository.enableSystemAuth(authorized(request))
        assertTrue(repository.systemAuthRequired())
        assertEquals("system-auth", repository.wrapMode())
        assertEquals(80, envelopes.envelope!!.ciphertext.size)
    }

    @Test fun enabledPolicyBlocksAllOldUnlockPathsAfterProcessRecreation() = runBlocking {
        val repository = repo()
        repository.initialize(password())
        repository.enableSystemAuth(authorized(repository.prepareSystemEnable()))
        repository.lock()
        val reopened = repo()
        val supplied = password()
        assertAccess(VaultAccessError.SYSTEM_AUTH_REQUIRED) { reopened.unlockPassword(supplied) }
        assertTrue(supplied.all { it == '\u0000' })
        assertAccess(VaultAccessError.SYSTEM_AUTH_REQUIRED) { reopened.unlockDevice() }
        assertAccess(VaultAccessError.SYSTEM_AUTH_REQUIRED) { reopened.prepareUnlock() }
        assertNull(reopened.model)
        val request = reopened.prepareSystemUnlock()
        assertEquals(0, reopened.unlockSystemAuth(authorized(request)).items.size)
    }

    @Test fun missingEnvelopeCannotSilentlyDisableThePersistedPolicy() = runBlocking {
        val repository = repo()
        repository.initialize(password())
        repository.enableSystemAuth(authorized(repository.prepareSystemEnable()))
        repository.lock()
        envelopes.envelope = null
        val reopened = repo()
        assertTrue(reopened.systemAuthRequired())
        assertEquals("system-auth", reopened.wrapMode())
        assertAccess(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED) { reopened.prepareSystemUnlock() }
        assertAccess(VaultAccessError.SYSTEM_AUTH_REQUIRED) { reopened.unlockPassword(password()) }
        val recovery = reopened.prepareSystemRecovery()
        assertNotNull(reopened.recoverSystemAuth(password(), authorized(recovery)))
        assertTrue(reopened.systemAuthRequired())
    }

    @Test fun staleUnrelatedAndReusedProofsCannotAuthorizeAnOperation() = runBlocking {
        val repository = repo()
        repository.initialize(password())
        val first = repository.prepareSystemEnable()
        val unrelated = repo().run {
            unlockPassword(password())
            prepareSystemEnable()
        }
        assertAccess(VaultAccessError.INVALID_STATE) { repository.enableSystemAuth(authorized(unrelated)) }
        val expiredRequest = repository.prepareSystemEnable()
        val expired = SystemAuthProof(expiredRequest, expiredRequest.cipher,
            authenticatedAtMillis = SystemClock.elapsedRealtime() - 20_000L)
        assertAccess(VaultAccessError.SYSTEM_AUTH_EXPIRED) { repository.enableSystemAuth(expired) }
        val validRequest = repository.prepareSystemEnable()
        val proof = authorized(validRequest)
        repository.enableSystemAuth(proof)
        assertAccess(VaultAccessError.INVALID_STATE) { repository.enableSystemAuth(proof) }
        assertTrue(repository.systemAuthRequired())
    }

    @Test fun lockingInvalidatesAPendingSystemEnableWithoutChangingThePolicy() = runBlocking {
        val repository = repo()
        repository.initialize(password())
        val request = repository.prepareSystemEnable()
        val proof = authorized(request)
        repository.lock()

        assertAccess(VaultAccessError.INVALID_STATE) { repository.enableSystemAuth(proof) }
        assertFalse(repository.systemAuthRequired())
    }

    @Test fun passwordChangeAndImportKeepThePolicyAndRequireAuthorizedRebinding() = runBlocking {
        val repository = repo()
        repository.initialize(password())
        repository.enableSystemAuth(authorized(repository.prepareSystemEnable()))
        val replacement = "another strong recovery phrase"
        repository.setPassword(password(), replacement.toCharArray())
        assertTrue(repository.systemAuthRequired())
        assertNull(envelopes.envelope)
        repository.lock()
        repository.recoverSystemAuth(replacement.toCharArray(), authorized(repository.prepareSystemRecovery()))
        repository.importBackup(repository.model!!.copy(items = emptyList()), MergeConflictPolicy.KEEP_LOCAL, false)
        assertTrue(repository.systemAuthRequired())
        repository.lock()
        assertAccess(VaultAccessError.SYSTEM_AUTH_REQUIRED) { repository.unlockPassword(replacement.toCharArray()) }
        repository.recoverSystemAuth(replacement.toCharArray(), authorized(repository.prepareSystemRecovery()))
        assertTrue(repository.systemAuthRequired())
    }

    @Test fun failedRecoveryAndFailedDisableLeaveThePolicyAndCommittedVaultIntact() = runBlocking {
        val repository = repo()
        repository.initialize(password())
        repository.enableSystemAuth(authorized(repository.prepareSystemEnable()))
        val committed = files.bytes!!.copyOf()
        repository.lock()
        val recovery = repository.prepareSystemRecovery()
        try { repository.recoverSystemAuth("incorrect phrase".toCharArray(), authorized(recovery)); fail("wrong password opened vault") }
        catch (_: Exception) { }
        assertNull(repository.model)
        assertArrayEquals(committed, files.bytes)
        repository.recoverSystemAuth(password(), authorized(repository.prepareSystemRecovery()))
        val disable = repository.prepareSystemDisable()
        envelopes.failPolicyWrite = true
        assertAccess(VaultAccessError.STORAGE_FAILURE) { repository.disableSystemAuth(authorized(disable)) }
        assertTrue(repository.systemAuthRequired())
        assertArrayEquals(committed, files.bytes)
        assertNotNull(repository.model)
    }

    @Test fun successfulDisableRequiresNewProofThenRestoresPasswordOnlyAccess() = runBlocking {
        val repository = repo()
        repository.initialize(password())
        repository.enableSystemAuth(authorized(repository.prepareSystemEnable()))
        repository.disableSystemAuth(authorized(repository.prepareSystemDisable()))
        assertFalse(repository.systemAuthRequired())
        assertEquals("password-only", repository.wrapMode())
        repository.lock()
        assertNotNull(repo().unlockPassword(password()))
    }

    @Test fun unreadablePolicyFailsClosedInsteadOfFallingBackToPasswordUnlock() = runBlocking {
        val repository = repo()
        repository.initialize(password())
        envelopes.failPolicyRead = true
        assertAccess(VaultAccessError.SYSTEM_AUTH_UNAVAILABLE) { repository.systemAuthRequired() }
        assertAccess(VaultAccessError.SYSTEM_AUTH_UNAVAILABLE) { repository.unlockPassword(password()) }
        assertNull(repository.model)
    }

    @Test fun legacyCredentialFlowStillRequiresANewProofForEachKeyOperation() = runBlocking {
        val repository = repo(29)
        repository.initialize(password())
        val enable = repository.prepareSystemEnable()
        assertNull(enable.cipher)
        repository.enableSystemAuth(authorized(enable))
        repository.lock()
        val unlock = repository.prepareSystemUnlock()
        assertNull(unlock.cipher)
        repository.unlockSystemAuth(authorized(unlock))
        repository.lock()
        assertAccess(VaultAccessError.INVALID_STATE) { repository.unlockSystemAuth(authorized(unlock)) }
        assertNull(repository.model)
    }

    private suspend fun assertAccess(code: VaultAccessError, action: suspend () -> Any?) {
        try { action(); fail("operation unexpectedly succeeded") }
        catch (failure: VaultAccessException) { assertEquals(code, failure.code) }
    }

    private class MemoryFiles : VaultFiles {
        var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read() = bytes!!.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
    private class MemoryEnvelopes : EnvelopeStore {
        var envelope: DeviceEnvelope? = null
        var required = false
        var failPolicyRead = false
        var failPolicyWrite = false
        override suspend fun read() = envelope
        override suspend fun write(value: DeviceEnvelope?) { envelope = value }
        override suspend fun systemAuthRequired(): Boolean {
            if (failPolicyRead) throw IOException("policy unavailable")
            return required
        }
        override suspend fun setSystemAuthRequired(required: Boolean) {
            if (failPolicyWrite) throw IOException("policy write failed")
            this.required = required
        }
    }
    private class TestKeys : DeviceKeys {
        private val values = mutableMapOf<String, javax.crypto.SecretKey>()
        private fun key(mode: String) = values.getOrPut(mode) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun encryptCipher(mode: String) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(mode)) }
        override fun decryptCipher(mode: String, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(mode), GCMParameterSpec(128, iv))
        }
        override fun delete(mode: String) { values.remove(mode) }
    }
}
