import Foundation

public enum InterchangeFormat: String, CaseIterable, Identifiable, Sendable {
    case aegisEncrypted = "AEGIS_ENCRYPTED"
    case aegisPlaintext = "AEGIS_PLAINTEXT"
    case twofasEncrypted = "TWOFAS_ENCRYPTED"
    case twofasPlaintext = "TWOFAS_PLAINTEXT"
    case otpauthText = "OTPAUTH_TEXT"

    public var id: Self { self }

    public var fileExtension: String {
        switch self {
        case .aegisEncrypted, .aegisPlaintext: "json"
        case .twofasEncrypted, .twofasPlaintext: "2fas"
        case .otpauthText: "txt"
        }
    }

    public var plaintext: Bool {
        self == .aegisPlaintext || self == .twofasPlaintext || self == .otpauthText
    }

    public var requiresPassword: Bool { !plaintext }
}

public struct InterchangeError: LocalizedError, Equatable, Sendable {
    public enum Code: String, Sendable {
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
    }

    public let code: Code
    public let entryIndexes: [Int]
    public var entry: Int? { entryIndexes.first }

    public init(_ code: Code, entryIndexes: [Int] = []) {
        self.code = code
        self.entryIndexes = entryIndexes
    }

    public var errorDescription: String? {
        let message: String = switch code {
        case .unknownFormat:
            interchangeMessage("Choose an Aegis, 2FAS, or otpauth text file.", "请选择 Aegis、2FAS 或 otpauth 文本文件。")
        case .malformedFile:
            interchangeMessage("The backup is incomplete or damaged.", "文件不完整或已损坏。")
        case .fileTooLarge:
            interchangeMessage("The file exceeds 12 MiB. Export it in smaller batches.", "文件超过 12 MiB，请分批导出。")
        case .tooManyEntries:
            interchangeMessage("The file contains more than 10,000 accounts. Export them in smaller batches.", "文件超过 10000 个账号，请分批导出。")
        case .unsupportedVersion:
            interchangeMessage("This backup version is unsupported. Export a compatible file from the original app.", "暂不支持此文件版本，请在来源应用中导出受支持格式。")
        case .unsupportedKDF:
            interchangeMessage("The backup’s encryption parameters exceed the supported limits.", "备份的加密参数超出支持上限。")
        case .unsupportedEncryption:
            interchangeMessage("No supported password slot is available. Choose a password-encrypted or plaintext export.", "备份没有受支持的口令通道，请选择口令加密或明文导出。")
        case .passwordRequired:
            interchangeMessage("Enter the password used to export this backup.", "请输入导出这份备份时使用的口令。")
        case .authenticationFailed:
            interchangeMessage("The password is incorrect or the backup is damaged.", "口令不正确或备份已损坏。")
        case .unsupportedEntries:
            interchangeMessage("The backup contains unsupported or invalid account settings.", "文件包含不支持或无效的账号参数。")
        }
        guard !entryIndexes.isEmpty else { return message }
        let shown = entryIndexes.prefix(16).map(String.init).joined(separator: ", ")
        let remaining = entryIndexes.count - min(entryIndexes.count, 16)
        let indexes = remaining > 0 ? shown + interchangeMessage(" and \(remaining) more", " 等 \(entryIndexes.count) 项") : shown
        return message + interchangeMessage(" Affected entries: ", " 涉及条目：") + indexes
    }
}

public enum InterchangeWarning: String, Sendable {
    case plaintextSecrets = "PLAINTEXT_SECRETS"
    case metadataNotTransferred = "METADATA_NOT_TRANSFERRED"
    case localSettingsNotTransferred = "LOCAL_SETTINGS_NOT_TRANSFERRED"
    case legacyDefaults = "LEGACY_DEFAULTS"
    case sourceNormalized = "SOURCE_NORMALIZED"

