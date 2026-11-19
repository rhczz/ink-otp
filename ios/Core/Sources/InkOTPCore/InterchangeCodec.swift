import Foundation

/// Offline conversion of a complete vault. The caller decides whether to merge or export.
public enum InterchangeCodec {
    public static let maxFileBytes = 12 * 1024 * 1024
    private static let maxEntries = 10_000

    private enum Source {
        case inkotp
        case uri(String)
        case aegis(InterchangeFormat, InterchangeObject)
        case twoFas(InterchangeFormat, InterchangeObject)

        var format: InterchangeFormat {
            switch self {
            case .inkotp: .inkotp2fab
            case .uri: .otpauthText
            case .aegis(let format, _), .twoFas(let format, _): format
            }
        }
    }

    public static func detect(_ bytes: Data) throws -> InterchangeFormat {
        try guarded { try source(bytes).format }
    }

    public static func decode(_ bytes: Data, password: String? = nil) throws -> ImportedVault {
        try guarded {
            let source = try Self.source(bytes)
            let format = source.format
            let password = try checkedPassword(password, for: format)
            var warnings: Set<InterchangeWarningCode> = []
            let vault: VaultModel
            switch source {
            case .inkotp:
                vault = try BackupCodec.open(bytes, password: password)
            case .uri(let text):
                vault = try decodeURIs(text, warnings: &warnings)
            case .aegis(let format, let root):
                vault = try decodeAegis(root, encrypted: format.requiresPassword, password: password, warnings: &warnings)
            case .twoFas(let format, let root):
                vault = try decodeTwoFas(root, encrypted: format.requiresPassword, password: password, warnings: &warnings)
            }
            if format.plaintext { warnings.insert(.plaintextSecrets) }
            if format != .inkotp2fab { warnings.insert(.localSettingsNotTransferred) }
            return ImportedVault(
                model: vault, format: format,
                warnings: warnings.sorted { $0.rawValue < $1.rawValue }.map { InterchangeWarning($0) }
            )
        }
    }

    public static func encode(_ model: VaultModel, format: InterchangeFormat, password: String? = nil) throws -> ExportedVault {
        try guarded {
            let password = try checkedPassword(password, for: format)
            let checked = try VaultJson.validate(model)
            let entries = checked.items.sorted { $0.sortIndex < $1.sortIndex }
            let bytes: Data
            switch format {
            case .inkotp2fab:
                bytes = try BackupCodec.seal(checked, password: password)
            case .otpauthText:
                bytes = Data(entries.map { OtpauthParser.encode($0) + "\n" }.joined().utf8)
            case .aegisEncrypted, .aegisPlaintext:
                bytes = try encodeAegis(entries, encrypted: format.requiresPassword, password: password)
            case .twofasEncrypted, .twofasPlaintext:
                bytes = try encodeTwoFas(entries, encrypted: format.requiresPassword, password: password)
            }
            guard bytes.count <= maxFileBytes else { throw InterchangeFailure(.fileTooLarge) }
            var warnings: [InterchangeWarning] = []
            if format != .inkotp2fab {
                warnings.append(InterchangeWarning(.metadataNotTransferred, count: model.items.count))
                warnings.append(InterchangeWarning(.localSettingsNotTransferred))
            }
            if format.plaintext { warnings.append(InterchangeWarning(.plaintextSecrets)) }
            return ExportedVault(bytes: bytes, format: format, warnings: warnings)
        }
    }

