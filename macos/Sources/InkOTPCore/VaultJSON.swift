import CryptoKit
import Foundation

public struct VaultIcon: Codable, Equatable, Sendable {
    public var kind: String
    public var value: String
    public var colorHex: String?

    public init(kind: String, value: String, colorHex: String? = nil) {
        self.kind = kind
        self.value = value
        self.colorHex = colorHex
    }
}

extension OTPType: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        guard let value = Self(rawValue: try container.decode(String.self).uppercased()) else {
            throw CoreError("VLT_E_SCHEMA")
        }
        self = value
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue.lowercased())
    }
}

extension OTPAlgorithm: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        guard let value = Self(rawValue: try container.decode(String.self).uppercased()) else {
            throw CoreError("VLT_E_SCHEMA")
        }
        self = value
    }
}

public struct VaultItem: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var type: OTPType
    public var issuer: String?
    public var accountName: String
    public var secret: Data
    public var algorithm: OTPAlgorithm
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

    private enum CodingKeys: String, CodingKey {
        case id, type, issuer, accountName, algorithm, digits, period, counter, tags, icon
        case sortIndex, origin, createdAt, updatedAt, website
        case secret = "secretB64"
    }

    public static func make(type: OTPType = .totp, issuer: String? = nil, accountName: String,
                            secret: Data, algorithm: OTPAlgorithm = .sha1, digits: Int = 6,
                            period: Int = 30, counter: Int64? = nil, sortIndex: Int = 1000,
                            origin: String? = nil) -> Self {
        let now = VaultJSON.timestamp()
        return Self(id: UUID().uuidString.lowercased(), type: type, issuer: issuer, accountName: accountName,
                    secret: secret, algorithm: algorithm, digits: digits, period: period,
                    counter: counter, tags: [], icon: nil, sortIndex: sortIndex, origin: origin,
                    createdAt: now, updatedAt: now, website: nil)
    }

    private init(id: String, type: OTPType, issuer: String?, accountName: String, secret: Data,
                 algorithm: OTPAlgorithm, digits: Int, period: Int, counter: Int64?, tags: [String],
                 icon: VaultIcon?, sortIndex: Int, origin: String?, createdAt: String, updatedAt: String,
                 website: String?) {
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

    public init(from decoder: Decoder) throws {
        let value = try decoder.container(keyedBy: CodingKeys.self)
        id = try value.decode(String.self, forKey: .id)
        type = try value.decode(OTPType.self, forKey: .type)
        issuer = try value.decodeIfPresent(String.self, forKey: .issuer)
        accountName = try value.decode(String.self, forKey: .accountName)
        var base64 = try value.decode(String.self, forKey: .secret)
        if !base64.contains("="), base64.utf8.count % 4 != 1 {
            base64 += String(repeating: "=", count: (4 - base64.utf8.count % 4) % 4)
        }
        guard let secret = Data(base64Encoded: base64) else { throw CoreError("VLT_E_SCHEMA") }
        self.secret = secret
        algorithm = try value.decodeIfPresent(OTPAlgorithm.self, forKey: .algorithm) ?? .sha1
        digits = try value.decodeIfPresent(Int.self, forKey: .digits) ?? 6
        period = try value.decodeIfPresent(Int.self, forKey: .period) ?? 30
        counter = try value.decodeIfPresent(Int64.self, forKey: .counter)
        tags = try value.decodeIfPresent([String].self, forKey: .tags) ?? []
        icon = try value.decodeIfPresent(VaultIcon.self, forKey: .icon)
        sortIndex = try value.decode(Int.self, forKey: .sortIndex)
        origin = try value.decodeIfPresent(String.self, forKey: .origin)
        createdAt = try value.decode(String.self, forKey: .createdAt)
        updatedAt = try value.decode(String.self, forKey: .updatedAt)
        website = try value.decodeIfPresent(String.self, forKey: .website)
    }
}

public struct VaultSettings: Codable, Equatable, Sendable {
    public var lockTimeoutSeconds: Int
    public var hideCodesByDefault: Bool
    public var clipboardClearSeconds: Int

    private enum CodingKeys: String, CodingKey {
        case lockTimeoutSeconds, hideCodesByDefault, clipboardClearSeconds
    }

