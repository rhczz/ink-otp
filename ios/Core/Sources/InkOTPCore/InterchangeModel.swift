import Foundation

public enum InterchangeFormat: String, CaseIterable, Identifiable, Sendable {
    case aegisEncrypted = "AEGIS_ENCRYPTED"
    case aegisPlaintext = "AEGIS_PLAINTEXT"
    case twofasEncrypted = "TWOFAS_ENCRYPTED"
    case twofasPlaintext = "TWOFAS_PLAINTEXT"
    case otpauthText = "OTPAUTH_TEXT"

    public var id: String { rawValue }

    public var fileExtension: String {
        switch self {
        case .aegisEncrypted, .aegisPlaintext: "json"
        case .twofasEncrypted, .twofasPlaintext: "2fas"
        case .otpauthText: "txt"
        }
    }

    public var requiresPassword: Bool {
        switch self {
        case .aegisEncrypted, .twofasEncrypted: true
        case .aegisPlaintext, .twofasPlaintext, .otpauthText: false
        }
    }

    public var plaintext: Bool { !requiresPassword }

    public func filename(prefix: String) -> String {
        "\(prefix)-\(rawValue).\(fileExtension)"
    }
}

public enum InterchangeError: String, Sendable {
    case unknownFormat = "UNKNOWN_FORMAT"
    case malformedFile = "MALFORMED_FILE"
    case fileTooLarge = "FILE_TOO_LARGE"
    case tooManyEntries = "TOO_MANY_ENTRIES"
    case unsupportedVersion = "UNSUPPORTED_VERSION"
    case unsupportedKDF = "UNSUPPORTED_KDF"
    case unsupportedEncryption = "UNSUPPORTED_ENCRYPTION"
    case passwordRequired = "PASSWORD_REQUIRED"
    case authenticationFailed = "AUTHENTICATION_FAILED"
    case unsupportedEntries = "UNSUPPORTED_ENTRIES"

    var message: String {
        switch self {
        case .unknownFormat: interchangeText("无法识别文件格式，请选择 Aegis、2FAS 或 otpauth 文本文件。", "Choose an Aegis, 2FAS, or otpauth text backup file.")
        case .malformedFile: interchangeText("文件内容无效或已损坏，现有账号不会被替换。", "The backup file is invalid or damaged. Your existing accounts are intact.")
        case .fileTooLarge: interchangeText("交换文件超过 12 MiB，请分批导出。", "The backup exceeds 12 MiB. Export smaller batches.")
        case .tooManyEntries: interchangeText("交换文件超过 10000 个账号，请分批导出。", "The backup exceeds 10,000 accounts. Export smaller batches.")
        case .unsupportedVersion: interchangeText("暂不支持此文件版本，请在来源应用中导出受支持格式。", "This backup version is unsupported. Export a compatible format from the source app.")
        case .unsupportedKDF: interchangeText("来源备份的加密参数超出安全上限，请在来源应用中重新导出。", "The backup's encryption parameters exceed the safety limits. Export again from the source app.")
        case .unsupportedEncryption: interchangeText("此备份没有受支持的口令加密通道，请重新导出。", "This backup has no supported password slot. Export a compatible backup from the source app.")
        case .passwordRequired: interchangeText("请输入这份备份导出时使用的口令。", "Enter the password used to export this backup.")
        case .authenticationFailed: interchangeText("无法解密备份。口令不正确或文件已损坏。", "The backup could not be decrypted. Check the password and file.")
        case .unsupportedEntries: interchangeText("文件包含不支持或无效的账号参数。请修正列出的条目后重试。", "The backup contains unsupported or invalid accounts. Correct the listed entries and try again.")
        }
    }
}

/// An error carries only a stable code and one-based source entry positions.
public struct InterchangeFailure: LocalizedError, Equatable, Sendable {
    public let code: InterchangeError
    public let entryIndexes: [Int]

    init(_ code: InterchangeError, entryIndexes: [Int] = []) {
        self.code = code
        self.entryIndexes = entryIndexes
    }

    public var errorDescription: String? {
        guard !entryIndexes.isEmpty else { return code.message }
        let shown = entryIndexes.prefix(20).map(String.init).joined(separator: ", ")
        let suffix = entryIndexes.count > 20 ? "…" : ""
        return code.message + " " + shown + suffix
    }
}

public enum InterchangeWarningCode: String, Sendable {
    case plaintextSecrets = "PLAINTEXT_SECRETS"
    case metadataNotTransferred = "METADATA_NOT_TRANSFERRED"
    case localSettingsNotTransferred = "LOCAL_SETTINGS_NOT_TRANSFERRED"
    case legacyDefaults = "LEGACY_DEFAULTS"
    case sourceNormalized = "SOURCE_NORMALIZED"
}

public struct InterchangeWarning: Equatable, Sendable {
    public let code: InterchangeWarningCode
    public let count: Int

    init(_ code: InterchangeWarningCode, count: Int = 0) {
        self.code = code
        self.count = count
    }

    public var message: String {
        switch code {
        case .plaintextSecrets: interchangeText("明文文件包含全部账号密钥。请在迁移后妥善删除。", "This plaintext file contains the account secrets. Delete it securely after migration.")
        case .metadataNotTransferred: interchangeText("交换保留账号与验证码参数。来源图标、备注、分组和扩展元数据可能无法保留。", "Account and OTP parameters transfer. Icons, notes, groups, and other source metadata may be omitted.")
        case .localSettingsNotTransferred: interchangeText("本机解锁、生物识别、安全设置及设备密钥不随此格式迁移。", "Device unlock, biometrics, security settings, and device keys stay on their original device.")
        case .legacyDefaults: interchangeText("旧版来源未记录的验证码参数使用其官方默认值。", "Unspecified settings in the legacy backup use the source format's defaults.")
        case .sourceNormalized: interchangeText("来源字段已规范化，请核对预览中的服务名和账号。", "Source fields were normalized. Review the issuers and accounts in the preview.")
        }
    }
}

public struct ImportedVault: Sendable {
    public let model: VaultModel
    public let format: InterchangeFormat
    public let warnings: [InterchangeWarning]
}

public struct ExportedVault: Sendable {
    public let bytes: Data
    public let format: InterchangeFormat
    public let warnings: [InterchangeWarning]
}

private func interchangeText(_ chinese: String, _ english: String) -> String {
    Locale.preferredLanguages.first?.lowercased().hasPrefix("zh") == true ? chinese : english
}
