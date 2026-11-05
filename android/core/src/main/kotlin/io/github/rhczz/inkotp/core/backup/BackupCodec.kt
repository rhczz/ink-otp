package io.github.rhczz.inkotp.core.backup

import io.github.rhczz.inkotp.core.crypto.Aead
import io.github.rhczz.inkotp.core.crypto.AeadException
import io.github.rhczz.inkotp.core.crypto.Argon2Kdf
import io.github.rhczz.inkotp.core.crypto.JceAead
import io.github.rhczz.inkotp.core.crypto.Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.core.crypto.RandomSource
import io.github.rhczz.inkotp.core.crypto.SecretBytes
import io.github.rhczz.inkotp.core.crypto.SecureRandomSource
import io.github.rhczz.inkotp.core.json.VaultJson
import io.github.rhczz.inkotp.core.json.VaultJsonException
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSchemaException

/**
 * `.2fab` v1 codec. See spec/backup-format.md.
 *
 * The backup ciphertext is AES-256-GCM(KEK, vault JSON) directly: there is no
 * DEK inside the backup. Import generates a new local DEK and re-encrypts the
 * vault (ADR-019, design doc §7.4).
 */
object BackupCodec {

    private val MAGIC = byteArrayOf(0x32, 0x46, 0x41, 0x42) // "2FAB"
    private const val VERSION = 1
    private const val KDF_ARGON2ID = 1
    private const val HEADER_LEN = 50
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_LEN = 16
    private const val MAX_FILE = 12 * 1024 * 1024
    private const val MAX_CT = 8 * 1024 * 1024

    fun seal(
        model: VaultModel,
        password: CharArray,
        kdfParams: KdfParams = KdfParams.V1,
        random: RandomSource = SecureRandomSource(),
        kdf: Kdf = Argon2Kdf(),
        aead: Aead = JceAead(),
    ): ByteArray {
        val json = VaultJson.encode(model)
        require(json.size in 1..MAX_CT) { "vault JSON is too large" }
        val salt = random.bytes(SALT_LEN)
        val nonce = random.bytes(NONCE_LEN)
        var kek: ByteArray? = null
        try {
            val header = ByteArray(HEADER_LEN)
            MAGIC.copyInto(header, 0)
            header[4] = VERSION.toByte()
            header[5] = KDF_ARGON2ID.toByte()
            writeU32(header, 6, kdfParams.mKiB)
            writeU32(header, 10, kdfParams.tIter)
            writeU32(header, 14, kdfParams.pLanes)
            salt.copyInto(header, 18)
            nonce.copyInto(header, 34)
            writeU32(header, 46, json.size)
            kek = kdf.deriveKey(password, salt, kdfParams)
            val ctAndTag = aead.encrypt(kek, nonce, json, header)
            if (ctAndTag.size != json.size + TAG_LEN) throw IllegalStateException("unexpected AEAD length")
            return header + ctAndTag
        } finally {
            SecretBytes.wipe(kek, json)
        }
    }

    fun parseHeader(bytes: ByteArray): BackupHeader {
        if (bytes.size > MAX_FILE) fail(BackupError.BAK_E_FILE_TOO_LARGE)
        if (bytes.size < HEADER_LEN) fail(BackupError.BAK_E_FILE_TRUNCATED)
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) fail(BackupError.BAK_E_MAGIC)
        }
        if ((bytes[4].toInt() and 0xFF) != VERSION) fail(BackupError.BAK_E_VERSION)
        if ((bytes[5].toInt() and 0xFF) != KDF_ARGON2ID) fail(BackupError.BAK_E_KDF_ALGO)
        val mKiB = readU32(bytes, 6)
        val tIter = readU32(bytes, 10)
        val pLanes = readU32(bytes, 14)
        if (mKiB > Int.MAX_VALUE || tIter > Int.MAX_VALUE || pLanes > Int.MAX_VALUE) {
            fail(BackupError.BAK_E_KDF_PARAMS)
        }
        if (!KdfParams.isValid(mKiB.toInt(), tIter.toInt(), pLanes.toInt())) {
            fail(BackupError.BAK_E_KDF_PARAMS)
        }
        val salt = bytes.copyOfRange(18, 34)
        val nonce = bytes.copyOfRange(34, 46)
        val ctLenLong = readU32(bytes, 46)
        if (ctLenLong <= 0 || ctLenLong > MAX_CT) fail(BackupError.BAK_E_CT_LEN)
        val ctLen = ctLenLong.toInt()
        val expectedLen = HEADER_LEN.toLong() + ctLen + TAG_LEN
        if (bytes.size < expectedLen) fail(BackupError.BAK_E_FILE_TRUNCATED)
        if (bytes.size > expectedLen) fail(BackupError.BAK_E_TRAILING_DATA)
        return BackupHeader(
            version = VERSION,
            kdfParams = KdfParams(mKiB.toInt(), tIter.toInt(), pLanes.toInt()),
            salt = salt,
            nonce = nonce,
            ctLen = ctLen,
            fileLen = expectedLen.toInt(),
        )
    }

    fun inspect(bytes: ByteArray): BackupHeader = parseHeader(bytes)

    fun open(
        bytes: ByteArray,
        password: CharArray,
        kdf: Kdf = Argon2Kdf(),
        aead: Aead = JceAead(),
    ): VaultModel {
        val header = parseHeader(bytes)
        val aad = bytes.copyOfRange(0, HEADER_LEN)
        val ctAndTag = bytes.copyOfRange(HEADER_LEN, header.fileLen)
        var kek: ByteArray? = null
        val plain = try {
            kek = kdf.deriveKey(password, header.salt, header.kdfParams)
            aead.decrypt(kek, header.nonce, ctAndTag, aad)
        } catch (_: AeadException) {
            fail(BackupError.BAK_E_AUTH)
        } finally {
            SecretBytes.wipe(kek)
        }
        return try {
            VaultJson.decode(plain)
        } catch (_: VaultJsonException) {
            fail(BackupError.BAK_E_JSON)
        } catch (_: VaultSchemaException) {
            fail(BackupError.BAK_E_SCHEMA)
        } finally {
            SecretBytes.wipe(plain)
        }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) fail(BackupError.BAK_E_FILE_TRUNCATED)
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

    private fun fail(code: BackupError): Nothing = throw BackupException(code)
}
