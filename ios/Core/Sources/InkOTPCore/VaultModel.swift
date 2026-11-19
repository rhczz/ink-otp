import Foundation

public struct VaultSettings: Codable, Equatable, Sendable {
    public var lockTimeoutSeconds: Int
    public var hideCodesByDefault: Bool
    public var clipboardClearSeconds: Int

    public init(lockTimeoutSeconds: Int = 30, hideCodesByDefault: Bool = false, clipboardClearSeconds: Int = 30) {
        self.lockTimeoutSeconds = lockTimeoutSeconds
        self.hideCodesByDefault = hideCodesByDefault
        self.clipboardClearSeconds = clipboardClearSeconds
    }

    private enum CodingKeys: String, CodingKey {
        case lockTimeoutSeconds, hideCodesByDefault, clipboardClearSeconds
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        lockTimeoutSeconds = try container.decode(Int.self, forKey: .lockTimeoutSeconds, defaultingTo: 30)
        hideCodesByDefault = try container.decode(Bool.self, forKey: .hideCodesByDefault, defaultingTo: false)
        clipboardClearSeconds = try container.decode(Int.self, forKey: .clipboardClearSeconds, defaultingTo: 30)
    }
}

public struct VaultIcon: Codable, Equatable, Sendable {
    public var kind: String
    public var value: String
    public var colorHex: String?

    public init(kind: String, value: String, colorHex: String? = nil) {
        self.kind = kind
        self.value = value
        self.colorHex = colorHex
    }

    private enum CodingKeys: String, CodingKey {
        case kind, value, colorHex
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(kind, forKey: .kind)
        try container.encode(value, forKey: .value)
        try container.encode(colorHex, forKey: .colorHex)
    }
}

public struct VaultItem: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var type: OtpType
    public var issuer: String?
    public var accountName: String
    public var secret: Data
    public var algorithm: OtpAlgorithm
    public var digits: Int
    public var period: Int
    public var counter: Int64?
    public var tags: [String]
    public var icon: VaultIcon?
    public var sortIndex: Int
    public var origin: String?
    public var createdAt: String
    public var updatedAt: String
    public var website: String?

    public init(
        id: String = UUID().uuidString.lowercased(),
        type: OtpType = .totp,
        issuer: String? = nil,
        accountName: String,
        secret: Data,
        algorithm: OtpAlgorithm = .sha1,
        digits: Int = 6,
        period: Int = 30,
        counter: Int64? = nil,
        tags: [String] = [],
        icon: VaultIcon? = nil,
        sortIndex: Int = 1000,
        origin: String? = nil,
        createdAt: String = VaultJson.isoTimestamp(),
        updatedAt: String = VaultJson.isoTimestamp(),
        website: String? = nil
    ) {
        self.id = id
        self.type = type
        self.issuer = issuer
        self.accountName = accountName
        self.secret = secret
        self.algorithm = algorithm
        self.digits = digits
        self.period = period
        self.counter = counter
        self.tags = tags
        self.icon = icon
        self.sortIndex = sortIndex
        self.origin = origin
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.website = website
    }

    private enum CodingKeys: String, CodingKey {
        case id, type, issuer, accountName
        case secret = "secretB64"
        case algorithm, digits, period, counter, tags, icon, sortIndex, origin, createdAt, updatedAt, website
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        let rawType = try container.decode(String.self, forKey: .type)
        guard let decodedType = OtpType(rawValue: rawType.lowercased()) else {
            throw VaultJsonError.invalidField("type")
        }
        type = decodedType
        issuer = try container.decodeIfPresent(String.self, forKey: .issuer)
        accountName = try container.decode(String.self, forKey: .accountName)
        var base64 = try container.decode(String.self, forKey: .secret)
        if !base64.contains("=") {
            let paddingLength = (4 - base64.utf8.count % 4) % 4
            base64.append(String(repeating: "=", count: paddingLength))
        }
        guard let decodedSecret = Data(base64Encoded: base64) else {
            throw VaultJsonError.invalidField("secretB64")
        }
        secret = decodedSecret
        let rawAlgorithm = try container.decode(String.self, forKey: .algorithm, defaultingTo: "SHA1")
        do {
            algorithm = try OtpAlgorithm.specName(rawAlgorithm)
        } catch {
            throw VaultJsonError.invalidField("algorithm")
        }
        digits = try container.decode(Int.self, forKey: .digits, defaultingTo: 6)
        period = try container.decode(Int.self, forKey: .period, defaultingTo: 30)
        counter = try container.decodeIfPresent(Int64.self, forKey: .counter)
        tags = try container.decode([String].self, forKey: .tags, defaultingTo: [])
        icon = try container.decodeIfPresent(VaultIcon.self, forKey: .icon)
        sortIndex = Int(try container.decode(Int32.self, forKey: .sortIndex))
        origin = try container.decodeIfPresent(String.self, forKey: .origin)
        createdAt = try container.decode(String.self, forKey: .createdAt)
        updatedAt = try container.decode(String.self, forKey: .updatedAt)
        website = try container.decodeIfPresent(String.self, forKey: .website)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(type, forKey: .type)
        try container.encode(issuer, forKey: .issuer)
        try container.encode(accountName, forKey: .accountName)
        try container.encode(secret.base64EncodedString(), forKey: .secret)
        try container.encode(algorithm, forKey: .algorithm)
        try container.encode(digits, forKey: .digits)
        try container.encode(period, forKey: .period)
        try container.encode(counter, forKey: .counter)
        try container.encode(tags, forKey: .tags)
        try container.encode(icon, forKey: .icon)
        try container.encode(sortIndex, forKey: .sortIndex)
        try container.encode(origin, forKey: .origin)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encode(updatedAt, forKey: .updatedAt)
        try container.encode(website, forKey: .website)
    }
}