    public var message: String {
        switch self {
        case .plaintextSecrets:
            interchangeMessage("Anyone with this plaintext file can generate your codes. Store it securely.", "明文文件包含完整密钥。取得文件的人可以生成验证码，请妥善保管。")
        case .metadataNotTransferred:
            interchangeMessage("Icons, notes, groups, and app-specific metadata may be omitted.", "图标、备注、分组和应用特有字段可能无法保留。")
        case .localSettingsNotTransferred:
            interchangeMessage("Device authentication settings and device keys stay on this device.", "本机验证设置与设备密钥不会随交换文件迁移。")
        case .legacyDefaults:
            interchangeMessage("Missing legacy parameters use the source defaults (TOTP, SHA1, 6 digits, 30 seconds).", "旧版缺失的参数按来源默认值处理（TOTP、SHA1、6 位、30 秒）。")
        case .sourceNormalized:
            interchangeMessage("Some imported labels were normalized. Review names and accounts in the preview.", "来源字段已规范化，请核对预览中的服务名和账号。")
        }
    }
}

public struct ImportedVault: Sendable {
    public let model: VaultModel
    public let format: InterchangeFormat
    public let warningCodes: [InterchangeWarning]
    public var warnings: [String] { warningCodes.map(\.message) }
}

public struct ExportedVault: Sendable {
    public let bytes: Data
    public let format: InterchangeFormat
    public let warningCodes: [InterchangeWarning]
    public var warnings: [String] { warningCodes.map(\.message) }
}

private func interchangeMessage(_ english: String, _ chinese: String) -> String {
    Locale.preferredLanguages.first?.hasPrefix("zh") == true ? chinese : english
}

public enum Interchange {
    public static let maximumFileBytes = 12 * 1024 * 1024
    private static let maximumEntries = 10_000
    private static let maximumLineBytes = 16_384
    private static let maximumSecretText = 2_048

    private enum Input {
        case uris(String)
        case aegis(JSONObject, encrypted: Bool)
        case twoFas(JSONObject, encrypted: Bool)

        var format: InterchangeFormat {
            switch self {
            case .uris: .otpauthText
            case .aegis(_, let encrypted): encrypted ? .aegisEncrypted : .aegisPlaintext
            case .twoFas(_, let encrypted): encrypted ? .twofasEncrypted : .twofasPlaintext
            }
        }
    }

    public static func detect(_ bytes: Data) throws -> InterchangeFormat {
        try guarded { try identify(bytes).format }
    }

    public static func decode(_ bytes: Data, password: String? = nil) throws -> ImportedVault {
        try guarded {
            let input = try identify(bytes)
            let format = input.format
            if format.requiresPassword && password == nil { throw InterchangeError(.passwordRequired) }
            var warnings = [InterchangeWarning]()
            let model: VaultModel
            switch input {
            case .uris(let text):
                model = try decodeURIText(text, warnings: &warnings)
            case .aegis(let root, let encrypted):
                model = try decodeAegis(root, password: encrypted ? password : nil, warnings: &warnings)
            case .twoFas(let root, let encrypted):
                model = try decodeTwoFas(root, password: encrypted ? password : nil, warnings: &warnings)
            }
            if format.plaintext { addWarning(.plaintextSecrets, to: &warnings) }
            addWarning(.localSettingsNotTransferred, to: &warnings)
            return ImportedVault(model: model, format: format, warningCodes: warnings)
        }
    }

