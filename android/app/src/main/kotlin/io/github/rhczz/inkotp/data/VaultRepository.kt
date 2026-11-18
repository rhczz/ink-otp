package io.github.rhczz.inkotp.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import io.github.rhczz.inkotp.platform.SystemAuthRequest
import io.github.rhczz.inkotp.platform.SystemAuthProof
import io.github.rhczz.inkotp.core.backup.BackupCodec
import io.github.rhczz.inkotp.core.backup.BackupMerge
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.core.crypto.Argon2Kdf
import io.github.rhczz.inkotp.core.crypto.Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.crypto.RandomSource
import io.github.rhczz.inkotp.core.crypto.SecretBytes
import io.github.rhczz.inkotp.core.crypto.SecureRandomSource
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSettings
import io.github.rhczz.inkotp.core.vault.VaultCodec
import io.github.rhczz.inkotp.core.vault.VaultError
import io.github.rhczz.inkotp.core.vault.VaultException
import io.github.rhczz.inkotp.core.vault.VaultHeader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher

/**
 * The caller serializes operations and runs KDF/file work off the main thread.
 * Passphrase arguments are consumed and erased. [model] is owned by this
 * session: its secret arrays are erased when it is replaced or locked.
 */
class VaultRepository internal constructor(
    private val files: VaultFiles,
    private val envelopes: EnvelopeStore,
    private val keys: DeviceKeys,
    private val kdf: Kdf = Argon2Kdf(),
    private val kdfParams: KdfParams = KdfParams.V1,
    private val random: RandomSource = SecureRandomSource(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    constructor(context: Context) : this(
        AtomicVaultFiles(context.applicationContext),
        DataStoreEnvelopeStore(context.applicationContext),
        AndroidDeviceKeys(context.applicationContext),
    )

    var model: VaultModel? = null
        private set
    private var dek: ByteArray? = null
    private var kek: ByteArray? = null
    private var salt: ByteArray? = null
    private var parameters: KdfParams? = null
    private var pendingCipher: Cipher? = null
    private var pendingEnvelope: DeviceEnvelope? = null
    private enum class SystemAction { ENABLE, UNLOCK, DISABLE, RECOVER, REBIND }
    private var pendingSystemRequest: SystemAuthRequest? = null
    private var pendingSystemAction: SystemAction? = null
    private var pendingSystemEnvelope: DeviceEnvelope? = null

    suspend fun exists(): Boolean = files.exists()
    suspend fun hasPassword(): Boolean = header().hasPassword

    suspend fun systemAuthRequired(): Boolean = try { envelopes.systemAuthRequired() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { fail(VaultAccessError.SYSTEM_AUTH_UNAVAILABLE) }

    suspend fun wrapMode(): String {
        if (systemAuthRequired()) return SYSTEM
        val header = header()
        if (!header.hasPassword) return DEVICE
        val envelope = readEnvelope()?.takeIf { it.salt.contentEquals(header.salt) }
        return envelope?.mode?.takeIf { it == BIOMETRIC || it == DEVICE_LOCAL } ?: PASSWORD
    }

    suspend fun initialize(password: CharArray?): VaultModel {
        var newDek: ByteArray? = null
        var newKek: ByteArray? = null
        var committed = false
        try {
            if (files.exists()) fail(VaultAccessError.ALREADY_EXISTS)
            rejectSystemPolicyBypass()
            password?.let(::requireStrongPassword)
            val now = Instant.now().toString()
            val initial = VaultModel(vaultId = UUID.randomUUID().toString(), createdAt = now, updatedAt = now,
                settings = VaultSettings(lockTimeoutSeconds = 0))
            newDek = random.bytes(32)
            val newSalt = if (password != null) random.bytes(16) else null
            newKek = if (password != null) kdf.deriveKey(password, newSalt!!, kdfParams) else null
            val params = if (password != null) kdfParams else null
            val bytes = VaultCodec.sealWithKey(initial, newDek, newKek, newSalt, params, random)
            if (newKek == null) writeEnvelope(sealEnvelope(DEVICE, newDek, null, ByteArray(16)))
            else writeEnvelope(sealLocalEnvelope(newDek, newKek, newSalt!!))
            write(bytes)
            committed = true
            install(initial, newDek, newKek, newSalt, params)
            newDek = null
            newKek = null
            return model!!
        } finally {
            password?.fill('\u0000')
            SecretBytes.wipe(newDek, newKek)
            if (!committed && !files.exists()) {
                // A failed first creation has no previous vault to preserve.
                try { writeEnvelope(null) } catch (_: VaultAccessException) { }
            }
        }
    }

    suspend fun unlockPassword(password: CharArray): VaultModel {
        val opened = openPassword(password, systemAuthorized = false)
        migrateDeviceEnvelope()
        return opened
    }

    private suspend fun openPassword(password: CharArray, systemAuthorized: Boolean): VaultModel {
        var openedDek: ByteArray? = null
        var openedKek: ByteArray? = null
        var openedModel: VaultModel? = null
        try {
            // Failed re-authentication cannot leave a previous unlocked session.
            lock()
            if (!systemAuthorized) rejectSystemPolicyBypass()
            val bytes = read()
            val header = VaultCodec.parseHeader(bytes)
            if (!header.hasPassword) fail(VaultAccessError.PASSWORD_REQUIRED)
            openedKek = kdf.deriveKey(password, header.salt, header.kdfParams!!)
            openedDek = VaultCodec.unwrapDekWithKey(bytes, openedKek)
            openedModel = VaultCodec.openWithDek(bytes, openedDek)
            currentCoroutineContext().ensureActive()
            install(openedModel, openedDek, openedKek, header.salt, header.kdfParams)
            openedModel = null
            openedDek = null
            openedKek = null
            return model!!
        } finally {
            password.fill('\u0000')
            SecretBytes.wipe(openedDek, openedKek)
            discardModel(openedModel)
        }
    }

    suspend fun unlockDevice(): VaultModel {
        lock()
        rejectSystemPolicyBypass()
        val bytes = read()
        val header = VaultCodec.parseHeader(bytes)
        val mode = if (header.hasPassword) DEVICE_LOCAL else DEVICE
        val envelope = readEnvelope()?.takeIf { it.mode == mode && it.salt.contentEquals(header.salt) }
            ?: fail(VaultAccessError.DEVICE_UNAVAILABLE)
        val cipher = deviceOperation { keys.decryptCipher(mode, envelope.iv) }
        return openEnvelope(bytes, header, envelope, cipher)
    }

    suspend fun save(model: VaultModel) {
        val currentDek = dek ?: fail(VaultAccessError.LOCKED)
        val next = copyModel(model)
        try {
            val bytes = VaultCodec.sealWithKey(next, currentDek, kek, salt, parameters, random)
            write(bytes)
        } catch (failure: Exception) {
            discardModel(next)
            throw failure
        }
        discardModel(this.model)
        this.model = next
    }

    suspend fun setPassword(currentPassword: CharArray?, newPassword: CharArray, proof: SystemAuthProof? = null): VaultModel {
        var newKek: ByteArray? = null
        try {
            val current = model ?: fail(VaultAccessError.LOCKED)
            val currentDek = dek ?: fail(VaultAccessError.LOCKED)
            requireStrongPassword(newPassword)
            if (header().hasPassword) {
                if (currentPassword == null) fail(VaultAccessError.PASSWORD_REQUIRED)
                if (!verifyPassword(currentPassword)) fail(VaultAccessError.PASSWORD_INCORRECT)
            }
            val newSalt = random.bytes(16)
            newKek = kdf.deriveKey(newPassword, newSalt, kdfParams)
            val replacement = replacementEnvelope(currentDek, newKek, newSalt, proof)
            val bytes = VaultCodec.sealWithKey(current, currentDek, newKek, newSalt, kdfParams, random)
            write(bytes)
            SecretBytes.wipe(kek, salt)
            kek = newKek
            newKek = null
            salt = newSalt
            parameters = kdfParams
            clearPending()
            saveEnvelopeAfterCommit(replacement)
            return current
        } finally {
            currentPassword?.fill('\u0000')
            newPassword.fill('\u0000')
            SecretBytes.wipe(newKek)
        }
    }

    suspend fun verifyPassword(password: CharArray): Boolean {
        var verifiedDek: ByteArray? = null
        try {
            val bytes = read()
            if (!VaultCodec.parseHeader(bytes).hasPassword) return false
            verifiedDek = try { VaultCodec.unwrapDek(bytes, password, kdf) }
            catch (failure: VaultException) {
                if (failure.code == VaultError.VLT_E_WRAP_AUTH) return false
                throw failure
            }
            val verified = VaultCodec.openWithDek(bytes, verifiedDek)
            discardModel(verified)
            return true
        } finally {
            password.fill('\u0000')
            SecretBytes.wipe(verifiedDek)
        }
    }

    suspend fun exportBackup(password: CharArray): ByteArray {
        try {
            val current = model ?: fail(VaultAccessError.LOCKED)
            if (!hasPassword()) fail(VaultAccessError.PASSWORD_REQUIRED)
            if (!verifyPassword(password.copyOf())) fail(VaultAccessError.PASSWORD_INCORRECT)
            return BackupCodec.seal(current, password, random = random, kdf = kdf)
        } finally { password.fill('\u0000') }
    }

    suspend fun decodeBackup(bytes: ByteArray, password: CharArray): VaultModel = try {
        BackupCodec.open(bytes, password, kdf = kdf)
    } finally { password.fill('\u0000') }

    suspend fun importBackup(
        remote: VaultModel, policy: MergeConflictPolicy, replace: Boolean, proof: SystemAuthProof? = null,
    ): VaultModel {
        val current = model ?: fail(VaultAccessError.LOCKED)
        val currentKek = kek ?: fail(VaultAccessError.PASSWORD_REQUIRED)
        val newDek = random.bytes(32)
        // Imported ids are local identities; collisions must not bypass deduplication.
        val incoming = remote.items.map { it.copy(id = UUID.randomUUID().toString(), secret = it.secret.copyOf()) }
        val merged = if (replace) incoming else BackupMerge.merge(current.items, incoming, policy).items
        val next = copyModel(current.copy(vaultId = UUID.randomUUID().toString(), updatedAt = Instant.now().toString(), items = merged))
        var committed = false
        try {
            val replacement = replacementEnvelope(newDek, currentKek, salt!!, proof)
            val bytes = VaultCodec.sealWithKey(next, newDek, currentKek, salt, parameters, random)
            write(bytes)
            committed = true
            SecretBytes.wipe(dek)
            dek = newDek
            discardModel(model)
            model = next
            clearPending()
            saveEnvelopeAfterCommit(replacement)
            return next
        } finally {
            incoming.forEach { SecretBytes.wipe(it.secret) }
            if (!committed) {
                SecretBytes.wipe(newDek)
                discardModel(next)
            }
        }
    }

    suspend fun prepareUnlock(): Cipher {
        lock()
        rejectSystemPolicyBypass()
        val header = header()
        val envelope = readEnvelope()?.takeIf { it.mode == BIOMETRIC && it.salt.contentEquals(header.salt) }
            ?: fail(VaultAccessError.BIOMETRIC_CHANGED)
        if (!header.hasPassword) fail(VaultAccessError.PASSWORD_REQUIRED)
        val cipher = deviceOperation(VaultAccessError.BIOMETRIC_CHANGED) { keys.decryptCipher(BIOMETRIC, envelope.iv) }
        pendingCipher = cipher
        pendingEnvelope = envelope
        return cipher
    }

    suspend fun unlock(cipher: Cipher): VaultModel {
        rejectSystemPolicyBypass()
        if (cipher !== pendingCipher) fail(VaultAccessError.INVALID_STATE)
        val envelope = pendingEnvelope ?: fail(VaultAccessError.INVALID_STATE)
        try {
            val bytes = read()
            val opened = openEnvelope(bytes, VaultCodec.parseHeader(bytes), envelope, cipher)
            migrateDeviceEnvelope()
            return opened
        } finally { clearPending() }
    }

    suspend fun prepareSystemEnable(): SystemAuthRequest {
        if (model == null) fail(VaultAccessError.LOCKED)
        if (kek == null || !hasPassword()) fail(VaultAccessError.PASSWORD_REQUIRED)
        if (systemAuthRequired()) fail(VaultAccessError.INVALID_STATE)
        clearPending()
        return rememberSystemRequest(SystemAction.ENABLE, if (sdkInt >= 30) systemEncryptCipher() else null)
    }

    suspend fun enableSystemAuth(proof: SystemAuthProof) {
        val authorizedCipher = consumeSystemProof(proof, SystemAction.ENABLE)
        val currentDek = dek ?: fail(VaultAccessError.LOCKED)
        val currentKek = kek ?: fail(VaultAccessError.PASSWORD_REQUIRED)
        try {
            val envelope = sealEnvelope(SYSTEM, currentDek, currentKek, salt!!, authorizedCipher)
            writeSystemConfiguration(envelope, required = true)
            deleteKeys(DEVICE, DEVICE_LOCAL, BIOMETRIC)
        } finally { clearPending() }
    }

    suspend fun prepareSystemUnlock(): SystemAuthRequest {
        lock()
        if (!systemAuthRequired()) fail(VaultAccessError.INVALID_STATE)
        val envelope = currentSystemEnvelope()
        val cipher = if (sdkInt >= 30) systemDeviceOperation { keys.decryptCipher(SYSTEM, envelope.iv) } else null
        pendingSystemEnvelope = envelope
        return rememberSystemRequest(SystemAction.UNLOCK, cipher)
    }

    suspend fun unlockSystemAuth(proof: SystemAuthProof): VaultModel {
        val envelope = pendingSystemEnvelope ?: fail(VaultAccessError.INVALID_STATE)
        val authorizedCipher = consumeSystemProof(proof, SystemAction.UNLOCK)
        try {
            if (!systemAuthRequired()) fail(VaultAccessError.INVALID_STATE)
            val cipher = authorizedCipher ?: systemDeviceOperation { keys.decryptCipher(SYSTEM, envelope.iv) }
            val bytes = read()
            return openEnvelope(bytes, VaultCodec.parseHeader(bytes), envelope, cipher)
        } finally { clearPending() }
    }

    suspend fun prepareSystemDisable(): SystemAuthRequest {
        if (model == null || kek == null) fail(VaultAccessError.LOCKED)
        if (!systemAuthRequired()) fail(VaultAccessError.INVALID_STATE)
        clearPending()
        val envelope = currentSystemEnvelope()
        val cipher = if (sdkInt >= 30) systemDeviceOperation { keys.decryptCipher(SYSTEM, envelope.iv) } else null
        pendingSystemEnvelope = envelope
        return rememberSystemRequest(SystemAction.DISABLE, cipher)
    }

    suspend fun disableSystemAuth(proof: SystemAuthProof) {
        val envelope = pendingSystemEnvelope ?: fail(VaultAccessError.INVALID_STATE)
        val authorizedCipher = consumeSystemProof(proof, SystemAction.DISABLE)
        val expected = (dek ?: fail(VaultAccessError.LOCKED)) + (kek ?: fail(VaultAccessError.PASSWORD_REQUIRED))
        var material: ByteArray? = null
        try {
            if (!systemAuthRequired()) fail(VaultAccessError.INVALID_STATE)
            val cipher = authorizedCipher ?: systemDeviceOperation { keys.decryptCipher(SYSTEM, envelope.iv) }
            cipher.updateAAD(aad(SYSTEM, envelope.salt))
            material = systemDeviceOperation { cipher.doFinal(envelope.ciphertext) }
            if (!MessageDigest.isEqual(expected, material)) fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED)
            val local = sealEnvelope(DEVICE_LOCAL, dek!!, kek!!, salt!!)
            writeSystemConfiguration(local, required = false)
            deleteKeys(SYSTEM)
        } finally { SecretBytes.wipe(expected, material); clearPending() }
    }

    suspend fun prepareSystemRecovery(): SystemAuthRequest {
        lock()
        if (!systemAuthRequired()) fail(VaultAccessError.INVALID_STATE)
        if (!hasPassword()) fail(VaultAccessError.PASSWORD_REQUIRED)
        return rememberSystemRequest(SystemAction.RECOVER, if (sdkInt >= 30) systemEncryptCipher() else null)
    }

    suspend fun recoverSystemAuth(password: CharArray, proof: SystemAuthProof): VaultModel {
        try {
            val authorizedCipher = consumeSystemProof(proof, SystemAction.RECOVER)
            if (!systemAuthRequired()) fail(VaultAccessError.INVALID_STATE)
            val opened = openPassword(password, systemAuthorized = true)
            writeEnvelope(sealEnvelope(SYSTEM, dek!!, kek!!, salt!!, authorizedCipher))
            return opened
        } catch (failure: Exception) {
            lock()
            throw failure
        } finally { password.fill('\u0000'); clearPending() }
    }

    private fun rememberSystemRequest(action: SystemAction, cipher: Cipher?): SystemAuthRequest =
        SystemAuthRequest(cipher).also { pendingSystemAction = action; pendingSystemRequest = it }

    private fun consumeSystemProof(proof: SystemAuthProof, action: SystemAction): Cipher? {
        val request = pendingSystemRequest ?: fail(VaultAccessError.INVALID_STATE)
        if (pendingSystemAction != action || proof.request !== request) fail(VaultAccessError.INVALID_STATE)
        if (!proof.consume(request)) { clearPending(); fail(VaultAccessError.SYSTEM_AUTH_EXPIRED) }
        val cipher = request.cipher
        clearPending()
        return cipher
    }

    private suspend fun currentSystemEnvelope(): DeviceEnvelope {
        val header = header()
        if (!header.hasPassword) fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED)
        return readEnvelope()?.takeIf { it.mode == SYSTEM && it.salt.contentEquals(header.salt) }
            ?: fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED)
    }

    private suspend fun rejectSystemPolicyBypass() {
        if (systemAuthRequired()) fail(VaultAccessError.SYSTEM_AUTH_REQUIRED)
    }

    private suspend fun writeSystemConfiguration(envelope: DeviceEnvelope, required: Boolean) {
        currentCoroutineContext().ensureActive()
        try { envelopes.writeSystemConfiguration(envelope, required) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(VaultAccessError.STORAGE_FAILURE) }
    }

    private fun systemEncryptCipher(): Cipher = systemDeviceOperation {
        try { keys.encryptCipher(SYSTEM) }
        catch (_: KeyPermanentlyInvalidatedException) { keys.delete(SYSTEM); keys.encryptCipher(SYSTEM) }
    }

    private fun <T> systemDeviceOperation(operation: () -> T): T = try { operation() }
    catch (failure: VaultAccessException) {
        if (failure.code == VaultAccessError.DEVICE_UNAVAILABLE) fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED)
        throw failure
    }
    catch (_: KeyPermanentlyInvalidatedException) { fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED) }
    catch (_: UserNotAuthenticatedException) { fail(VaultAccessError.SYSTEM_AUTH_EXPIRED) }
    catch (_: GeneralSecurityException) { fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED) }

    fun lock() {
        clearPending()
        discardModel(model)
        model = null
        SecretBytes.wipe(dek, kek, salt)
        dek = null
        kek = null
        salt = null
        parameters = null
    }

    /** Only for separately owned import previews; never pass a shared save candidate. */
    fun discardModel(value: VaultModel?) { value?.items?.forEach { SecretBytes.wipe(it.secret) } }

    private fun install(value: VaultModel, newDek: ByteArray, newKek: ByteArray?, newSalt: ByteArray?, params: KdfParams?) {
        lock()
        model = value
        dek = newDek
        kek = newKek
        salt = newSalt
        parameters = params
    }

    private fun copyModel(value: VaultModel) = value.copy(items = value.items.map { it.copy(secret = it.secret.copyOf()) })
    private fun clearPending() {
        pendingCipher = null; pendingEnvelope = null
        pendingSystemRequest = null; pendingSystemAction = null; pendingSystemEnvelope = null
    }
    private fun header(): VaultHeader = VaultCodec.parseHeader(read())
    private fun read(): ByteArray = try {
        if (!files.exists()) fail(VaultAccessError.NOT_CREATED)
        files.read()
    } catch (_: IOException) { fail(VaultAccessError.STORAGE_FAILURE) }
    private suspend fun write(bytes: ByteArray) {
        currentCoroutineContext().ensureActive()
        try { files.write(bytes) }
        catch (_: IOException) { fail(VaultAccessError.STORAGE_FAILURE) }
    }

    private suspend fun readEnvelope(): DeviceEnvelope? = try { envelopes.read() }
    catch (_: IOException) { null } // A damaged platform envelope must not prevent password recovery.
    private suspend fun writeEnvelope(value: DeviceEnvelope?) {
        currentCoroutineContext().ensureActive()
        try { envelopes.write(value) }
        catch (_: IOException) { fail(VaultAccessError.STORAGE_FAILURE) }
    }

    suspend fun prepareSystemRebind(): SystemAuthRequest? {
        if (!systemAuthRequired()) return null
        if (model == null || kek == null) fail(VaultAccessError.LOCKED)
        clearPending()
        return rememberSystemRequest(SystemAction.REBIND, if (sdkInt >= 30) systemEncryptCipher() else null)
    }

    private suspend fun replacementEnvelope(
        nextDek: ByteArray, nextKek: ByteArray, nextSalt: ByteArray, proof: SystemAuthProof?,
    ): DeviceEnvelope? {
        if (systemAuthRequired()) {
            val cipher = consumeSystemProof(proof ?: fail(VaultAccessError.SYSTEM_AUTH_REQUIRED), SystemAction.REBIND)
            return sealEnvelope(SYSTEM, nextDek, nextKek, nextSalt, cipher)
        }
        return sealLocalEnvelope(nextDek, nextKek, nextSalt)
    }

    private fun sealLocalEnvelope(dek: ByteArray, kek: ByteArray, salt: ByteArray): DeviceEnvelope? =
        try { sealEnvelope(DEVICE_LOCAL, dek, kek, salt) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // The persisted passphrase wrapper still permits recovery.

    private suspend fun saveEnvelopeAfterCommit(replacement: DeviceEnvelope?) {
        // The new vault is already durable. A stale envelope cannot pass salt and DEK checks.
        // A failed platform write must not invite the user to repeat a successful import.
        try {
            writeEnvelope(replacement)
            if (replacement?.mode == DEVICE_LOCAL) deleteKeys(DEVICE, BIOMETRIC)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: VaultAccessException) { }
    }

    private suspend fun migrateDeviceEnvelope() {
        try {
            val replacement = sealEnvelope(DEVICE_LOCAL, dek!!, kek!!, salt!!)
            writeEnvelope(replacement)
            deleteKeys(DEVICE, BIOMETRIC)
        } catch (cancelled: CancellationException) {
            lock()
            throw cancelled
        } catch (_: Exception) { /* Keep the valid session usable when the local wrapper is unavailable. */ }
    }

    private fun sealEnvelope(
        mode: String, dek: ByteArray, kek: ByteArray?, salt: ByteArray, authorizedCipher: Cipher? = null,
    ): DeviceEnvelope {
        val material = if (kek == null) dek else dek + kek
        try {
            val cipher = if (mode == SYSTEM) authorizedCipher ?: systemEncryptCipher()
                else deviceOperation {
                    try { keys.encryptCipher(mode) }
                    catch (_: KeyPermanentlyInvalidatedException) { keys.delete(mode); keys.encryptCipher(mode) }
                }
            cipher.updateAAD(aad(mode, salt))
            val ciphertext = if (mode == SYSTEM) systemDeviceOperation { cipher.doFinal(material) }
                else deviceOperation { cipher.doFinal(material) }
            return DeviceEnvelope(mode, cipher.iv, ciphertext, salt.copyOf())
        } finally { if (material !== dek) SecretBytes.wipe(material) }
    }

    private suspend fun openEnvelope(bytes: ByteArray, header: VaultHeader, envelope: DeviceEnvelope, cipher: Cipher): VaultModel {
        val unavailable = when (envelope.mode) {
            SYSTEM -> VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED
            BIOMETRIC -> VaultAccessError.BIOMETRIC_CHANGED
            else -> VaultAccessError.DEVICE_UNAVAILABLE
        }
        var material: ByteArray? = null
        var openedDek: ByteArray? = null
        var openedKek: ByteArray? = null
        var openedModel: VaultModel? = null
        try {
            if (!envelope.salt.contentEquals(header.salt)) fail(unavailable)
            cipher.updateAAD(aad(envelope.mode, envelope.salt))
            material = if (envelope.mode == SYSTEM) systemDeviceOperation { cipher.doFinal(envelope.ciphertext) }
                else deviceOperation(unavailable) { cipher.doFinal(envelope.ciphertext) }
            val expectedSize = if (header.hasPassword) 64 else 32
            if (material.size != expectedSize) fail(unavailable)
            openedDek = material.copyOfRange(0, 32)
            if (header.hasPassword) {
                openedKek = material.copyOfRange(32, 64)
                val verifiedDek = try { VaultCodec.unwrapDekWithKey(bytes, openedKek) }
                catch (failure: VaultException) {
                    if (failure.code == VaultError.VLT_E_WRAP_AUTH) fail(unavailable)
                    throw failure
                }
                try { if (!MessageDigest.isEqual(verifiedDek, openedDek)) fail(unavailable) }
                finally { SecretBytes.wipe(verifiedDek) }
            }
            openedModel = VaultCodec.openWithDek(bytes, openedDek)
            currentCoroutineContext().ensureActive()
            install(openedModel, openedDek, openedKek, header.salt.takeIf { header.hasPassword }, header.kdfParams)
            openedModel = null
            openedDek = null
            openedKek = null
            return model!!
        } finally {
            SecretBytes.wipe(material, openedDek, openedKek)
            discardModel(openedModel)
        }
    }

    private fun deleteKeys(vararg modes: String) {
        modes.forEach { mode ->
            try { keys.delete(mode) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The obsolete alias cannot open the current envelope. */ }
        }
    }

    private fun <T> deviceOperation(
        failure: VaultAccessError = VaultAccessError.DEVICE_UNAVAILABLE, block: () -> T,
    ): T = try { block() }
    catch (_: GeneralSecurityException) { fail(failure) }

    private fun aad(mode: String, salt: ByteArray) = "InkOTP/session/v1/$mode\u0000".toByteArray(Charsets.UTF_8) + salt
    private fun requireStrongPassword(password: CharArray) {
        val text = String(password)
        if (text.codePointCount(0, text.length) < 8 || text.all(Char::isDigit) ||
            text.lowercase() in setOf("password", "password1", "password123", "12345678", "qwertyui", "qwerty123", "letmein123")) {
            fail(VaultAccessError.WEAK_PASSWORD)
        }
    }

    private fun fail(code: VaultAccessError): Nothing = throw VaultAccessException(code)
    private companion object {
        const val DEVICE = "device-bound"
        const val DEVICE_LOCAL = "device-local"
        const val PASSWORD = "password-only"
        const val BIOMETRIC = "biometric"
        const val SYSTEM = "system-auth"
    }
}