public struct VaultModel: Codable, Equatable, Sendable {
    public var schemaVersion: Int
    public var vaultId: String
    public var createdAt: String
    public var updatedAt: String
    public var settings: VaultSettings
    public var items: [VaultItem]

    public init(
        schemaVersion: Int = 1,
        vaultId: String = UUID().uuidString.lowercased(),
        createdAt: String = VaultJson.isoTimestamp(),
        updatedAt: String = VaultJson.isoTimestamp(),
        settings: VaultSettings = .init(),
        items: [VaultItem] = []
    ) {
        self.schemaVersion = schemaVersion
        self.vaultId = vaultId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.settings = settings
        self.items = items
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, vaultId, createdAt, updatedAt, settings, items
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
        guard schemaVersion == 1 else { throw VaultJsonError.unsupportedSchema }
        vaultId = try container.decode(String.self, forKey: .vaultId)
        createdAt = try container.decode(String.self, forKey: .createdAt)
        updatedAt = try container.decode(String.self, forKey: .updatedAt)
        settings = try container.decode(VaultSettings.self, forKey: .settings, defaultingTo: .init())
        items = try container.decode([VaultItem].self, forKey: .items)
    }
}

public enum VaultJsonError: Error, Equatable, Sendable {
    case invalidUTF8
    case invalidJSON
    case unsupportedSchema
    case invalidField(String)
    case duplicateItemID
}

enum VaultText {
    static func normalize(_ input: String) -> (value: String, strippedControls: Bool) {
        let normalized = input.precomposedStringWithCanonicalMapping
        var output = String.UnicodeScalarView()
        var strippedControls = false
        for scalar in normalized.unicodeScalars {
            switch scalar.value {
            case 0x00...0x1F, 0x7F...0x9F:
                strippedControls = true
            default:
                output.append(scalar)
            }
        }
        return (String(output), strippedControls)
    }

    static func nonEmpty(_ input: String?) -> String? {
        guard let input else { return nil }
        let text = normalize(input).value
        return text.isEmpty ? nil : text
    }
}

public enum VaultJson {
    public static func decode(_ data: Data) throws -> VaultModel {
        guard String(data: data, encoding: .utf8) != nil else { throw VaultJsonError.invalidUTF8 }
        do {
            let decoded = try JSONDecoder().decode(VaultModel.self, from: data)
            return try validate(decoded)
        } catch let error as VaultJsonError {
            throw error
        } catch {
            throw VaultJsonError.invalidJSON
        }
    }

    public static func encode(_ model: VaultModel) throws -> Data {
        let validated = try validate(model)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(validated)
    }

    public static func isoTimestamp(_ date: Date = Date()) -> String {
        date.ISO8601Format(.init(includingFractionalSeconds: true))
    }

