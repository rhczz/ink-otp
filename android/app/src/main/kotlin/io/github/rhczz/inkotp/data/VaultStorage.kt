package io.github.rhczz.inkotp.data

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

internal interface VaultFiles {
    fun exists(): Boolean
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

internal class AtomicVaultFiles(
    private val base: File,
    private val finishWrite: (AtomicFile, FileOutputStream) -> Unit = { file, output -> file.finishWrite(output) },
) : VaultFiles {
    constructor(context: Context) : this(File(context.filesDir, "vault.bin"))
    private val file = AtomicFile(base)

    override fun exists() = base.exists() || File(base.path + ".bak").exists()

    override fun read(): ByteArray = file.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_FILE_BYTES) throw IOException("vault size limit")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    override fun write(bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            // AtomicFile logs some sync/rename failures instead of throwing.
            // An explicit sync plus reopening the committed base file prevents
            // the session from advancing on a silently abandoned replacement.
            output.fd.sync()
            finishWrite(file, output)
            if (!MessageDigest.isEqual(bytes, read())) throw IOException("vault commit verification failed")
        } catch (failure: Exception) {
            try { file.failWrite(output) } catch (_: Exception) { /* Preserve the commit failure. */ }
            throw failure
        }
    }

    private companion object { const val MAX_FILE_BYTES = 12 * 1024 * 1024 }
}

internal data class DeviceEnvelope(
    val mode: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val salt: ByteArray,
)

internal interface EnvelopeStore {
    suspend fun read(): DeviceEnvelope?
    suspend fun write(value: DeviceEnvelope?)
    suspend fun systemAuthRequired(): Boolean = false
    suspend fun setSystemAuthRequired(required: Boolean) { throw IOException("policy persistence unavailable") }
}

// One delegate provides one DataStore instance per application/file.
private val Context.deviceWrapDataStore by preferencesDataStore(name = "device_wrap")

internal class DataStoreEnvelopeStore(context: Context) : EnvelopeStore {
    private val store = context.applicationContext.deviceWrapDataStore
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    override suspend fun read(): DeviceEnvelope? {
        val values = store.data.first()
        val mode = values[MODE] ?: return null
        if (mode != "biometric" && mode != "device-bound" && mode != "system-auth") throw IOException("invalid wrap mode")
        return try {
            val iv = decoder.decode(values[IV] ?: throw IOException("missing iv"))
            val ct = decoder.decode(values[CIPHERTEXT] ?: throw IOException("missing ciphertext"))
            val salt = decoder.decode(values[SALT] ?: throw IOException("missing salt"))
            if (iv.size != 12 || ct.size != (if (mode == "device-bound") 48 else 80) || salt.size != 16) {
                throw IOException("invalid envelope length")
            }
            DeviceEnvelope(mode, iv, ct, salt)
        } catch (_: IllegalArgumentException) {
            throw IOException("invalid envelope encoding")
        }
    }

    override suspend fun write(value: DeviceEnvelope?) {
        store.edit { values ->
            // Authentication policy is independent of the replaceable encrypted envelope.
            values.remove(MODE); values.remove(IV); values.remove(CIPHERTEXT); values.remove(SALT)
            if (value != null) {
                values[MODE] = value.mode
                values[IV] = encoder.encodeToString(value.iv)
                values[CIPHERTEXT] = encoder.encodeToString(value.ciphertext)
                values[SALT] = encoder.encodeToString(value.salt)
            }
        }
    }

    override suspend fun systemAuthRequired(): Boolean = store.data.first()[SYSTEM_REQUIRED] ?: false

    override suspend fun setSystemAuthRequired(required: Boolean) {
        store.edit { it[SYSTEM_REQUIRED] = required }
    }

    private companion object {
        val MODE = stringPreferencesKey("wrapMode")
        val IV = stringPreferencesKey("iv")
        val CIPHERTEXT = stringPreferencesKey("ciphertext")
        val SALT = stringPreferencesKey("salt")
        val SYSTEM_REQUIRED = booleanPreferencesKey("systemAuthRequired")
    }
}