    private static func source(_ bytes: Data) throws -> Source {
        guard bytes.count <= maxFileBytes else { throw InterchangeFailure(.fileTooLarge) }
        if bytes.starts(with: BackupCodec.magic) { return .inkotp }
        let text = try InterchangeJSON.text(bytes)
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercasedPrefixMatches("otpauth://") { return .uri(text) }
        guard let root = try InterchangeJSON.parse(trimmed) as? InterchangeObject else {
            throw InterchangeFailure(.unknownFormat)
        }
        if let db = root["db"], root["version"] != nil {
            return .aegis(db is InterchangeObject ? .aegisPlaintext : .aegisEncrypted, root)
        }
        if root["schemaVersion"] != nil && (root["services"] != nil || root["servicesEncrypted"] != nil) {
            let services = try root.optionalText("servicesEncrypted")?.trimmingCharacters(in: .whitespacesAndNewlines)
            let reference = try root.optionalText("reference")?.trimmingCharacters(in: .whitespacesAndNewlines)
            let plain = (services?.isEmpty ?? true) && (reference?.isEmpty ?? true)
            return .twoFas(plain ? .twofasPlaintext : .twofasEncrypted, root)
        }
        throw InterchangeFailure(.unknownFormat)
    }

    private static func decodeAegis(
        _ root: InterchangeObject, encrypted: Bool, password: String,
        warnings: inout Set<InterchangeWarningCode>
    ) throws -> VaultModel {
        guard try root.integer("version") == 1 else { throw InterchangeFailure(.unsupportedVersion) }
        let db: InterchangeObject
        if encrypted {
            var plain = try InterchangeCrypto.openAegis(root, password: password)
            defer { plain.resetBytes(in: plain.indices) }
            guard let object = try InterchangeJSON.parse(InterchangeJSON.text(plain)) as? InterchangeObject else {
                throw InterchangeFailure(.malformedFile)
            }
            db = object
        } else {
            db = try root.object("db")
        }
        guard try (1...3).contains(db.integer("version")) else { throw InterchangeFailure(.unsupportedVersion) }
        if root.hasExtraKeys(["version", "header", "db"]) ||
            db.hasExtraKeys(["version", "entries", "groups"]) || hasGroupMetadata(db) {
            warnings.insert(.metadataNotTransferred)
        }
        return try decodeEntries(db.array("entries"), warnings: &warnings, convert: aegisItem)
    }

    private static func decodeTwoFas(
        _ root: InterchangeObject, encrypted: Bool, password: String,
        warnings: inout Set<InterchangeWarningCode>
    ) throws -> VaultModel {
        let version = try root.integer("schemaVersion")
        guard (1...4).contains(version) else { throw InterchangeFailure(.unsupportedVersion) }
        if version <= 2 { warnings.insert(.legacyDefaults) }
        let allowed: Set<String> = [
            "schemaVersion", "services", "groups", "updatedAt", "appVersionCode", "appVersionName",
            "appOrigin", "account", "servicesEncrypted", "reference"
        ]
        if root.hasExtraKeys(allowed) || hasGroupMetadata(root) { warnings.insert(.metadataNotTransferred) }

        let entries: [Any]
        if encrypted {
            if let advertised = root["services"] as? [Any], !advertised.isEmpty {
                throw InterchangeFailure(.malformedFile)
            }
            var plain = try InterchangeCrypto.openTwoFas(root, password: password)
            defer { plain.resetBytes(in: plain.indices) }
            guard let array = try InterchangeJSON.parse(InterchangeJSON.text(plain)) as? [Any] else {
                throw InterchangeFailure(.malformedFile)
            }
            entries = array
        } else {
            entries = try root.array("services")
        }
        return try decodeEntries(entries, warnings: &warnings, convert: twoFasItem)
    }

    private static func aegisItem(
        _ source: InterchangeObject, _ index: Int, _ warnings: inout Set<InterchangeWarningCode>
    ) throws -> VaultItem {
        let info = try source.object("info")
        guard !info.hasExtraKeys(["secret", "algo", "digits", "period", "counter"]) else {
            throw InterchangeFailure(.unsupportedEntries)
        }
        if source.hasExtraKeys(["type", "uuid", "name", "issuer", "info"]) { warnings.insert(.metadataNotTransferred) }
        return try entry(
            type: source.requiredText("type"), issuer: source.optionalText("issuer"),
            account: source.requiredText("name"), secret: info.requiredText("secret"),
            algorithm: info.optionalText("algo"), digits: info.optionalInt("digits") ?? 6,
            period: info.optionalInt("period") ?? 30, counter: info.optionalInt64("counter"),
            sortIndex: index, warnings: &warnings
        )
    }