    public static func validate(_ model: VaultModel) throws -> VaultModel {
        guard model.schemaVersion == 1 else { throw VaultJsonError.unsupportedSchema }
        guard let vaultID = UUID(uuidString: model.vaultId) else {
            throw VaultJsonError.invalidField("vaultId")
        }
        try requireInstant(model.createdAt, field: "createdAt")
        try requireInstant(model.updatedAt, field: "updatedAt")
        guard (0...3600).contains(model.settings.lockTimeoutSeconds) else {
            throw VaultJsonError.invalidField("lockTimeoutSeconds")
        }
        guard (0...300).contains(model.settings.clipboardClearSeconds) else {
            throw VaultJsonError.invalidField("clipboardClearSeconds")
        }
        guard model.items.count <= 10_000 else { throw VaultJsonError.invalidField("items") }

        var result = model
        result.vaultId = vaultID.uuidString.lowercased()
        var ids = Set<String>()
        result.items = try model.items.map { item in
            let validated = try validateItem(item)
            guard ids.insert(validated.id).inserted else { throw VaultJsonError.duplicateItemID }
            return validated
        }
        return result
    }

    private static func validateItem(_ item: VaultItem) throws -> VaultItem {
        guard let id = UUID(uuidString: item.id) else { throw VaultJsonError.invalidField("item.id") }
        var result = item
        result.id = id.uuidString.lowercased()
        result.accountName = VaultText.normalize(item.accountName).value
        result.issuer = VaultText.nonEmpty(item.issuer)
        guard (1...256).contains(result.accountName.unicodeScalars.count) else {
            throw VaultJsonError.invalidField("accountName")
        }
        guard result.issuer.map({ $0.unicodeScalars.count <= 128 }) ?? true else {
            throw VaultJsonError.invalidField("issuer")
        }

        do {
            try OtpEngine.validateSecret(item.secret)
            try OtpEngine.validateDigits(item.digits)
            try OtpEngine.validatePeriod(item.period)
        } catch let error as OtpError {
            throw VaultJsonError.invalidField(error.rawValue)
        }

        switch item.type {
        case .totp:
            guard item.counter == nil else { throw VaultJsonError.invalidField("counter") }
        case .hotp:
            guard let counter = item.counter, counter >= 0 else { throw VaultJsonError.invalidField("counter") }
        }
        guard Int32(exactly: item.sortIndex) != nil else { throw VaultJsonError.invalidField("sortIndex") }
        guard item.tags.count <= 20 else { throw VaultJsonError.invalidField("tags") }
        result.tags = try item.tags.map {
            let tag = VaultText.normalize($0).value
            guard (1...32).contains(tag.unicodeScalars.count) else {
                throw VaultJsonError.invalidField("tag")
            }
            return tag
        }
        result.icon = try item.icon.map(validateIcon)
        result.origin = VaultText.nonEmpty(item.origin)
        guard result.origin.map({ $0.utf16.count <= 32 }) ?? true else {
            throw VaultJsonError.invalidField("origin")
        }
        result.website = VaultText.nonEmpty(item.website)
        guard result.website.map({ $0.unicodeScalars.count <= 256 }) ?? true else {
            throw VaultJsonError.invalidField("website")
        }
        try requireInstant(item.createdAt, field: "item.createdAt")
        try requireInstant(item.updatedAt, field: "item.updatedAt")
        return result
    }

    private static func validateIcon(_ icon: VaultIcon) throws -> VaultIcon {
        let kind = VaultText.normalize(icon.kind).value
        let value = VaultText.normalize(icon.value).value
        guard (1...32).contains(kind.utf16.count), (1...2).contains(value.unicodeScalars.count) else {
            throw VaultJsonError.invalidField("icon")
        }
        let color = icon.colorHex.flatMap { $0.isEmpty ? nil : $0 }
        if let color {
            let bytes = color.utf8
            guard bytes.count == 7, bytes.first == 35,
                  bytes.dropFirst().allSatisfy({ $0.isASCIIHexDigit }) else {
                throw VaultJsonError.invalidField("icon.colorHex")
            }
        }
        return VaultIcon(kind: kind, value: value, colorHex: color)
    }

    private static func requireInstant(_ value: String, field: String) throws {
        let style = Date.ISO8601FormatStyle(timeZoneSeparator: .colon, includingFractionalSeconds: value.contains("."))
        let bounds = value.startIndex..<value.endIndex
        guard let parsed = try? style.consuming(value, startingAt: bounds.lowerBound, in: bounds),
              parsed.upperBound == bounds.upperBound else {
            throw VaultJsonError.invalidField(field)
        }
    }
}

private extension KeyedDecodingContainer {
    func decode<T: Decodable>(_ type: T.Type, forKey key: Key, defaultingTo value: T) throws -> T {
        guard contains(key) else { return value }
        return try decode(type, forKey: key)
    }
}

extension UInt8 {
    var isASCIIHexDigit: Bool {
        (48...57).contains(self) || (65...70).contains(self) || (97...102).contains(self)
    }
}
