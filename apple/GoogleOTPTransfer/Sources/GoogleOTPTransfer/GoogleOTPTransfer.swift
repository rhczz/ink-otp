import Foundation

public enum GoogleOTPAlgorithm: UInt64, Sendable { case sha1 = 1, sha256 = 2, sha512 = 3 }
public enum GoogleOTPType: UInt64, Sendable { case hotp = 1, totp = 2 }

public struct GoogleOTPAccount: Equatable, Sendable {
    public let secret: Data
    public let name: String
    public let issuer: String?
    public var algorithm: GoogleOTPAlgorithm
    public var digits: Int
    public var type: GoogleOTPType
    public var period: Int
    public var counter: Int64?

    public init(secret: Data, name: String, issuer: String?, algorithm: GoogleOTPAlgorithm, digits: Int,
                type: GoogleOTPType, period: Int = 30, counter: Int64? = nil) {
        self.secret = secret
        self.name = name
        self.issuer = issuer
        self.algorithm = algorithm
        self.digits = digits
        self.type = type
        self.period = period
        self.counter = counter
    }
}

public enum GoogleOTPExportIssue: Equatable, Sendable {
    case algorithm, digits, period, counter, accountName, secret, oversized
}

public enum GoogleOTPTransferError: Error, Equatable, Sendable {
    case invalidURI, malformed, unsupportedVersion, unsupportedAccount(Int)
    case mixedBatch, conflictingPage, limitExceeded
    case incompatibleAccount(Int, GoogleOTPExportIssue)
}

public struct GoogleOTPPage: Sendable {
    public let accounts: [GoogleOTPAccount]
    public let version: Int
    public let batchID: Int32
    public let index: Int
    public let total: Int
    fileprivate let payload: Data
}

public struct GoogleOTPBatch {
    private var pages: [Int: GoogleOTPPage] = [:]
    private var firstPage: GoogleOTPPage?
    private var accountCount = 0

    public init() {}

    public var receivedCount: Int { pages.count }
    public var totalCount: Int? { firstPage?.total }

    public mutating func add(_ page: GoogleOTPPage) throws -> [GoogleOTPAccount]? {
        if let firstPage,
           firstPage.version != page.version || firstPage.batchID != page.batchID || firstPage.total != page.total {
            throw GoogleOTPTransferError.mixedBatch
        }
        if let existing = pages[page.index] {
            guard existing.payload == page.payload else { throw GoogleOTPTransferError.conflictingPage }
            return nil
        }
        guard accountCount + page.accounts.count <= GoogleOTPTransferCodec.maxAccounts else {
            throw GoogleOTPTransferError.limitExceeded
        }
        if firstPage == nil { firstPage = page }
        pages[page.index] = page
        accountCount += page.accounts.count
        guard pages.count == page.total else { return nil }
        return (0..<page.total).flatMap { pages[$0]?.accounts ?? [] }
    }
}

public enum GoogleOTPTransferCodec {
    public static let maxAccounts = 500
    private static let maxPages = 500
    private static let maxInputBytes = 16_384
    private static let maxOutputBytes = 2000
    private static let maxAccountsPerOutputPage = 10
    private static let prefix = "otpauth-migration://offline?data="

    public static func isMigrationURI(_ input: String) -> Bool {
        let scheme = "otpauth-migration:"
        return input.trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(scheme.count).lowercased() == scheme
    }

    public static func decode(_ uri: String) throws -> GoogleOTPPage {
        let input = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard input.utf8.count <= maxInputBytes else { throw GoogleOTPTransferError.limitExceeded }
        guard input.utf8.allSatisfy({ (33...126).contains($0) }),
              let url = URLComponents(string: input),
              url.scheme?.lowercased() == "otpauth-migration",
              url.percentEncodedHost?.lowercased() == "offline",
              url.user == nil, url.password == nil, url.port == nil, url.fragment == nil,
              url.path.isEmpty || url.path == "/",
              let query = url.queryItems, query.count == 1, query[0].name == "data",
              let encoded = query[0].value, let payload = base64(encoded) else {
            throw GoogleOTPTransferError.invalidURI
        }

        var reader = ProtoReader(payload)
        var rows: [Data] = []
        var fields: [UInt64: UInt64] = [:]
        while let key = try reader.nextKey() {
            switch key {
            case 10:
                guard rows.count < maxAccounts else { throw GoogleOTPTransferError.limitExceeded }
                rows.append(try reader.bytes())
            case 16, 24, 32, 40:
                guard fields[key] == nil else { throw GoogleOTPTransferError.malformed }
                fields[key] = try reader.varint()
            default:
                throw GoogleOTPTransferError.malformed
            }
        }
        guard let version = fields[16], version == 1 || version == 2 else {
            throw GoogleOTPTransferError.unsupportedVersion
        }
        let total = fields[24] ?? 1
        let index = fields[32] ?? 0
        let batchID = fields[40] ?? 0
        guard total <= UInt64(maxPages) else { throw GoogleOTPTransferError.limitExceeded }
        guard total > 0, index < total, !rows.isEmpty,
              total == 1 || fields[40] != nil,
              batchID <= UInt64(Int32.max) || batchID >= UInt64(bitPattern: Int64(Int32.min)) else {
            throw GoogleOTPTransferError.malformed
        }
        let accounts = try rows.enumerated().map { index, bytes in
            do { return try decodeAccount(bytes) }
            catch { throw GoogleOTPTransferError.unsupportedAccount(index + 1) }
        }
        return GoogleOTPPage(accounts: accounts, version: Int(version), batchID: Int32(truncatingIfNeeded: batchID),
                             index: Int(index), total: Int(total), payload: payload)
    }