    private static func twoFasItem(
        _ source: InterchangeObject, _ index: Int, _ warnings: inout Set<InterchangeWarningCode>
    ) throws -> VaultItem {
        let otp = try source.object("otp")
        let otpKeys: Set<String> = [
            "link", "label", "account", "issuer", "digits", "period", "algorithm", "counter", "tokenType", "source"
        ]
        guard !otp.hasExtraKeys(otpKeys) else { throw InterchangeFailure(.unsupportedEntries) }
        if source.hasExtraKeys(["name", "secret", "updatedAt", "otp", "order", "type", "serviceTypeID"]) {
            warnings.insert(.metadataNotTransferred)
        }
        let serviceName = try source.optionalText("name")
        let issuer = nonempty(try otp.optionalText("issuer")) ?? serviceName
        if serviceName != issuer { warnings.insert(.metadataNotTransferred) }
        let account = try nonempty(otp.optionalText("account")) ?? nonempty(otp.optionalText("label")) ?? source.requiredText("name")
        let counter = try otp.optionalInt64("counter")
        let sortIndex = try (source["order"] as? InterchangeObject)?.optionalInt("position") ?? index
        guard (counter ?? 0) <= Int64(Int32.max), sortIndex >= 0 else {
            throw InterchangeFailure(.unsupportedEntries)
        }
        return try entry(
            type: otp.optionalText("tokenType") ?? "TOTP", issuer: issuer, account: account,
            secret: source.requiredText("secret"), algorithm: otp.optionalText("algorithm"),
            digits: otp.optionalInt("digits") ?? 6, period: otp.optionalInt("period") ?? 30,
            counter: counter, sortIndex: sortIndex, warnings: &warnings
        )
    }

    private static func entry(
        type: String, issuer: String?, account: String, secret: String, algorithm: String?,
        digits: Int, period: Int, counter: Int64?, sortIndex: Int, warnings: inout Set<InterchangeWarningCode>
    ) throws -> VaultItem {
        guard secret.utf16.count <= 2048 else { throw InterchangeFailure(.unsupportedEntries) }
        guard let kind = OtpType(rawValue: type.lowercased()) else { throw InterchangeFailure(.unsupportedEntries) }
        let algorithm = try OtpAlgorithm.specName(algorithm)
        var bytes = try Base32.decode(secret)
        var adopted = false
        defer {
            if !adopted { bytes.resetBytes(in: bytes.indices) }
        }
        try OtpEngine.validateSecret(bytes)
        try OtpEngine.validateDigits(digits)
        try OtpEngine.validatePeriod(period)
        switch kind {
        case .totp:
            guard counter == nil else { throw InterchangeFailure(.unsupportedEntries) }
        case .hotp:
            guard let counter, counter >= 0 else { throw InterchangeFailure(.unsupportedEntries) }
        }
        let normalizedIssuer = try issuer.map { try normalize($0, limit: 128, warnings: &warnings) }
        let normalizedAccount = try normalize(account, limit: 256, warnings: &warnings)
        guard !normalizedAccount.isEmpty else { throw InterchangeFailure(.unsupportedEntries) }
        let item = VaultItem(
            type: kind, issuer: nonempty(normalizedIssuer), accountName: normalizedAccount, secret: bytes,
            algorithm: algorithm, digits: digits, period: period, counter: counter,
            sortIndex: sortIndex, origin: "import"
        )
        adopted = true
        return item
    }

    private static func decodeEntries(
        _ raw: [Any], warnings: inout Set<InterchangeWarningCode>,
        convert: (InterchangeObject, Int, inout Set<InterchangeWarningCode>) throws -> VaultItem
    ) throws -> VaultModel {
        guard raw.count <= maxEntries else { throw InterchangeFailure(.tooManyEntries) }
        var entries: [VaultItem] = []
        var errors: [Int] = []
        for (index, element) in raw.enumerated() {
            do {
                guard let source = element as? InterchangeObject else { throw InterchangeFailure(.unsupportedEntries) }
                entries.append(try convert(source, index, &warnings))
            } catch {
                errors.append(index + 1)
            }
        }
        if !errors.isEmpty {
            wipe(&entries)
            throw InterchangeFailure(.unsupportedEntries, entryIndexes: errors)
        }
        entries.sort { $0.sortIndex < $1.sortIndex }
        for index in entries.indices { entries[index].sortIndex = (index + 1) * 1000 }
        return VaultModel(items: entries)
    }

