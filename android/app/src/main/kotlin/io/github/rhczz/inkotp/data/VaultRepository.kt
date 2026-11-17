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
    private var pendingEnable = false
    private var wrapsSuppressed = false
    private enum class SystemAction { ENABLE, UNLOCK, DISABLE, RECOVER }
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
        if (wrapsSuppressed) return PASSWORD
        val envelope = readEnvelope()
        return if (envelope?.mode == BIOMETRIC && envelope.salt.contentEquals(header.salt)) BIOMETRIC else PASSWORD
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
            if (password == null) {
                val cipher = deviceOperation { keys.encryptCipher(DEVICE) }
                val envelopeSalt = ByteArray(16)
                cipher.updateAAD(aad(DEVICE, envelopeSalt))
                writeEnvelope(DeviceEnvelope(DEVICE, cipher.iv, cipher.doFinal(newDek), envelopeSalt))
            } else writeEnvelope(null)
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

    suspend fun unlockPassword(password: CharArray): VaultModel = openPassword(password, systemAuthorized = false)

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
        if (VaultCodec.parseHeader(bytes).hasPassword) fail(VaultAccessError.PASSWORD_REQUIRED)
        val envelope = readEnvelope()?.takeIf { it.mode == DEVICE }
            ?: fail(VaultAccessError.DEVICE_UNAVAILABLE)
        var openedDek: ByteArray? = null
        var openedModel: VaultModel? = null
        try {
            val cipher = deviceOperation { keys.decryptCipher(DEVICE, envelope.iv) }
            cipher.updateAAD(aad(DEVICE, envelope.salt))
            openedDek = deviceOperation { cipher.doFinal(envelope.ciphertext) }
            if (openedDek.size != 32) fail(VaultAccessError.DEVICE_UNAVAILABLE)
            openedModel = VaultCodec.openWithDek(bytes, openedDek)
            currentCoroutineContext().ensureActive()
            install(openedModel, openedDek, null, null, null)
            openedModel = null
            openedDek = null
            return model!!
        } finally { SecretBytes.wipe(openedDek); discardModel(openedModel) }
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

    suspend fun setPassword(currentPassword: CharArray?, newPassword: CharArray): VaultModel {
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
            val bytes = VaultCodec.sealWithKey(current, currentDek, newKek, newSalt, kdfParams, random)
            write(bytes)
            SecretBytes.wipe(kek, salt)
            kek = newKek
            newKek = null
            salt = newSalt
            parameters = kdfParams
            clearPending()
            clearDeviceWrapsAfterCommit()
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

    suspend fun importBackup(remote: VaultModel, policy: MergeConflictPolicy, replace: Boolean): VaultModel {
        val current = model ?: fail(VaultAccessError.LOCKED)
        val currentKek = kek ?: fail(VaultAccessError.PASSWORD_REQUIRED)
        val newDek = random.bytes(32)
        // Imported ids are local identities; collisions must not bypass deduplication.
        val incoming = remote.items.map { it.copy(id = UUID.randomUUID().toString(), secret = it.secret.copyOf()) }
        val merged = if (replace) incoming else BackupMerge.merge(current.items, incoming, policy).items
        val next = copyModel(current.copy(vaultId = UUID.randomUUID().toString(), updatedAt = Instant.now().toString(), items = merged))
        var committed = false
        try {
            val bytes = VaultCodec.sealWithKey(next, newDek, currentKek, salt, parameters, random)
            write(bytes)
            committed = true
            SecretBytes.wipe(dek)
            dek = newDek
            discardModel(model)
            model = next
            clearPending()
            clearDeviceWrapsAfterCommit()
            return next
        } finally {
            incoming.forEach { SecretBytes.wipe(it.secret) }
            if (!committed) {
                SecretBytes.wipe(newDek)
                discardModel(next)
            }
        }
    }

    suspend fun prepareEnable(): Cipher {
        rejectSystemPolicyBypass()
        if (model == null) fail(VaultAccessError.LOCKED)
        if (kek == null || !hasPassword()) fail(VaultAccessError.PASSWORD_REQUIRED)
        clearPending()
        val cipher = try { keys.encryptCipher(BIOMETRIC) }
        catch (_: KeyPermanentlyInvalidatedException) {
            keys.delete(BIOMETRIC)
            deviceOperation { keys.encryptCipher(BIOMETRIC) }
        }
        pendingCipher = cipher
        pendingEnable = true
        return cipher
    }

    suspend fun enable(cipher: Cipher) {
        rejectSystemPolicyBypass()
        if (cipher !== pendingCipher || !pendingEnable) fail(VaultAccessError.INVALID_STATE)
        val currentDek = dek ?: fail(VaultAccessError.LOCKED)
        val currentKek = kek ?: fail(VaultAccessError.PASSWORD_REQUIRED)
        val material = currentDek + currentKek
        try {
            cipher.updateAAD(aad(BIOMETRIC, salt!!))
            val ciphertext = deviceOperation { cipher.doFinal(material) }
            writeEnvelope(DeviceEnvelope(BIOMETRIC, cipher.iv, ciphertext, salt!!.copyOf()))
            wrapsSuppressed = false
            // The password header already forbids device-bound unlock. A stale
            // unused alias must not turn a committed enable into a false failure.
            try { keys.delete(DEVICE) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        } finally {
            SecretBytes.wipe(material)
            clearPending()
        }
    }

    suspend fun prepareUnlock(): Cipher {
        lock()
        rejectSystemPolicyBypass()
        if (wrapsSuppressed) fail(VaultAccessError.BIOMETRIC_CHANGED)
        val header = header()
        val envelope = readEnvelope()?.takeIf { it.mode == BIOMETRIC && it.salt.contentEquals(header.salt) }
            ?: fail(VaultAccessError.BIOMETRIC_CHANGED)
        if (!header.hasPassword) fail(VaultAccessError.PASSWORD_REQUIRED)
        val cipher = deviceOperation { keys.decryptCipher(BIOMETRIC, envelope.iv) }
        pendingCipher = cipher
        pendingEnvelope = envelope
        return cipher
    }

    suspend fun unlock(cipher: Cipher): VaultModel {
        rejectSystemPolicyBypass()
        if (cipher !== pendingCipher || pendingEnable) fail(VaultAccessError.INVALID_STATE)
        val envelope = pendingEnvelope ?: fail(VaultAccessError.INVALID_STATE)
        var material: ByteArray? = null
        var openedDek: ByteArray? = null
        var openedKek: ByteArray? = null
        var openedModel: VaultModel? = null
        try {
            cipher.updateAAD(aad(BIOMETRIC, envelope.salt))
            material = deviceOperation { cipher.doFinal(envelope.ciphertext) }
            if (material.size != 64) fail(VaultAccessError.BIOMETRIC_CHANGED)
            openedDek = material.copyOfRange(0, 32)
            openedKek = material.copyOfRange(32, 64)
            val bytes = read()
            val header = VaultCodec.parseHeader(bytes)
            val verifiedDek = VaultCodec.unwrapDekWithKey(bytes, openedKek)
            val matches = MessageDigest.isEqual(verifiedDek, openedDek)
            SecretBytes.wipe(verifiedDek)
            if (!matches) fail(VaultAccessError.BIOMETRIC_CHANGED)
            openedModel = VaultCodec.openWithDek(bytes, openedDek)
            currentCoroutineContext().ensureActive()
            install(openedModel, openedDek, openedKek, header.salt, header.kdfParams)
            openedModel = null
            openedDek = null
            openedKek = null
            return model!!
        } finally {
            SecretBytes.wipe(material, openedDek, openedKek)
            discardModel(openedModel)
            clearPending()
        }
    }

    suspend fun disableBiometric() {
        rejectSystemPolicyBypass()
        if (model == null || kek == null) fail(VaultAccessError.LOCKED)
        clearPending()
        clearDeviceWraps()
        wrapsSuppressed = true
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
        val material = currentDek + currentKek
        try {
            val cipher = authorizedCipher ?: systemEncryptCipher()
            cipher.updateAAD(aad(SYSTEM, salt!!))
            val ciphertext = systemDeviceOperation { cipher.doFinal(material) }
            writeEnvelope(DeviceEnvelope(SYSTEM, cipher.iv, ciphertext, salt!!.copyOf()))
            writeSystemPolicy(true)
            wrapsSuppressed = false
            try { keys.delete(DEVICE); keys.delete(BIOMETRIC) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The committed system policy rejects these legacy unlock paths. */ }
        } finally { SecretBytes.wipe(material); clearPending() }
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
        var material: ByteArray? = null
        var openedDek: ByteArray? = null
        var openedKek: ByteArray? = null
        var openedModel: VaultModel? = null
        try {
            if (!systemAuthRequired()) fail(VaultAccessError.INVALID_STATE)
            val cipher = authorizedCipher ?: systemDeviceOperation { keys.decryptCipher(SYSTEM, envelope.iv) }
            cipher.updateAAD(aad(SYSTEM, envelope.salt))
            material = systemDeviceOperation { cipher.doFinal(envelope.ciphertext) }
            if (material.size != 64) fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED)
            openedDek = material.copyOfRange(0, 32)
            openedKek = material.copyOfRange(32, 64)
            val bytes = read()
            val header = VaultCodec.parseHeader(bytes)
            val verifiedDek = VaultCodec.unwrapDekWithKey(bytes, openedKek)
            val matches = MessageDigest.isEqual(verifiedDek, openedDek)
            SecretBytes.wipe(verifiedDek)
            if (!matches || !header.salt.contentEquals(envelope.salt)) fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED)
            openedModel = VaultCodec.openWithDek(bytes, openedDek)
            currentCoroutineContext().ensureActive()
            install(openedModel, openedDek, openedKek, header.salt, header.kdfParams)
            openedModel = null; openedDek = null; openedKek = null
            return model!!
        } finally {
            SecretBytes.wipe(material, openedDek, openedKek)
            discardModel(openedModel)
            clearPending()
        }
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
            // Clear the envelope first: a failure leaves the independent policy enabled, never bypassed.
            writeEnvelope(null)
            writeSystemPolicy(false)
            wrapsSuppressed = true
            try { keys.delete(SYSTEM) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
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
            val material = dek!! + kek!!
            try {
                val cipher = authorizedCipher ?: systemEncryptCipher()
                cipher.updateAAD(aad(SYSTEM, salt!!))
                val ciphertext = systemDeviceOperation { cipher.doFinal(material) }
                writeEnvelope(DeviceEnvelope(SYSTEM, cipher.iv, ciphertext, salt!!.copyOf()))
                wrapsSuppressed = false
                return opened
            } finally { SecretBytes.wipe(material) }
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
        if (!header.hasPassword || wrapsSuppressed) fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED)
        return readEnvelope()?.takeIf { it.mode == SYSTEM && it.salt.contentEquals(header.salt) }
            ?: fail(VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED)
    }

    private suspend fun rejectSystemPolicyBypass() {
        if (systemAuthRequired()) fail(VaultAccessError.SYSTEM_AUTH_REQUIRED)
    }

    private suspend fun writeSystemPolicy(required: Boolean) {
        currentCoroutineContext().ensureActive()
        try { envelopes.setSystemAuthRequired(required) }
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
        pendingCipher = null; pendingEnvelope = null; pendingEnable = false
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

    private suspend fun clearDeviceWraps() {
        writeEnvelope(null)
        keys.delete(BIOMETRIC)
        keys.delete(DEVICE)
        keys.delete(SYSTEM)
    }

    private suspend fun clearDeviceWrapsAfterCommit() {
        // The new authenticated vault is already durable. Cleanup failure must
        // not report a failed password change/import or invite a destructive retry.
        // Old wrappers cannot pass the current salt + DEK/KEK authentication.
        wrapsSuppressed = true
        try { clearDeviceWraps() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Recover with the committed local password. */ }
    }

    private fun <T> deviceOperation(block: () -> T): T = try { block() }
    catch (_: KeyPermanentlyInvalidatedException) { fail(VaultAccessError.BIOMETRIC_CHANGED) }
    catch (_: GeneralSecurityException) { fail(VaultAccessError.DEVICE_UNAVAILABLE) }

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
        const val PASSWORD = "password-only"
        const val BIOMETRIC = "biometric"
        const val SYSTEM = "system-auth"
    }
}