    public static func exportIssue(_ account: GoogleOTPAccount) -> GoogleOTPExportIssue? {
        if account.algorithm != .sha1 { return .algorithm }
        if account.digits != 6 { return .digits }
        if account.type == .totp && account.period != 30 { return .period }
        if account.type == .hotp ? (account.counter ?? -1) < 0 : account.counter != nil { return .counter }
        if !(1...255).contains(account.secret.count) { return .secret }

        let labels = splitName(account.name, issuer: account.issuer ?? "")
        if account.name.isEmpty || !labels.name.utf8.elementsEqual(account.name.utf8) ||
            !(labels.issuer ?? "").utf8.elementsEqual((account.issuer ?? "").utf8) || account.issuer == "" ||
            account.name.unicodeScalars.count > 256 || (account.issuer?.unicodeScalars.count ?? 0) > 128 {
            return .accountName
        }
        if !fitsOutput([encodeAccount(account)]) { return .oversized }
        return nil
    }

    public static func encode(_ accounts: [GoogleOTPAccount], batchID: Int32? = nil) throws -> [String] {
        guard (1...maxAccounts).contains(accounts.count) else { throw GoogleOTPTransferError.limitExceeded }
        var pages: [[Data]] = []
        var page: [Data] = []
        for (index, account) in accounts.enumerated() {
            if let issue = exportIssue(account) {
                throw GoogleOTPTransferError.incompatibleAccount(index + 1, issue)
            }
            let entry = encodeAccount(account)
            if page.count == maxAccountsPerOutputPage || !fitsOutput(page + [entry]) {
                pages.append(page)
                page.removeAll(keepingCapacity: true)
            }
            page.append(entry)
        }
        pages.append(page)
        let id = batchID ?? Int32.random(in: 1...Int32.max)
        return try pages.enumerated().map { index, page in
            let uri = outputURI(page, index: index, total: pages.count, batchID: id)
            guard uri.utf8.count <= maxOutputBytes else { throw GoogleOTPTransferError.limitExceeded }
            return uri
        }
    }

    private static func decodeAccount(_ bytes: Data) throws -> GoogleOTPAccount {
        var reader = ProtoReader(bytes)
        var strings: [UInt64: Data] = [:]
        var numbers: [UInt64: UInt64] = [:]
        while let key = try reader.nextKey() {
            switch key {
            case 10, 18, 26:
                guard strings[key] == nil else { throw GoogleOTPTransferError.malformed }
                strings[key] = try reader.bytes()
            case 66:
                // Google's unique_id is app metadata. It does not affect OTP generation.
                guard strings[key] == nil else { throw GoogleOTPTransferError.malformed }
                let uniqueID = try reader.bytes()
                guard uniqueID.count <= 1024, String(data: uniqueID, encoding: .utf8) != nil else {
                    throw GoogleOTPTransferError.malformed
                }
                strings[key] = uniqueID
            case 32, 40, 48, 56:
                guard numbers[key] == nil else { throw GoogleOTPTransferError.malformed }
                numbers[key] = try reader.varint()
            default:
                throw GoogleOTPTransferError.malformed
            }
        }
        func defaulted(_ key: UInt64, _ fallback: UInt64) -> UInt64 {
            let value = numbers[key] ?? 0
            return value == 0 ? fallback : value
        }
        let digits = defaulted(40, 1)
        let counter = numbers[56] ?? 0
        guard let secret = strings[10], (1...255).contains(secret.count),
              let rawName = strings[18], let name = String(data: rawName, encoding: .utf8),
              let issuer = String(data: strings[26] ?? Data(), encoding: .utf8),
              let algorithm = GoogleOTPAlgorithm(rawValue: defaulted(32, 1)),
              let type = GoogleOTPType(rawValue: defaulted(48, 2)),
              digits == 1 || digits == 2, counter <= UInt64(Int64.max),
              type == .hotp || counter == 0 else { throw GoogleOTPTransferError.malformed }
        let labels = splitName(name, issuer: issuer)
        guard (1...256).contains(labels.name.unicodeScalars.count),
              (labels.issuer?.unicodeScalars.count ?? 0) <= 128 else { throw GoogleOTPTransferError.malformed }
        return GoogleOTPAccount(secret: secret, name: labels.name, issuer: labels.issuer, algorithm: algorithm,
                                digits: digits == 1 ? 6 : 8, type: type, counter: type == .hotp ? Int64(counter) : nil)
    }