    public init(lockTimeoutSeconds: Int = 30, hideCodesByDefault: Bool = false, clipboardClearSeconds: Int = 30) {
        self.lockTimeoutSeconds = lockTimeoutSeconds
        self.hideCodesByDefault = hideCodesByDefault
        self.clipboardClearSeconds = clipboardClearSeconds
    }

    public init(from decoder: Decoder) throws {
        let value = try decoder.container(keyedBy: CodingKeys.self)
        lockTimeoutSeconds = try value.decodeIfPresent(Int.self, forKey: .lockTimeoutSeconds) ?? 30
        hideCodesByDefault = try value.decodeIfPresent(Bool.self, forKey: .hideCodesByDefault) ?? false
        clipboardClearSeconds = try value.decodeIfPresent(Int.self, forKey: .clipboardClearSeconds) ?? 30
    }
}

public struct VaultModel: Codable, Equatable, Sendable {
    public var schemaVersion: Int
    public var vaultId: String
    public var createdAt: String
    public var updatedAt: String
    public var settings: VaultSettings
    public var items: [VaultItem]

    private enum CodingKeys: String, CodingKey { case schemaVersion, vaultId, createdAt, updatedAt, settings, items }

    private init(vaultId: String, now: String) {
        self.schemaVersion = 1
        self.vaultId = vaultId
        self.createdAt = now
        self.updatedAt = now
        self.settings = VaultSettings()
        self.items = []
    }

    public static func new() -> Self {
        Self(vaultId: UUID().uuidString.lowercased(), now: VaultJSON.timestamp())
    }

    public init(from decoder: Decoder) throws {
        let value = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try value.decode(Int.self, forKey: .schemaVersion)
        guard schemaVersion == 1 else { throw CoreError("VLT_E_SCHEMA") }
        vaultId = try value.decode(String.self, forKey: .vaultId)
        createdAt = try value.decode(String.self, forKey: .createdAt)
        updatedAt = try value.decode(String.self, forKey: .updatedAt)
        settings = try value.decodeIfPresent(VaultSettings.self, forKey: .settings) ?? VaultSettings()
        var entries = try value.nestedUnkeyedContainer(forKey: .items)
        guard let count = entries.count, count <= 10_000 else { throw CoreError("VLT_E_SCHEMA") }
        items = []
        items.reserveCapacity(count)
        while !entries.isAtEnd { items.append(try entries.decode(VaultItem.self)) }
    }
}

public enum VaultJSON {
    public static func timestamp(_ date: Date = .now) -> String {
        date.formatted(.iso8601)
    }

