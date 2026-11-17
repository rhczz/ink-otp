package io.github.rhczz.inkotp.platform

import android.hardware.biometrics.BiometricManager
import android.os.Looper
import androidx.biometric.BiometricPrompt
import androidx.biometric.internal.BiometricViewModel
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowBiometricPrompt
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BiometricAuthenticationTest {
    private lateinit var activity: FragmentActivity
    private lateinit var security: DeviceSecurity
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before fun setUp() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        Shadow.extract<ShadowBiometricManager>(activity.getSystemService(BiometricManager::class.java)).setCanAuthenticate(true)
        security = DeviceSecurity(activity)
    }

    @After fun tearDown() {
        scope.cancel()
        security.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun ordinaryBiometricsProtectsTheHostLifecycleAndRejectsDuplicatePrompts() {
        val first = scope.async { security.authenticate(cipher(), "解锁 InkOTP") }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(ShadowBiometricPrompt.getCurrentPrompt())
        assertTrue("all system prompts must protect the host lifecycle", security.isAuthenticationInProgress)

        val second = scope.async { runCatching { security.authenticate(cipher(), "重复解锁") } }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(second.getCompleted().exceptionOrNull() is BiometricAuthenticationException)
        assertTrue(security.isAuthenticationInProgress)

        first.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(security.isAuthenticationInProgress)
    }

    @Test fun ordinaryBiometricPromptTimesOutAndLeavesNoLifecycleExemption() {
        val result = scope.async { runCatching { security.authenticate(cipher(), "解锁 InkOTP") } }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(ShadowBiometricPrompt.getCurrentPrompt())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))

        assertTrue("a biometric prompt must have a bounded lifetime", result.isCompleted)
        val failure = result.getCompleted().exceptionOrNull() as BiometricAuthenticationException
        assertTrue(failure.cancelled)
        assertFalse(security.isAuthenticationInProgress)
    }

    @Test fun unauthorisedSuccessWithoutCryptoObjectCannotOpenTheVault() {
        val result = scope.async { runCatching { security.authenticate(cipher(), "解锁 InkOTP") } }
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's success grant deliberately omits a CryptoObject.
        ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(result.getCompleted().exceptionOrNull() is BiometricAuthenticationException)
        assertFalse(security.isAuthenticationInProgress)
    }

    @Test fun systemGrantReturnsTheExactAuthorisedCipherAndClearsLifecycleProtection() {
        val requested = cipher()
        val result = scope.async { security.authenticate(requested, "解锁 InkOTP") }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(ShadowBiometricPrompt.getCurrentPrompt())

        // Simulate only the OS grant. Actual biometric/Keystore hardware remains a phone check.
        ViewModelProvider(activity)[BiometricViewModel::class.java].clientCallback.onAuthenticationSucceeded(
            BiometricPrompt.AuthenticationResult(BiometricPrompt.CryptoObject(requested), BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(requested, result.getCompleted())
        assertFalse(security.isAuthenticationInProgress)
    }

    @Test fun systemNegativeButtonRequestsTheRecoveryPhraseAndClosesThePrompt() {
        val result = scope.async { runCatching { security.authenticate(cipher(), "解锁 InkOTP") } }
        shadowOf(Looper.getMainLooper()).idle()

        ShadowBiometricPrompt.authenticateCurrentSessionWithError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "使用恢复口令")
        shadowOf(Looper.getMainLooper()).idle()

        val failure = result.getCompleted().exceptionOrNull() as BiometricAuthenticationException
        assertTrue(failure.cancelled)
        assertTrue(failure.recoveryPasswordRequested)
        assertFalse(security.isAuthenticationInProgress)
    }

    @Test fun aCryptoObjectForAnotherRequestCannotAuthoriseThisPrompt() {
        val result = scope.async { runCatching { security.authenticate(cipher(), "解锁 InkOTP") } }
        shadowOf(Looper.getMainLooper()).idle()

        ViewModelProvider(activity)[BiometricViewModel::class.java].clientCallback.onAuthenticationSucceeded(
            BiometricPrompt.AuthenticationResult(BiometricPrompt.CryptoObject(cipher()), BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(result.getCompleted().exceptionOrNull() is BiometricAuthenticationException)
        assertFalse(security.isAuthenticationInProgress)
    }

    private fun cipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    }
}