    private static func splitName(_ name: String, issuer: String) -> (name: String, issuer: String?) {
        let issuer = normalized(issuer)
        let name = normalized(name)
        if issuer.isEmpty, let colon = name.firstIndex(of: ":") {
            let prefix = normalized(String(name[..<colon]))
            if !prefix.isEmpty {
                return (normalized(String(name[name.index(after: colon)...])), prefix)
            }
        }
        if !issuer.isEmpty && name.hasPrefix(issuer + ":") {
            return (normalized(String(name.dropFirst(issuer.count + 1))), issuer)
        }
        return (name, issuer.isEmpty ? nil : issuer)
    }

    private static func normalized(_ text: String) -> String {
        String(String.UnicodeScalarView(text.unicodeScalars.filter {
            !(0...31).contains($0.value) && !(127...159).contains($0.value)
        })).precomposedStringWithCanonicalMapping.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func base64(_ text: String) -> Data? {
        var normalized = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        if !normalized.contains("=") { normalized += String(repeating: "=", count: (4 - normalized.count % 4) % 4) }
        return Data(base64Encoded: normalized)
    }

    private static func fitsOutput(_ page: [Data]) -> Bool {
        outputURI(page, index: maxPages - 1, total: maxPages, batchID: -1).utf8.count <= maxOutputBytes
    }

    private static func outputURI(_ page: [Data], index: Int, total: Int, batchID: Int32) -> String {
        var writer = ProtoWriter()
        for account in page { writer.bytes(account, field: 1) }
        writer.number(1, field: 2)
        writer.number(UInt64(total), field: 3)
        writer.number(UInt64(index), field: 4)
        writer.number(UInt64(bitPattern: Int64(batchID)), field: 5)
        let query = writer.data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "%2B")
            .replacingOccurrences(of: "/", with: "%2F")
            .replacingOccurrences(of: "=", with: "%3D")
        return prefix + query
    }

    private static func encodeAccount(_ account: GoogleOTPAccount) -> Data {
        var writer = ProtoWriter()
        writer.bytes(account.secret, field: 1)
        writer.bytes(Data(account.name.utf8), field: 2)
        if let issuer = account.issuer { writer.bytes(Data(issuer.utf8), field: 3) }
        writer.number(account.algorithm.rawValue, field: 4)
        writer.number(1, field: 5)
        writer.number(account.type.rawValue, field: 6)
        if let counter = account.counter { writer.number(UInt64(counter), field: 7) }
        return writer.data
    }
}

private struct ProtoReader {
    private let data: [UInt8]
    private var offset = 0

    init(_ data: Data) { self.data = Array(data) }

    mutating func nextKey() throws -> UInt64? {
        offset < data.count ? try varint() : nil
    }

    mutating func varint() throws -> UInt64 {
        var result: UInt64 = 0
        for shift in stride(from: 0, through: 63, by: 7) {
            guard offset < data.count else { throw GoogleOTPTransferError.malformed }
            let byte = data[offset]
            offset += 1
            guard shift < 63 || byte <= 1 else { throw GoogleOTPTransferError.malformed }
            result |= UInt64(byte & 127) << shift
            if byte < 128 { return result }
        }
        throw GoogleOTPTransferError.malformed
    }

    mutating func bytes() throws -> Data {
        let length = try varint()
        guard length <= UInt64(data.count - offset) else { throw GoogleOTPTransferError.malformed }
        let end = offset + Int(length)
        defer { offset = end }
        return Data(data[offset..<end])
    }
}

private struct ProtoWriter {
    private(set) var data = Data()

    mutating func bytes(_ value: Data, field: UInt64) {
        varint((field << 3) | 2)
        varint(UInt64(value.count))
        data.append(value)
    }

    mutating func number(_ value: UInt64, field: UInt64) {
        varint(field << 3)
        varint(value)
    }

    private mutating func varint(_ value: UInt64) {
        var remaining = value
        while remaining >= 128 {
            data.append(UInt8(truncatingIfNeeded: remaining) | 128)
            remaining >>= 7
        }
        data.append(UInt8(remaining))
    }
}