    private static func decodeURIs(_ text: String, warnings: inout Set<InterchangeWarningCode>) throws -> VaultModel {
        let bytes = text.utf8
        var start = bytes.startIndex
        var entries: [VaultItem] = []
        var errors: [Int] = []
        var line = 1
        var entryCount = 0

        // Walk the physical lines so blank lines consume no additional retained storage.
        while start < bytes.endIndex {
            let stop = bytes[start...].firstIndex { $0 == 10 || $0 == 13 } ?? bytes.endIndex
            let text = String(decoding: bytes[start..<stop], as: UTF8.self)
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                entryCount += 1
                guard entryCount <= maxEntries else {
                    wipe(&entries)
                    throw InterchangeFailure(.tooManyEntries)
                }
                do {
                    guard text.utf16.count <= 16_384, trimmed.lowercasedPrefixMatches("otpauth://"),
                          case .uri(let fields) = try OtpauthParser.parse(trimmed),
                          let account = fields.accountName else { throw InterchangeFailure(.unsupportedEntries) }
                    if !fields.warnings.isEmpty { warnings.insert(.sourceNormalized) }
                    entries.append(VaultItem(
                        type: fields.type, issuer: fields.issuer, accountName: account, secret: fields.secret,
                        algorithm: fields.algorithm, digits: fields.digits, period: fields.period ?? 30, counter: fields.counter,
                        sortIndex: (entries.count + 1) * 1000, origin: "import"
                    ))
                } catch {
                    errors.append(line)
                }
            }
            guard stop != bytes.endIndex else { break }
            start = bytes.index(after: stop)
            if bytes[stop] == 13, start < bytes.endIndex, bytes[start] == 10 { bytes.formIndex(after: &start) }
            line += 1
        }
        if !errors.isEmpty {
            wipe(&entries)
            throw InterchangeFailure(.unsupportedEntries, entryIndexes: errors)
        }
        return VaultModel(items: entries)
    }

    private static func encodeAegis(_ entries: [VaultItem], encrypted: Bool, password: String) throws -> Data {
        let accounts: [InterchangeObject] = entries.map { item in
            var info: InterchangeObject = [
                "secret": Base32.encode(item.secret, padding: false), "algo": item.algorithm.rawValue, "digits": item.digits
            ]
            if let counter = item.counter {
                info["counter"] = counter
            } else {
                info["period"] = item.period
            }
            return [
                "type": item.type.rawValue, "uuid": item.id, "name": item.accountName, "issuer": item.issuer ?? "",
                "note": "", "favorite": false, "icon": NSNull(), "icon_mime": NSNull(),
                "icon_hash": NSNull(), "groups": [Any](), "info": info
            ]
        }
        let db: InterchangeObject = ["version": 3, "groups": [Any](), "entries": accounts]
        if encrypted {
            var plain = try InterchangeJSON.encode(db)
            defer { plain.resetBytes(in: plain.indices) }
            return try InterchangeJSON.encode(InterchangeCrypto.sealAegis(plain, password: password))
        }
        let header: InterchangeObject = ["slots": NSNull(), "params": NSNull()]
        return try InterchangeJSON.encode(["version": 1, "header": header, "db": db] as InterchangeObject)
    }

    private static func encodeTwoFas(_ entries: [VaultItem], encrypted: Bool, password: String) throws -> Data {
        let incompatible = entries.enumerated().compactMap { index, item in
            (item.counter ?? 0) > Int64(Int32.max) ? index + 1 : nil
        }
        guard incompatible.isEmpty else { throw InterchangeFailure(.unsupportedEntries, entryIndexes: incompatible) }

        let date = ISO8601DateFormatter()
        let fractionalDate = ISO8601DateFormatter()
        fractionalDate.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        let services: [InterchangeObject] = try entries.enumerated().map { index, item in
            guard let timestamp = fractionalDate.date(from: item.updatedAt) ?? date.date(from: item.updatedAt) else {
                throw InterchangeFailure(.malformedFile)
            }
            let otp: InterchangeObject = [
                "link": OtpauthParser.encode(item), "label": item.accountName, "account": item.accountName,
                "issuer": item.issuer as Any? ?? NSNull(), "digits": item.digits, "period": item.period,
                "algorithm": item.algorithm.rawValue, "counter": item.counter as Any? ?? NSNull(),
                "tokenType": item.type.rawValue.uppercased(), "source": "Manual"
            ]
            return [
                "name": item.issuer ?? item.accountName, "secret": Base32.encode(item.secret, padding: false),
                "updatedAt": milliseconds(timestamp), "serviceTypeID": NSNull(), "groupId": NSNull(),
                "badge": NSNull(), "icon": NSNull(), "order": ["position": index], "otp": otp
            ]
        }
        var root: InterchangeObject = [
            "schemaVersion": 4, "updatedAt": milliseconds(Date()), "appOrigin": "ios",
            "appVersionCode": 0, "appVersionName": "InkOTP interchange", "groups": [Any]()
        ]
        if encrypted {
            var plain = try InterchangeJSON.encode(services)
            defer { plain.resetBytes(in: plain.indices) }
            let fields = try InterchangeCrypto.sealTwoFas(plain, password: password)
            root["servicesEncrypted"] = fields.services
            root["reference"] = fields.reference
            root["services"] = [Any]()
        } else {
            root["services"] = services
        }
        return try InterchangeJSON.encode(root)
    }

    private static func hasGroupMetadata(_ object: InterchangeObject) -> Bool {
        (object["groups"] as? [Any])?.isEmpty == false
    }

    private static func milliseconds(_ date: Date) -> Int64 {
        Int64(floor(date.timeIntervalSince1970 * 1000))
    }

    private static func checkedPassword(_ password: String?, for format: InterchangeFormat) throws -> String {
        guard format.requiresPassword else { return "" }
        guard let password, password.utf16.count <= 1024, !password.isEmpty || format == .inkotp2fab else {
            throw InterchangeFailure(.passwordRequired)
        }
        return password
    }

    private static func nonempty(_ string: String?) -> String? {
        guard let string, !string.isEmpty else { return nil }
        return string
    }

    private static func normalize(_ input: String, limit: Int, warnings: inout Set<InterchangeWarningCode>) throws -> String {
        let normalized = VaultText.normalize(input).value
        guard normalized.unicodeScalars.count <= limit else { throw InterchangeFailure(.unsupportedEntries) }
        if !normalized.utf8.elementsEqual(input.utf8) { warnings.insert(.sourceNormalized) }
        return normalized
    }

    private static func wipe(_ entries: inout [VaultItem]) {
        for index in entries.indices {
            let range = entries[index].secret.indices
            entries[index].secret.resetBytes(in: range)
        }
    }

    private static func guarded<T>(_ action: () throws -> T) throws -> T {
        do {
            return try action()
        } catch let failure as InterchangeFailure {
            throw failure
        } catch let failure as BackupFailure {
            switch failure {
            case .authentication: throw InterchangeFailure(.authenticationFailed)
            case .version: throw InterchangeFailure(.unsupportedVersion)
            case .kdfAlgorithm, .kdfParameters: throw InterchangeFailure(.unsupportedKDF)
            default: throw InterchangeFailure(.malformedFile)
            }
        } catch {
            throw InterchangeFailure(.malformedFile)
        }
    }
}

private extension String {
    func lowercasedPrefixMatches(_ prefix: String) -> Bool {
        self.prefix(prefix.count).lowercased() == prefix
    }
}
