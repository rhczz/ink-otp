package io.github.rhczz.inkotp.data

import android.content.Context
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface DeviceKeys {
    fun encryptCipher(mode: String): Cipher
    fun decryptCipher(mode: String, iv: ByteArray): Cipher
    fun delete(mode: String)
}

internal class AndroidDeviceKeys(private val context: Context) : DeviceKeys {
    private val keyStore: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(mode: String) = deviceKeyAlias(context.packageName, mode)

    override fun encryptCipher(mode: String): Cipher {
        requireDeviceUnlocked()
        val key = keyStore.getKey(alias(mode), null) as? SecretKey ?: generate(mode)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(mode: String, iv: ByteArray): Cipher {
        requireDeviceUnlocked()
        val key = keyStore.getKey(alias(mode), null) as? SecretKey
            ?: throw VaultAccessException(VaultAccessError.DEVICE_UNAVAILABLE)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    override fun delete(mode: String) {
        val store = keyStore
        if (mode == "system-auth") {
            store.deleteEntry(deviceKeyAlias(context.packageName, mode, sdkInt = 29))
            store.deleteEntry(deviceKeyAlias(context.packageName, mode, sdkInt = 30))
        } else store.deleteEntry(alias(mode))
    }

    private fun generate(mode: String): SecretKey {
        val strongBox = Build.VERSION.SDK_INT >= 28 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return generateWithStrongBoxFallback(strongBox) { generate(mode, it) }
    }

    private fun generate(mode: String, strongBox: Boolean): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(createDeviceKeySpec(alias(mode), mode, strongBox))
        }.generateKey()
    }

    private fun requireDeviceUnlocked() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || keyguard.isDeviceLocked) throw VaultAccessException(VaultAccessError.DEVICE_LOCKED)
    }
}

internal fun deviceKeyAlias(packageName: String, mode: String, sdkInt: Int = Build.VERSION.SDK_INT): String {
    val policy = if (mode == "system-auth") "$mode.${if (sdkInt >= 30) "per-use" else "legacy-window"}" else mode
    return "$packageName.session.v1.$policy"
}

/** Parameter policy only; hardware enforcement still requires device testing. */
@Suppress("DEPRECATION")
internal fun createDeviceKeySpec(
    alias: String,
    mode: String,
    strongBox: Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT,
): KeyGenParameterSpec {
    require(mode == "biometric" || mode == "device-bound" || mode == "system-auth") { "unsupported device wrap mode" }
    val biometric = mode == "biometric"
    val system = mode == "system-auth"
    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(biometric || system)
    if (biometric) {
        spec.setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30 && sdkInt >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else spec.setUserAuthenticationValidityDurationSeconds(-1)
    }
    if (system) {
        spec.setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= 30 && sdkInt >= 30) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            // Legacy credentials cannot authorize per-use CryptoObject operations.
            // A fresh Keyguard result is additionally required by the repository for every request.
            spec.setUserAuthenticationValidityDurationSeconds(15)
        }
    }
    // Android 12–14 have known availability/key-loss bugs with this flag.
    // Older versions use the explicit Keyguard gate above (ADR-023 / EX-003).
    if (Build.VERSION.SDK_INT >= 35 && sdkInt >= 35) spec.setUnlockedDeviceRequired(true)
    if (Build.VERSION.SDK_INT >= 28 && sdkInt >= 28) spec.setIsStrongBoxBacked(strongBox)
    return spec.build()
}

/** Retry only key generation; never wrap Cipher.init or authentication in this fallback. */
internal fun <T> generateWithStrongBoxFallback(preferStrongBox: Boolean, generate: (Boolean) -> T): T {
    if (!preferStrongBox) return generate(false)
    return try { generate(true) }
    catch (_: ProviderException) { generate(false) }
    catch (_: GeneralSecurityException) { generate(false) }
}
