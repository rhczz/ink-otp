package io.github.rhczz.inkotp.core.vault

import io.github.rhczz.inkotp.core.crypto.Aead
import io.github.rhczz.inkotp.core.crypto.AeadException
import io.github.rhczz.inkotp.core.crypto.JceAead
import io.github.rhczz.inkotp.core.crypto.Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.crypto.Argon2Kdf
import io.github.rhczz.inkotp.core.crypto.RandomSource
import io.github.rhczz.inkotp.core.crypto.SecretBytes
import io.github.rhczz.inkotp.core.crypto.SecureRandomSource
import io.github.rhczz.inkotp.core.json.VaultJson
import io.github.rhczz.inkotp.core.json.VaultJsonException
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSchemaException

data class OpenedVault(
    val model: VaultModel,
    val dek: ByteArray,
)

/**
 * `vault.bin` v1 codec. See spec/vault-format.md; this is the only
 * implementation of the on-disk layout in the Android app.
 *
 * The header is variable length: [VaultHeader.ciphertextOffset] is 115 with a
 * password channel (63-byte prefix + 32-byte wrapped DEK + 16-byte tag + 4-byte
 * ctLen) and 67 without one (63 + 4). The no-password layout deliberately does
 * not reserve an empty tag.
 */
object VaultCodec {

    private val MAGIC = byteArrayOf(0x32, 0x46, 0x41, 0x56) // "2FAV"
    private const val VERSION = 1
    private const val FLAG_PASSWORD = 1
    private const val KDF_ARGON2ID = 1
    private const val PREFIX_LEN = 63
    private const val WRAPPED_DEK_LEN = 32
    private const val WRAPPED_TAG_LEN = 16
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val DEK_LEN = 32
    private const val TAG_LEN = 16
    private const val MAX_FILE = 12 * 1024 * 1024
    private const val MAX_CT = 8 * 1024 * 1024

    fun seal(
        model: VaultModel,
        dek: ByteArray,
        password: CharArray?,
        kdfParams: KdfParams = KdfParams.V1,
        random: RandomSource = SecureRandomSource(),
        kdf: Kdf = Argon2Kdf(),
        aead: Aead = JceAead(),
    ): ByteArray {
        val salt = if (password != null) random.bytes(SALT_LEN) else null
        val kek = if (password != null) kdf.deriveKey(password, salt!!, kdfParams) else null
        return try {
            sealWithKey(model, dek, kek, salt, if (password != null) kdfParams else null, random, aead)
        } finally {
            SecretBytes.wipe(kek)
        }
    }

    /**
     * Save an unlocked session without retaining its passphrase. The KEK and
     * salt stay stable for the session; both GCM nonces must be fresh on every
     * save because the password-wrap AAD includes the vault nonce.
     */
    fun sealWithKey(
        model: VaultModel,
        dek: ByteArray,
        kek: ByteArray? = null,
        salt: ByteArray? = null,
        kdfParams: KdfParams? = null,
        random: RandomSource = SecureRandomSource(),
        aead: Aead = JceAead(),
    ): ByteArray {
        require(dek.size == DEK_LEN) { "DEK must be 32 bytes" }
        require((kek == null) == (salt == null) && (kek == null) == (kdfParams == null)) {
            "password key, salt and parameters must be supplied together"
        }
        require(kek == null || kek.size == DEK_LEN) { "KEK must be 32 bytes" }
        require(salt == null || (salt.size == SALT_LEN && salt.any { it != 0.toByte() })) { "invalid salt" }
        val json = VaultJson.encode(model)
        try {
            require(json.size in 1..MAX_CT) { "vault JSON is too large" }
            val headerSalt = salt ?: ByteArray(SALT_LEN)
            val dekNonce = ByteArray(NONCE_LEN)
            val vaultNonce = random.bytes(NONCE_LEN)
            val header: ByteArray
            if (kek != null) {
                random.bytes(NONCE_LEN).copyInto(dekNonce)
                val prefix = ByteArray(PREFIX_LEN)
                writePrefix(prefix, hasPassword = true, kdfParams, headerSalt, dekNonce, vaultNonce)
                val wrapped = aead.encrypt(kek, dekNonce, dek, prefix)
                if (wrapped.size != WRAPPED_DEK_LEN + WRAPPED_TAG_LEN) {
                    throw IllegalStateException("unexpected wrapped DEK length")
                }
                header = ByteArray(PREFIX_LEN + WRAPPED_DEK_LEN + WRAPPED_TAG_LEN + 4)
                prefix.copyInto(header, 0)
                wrapped.copyInto(header, PREFIX_LEN)
                writeU32(header, PREFIX_LEN + WRAPPED_DEK_LEN + WRAPPED_TAG_LEN, json.size)
            } else {
                val prefix = ByteArray(PREFIX_LEN)
                writePrefix(prefix, hasPassword = false, null, headerSalt, dekNonce, vaultNonce)
                header = ByteArray(PREFIX_LEN + 4)
                prefix.copyInto(header, 0)
                writeU32(header, PREFIX_LEN, json.size)
            }
            val ctAndTag = aead.encrypt(dek, vaultNonce, json, header)
            if (ctAndTag.size != json.size + TAG_LEN) throw IllegalStateException("unexpected AEAD length")
            return header + ctAndTag
        } finally {
            SecretBytes.wipe(json)
        }
    }