    public static func encode(_ model: VaultModel, format: InterchangeFormat, password: String? = nil) throws -> ExportedVault {
        try guarded {
            if format.requiresPassword && password == nil { throw InterchangeError(.passwordRequired) }
            guard model.items.count <= maximumEntries else { throw InterchangeError(.tooManyEntries) }
            let items = try VaultJSON.validate(model).items.sorted { $0.sortIndex < $1.sortIndex }
            var warnings: [InterchangeWarning] = [.metadataNotTransferred, .localSettingsNotTransferred]
            if format.plaintext { warnings.append(.plaintextSecrets) }

            let bytes: Data
            switch format {
            case .otpauthText:
                let lines = items.map(Otpauth.encode)
                bytes = Data((lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n")).utf8)
            case .aegisPlaintext, .aegisEncrypted:
                let database = aegisDatabase(items)
                if format.requiresPassword, let password {
                    var plaintext = try InterchangeJSON.encode(database)
                    defer { plaintext.eraseInterchangeBytes() }
                    bytes = try InterchangeJSON.encode(InterchangeCrypto.sealAegis(plaintext, password: password))
                } else {
                    let header: JSONObject = ["slots": NSNull(), "params": NSNull()]
                    bytes = try InterchangeJSON.encode(["version": 1, "db": database, "header": header] as JSONObject)
                }
            case .twofasPlaintext, .twofasEncrypted:
                bytes = try encodeTwoFas(items, password: format.requiresPassword ? password : nil)
            }
            try checkSize(bytes)
            return ExportedVault(bytes: bytes, format: format, warningCodes: warnings)
        }
    }

    private static func identify(_ bytes: Data) throws -> Input {
        try checkSize(bytes)
        let text = try InterchangeJSON.text(bytes)
        let first = text.drop(while: \.isWhitespace)
        if first.prefix(10).lowercased() == "otpauth://" { return .uris(text) }
        guard let root = try InterchangeJSON.parse(text) as? JSONObject else { throw InterchangeError(.unknownFormat) }
        if let database = root["db"], root["version"] != nil { return .aegis(root, encrypted: !(database is JSONObject)) }
        if root["schemaVersion"] != nil && (root["services"] != nil || root["servicesEncrypted"] != nil) {
            let encrypted = try ["servicesEncrypted", "reference"].contains { key in
                try !(root.optionalString(key)?.allSatisfy(\.isWhitespace) ?? true)
            }
            return .twoFas(root, encrypted: encrypted)
        }
        throw InterchangeError(.unknownFormat)
    }

    private static func decodeAegis(
        _ root: JSONObject, password: String?, warnings: inout [InterchangeWarning]
    ) throws -> VaultModel {
        guard try root.requiredInteger("version") == 1 else { throw InterchangeError(.unsupportedVersion) }
        let database: JSONObject
        if let password {
            var plaintext = try InterchangeCrypto.openAegis(root, password: password)
            defer { plaintext.eraseInterchangeBytes() }
            guard let object = try InterchangeJSON.parse(plaintext) as? JSONObject else { throw InterchangeError(.malformedFile) }
            database = object
        } else {
            database = try root.requiredObject("db")
        }
        guard try (1...3).contains(database.requiredInteger("version")) else { throw InterchangeError(.unsupportedVersion) }
        if database.hasKeysOutside(["version", "entries", "groups"])
            || root.hasKeysOutside(["version", "header", "db"])
            || ((database["groups"] as? [Any])?.isEmpty == false) {
            addWarning(.metadataNotTransferred, to: &warnings)
        }
        return try decodeEntries(database.requiredArray("entries"), warnings: &warnings, using: decodeAegisItem)
    }

    private static func decodeTwoFas(
        _ root: JSONObject, password: String?, warnings: inout [InterchangeWarning]
    ) throws -> VaultModel {
        let version = try root.requiredInteger("schemaVersion")
        guard (1...4).contains(version) else { throw InterchangeError(.unsupportedVersion) }
        if version <= 2 { addWarning(.legacyDefaults, to: &warnings) }
        if (root["groups"] as? [Any])?.isEmpty == false
            || root.hasKeysOutside(["schemaVersion", "services", "groups", "updatedAt", "appVersionCode",
                                     "appVersionName", "appOrigin", "account", "servicesEncrypted", "reference"]) {
            addWarning(.metadataNotTransferred, to: &warnings)
        }
        let entries: [Any]
        if let password {
            if let clear = root["services"], !(clear is NSNull) {
                guard let array = clear as? [Any], array.isEmpty else { throw InterchangeError(.malformedFile) }
            }
            var plaintext = try InterchangeCrypto.openTwoFas(root, password: password)
            defer { plaintext.eraseInterchangeBytes() }
            guard let array = try InterchangeJSON.parse(plaintext) as? [Any] else { throw InterchangeError(.malformedFile) }
            entries = array
        } else {
            entries = try root.requiredArray("services")
        }
        return try decodeEntries(entries, warnings: &warnings, using: decodeTwoFasItem)
    }

    private static func decodeEntries(
        _ entries: [Any], warnings: inout [InterchangeWarning],
        using convert: (JSONObject, Int, inout [InterchangeWarning]) throws -> VaultItem
    ) throws -> VaultModel {
        guard entries.count <= maximumEntries else { throw InterchangeError(.tooManyEntries) }
        var items = [VaultItem]()
        var rejected = [Int]()
        for (position, raw) in entries.enumerated() {
            do {
                guard let object = raw as? JSONObject else { throw InterchangeError(.unsupportedEntries) }
                items.append(try convert(object, position, &warnings))
            } catch {
                rejected.append(position + 1)
            }
        }
        guard rejected.isEmpty else {
            wipeItems(&items)
            throw InterchangeError(.unsupportedEntries, entryIndexes: rejected)
        }
        var model = VaultModel.new()
        model.items = items.sorted { $0.sortIndex < $1.sortIndex }
        for index in model.items.indices { model.items[index].sortIndex = (index + 1) * 1000 }
        return model
    }

    private static func decodeAegisItem(
        _ item: JSONObject, position: Int, warnings: inout [InterchangeWarning]
    ) throws -> VaultItem {
        let info = try item.requiredObject("info")
        guard !info.hasKeysOutside(["secret", "algo", "digits", "period", "counter"]) else {
            throw InterchangeError(.unsupportedEntries)
        }
        if item.hasKeysOutside(["type", "uuid", "name", "issuer", "info"]) {
            addWarning(.metadataNotTransferred, to: &warnings)
        }
        return try entry(
            type: item.requiredString("type"), issuer: item.optionalString("issuer"), account: item.requiredString("name"),
            secret: info.requiredString("secret"), algorithm: info.optionalString("algo"),
            digits: info.optionalInteger("digits") ?? 6, period: info.optionalInteger("period") ?? 30,
            counter: info.optionalInteger("counter"), position: position, warnings: &warnings
        )
    }

    private static func decodeTwoFasItem(
        _ item: JSONObject, position: Int, warnings: inout [InterchangeWarning]
    ) throws -> VaultItem {
        let otp = try item.requiredObject("otp")
        guard !otp.hasKeysOutside(["link", "label", "account", "issuer", "digits", "period", "algorithm",
                                   "counter", "tokenType", "source"]) else {
            throw InterchangeError(.unsupportedEntries)
        }
        if item.hasKeysOutside(["name", "secret", "updatedAt", "otp", "order", "type", "serviceTypeID"]) {
            addWarning(.metadataNotTransferred, to: &warnings)
        }
        let name = try item.optionalString("name")
        let issuer = try otp.optionalString("issuer").flatMap { $0.isEmpty ? nil : $0 } ?? name
        let account = try otp.optionalString("account").flatMap { $0.isEmpty ? nil : $0 }
            ?? otp.optionalString("label").flatMap { $0.isEmpty ? nil : $0 }
            ?? item.requiredString("name")
        if name != issuer { addWarning(.metadataNotTransferred, to: &warnings) }
        let counter = try otp.optionalInteger("counter")
        let order = try (item["order"] as? JSONObject)?.optionalInteger("position") ?? Int64(position)
        guard (counter ?? 0) <= Int64(Int32.max), (0...Int64(Int32.max)).contains(order) else {
            throw InterchangeError(.unsupportedEntries)
        }
        return try entry(
            type: otp.optionalString("tokenType") ?? "TOTP", issuer: issuer, account: account,
            secret: item.requiredString("secret"), algorithm: otp.optionalString("algorithm"),
            digits: otp.optionalInteger("digits") ?? 6, period: otp.optionalInteger("period") ?? 30,
            counter: counter, position: Int(order), warnings: &warnings
        )
    }

    private static func entry(
        type: String, issuer: String?, account: String, secret: String, algorithm: String?,
        digits: Int64, period: Int64, counter: Int64?, position: Int, warnings: inout [InterchangeWarning]
    ) throws -> VaultItem {
        let algorithmName = algorithm.flatMap { $0.isEmpty ? nil : $0 } ?? "SHA1"
        guard secret.utf8.count <= maximumSecretText,
              let type = OTPType(rawValue: type.uppercased()),
              let algorithm = OTPAlgorithm(rawValue: algorithmName.uppercased()),
              digits == 6 || digits == 8, (1...300).contains(period),
              type == .hotp ? (counter ?? -1) >= 0 : counter == nil else {
            throw InterchangeError(.unsupportedEntries)
        }
        let normalizedIssuer = try issuer.map { try normalize($0, max: 128, warnings: &warnings) }
        let normalizedAccount = try normalize(account, max: 256, warnings: &warnings)
        guard !normalizedAccount.isEmpty else { throw InterchangeError(.unsupportedEntries) }
        var secretBytes = try OTP.base32Decode(secret)
        guard (1...255).contains(secretBytes.count) else {
            secretBytes.eraseInterchangeBytes()
            throw InterchangeError(.unsupportedEntries)
        }
        return VaultItem.make(
            type: type, issuer: normalizedIssuer.flatMap { $0.isEmpty ? nil : $0 }, accountName: normalizedAccount,
            secret: secretBytes, algorithm: algorithm, digits: Int(digits), period: Int(period),
            counter: counter, sortIndex: position, origin: "import"
        )
    }

    private static func normalize(_ value: String, max: Int, warnings: inout [InterchangeWarning]) throws -> String {
        let normalized = normalizedText(value).value
        guard normalized.unicodeScalars.count <= max else { throw InterchangeError(.unsupportedEntries) }
        if !value.utf8.elementsEqual(normalized.utf8) { addWarning(.sourceNormalized, to: &warnings) }
        return normalized
    }

    private static func decodeURIText(_ text: String, warnings: inout [InterchangeWarning]) throws -> VaultModel {
        var model = VaultModel.new()
        var rejected = [Int]()
        var nonblank = 0
        let bytes = text.utf8
        var start = bytes.startIndex
        var lineNumber = 1
        var afterCR = false
        func consumeLine(upTo end: String.Index) throws {
            let line = String(decoding: bytes[start..<end], as: UTF8.self)
            guard !line.allSatisfy(\.isWhitespace) else { return }
            nonblank += 1
            guard nonblank <= maximumEntries else { throw InterchangeError(.tooManyEntries) }
            do {
                let value = line.trimmingCharacters(in: .whitespacesAndNewlines)
                guard line.utf8.count <= maximumLineBytes, value.prefix(10).lowercased() == "otpauth://" else {
                    throw InterchangeError(.unsupportedEntries)
                }
                let draft = try Otpauth.parse(value)
                let counter = draft.counter.flatMap { Int64(exactly: $0) }
                guard draft.kind == .entry, let account = draft.accountName,
                      draft.type == .totp || counter != nil else {
                    throw InterchangeError(.unsupportedEntries)
                }
                if !draft.warnings.isEmpty { addWarning(.sourceNormalized, to: &warnings) }
                model.items.append(VaultItem.make(
                    type: draft.type, issuer: draft.issuer, accountName: account, secret: draft.secret,
                    algorithm: draft.algorithm, digits: draft.digits, period: draft.period,
                    counter: counter, sortIndex: (model.items.count + 1) * 1000, origin: "import"
                ))
            } catch {
                rejected.append(lineNumber)
            }
        }

        do {
            for index in bytes.indices {
                let byte = bytes[index]
                if byte == 10 && afterCR {
                    start = bytes.index(after: index)
                } else if byte == 10 || byte == 13 {
                    try consumeLine(upTo: index)
                    lineNumber += 1
                    start = bytes.index(after: index)
                }
                afterCR = byte == 13
            }
            try consumeLine(upTo: bytes.endIndex)
            guard rejected.isEmpty else { throw InterchangeError(.unsupportedEntries, entryIndexes: rejected) }
            return model
        } catch {
            wipeItems(&model.items)
            throw error
        }
    }

    private static func aegisDatabase(_ items: [VaultItem]) -> JSONObject {
        let entries = items.map { item -> JSONObject in
            var info: JSONObject = [
                "secret": OTP.base32Encode(item.secret, padding: false), "algo": item.algorithm.rawValue, "digits": item.digits
            ]
            if let counter = item.counter { info["counter"] = counter }
            else { info["period"] = item.period }
            return [
                "uuid": item.id, "type": item.type.rawValue.lowercased(), "issuer": item.issuer ?? "", "name": item.accountName,
                "note": "", "favorite": false, "icon": NSNull(), "icon_mime": NSNull(), "icon_hash": NSNull(), "groups": [String](),
                "info": info
            ]
        }
        return ["version": 3, "groups": [String](), "entries": entries]
    }

    private static func encodeTwoFas(_ items: [VaultItem], password: String?) throws -> Data {
        let incompatible = items.enumerated().compactMap { position, item in
            (item.counter ?? 0) > Int64(Int32.max) ? position + 1 : nil
        }
        guard incompatible.isEmpty else { throw InterchangeError(.unsupportedEntries, entryIndexes: incompatible) }
        let formatter = ISO8601DateFormatter()
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let entries = try items.enumerated().map { position, item -> JSONObject in
            guard let timestamp = fractional.date(from: item.updatedAt) ?? formatter.date(from: item.updatedAt),
                  let milliseconds = Int64(exactly: (timestamp.timeIntervalSince1970 * 1000).rounded(.down)) else {
                throw InterchangeError(.malformedFile)
            }
            let otp: JSONObject = [
                "link": Otpauth.encode(item), "label": item.accountName, "account": item.accountName,
                "issuer": (item.issuer as Any?) ?? NSNull(), "digits": item.digits, "period": item.period,
                "algorithm": item.algorithm.rawValue, "counter": (item.counter as Any?) ?? NSNull(),
                "tokenType": item.type.rawValue, "source": "Manual"
            ]
            return [
                "name": item.issuer ?? item.accountName, "secret": OTP.base32Encode(item.secret, padding: false),
                "updatedAt": milliseconds, "order": ["position": position],
                "serviceTypeID": NSNull(), "groupId": NSNull(), "badge": NSNull(), "icon": NSNull(), "otp": otp
            ]
        }
        var root: JSONObject = [
            "schemaVersion": 4, "updatedAt": Int64(Date.now.timeIntervalSince1970 * 1000),
            "appOrigin": "android", "appVersionCode": 0, "appVersionName": "InkOTP interchange",
            "services": entries, "groups": [String]()
        ]
        if let password {
            var plaintext = try InterchangeJSON.encode(entries)
            defer { plaintext.eraseInterchangeBytes() }
            let encrypted = try InterchangeCrypto.sealTwoFas(plaintext, password: password)
            root["services"] = [JSONObject]()
            root["servicesEncrypted"] = encrypted.services
            root["reference"] = encrypted.reference
        }
        return try InterchangeJSON.encode(root)
    }

    private static func addWarning(_ warning: InterchangeWarning, to warnings: inout [InterchangeWarning]) {
        if !warnings.contains(warning) { warnings.append(warning) }
    }

    private static func wipeItems(_ items: inout [VaultItem]) {
        for index in items.indices { items[index].secret.eraseInterchangeBytes() }
    }

    private static func checkSize(_ bytes: Data) throws {
        guard bytes.count <= maximumFileBytes else { throw InterchangeError(.fileTooLarge) }
    }

    private static func guarded<T>(_ operation: () throws -> T) throws -> T {
        do {
            return try operation()
        } catch let error as InterchangeError {
            throw error
        } catch {
            throw InterchangeError(.malformedFile)
        }
    }
}
