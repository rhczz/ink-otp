package io.github.rhczz.inkotp.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.core.crypto.Argon2Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSettings
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.vault.VaultCodec
import io.github.rhczz.inkotp.core.vault.VaultError
import io.github.rhczz.inkotp.core.vault.VaultException
import io.github.rhczz.inkotp.perf.OtpProjectionCache
import io.github.rhczz.inkotp.platform.SystemAuthProof
import io.github.rhczz.inkotp.platform.SystemAuthRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Reads previous layouts from real app storage. Keystore and OS proofs are simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PublicBetaUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val files = AtomicVaultFiles(context)
    private val envelopes = DataStoreEnvelopeStore(context)
    private val keys = TestDeviceKeys()
    private val params = KdfParams(8192, 1, 1)
    private val password = "public beta fixture phrase"
    private val stamp = "2026-11-18T00:00:00Z"
    private val existing = VaultModel(
        vaultId = "11111111-0000-4000-8000-000000000000",
        createdAt = stamp,
        updatedAt = stamp,
        settings = VaultSettings(lockTimeoutSeconds = 60, hideCodesByDefault = true, clipboardClearSeconds = 15),
        items = listOf(
            VaultItem(
                id = "11111111-0000-4000-8000-000000000001", type = OtpType.TOTP,
                issuer = "Example", accountName = "personal@example.test",
                secret = "12345678901234567890".toByteArray(),
                sortIndex = 2000, createdAt = stamp, updatedAt = stamp,
            ),
            VaultItem(
                id = "11111111-0000-4000-8000-000000000002", type = OtpType.TOTP,
                issuer = "Example", accountName = "work@example.test",
                secret = "12345678901234567890123456789012".toByteArray(),
                algorithm = OtpAlgorithm.SHA256, digits = 8, period = 45,
                sortIndex = 1000, createdAt = stamp, updatedAt = stamp,
            ),
        ),
    )

    @After fun clearDeviceMetadata() = runBlocking {
        envelopes.writeSystemConfiguration(null, required = false)
    }

    @Test fun deviceBoundUpgradeReopensTheExistingLibraryWithItsOriginalSettingsAndCodes() = runBlocking {
        val committed = writePreviousLibrary("device-bound")
        val updated = reopen()

        assertFalse(updated.hasPassword())
        assertEquals("device-bound", updated.wrapMode())
        assertLibrary(updated.unlockDevice(), committed)
        updated.lock()
        assertLibrary(reopen().unlockDevice(), committed)
    }

    @Test fun existingLocalWrapperRemainsUsableOnTheFirstColdStartAfterUpgrade() = runBlocking {
        val committed = writePreviousLibrary("device-local")
        val updated = reopen()

        assertTrue(updated.hasPassword())
        assertEquals("device-local", updated.wrapMode())
        assertLibrary(updated.unlockDevice(), committed)
        updated.lock()
        assertLibrary(reopen().unlockDevice(), committed)
    }

    @Test fun passwordOnlyUpgradeDoesNotRewriteTheVaultWhenCreatingItsDeviceWrapper() = runBlocking {
        val committed = writePreviousLibrary(null)
        val updated = reopen()
        assertEquals("password-only", updated.wrapMode())

        val failure = runCatching { updated.unlockPassword("incorrect fixture phrase".toCharArray()) }
            .exceptionOrNull() as? VaultException
        assertEquals(VaultError.VLT_E_WRAP_AUTH, failure?.code)
        assertArrayEquals(committed, files.read())
        assertNull(updated.model)
        assertNull(envelopes.read())

        assertLibrary(updated.unlockPassword(password.toCharArray()), committed)
        updated.lock()
        val restarted = reopen()
        assertEquals("device-local", restarted.wrapMode())
        assertLibrary(restarted.unlockDevice(), committed)
    }

    @Test fun legacyBiometricUpgradeCanCancelAndThenMigrateWithoutChangingAccountData() = runBlocking {
        val committed = writePreviousLibrary("biometric")
        val updated = reopen()
        assertEquals("biometric", updated.wrapMode())
        val cancelled = updated.prepareUnlock()
        updated.lock()

        assertAccess(VaultAccessError.INVALID_STATE) { updated.unlock(cancelled) }
        assertNull(updated.model)
        assertEquals("biometric", reopen().wrapMode())
        assertArrayEquals(committed, files.read())

        assertLibrary(updated.unlock(updated.prepareUnlock()), committed)
        updated.lock()
        val restarted = reopen()
        assertEquals("device-local", restarted.wrapMode())
        assertLibrary(restarted.unlockDevice(), committed)
    }

    @Test fun mandatoryPolicySurvivesUpgradeAndRebindingOnBothCredentialPaths() = runBlocking {
        for (sdk in listOf(29, 36)) {
            val committed = writePreviousLibrary("system-auth")
            val updated = reopen(sdk)
            assertTrue(updated.systemAuthRequired())
            assertAccess(VaultAccessError.SYSTEM_AUTH_REQUIRED) { updated.unlockPassword(password.toCharArray()) }
            val cancelled = updated.prepareSystemUnlock()
            assertEquals(sdk < 30, cancelled.cipher == null)
            updated.lock()

            assertAccess(VaultAccessError.INVALID_STATE) { updated.unlockSystemAuth(authorized(cancelled)) }
            assertNull(updated.model)
            assertLibrary(updated.unlockSystemAuth(authorized(updated.prepareSystemUnlock())), committed)
            updated.lock()

            keys.delete("system-auth")
            assertAccess(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED) {
                updated.unlockSystemAuth(authorized(updated.prepareSystemUnlock()))
            }
            val cancelledRebind = updated.prepareSystemRecovery()
            updated.lock()
            assertAccess(VaultAccessError.INVALID_STATE) {
                updated.recoverSystemAuth(password.toCharArray(), authorized(cancelledRebind))
            }
            assertNull(updated.model)
            assertArrayEquals(committed, files.read())

            assertLibrary(updated.recoverSystemAuth(password.toCharArray(), authorized(updated.prepareSystemRecovery())), committed)
            updated.lock()
            val restarted = reopen(sdk)
            assertTrue(restarted.systemAuthRequired())
            assertLibrary(restarted.unlockSystemAuth(authorized(restarted.prepareSystemUnlock())), committed)
            restarted.lock()
        }
    }

    private fun reopen(sdk: Int = 36) =
        VaultRepository(AtomicVaultFiles(context), DataStoreEnvelopeStore(context), keys, kdfParams = params, sdkInt = sdk)

    private suspend fun writePreviousLibrary(mode: String?): ByteArray {
        val deviceOnly = mode == "device-bound"
        val dek = ByteArray(32) { (it + 1).toByte() }
        val salt = ByteArray(16) { (it + 17).toByte() }
        val kek = if (deviceOnly) null else Argon2Kdf().deriveKey(password.toCharArray(), salt, params)
        val bytes = VaultCodec.sealWithKey(existing, dek, kek, if (deviceOnly) null else salt, if (deviceOnly) null else params)
        files.write(bytes)
        val envelope = mode?.let {
            val bindingSalt = if (deviceOnly) ByteArray(16) else salt
            val cipher = keys.encryptCipher(it)
            cipher.updateAAD("InkOTP/session/v1/$it\u0000".toByteArray() + bindingSalt)
            DeviceEnvelope(it, cipher.iv, cipher.doFinal(if (kek == null) dek else dek + kek), bindingSalt)
        }
        if (mode == "system-auth") envelopes.writeSystemConfiguration(envelope, required = true)
        else envelopes.write(envelope)
        return bytes
    }

    private fun assertLibrary(model: VaultModel, committed: ByteArray) {
        assertEquals(existing, model)
        val rows = OtpProjectionCache().project(model, 59)
        assertEquals(listOf("work@example.test", "personal@example.test"), rows.map { it.accountName })
        // Both periods are at counter 1 at epoch 59. The codes are the RFC 6238 vectors.
        assertEquals(listOf("46119246", "287082"), rows.map { it.code })
        assertEquals(listOf(31, 1), rows.map { it.remainingSeconds })
        assertTrue(rows.all { it.hidden })
        assertArrayEquals(committed, files.read())
    }

    private suspend fun assertAccess(expected: VaultAccessError, action: suspend () -> Any?) {
        val failure = runCatching { action() }.exceptionOrNull() as? VaultAccessException
        assertEquals(expected, failure?.code)
    }

    private fun authorized(request: SystemAuthRequest) = SystemAuthProof(request, request.cipher)

    private class TestDeviceKeys : DeviceKeys {
        private val byMode = mutableMapOf<String, SecretKey>()

        override fun encryptCipher(mode: String): Cipher {
            val key = byMode.getOrPut(mode) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }

        override fun decryptCipher(mode: String, iv: ByteArray): Cipher {
            val key = byMode[mode] ?: throw VaultAccessException(VaultAccessError.DEVICE_UNAVAILABLE)
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        }

        override fun delete(mode: String) {
            byMode.remove(mode)
        }
    }
}
