package io.github.rhczz.inkotp.core.backup

enum class BackupError {
    BAK_E_FILE_TOO_LARGE,
    BAK_E_FILE_TRUNCATED,
    BAK_E_TRAILING_DATA,
    BAK_E_MAGIC,
    BAK_E_VERSION,
    BAK_E_KDF_ALGO,
    BAK_E_KDF_PARAMS,
    BAK_E_CT_LEN,
    BAK_E_AUTH,
    BAK_E_JSON,
    BAK_E_SCHEMA,
}

class BackupException(
    val code: BackupError,
) : IllegalArgumentException(code.name)

data class BackupHeader(
    val version: Int,
    val kdfParams: io.github.rhczz.inkotp.core.crypto.KdfParams,
    val salt: ByteArray,
    val nonce: ByteArray,
    val ctLen: Int,
    val fileLen: Int,
)
