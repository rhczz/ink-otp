package io.github.rhczz.inkotp.app

import android.app.Application
import android.app.KeyguardManager
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.core.crypto.Argon2Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.vault.VaultCodec
import io.github.rhczz.inkotp.data.DeviceEnvelope
import io.github.rhczz.inkotp.data.DeviceKeys
import io.github.rhczz.inkotp.data.EnvelopeStore
import io.github.rhczz.inkotp.data.VaultFiles
import io.github.rhczz.inkotp.data.VaultRepository
import io.github.rhczz.inkotp.platform.DeviceSecurity
import io.github.rhczz.inkotp.platform.SystemAuthProof
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowBiometricPrompt
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Tests policy/lifecycle at the real VM boundary; successful OS grants are simulated only when seeding fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SystemAuthenticationViewModelTest {
    private val files = MemoryFiles()
    private val envelopes = MemoryEnvelopes()
    private val keys = TestKeys()
    private val repository = VaultRepository(files, envelopes, keys, kdfParams = KdfParams(8192, 1, 1))
    private var store = ViewModelStore()
    private val securityServices = mutableListOf<DeviceSecurity>()
    private var blockedKey: Block? = null
    private val password = "correct horse battery staple"

    @After fun tearDown() {
        blockedKey?.release?.countDown()
        securityServices.forEach(DeviceSecurity::close)
        store.clear()
        pumpUntil("session cleanup") { repository.model == null }
    }

    @Test fun coldStartReadsThePolicyButNeverPublishesVaultContents() {
        seedSystemVault()

        val vm = viewModel()

        assertTrue(vm.state.systemAuthenticationRequired)
        assertTrue(vm.state.settings.systemAuthenticationEnabled)
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        assertFalse(vm.tickerActive)
    }

    @Test fun ordinaryRecoveryPasswordCannotSkipSystemVerificationAtTheVmBoundary() {
        seedSystemVault()
        val vm = viewModel()

        vm.unlockPassword(password)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        assertNull(repository.model)
        assertNotNull(vm.state.error)
    }

    @Test fun policyReadFailureIsReadyButLockedAndNeverAutoDegraded() {
        seedSystemVault()
        envelopes.failPolicyRead = true

        val vm = viewModel()

        assertTrue(vm.state.exists)
        assertTrue(vm.state.systemAuthenticationRequired)
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        assertNotNull(vm.state.error)
    }

    @Test fun systemPolicyOverridesTheOldSixtySecondBackgroundGrace() {
        val vm = activeSystemSession()
        assertEquals(60, vm.state.settings.lockTimeoutSeconds)

        vm.onBackground()

        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        assertFalse(vm.tickerActive)
        pumpUntil("background wipes secrets") { repository.model == null }
    }

    @Test fun anExplicitSystemFilePickerRemainsTheBoundedException() {
        val vm = activeSystemSession()
        val token = vm.beginPicker()
        vm.onBackground()
        assertTrue(vm.state.unlocked)
        assertFalse(vm.tickerActive)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))

        assertTrue(vm.endPicker(token))
        vm.onForeground()

        assertTrue(vm.state.unlocked)
        assertEquals(token, vm.sessionToken)
    }

    @Test fun repeatedSystemAuthenticationBackgroundCallbacksCannotRenewTheAbsoluteDeadline() {
        val vm = activeSystemSession()
        vm.onSystemAuthenticationBackground()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        vm.onSystemAuthenticationBackground()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(51))

        vm.onForeground()

        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
    }

    @Test fun failedSystemVerificationCannotRevealItemsOrAutomaticallyLoopOnRotation() {
        seedSystemVault()
        val vm = viewModel()
        val security = noLockScreenSecurity()
        vm.requestAutomaticAuthentication(security)
        pumpUntil("failed OS verification") { !vm.state.busy && vm.state.error != null }
        val attempts = keys.decryptAttempts
        assertEquals(1, attempts)
        assertFalse(vm.state.unlocked)
        assertNull(repository.model)

        // A recreated Activity owns a new helper but the VM remembers this generation's attempt.
        vm.requestAutomaticAuthentication(noLockScreenSecurity())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(attempts, keys.decryptAttempts)
        assertFalse(vm.state.unlocked)
    }

    @Test fun disabledPolicyOpensOnColdStartAndReturnsAfterAutomaticLockWithoutPrompt() {
        seedPasswordVault()
        val vm = viewModel()
        assertTrue(vm.state.unlocked)
        assertEquals(1, vm.state.items.size)
        assertEquals(1, keys.decryptAttempts)

        vm.onBackground()
        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        vm.onForeground()
        val security = noLockScreenSecurity()
        pumpUntil("background session cleared") { vm.state.ready && !vm.state.busy }
        repeat(3) { vm.requestAutomaticAuthentication(security) }
        pumpUntil("silent foreground reopening") { vm.state.unlocked && !vm.state.busy }
        assertEquals(2, keys.decryptAttempts)
        assertEquals(1, vm.state.items.size)

        vm.lock()
        pumpUntil("manual lock") { vm.state.ready && !vm.state.busy }
        vm.requestAutomaticAuthentication(security)
        assertFalse(vm.state.unlocked)
        vm.unlock(security)
        pumpUntil("explicit reopen after manual lock") { vm.state.unlocked && !vm.state.busy }
        assertEquals(3, keys.decryptAttempts)
    }

    @Test fun legacyBiometricsUnavailableOfferOneTimeRecoveryWithoutPrompt() {
        seedLegacyBiometricVault()
        val vm = viewModel()
        assertTrue(vm.state.biometricEnabled)

        vm.requestAutomaticAuthentication(noLockScreenSecurity())
        assertFalse(vm.state.biometricEnabled)
        assertFalse(vm.state.unlocked)
        assertEquals(0, keys.decryptAttempts)
        vm.unlockPassword(password)
        pumpUntil("legacy password recovery") { vm.state.unlocked && !vm.state.busy }
        assertTrue(vm.state.deviceUnlockAvailable)
    }

    @Test fun negativeSystemButtonPublishesRecoveryIntentWithoutUnlockingOrRepeatingThePrompt() {
        seedLegacyBiometricVault()
        val vm = viewModel()
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        Shadow.extract<ShadowBiometricManager>(activity.getSystemService(android.hardware.biometrics.BiometricManager::class.java)).setCanAuthenticate(true)
        val security = DeviceSecurity(activity).also(securityServices::add)

        vm.requestAutomaticAuthentication(security)
        pumpUntil("ordinary system prompt") { ShadowBiometricPrompt.getCurrentPrompt() != null }
        ShadowBiometricPrompt.authenticateCurrentSessionWithError(androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON, "使用恢复口令")
        pumpUntil("negative button recovery intent") { !vm.state.busy && vm.state.recoveryPasswordRequested }

        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        assertNull(repository.model)
        vm.requestAutomaticAuthentication(security)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, keys.decryptAttempts)
        assertNull(ShadowBiometricPrompt.getCurrentPrompt())
    }

    @Test fun aFreshVmDoesNotInheritThePreviousProcessAutomaticAttemptMarker() {
        seedSystemVault()
        val vm = viewModel()
        vm.requestAutomaticAuthentication(noLockScreenSecurity())
        pumpUntil("first process attempt") { !vm.state.busy && vm.state.error != null }
        store.clear()
        pumpUntil("first process closed") { repository.model == null }
        store = ViewModelStore()

        val restarted = viewModel(VaultRepository(files, envelopes, keys, kdfParams = KdfParams(8192, 1, 1)))
        restarted.requestAutomaticAuthentication(noLockScreenSecurity())
        pumpUntil("fresh process attempt") { !restarted.state.busy && restarted.state.error != null }

        assertEquals(2, keys.decryptAttempts)
        assertFalse(restarted.state.unlocked)
    }

    @Test fun lockingDuringSlowSystemKeyPreparationRejectsItsLateCompletion() {
        seedSystemVault()
        val vm = viewModel()
        val slow = Block().also { blockedKey = it; keys.nextDecrypt.set(it) }
        vm.unlock(noLockScreenSecurity())
        pumpUntil("key preparation blocked") { slow.started.count == 0L }

        vm.lock()
        slow.release.countDown()
        assertTrue(slow.finished.await(5, TimeUnit.SECONDS))
        pumpUntil("late preparation cleaned up") { repository.model == null && !vm.state.busy }

        assertFalse(vm.state.unlocked)
        assertTrue(vm.state.items.isEmpty())
        assertFalse(vm.tickerActive)
    }

    private fun activeSystemSession(): VaultViewModel {
        seedPasswordVault()
        val vm = viewModel()
        vm.unlockPassword(password)
        pumpUntil("password session") { vm.state.unlocked && !vm.state.busy }
        runBlocking {
            val request = repository.prepareSystemEnable()
            repository.enableSystemAuth(SystemAuthProof(request, request.cipher))
        }
        vm.settings { it.copy(lockTimeoutSeconds = 60) }
        pumpUntil("system policy reflected") { vm.state.systemAuthenticationRequired && !vm.state.busy }
        vm.onForeground()
        return vm
    }

    private fun seedSystemVault() {
        seedPasswordVault()
        runBlocking {
            repository.unlockPassword(password.toCharArray())
            val request = repository.prepareSystemEnable()
            repository.enableSystemAuth(SystemAuthProof(request, request.cipher))
            repository.lock()
        }
    }

    private fun seedPasswordVault() = runBlocking {
        repository.initialize(password.toCharArray())
        val item = VaultItem(UUID.randomUUID().toString(), OtpType.TOTP, "Example", "policy@example.test",
            "12345678901234567890".toByteArray(), sortIndex = 1000,
            createdAt = "2026-11-17T00:00:00Z", updatedAt = "2026-11-17T00:00:00Z")
        repository.save(repository.model!!.copy(items = listOf(item)))
        item.secret.fill(0)
        repository.lock()
    }

    private fun seedLegacyBiometricVault() = runBlocking {
        seedPasswordVault()
        val bytes = files.read()
        val header = VaultCodec.parseHeader(bytes)
        val passphrase = password.toCharArray()
        val kek = Argon2Kdf().deriveKey(passphrase, header.salt, header.kdfParams!!)
        val dek = VaultCodec.unwrapDekWithKey(bytes, kek)
        val material = dek + kek
        try {
            val cipher = keys.encryptCipher("biometric")
            cipher.updateAAD("InkOTP/session/v1/biometric\u0000".toByteArray() + header.salt)
            envelopes.write(DeviceEnvelope("biometric", cipher.iv, cipher.doFinal(material), header.salt))
        } finally {
            passphrase.fill('\u0000')
            listOf(dek, kek, material).forEach { it.fill(0) }
        }
    }

    private fun noLockScreenSecurity(): DeviceSecurity {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(false)
        Shadow.extract<ShadowBiometricManager>(activity.getSystemService(android.hardware.biometrics.BiometricManager::class.java)).setCanAuthenticate(false)
        return DeviceSecurity(activity).also(securityServices::add)
    }

    private fun viewModel(activeRepository: VaultRepository = repository): VaultViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VaultViewModel(app, activeRepository) as T
        }
        return ViewModelProvider(store, factory)[VaultViewModel::class.java].also { vm ->
            pumpUntil("VM ready") { vm.state.ready && !vm.state.busy }
            vm.onForeground()
        }
    }

    private fun pumpUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out: $description")
    }

    private class MemoryFiles : VaultFiles {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read() = bytes!!.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
    private class MemoryEnvelopes : EnvelopeStore {
        private var envelope: DeviceEnvelope? = null
        private var required = false
        var failPolicyRead = false
        override suspend fun read() = envelope
        override suspend fun write(value: DeviceEnvelope?) { envelope = value }
        override suspend fun systemAuthRequired(): Boolean {
            if (failPolicyRead) throw IOException("policy unavailable")
            return required
        }
        override suspend fun writeSystemConfiguration(value: DeviceEnvelope?, required: Boolean) {
            envelope = value
            this.required = required
        }
    }
    private class Block {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        fun enter() {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release key operation" }
        }
    }
    private class TestKeys : DeviceKeys {
        private val values = mutableMapOf<String, javax.crypto.SecretKey>()
        val nextDecrypt = AtomicReference<Block?>()
        @Volatile var decryptAttempts = 0
        private fun key(mode: String) = values.getOrPut(mode) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun encryptCipher(mode: String) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(mode)) }
        override fun decryptCipher(mode: String, iv: ByteArray): Cipher {
            decryptAttempts++
            val block = nextDecrypt.getAndSet(null)
            try {
                block?.enter()
                return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(mode), GCMParameterSpec(128, iv)) }
            } finally { block?.finished?.countDown() }
        }
        override fun delete(mode: String) { values.remove(mode) }
    }
}
