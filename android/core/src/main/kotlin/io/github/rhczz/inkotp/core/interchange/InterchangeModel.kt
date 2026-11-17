package io.github.rhczz.inkotp.core.interchange

import io.github.rhczz.inkotp.core.model.VaultModel

enum class InterchangeFormat(val extension: String, val requiresPassword: Boolean, val plaintext: Boolean) {
    AEGIS_ENCRYPTED("json", true, false),
    AEGIS_PLAINTEXT("json", false, true),
    TWOFAS_ENCRYPTED("2fas", true, false),
    TWOFAS_PLAINTEXT("2fas", false, true),
    OTPAUTH_TEXT("txt", false, true),
    INKOTP_2FAB("2fab", true, false),
}

enum class InterchangeError(val message: String) {
    UNKNOWN_FORMAT("无法识别文件格式，请选择 Aegis、2FAS、otpauth 文本或 InkOTP 备份。"),
    MALFORMED_FILE("文件内容无效或已损坏，现有账号不会被替换。"),
    FILE_TOO_LARGE("交换文件超过 12 MiB，请分批导出。"),
    TOO_MANY_ENTRIES("交换文件超过 10000 个账号，请分批导出。"),
    UNSUPPORTED_VERSION("暂不支持此文件版本，请在来源应用中导出受支持格式。"),
    UNSUPPORTED_KDF("来源备份的加密参数超出安全上限，请在来源应用中重新导出。"),
    UNSUPPORTED_ENCRYPTION("此备份没有受支持的口令加密通道，请在来源应用中导出口令备份或明文 JSON。"),
    PASSWORD_REQUIRED("请输入这份备份导出时使用的口令。"),
    AUTHENTICATION_FAILED("无法解密备份：口令不正确或文件已损坏。"),
    UNSUPPORTED_ENTRIES("文件包含不支持或无效的账号参数，未跳过任何条目，请修正列出的条目后重试。"),
}

/** Entry indexes are one-based; errors never include seeds or raw source JSON. */
class InterchangeException(val code: InterchangeError, val entryIndexes: List<Int> = emptyList()) :
    IllegalArgumentException(code.message)

enum class InterchangeWarningCode(val message: String) {
    PLAINTEXT_SECRETS("明文文件包含全部账号密钥，取得文件的人可以生成验证码，请迁移后妥善删除。"),
    METADATA_NOT_TRANSFERRED("交换仅保留账号与验证码参数；来源图标、备注、分组和扩展元数据可能无法保留。"),
    LOCAL_SETTINGS_NOT_TRANSFERRED("本机解锁、生物识别、安全设置及设备密钥不随此交换格式迁移。"),
    LEGACY_DEFAULTS("旧版来源未记录的验证码参数使用其官方默认值（TOTP、SHA1、6 位、30 秒）。"),
    SOURCE_NORMALIZED("来源字段已按现有 URI 规则规范化，请核对预览中的服务名和账号。"),
}

data class InterchangeWarning(val code: InterchangeWarningCode, val count: Int = 0) {
    val message: String get() = code.message
}

data class ImportedVault(val model: VaultModel, val format: InterchangeFormat, val warnings: List<InterchangeWarning>)
data class ExportedVault(val bytes: ByteArray, val format: InterchangeFormat, val warnings: List<InterchangeWarning>)
