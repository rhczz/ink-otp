package io.github.rhczz.inkotp.core.vault

enum class VaultError {
    VLT_E_FILE_TOO_LARGE,
    VLT_E_FILE_TRUNCATED,
    VLT_E_TRAILING_DATA,
    VLT_E_MAGIC,
    VLT_E_VERSION,
    VLT_E_FLAGS,
    VLT_E_KDF_ALGO,
    VLT_E_KDF_PARAMS,
    VLT_E_WRAPPED_LEN,
    VLT_E_CT_LEN,
    VLT_E_WRAP_AUTH,
    VLT_E_VAULT_AUTH,
    VLT_E_JSON,
    VLT_E_SCHEMA,
    VLT_E_DEVICE_WRAP_MISSING,
    VLT_E_DEVICE_WRAP_AUTH,
}

class VaultException(
    val code: VaultError,
) : IllegalArgumentException(code.name)

data class VaultHeader(
    val hasPassword: Boolean,
    val kdfParams: io.github.rhczz.inkotp.core.crypto.KdfParams?,
    val salt: ByteArray,
    val dekNonce: ByteArray,
    val vaultNonce: ByteArray,
    val wrappedDekCiphertext: ByteArray?,
    val wrappedDekTag: ByteArray?,
    val ctLen: Int,
    /** Offset of ctLen (and of the start of AAD_vault). */
    val ciphertextOffset: Int,
    val tagOffset: Int,
    val fileLen: Int,
)