    public static func encode(_ model: VaultModel) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(validate(model))
    }

    public static func decode(_ bytes: Data) throws -> VaultModel {
        guard bytes.count <= 8 * 1024 * 1024, String(data: bytes, encoding: .utf8) != nil else {
            throw CoreError("VLT_E_JSON")
        }
        let decoded: VaultModel
        do {
            decoded = try JSONDecoder().decode(VaultModel.self, from: bytes)
        } catch let error as CoreError {
            throw error
        } catch {
            throw CoreError("VLT_E_SCHEMA")
        }
        return try validate(decoded)
    }

    public static func validate(_ model: VaultModel) throws -> VaultModel {
        guard model.schemaVersion == 1, let vaultID = UUID(uuidString: model.vaultId),
              isTimestamp(model.createdAt), isTimestamp(model.updatedAt),
              (0...3600).contains(model.settings.lockTimeoutSeconds),
              (0...300).contains(model.settings.clipboardClearSeconds),
              model.items.count <= 10_000 else { throw CoreError("VLT_E_SCHEMA") }

        var output = model
        output.vaultId = vaultID.uuidString.lowercased()
        output.items = try output.items.map(validateItem)
        guard Set(output.items.map(\.id)).count == output.items.count else { throw CoreError("VLT_E_SCHEMA") }
        return output
    }

    private static func isTimestamp(_ value: String) -> Bool {
        guard value.hasSuffix("Z"), value.utf8.count <= 40 else { return false }
        return (try? Date.ISO8601FormatStyle(includingFractionalSeconds: value.contains(".")).parse(value)) != nil
    }

    private static func validateItem(_ item: VaultItem) throws -> VaultItem {
        guard let id = UUID(uuidString: item.id), (1...255).contains(item.secret.count),
              (1...300).contains(item.period), item.digits == 6 || item.digits == 8,
              (item.type == .totp && item.counter == nil) || (item.type == .hotp && (item.counter ?? -1) >= 0),
              Int32(exactly: item.sortIndex) != nil, item.tags.count <= 20,
              isTimestamp(item.createdAt), isTimestamp(item.updatedAt) else {
            throw CoreError("VLT_E_SCHEMA")
        }

        var output = item
        output.id = id.uuidString.lowercased()
        output.accountName = normalizedText(item.accountName).value
        output.issuer = optionalText(item.issuer)
        output.tags = item.tags.map { normalizedText($0).value }
        output.origin = optionalText(item.origin)
        output.website = optionalText(item.website)
        guard (1...256).contains(output.accountName.unicodeScalars.count),
              (output.issuer?.unicodeScalars.count ?? 0) <= 128,
              (output.origin?.utf16.count ?? 0) <= 32,
              (output.website?.unicodeScalars.count ?? 0) <= 256,
              output.tags.allSatisfy({ (1...32).contains($0.unicodeScalars.count) }) else {
            throw CoreError("VLT_E_SCHEMA")
        }
        if var icon = item.icon {
            icon.kind = normalizedText(icon.kind).value
            icon.value = normalizedText(icon.value).value
            if icon.colorHex == "" { icon.colorHex = nil }
            guard (1...32).contains(icon.kind.utf16.count), (1...2).contains(icon.value.unicodeScalars.count),
                  icon.colorHex.map({ $0.range(of: "^#[a-fA-F0-9]{6}$", options: .regularExpression) != nil }) ?? true else {
                throw CoreError("VLT_E_SCHEMA")
            }
            output.icon = icon
        }
        return output
    }

    private static func optionalText(_ value: String?) -> String? {
        guard let value else { return nil }
        let normalized = normalizedText(value).value
        return normalized.isEmpty ? nil : normalized
    }
}

public enum MergePolicy: String, CaseIterable, Sendable {
    case keepLocal, overwriteLocal, keepBoth
}

public enum VaultMerge {
    private struct Key: Hashable {
        let type: OTPType
        let issuer: String
        let account: String
        let secretHash: SHA256.Digest

        init(_ item: VaultItem) {
            type = item.type
            issuer = (item.issuer ?? "").lowercased()
            account = item.accountName.lowercased()
            secretHash = SHA256.hash(data: item.secret)
        }
    }

    public static func conflictCount(local: [VaultItem], incoming: [VaultItem]) -> Int {
        var keys = Set(local.map(Key.init))
        return incoming.filter { !keys.insert(Key($0)).inserted }.count
    }

    public static func merge(local: [VaultItem], incoming: [VaultItem], policy: MergePolicy) -> [VaultItem] {
        var items = local
        var index = [Key: Int]()
        var ids = Set(local.map(\.id))
        var names = Set(local.map(\.accountName))
        for (position, item) in local.enumerated() where index[Key(item)] == nil {
            index[Key(item)] = position
        }

        for var item in incoming {
            if let position = index[Key(item)] {
                switch policy {
                case .keepLocal: continue
                case .overwriteLocal:
                    item.id = items[position].id
                    items[position] = item
                    continue
                case .keepBoth:
                    let base = item.accountName
                    var suffix = 2
                    while true {
                        let label = " (\(suffix))"
                        let prefix = String(base.unicodeScalars.prefix(256 - label.unicodeScalars.count))
                        item.accountName = prefix + label
                        if !names.contains(item.accountName) { break }
                        suffix += 1
                    }
                }
            }
            if !ids.insert(item.id).inserted {
                item.id = UUID().uuidString.lowercased()
                ids.insert(item.id)
            }
            names.insert(item.accountName)
            index[Key(item)] = items.count
            items.append(item)
        }

        for offset in items.indices { items[offset].sortIndex = (offset + 1) * 1000 }
        return items
    }
}
