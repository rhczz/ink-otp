package io.github.rhczz.inkotp.data

import android.app.Application
import android.app.KeyguardManager
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import java.security.ProviderException

/** Checks SDK-specific parameter policy on the existing SDK 36 JVM runtime, not hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceKeyPolicyTest {
    @Test
    fun android14AvoidsTheKnownUnlockedDeviceRequiredBugsInBothModes() {
        for (mode in listOf("biometric", "device-bound")) {
            assertFalse(createDeviceKeySpec("test-$mode", mode, false, sdkInt = 34).isUnlockedDeviceRequired)
        }
        val biometric = createDeviceKeySpec("test-biometric", "biometric", false, sdkInt = 34)
        assertTrue(biometric.isUserAuthenticationRequired)
        assertTrue(biometric.isInvalidatedByBiometricEnrollment)
        assertEquals(0, biometric.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, biometric.userAuthenticationType)
    }

    @Test
    fun android15RequiresUnlockedDeviceInBothModesAndKeepsBiometricPerUse() {
        for (mode in listOf("biometric", "device-bound")) {
            assertTrue(createDeviceKeySpec("test-$mode", mode, false, sdkInt = 35).isUnlockedDeviceRequired)
        }
        val biometric = createDeviceKeySpec("test-biometric", "biometric", false, sdkInt = 35)
        assertEquals(0, biometric.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, biometric.userAuthenticationType)
        assertFalse(createDeviceKeySpec("test-device", "device-bound", false, sdkInt = 35).isUserAuthenticationRequired)
    }

    @Test
    fun legacyBiometricKeyRequiresAuthenticationForEveryUse() {
        val biometric = createDeviceKeySpec("test-biometric", "biometric", false, sdkInt = 26)
        assertTrue(biometric.isUserAuthenticationRequired)
        assertTrue(biometric.isInvalidatedByBiometricEnrollment)
        // SDK 36's implementation normalizes the legacy -1 setter to the
        // equivalent per-operation timeout 0 + strong biometric policy.
        assertEquals(0, biometric.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, biometric.userAuthenticationType)
    }

    @Test
    fun lockedDeviceRejectsEncryptionAndDecryptionBeforeAccessingKeystore() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        val keys = AndroidDeviceKeys(application)
        for (operation in listOf<() -> Unit>(
            { keys.encryptCipher("device-bound") },
            { keys.decryptCipher("biometric", ByteArray(12)) },
        )) {
            try { operation(); fail("locked device allowed key access") }
            catch (failure: VaultAccessException) { assertEquals(VaultAccessError.DEVICE_LOCKED, failure.code) }
        }
    }

    @Test fun strongBoxGenerationProviderFailureRetriesWithoutWeakeningTheRequestedMode() {
        val attempts = mutableListOf<Boolean>()
        val result = generateWithStrongBoxFallback(true) { strongBox ->
            attempts += strongBox
            if (strongBox) throw ProviderException("unavailable")
            "fallback"
        }
        assertEquals(listOf(true, false), attempts)
        assertEquals("fallback", result)
    }

    @Test fun strongBoxGenerationSecurityFailureRetriesOnceAndPropagatesFallbackFailure() {
        val attempts = mutableListOf<Boolean>()
        try {
            generateWithStrongBoxFallback(true) { strongBox ->
                attempts += strongBox
                throw GeneralSecurityException("generation failed")
            }
            fail("fallback generation failure hidden")
        } catch (_: GeneralSecurityException) { }
        assertEquals(listOf(true, false), attempts)
    }

    @Test fun modernSystemAuthenticationRequiresEachUseAndAcceptsCredentialOrStrongBiometric() {
        val spec = createDeviceKeySpec("system-modern", "system-auth", false, sdkInt = 30)
        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
            spec.userAuthenticationType)
        assertFalse(spec.isInvalidatedByBiometricEnrollment)
    }

    @Test fun legacySystemAuthenticationUsesAShortCredentialBoundWindow() {
        val spec = createDeviceKeySpec("system-legacy", "system-auth", false, sdkInt = 29)
        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals(15, spec.userAuthenticationValidityDurationSeconds)
        assertFalse(spec.isInvalidatedByBiometricEnrollment)
    }

    @Test fun sdkUpgradeNeverReusesALegacyTimedKeyAsAPerUseSystemKey() {
        assertNotEquals(deviceKeyAlias("example.app", "system-auth", 29), deviceKeyAlias("example.app", "system-auth", 30))
        // Existing M3 keys keep their original identities when the OS is upgraded.
        assertEquals("example.app.session.v1.biometric", deviceKeyAlias("example.app", "biometric", 30))
        assertEquals(deviceKeyAlias("example.app", "device-bound", 29), deviceKeyAlias("example.app", "device-bound", 30))
    }
}