    fun parseHeader(bytes: ByteArray): VaultHeader {
        if (bytes.size > MAX_FILE) fail(VaultError.VLT_E_FILE_TOO_LARGE)
        if (bytes.size < PREFIX_LEN) fail(VaultError.VLT_E_FILE_TRUNCATED)
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) fail(VaultError.VLT_E_MAGIC)
        }
        if ((bytes[4].toInt() and 0xFF) != VERSION) fail(VaultError.VLT_E_VERSION)
        val flags = bytes[5].toInt() and 0xFF
        if ((flags and 0xFE) != 0) fail(VaultError.VLT_E_FLAGS)
        val hasPassword = flags and FLAG_PASSWORD != 0
        val kdfAlgo = bytes[6].toInt() and 0xFF
        val mKiB = readU32(bytes, 7)
        val tIter = readU32(bytes, 11)
        val pLanes = readU32(bytes, 15)
        val salt = bytes.copyOfRange(19, 35)
        val dekNonce = bytes.copyOfRange(35, 47)
        val vaultNonce = bytes.copyOfRange(47, 59)
        val wrappedLenLong = readU32(bytes, 59)
        if (wrappedLenLong > Int.MAX_VALUE) fail(VaultError.VLT_E_WRAPPED_LEN)
        val wrappedLen = wrappedLenLong.toInt()

        val kdfParams: KdfParams?
        val wrappedCt: ByteArray?
        val wrappedTag: ByteArray?
        val ciphertextOffset: Int
        if (hasPassword) {
            if (kdfAlgo != KDF_ARGON2ID) fail(VaultError.VLT_E_KDF_ALGO)
            if (wrappedLen != WRAPPED_DEK_LEN) fail(VaultError.VLT_E_WRAPPED_LEN)
            if (mKiB > Int.MAX_VALUE || tIter > Int.MAX_VALUE || pLanes > Int.MAX_VALUE) {
                fail(VaultError.VLT_E_KDF_PARAMS)
            }
            if (!KdfParams.isValid(mKiB.toInt(), tIter.toInt(), pLanes.toInt())) {
                fail(VaultError.VLT_E_KDF_PARAMS)
            }
            if (salt.all { it == 0.toByte() }) fail(VaultError.VLT_E_FLAGS)
            ciphertextOffset = PREFIX_LEN + WRAPPED_DEK_LEN + WRAPPED_TAG_LEN + 4
            if (bytes.size < ciphertextOffset) fail(VaultError.VLT_E_FILE_TRUNCATED)
            kdfParams = KdfParams(mKiB.toInt(), tIter.toInt(), pLanes.toInt())
            wrappedCt = bytes.copyOfRange(PREFIX_LEN, PREFIX_LEN + WRAPPED_DEK_LEN)
            wrappedTag = bytes.copyOfRange(
                PREFIX_LEN + WRAPPED_DEK_LEN,
                PREFIX_LEN + WRAPPED_DEK_LEN + WRAPPED_TAG_LEN,
            )
        } else {
            if (kdfAlgo != 0) fail(VaultError.VLT_E_KDF_ALGO)
            if (wrappedLen != 0) fail(VaultError.VLT_E_WRAPPED_LEN)
            if (mKiB != 0L || tIter != 0L || pLanes != 0L) fail(VaultError.VLT_E_KDF_PARAMS)
            if (salt.any { it != 0.toByte() } || dekNonce.any { it != 0.toByte() }) {
                fail(VaultError.VLT_E_FLAGS)
            }
            ciphertextOffset = PREFIX_LEN + 4
            if (bytes.size < ciphertextOffset) fail(VaultError.VLT_E_FILE_TRUNCATED)
            kdfParams = null
            wrappedCt = null
            wrappedTag = null
        }

        val ctLenLong = readU32(bytes, ciphertextOffset - 4)
        if (ctLenLong <= 0 || ctLenLong > MAX_CT) fail(VaultError.VLT_E_CT_LEN)
        val ctLen = ctLenLong.toInt()
        val expectedLen = ciphertextOffset.toLong() + ctLen + TAG_LEN
        if (bytes.size < expectedLen) fail(VaultError.VLT_E_FILE_TRUNCATED)
        if (bytes.size > expectedLen) fail(VaultError.VLT_E_TRAILING_DATA)

        return VaultHeader(
            hasPassword = hasPassword,
            kdfParams = kdfParams,
            salt = salt,
            dekNonce = dekNonce,
            vaultNonce = vaultNonce,
            wrappedDekCiphertext = wrappedCt,
            wrappedDekTag = wrappedTag,
            ctLen = ctLen,
            ciphertextOffset = ciphertextOffset,
            tagOffset = ciphertextOffset + ctLen,
            fileLen = expectedLen.toInt(),
        )
    }

    fun openWithDek(
        bytes: ByteArray,
        dek: ByteArray,
        aead: Aead = JceAead(),
    ): VaultModel {
        require(dek.size == DEK_LEN) { "DEK must be 32 bytes" }
        val header = parseHeader(bytes)
        val aad = bytes.copyOfRange(0, header.ciphertextOffset)
        val ctAndTag = bytes.copyOfRange(header.ciphertextOffset, header.fileLen)
        val plain = try {
            aead.decrypt(dek, header.vaultNonce, ctAndTag, aad)
        } catch (_: AeadException) {
            fail(VaultError.VLT_E_VAULT_AUTH)
        }
        return try {
            VaultJson.decode(plain)
        } catch (_: VaultJsonException) {
            fail(VaultError.VLT_E_JSON)
        } catch (_: VaultSchemaException) {
            fail(VaultError.VLT_E_SCHEMA)
        } finally {
            SecretBytes.wipe(plain)
        }
    }

    fun unwrapDek(
        bytes: ByteArray,
        password: CharArray,
        kdf: Kdf = Argon2Kdf(),
        aead: Aead = JceAead(),
    ): ByteArray {
        val header = parseHeader(bytes)
        if (!header.hasPassword) fail(VaultError.VLT_E_FLAGS)
        val params = header.kdfParams ?: fail(VaultError.VLT_E_KDF_PARAMS)
        var kek: ByteArray? = null
        try {
            kek = kdf.deriveKey(password, header.salt, params)
            return unwrapDekWithKey(bytes, kek, aead)
        } finally {
            SecretBytes.wipe(kek)
        }
    }

    /** Authenticate the retained session KEK against the current v1 header. */
    fun unwrapDekWithKey(bytes: ByteArray, kek: ByteArray, aead: Aead = JceAead()): ByteArray {
        require(kek.size == DEK_LEN) { "KEK must be 32 bytes" }
        val header = parseHeader(bytes)
        if (!header.hasPassword) fail(VaultError.VLT_E_FLAGS)
        return try {
            aead.decrypt(kek, header.dekNonce, header.wrappedDekCiphertext!! + header.wrappedDekTag!!,
                bytes.copyOfRange(0, PREFIX_LEN))
        } catch (_: AeadException) {
            fail(VaultError.VLT_E_WRAP_AUTH)
        }
    }

    fun openWithPassword(
        bytes: ByteArray,
        password: CharArray,
        kdf: Kdf = Argon2Kdf(),
        aead: Aead = JceAead(),
    ): OpenedVault {
        val dek = unwrapDek(bytes, password, kdf, aead)
        return try {
            OpenedVault(model = openWithDek(bytes, dek, aead), dek = dek)
        } catch (exception: RuntimeException) {
            SecretBytes.wipe(dek)
            throw exception
        }
    }

    private fun writePrefix(
        prefix: ByteArray,
        hasPassword: Boolean,
        params: KdfParams?,
        salt: ByteArray,
        dekNonce: ByteArray,
        vaultNonce: ByteArray,
    ) {
        MAGIC.copyInto(prefix, 0)
        prefix[4] = VERSION.toByte()
        prefix[5] = (if (hasPassword) FLAG_PASSWORD else 0).toByte()
        prefix[6] = (if (hasPassword) KDF_ARGON2ID else 0).toByte()
        if (hasPassword) {
            requireNotNull(params)
            writeU32(prefix, 7, params.mKiB)
            writeU32(prefix, 11, params.tIter)
            writeU32(prefix, 15, params.pLanes)
        }
        salt.copyInto(prefix, 19)
        dekNonce.copyInto(prefix, 35)
        vaultNonce.copyInto(prefix, 47)
        writeU32(prefix, 59, if (hasPassword) WRAPPED_DEK_LEN else 0)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) fail(VaultError.VLT_E_FILE_TRUNCATED)
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun fail(code: VaultError): Nothing = throw VaultException(code)
}
