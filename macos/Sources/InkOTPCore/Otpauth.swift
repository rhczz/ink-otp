import Foundation

public struct OTPDraft: Sendable {
    public enum Kind: Equatable, Sendable { case entry, bareSecret, migration }
    public let kind: Kind
    public let type: OTPType
    public let issuer: String?
    public let accountName: String?
    public let secret: Data
    public let algorithm: OTPAlgorithm
    public let digits: Int
    public let period: Int
    public let counter: UInt64?
    public let warnings: [String]
    public let host: String?
    public let dataBase64: String?

    init(kind: Kind = .entry, type: OTPType = .totp, issuer: String? = nil, accountName: String? = nil,
         secret: Data = Data(), algorithm: OTPAlgorithm = .sha1, digits: Int = 6, period: Int = 30,
         counter: UInt64? = nil, warnings: [String] = [], host: String? = nil, dataBase64: String? = nil) {
        self.kind = kind
        self.type = type
        self.issuer = issuer
        self.accountName = accountName
        self.secret = secret
        self.algorithm = algorithm
        self.digits = digits
        self.period = period
        self.counter = counter
        self.warnings = warnings
        self.host = host
        self.dataBase64 = dataBase64
    }
}

public enum Otpauth {
    public static func parse(_ input: String) throws -> OTPDraft {
        guard input.utf8.count <= 16_384 else { throw CoreError("URI_MALFORMED") }
        guard input.contains("://") else {
            return OTPDraft(kind: .bareSecret, secret: try decodeSecret(input))
        }

        guard !input.utf8.contains(where: { $0 <= 32 || $0 == 127 }),
              !input.contains("\\"),
              input.removingPercentEncoding != nil,
              let url = URLComponents(string: input),
              let scheme = url.scheme?.lowercased() else { throw CoreError("URI_MALFORMED") }
        guard scheme == "otpauth" || scheme == "otpauth-migration" else { throw CoreError("SCHEME_UNSUPPORTED") }
        guard let host = url.host?.lowercased(), !host.isEmpty else { throw CoreError("HOST_UNSUPPORTED") }

        let query = try parseQuery(url.percentEncodedQuery)
        if scheme == "otpauth-migration" {
            guard let data = query["data"], !data.isEmpty else { throw CoreError("URI_MALFORMED") }
            return OTPDraft(kind: .migration, host: host, dataBase64: data)
        }
        guard let type = OTPType(rawValue: host.uppercased()) else { throw CoreError("HOST_UNSUPPORTED") }

        let rawLabel = url.percentEncodedPath.dropFirst()
        guard !rawLabel.isEmpty else { throw CoreError("LABEL_EMPTY") }
        let label = rawLabel.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        let account = try decodeLabel(String(label[label.count - 1]))
        guard !account.value.isEmpty else { throw CoreError("LABEL_ACCOUNT_EMPTY") }
        guard account.value.unicodeScalars.count <= 256 else { throw CoreError("LABEL_ACCOUNT_TOO_LONG") }
        let labelIssuer = try label.count == 2 ? decodeLabel(String(label[0])) : ("", false)
        guard labelIssuer.0.unicodeScalars.count <= 128 else { throw CoreError("LABEL_ISSUER_TOO_LONG") }
        guard let secretText = query["secret"] else { throw CoreError("SECRET_MISSING") }
        let secret = try decodeSecret(secretText)

        let algorithmText = query["algorithm"].flatMap { $0.isEmpty ? nil : $0 } ?? "SHA1"
        guard let algorithm = OTPAlgorithm(rawValue: algorithmText.uppercased()) else {
            throw CoreError("ALGORITHM_UNSUPPORTED")
        }
        guard let digits = Int(query["digits"] ?? "6"), digits == 6 || digits == 8 else {
            throw CoreError("DIGITS_UNSUPPORTED")
        }
        let issuerField = query["issuer"].flatMap { $0.isEmpty ? nil : normalizedText($0) }
        guard (issuerField?.value.unicodeScalars.count ?? 0) <= 128 else { throw CoreError("ISSUER_TOO_LONG") }
        let issuer = issuerField?.value ?? (labelIssuer.0.isEmpty ? nil : labelIssuer.0)
        var warnings = [String]()
        if let issuer, issuerField != nil, !labelIssuer.0.isEmpty, labelIssuer.0 != issuer {
            warnings.append("ISSUER_MISMATCH")
        }
        if account.strippedControls || labelIssuer.1 || issuerField?.strippedControls == true {
            warnings.append("CONTROL_CHARS_STRIPPED")
        }

        if type == .hotp {
            let counter = try parseCounter(query["counter"] ?? "0")
            return OTPDraft(type: type, issuer: issuer, accountName: account.value, secret: secret,
                            algorithm: algorithm, digits: digits, counter: counter, warnings: warnings)
        }
        guard let period = Int(query["period"] ?? "30"), (1...300).contains(period) else {
            throw CoreError("PERIOD_OUT_OF_RANGE")
        }
        return OTPDraft(type: type, issuer: issuer, accountName: account.value, secret: secret,
                        algorithm: algorithm, digits: digits, period: period, warnings: warnings)
    }

    public static func encode(_ item: VaultItem) -> String {
        let issuer = item.issuer.map(escape)
        let label = (issuer.map { $0 + ":" } ?? "") + escape(item.accountName)
        var fields = ["secret=" + OTP.base32Encode(item.secret, padding: false)]
        if let issuer { fields.append("issuer=" + issuer) }
        fields.append("algorithm=" + item.algorithm.rawValue)
        fields.append("digits=" + String(item.digits))
        if item.type == .hotp {
            fields.append("counter=" + String(item.counter ?? 0))
        } else {
            fields.append("period=" + String(item.period))
        }
        return "otpauth://" + item.type.rawValue.lowercased() + "/" + label + "?" + fields.joined(separator: "&")
    }

    private static func escape(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }

    private static func decodeSecret(_ raw: String) throws -> Data {
        guard raw.utf8.count <= 2048 else { throw CoreError("SECRET_TOO_LONG") }
        guard let secret = try? OTP.base32Decode(raw) else { throw CoreError("SECRET_INVALID_BASE32") }
        guard !secret.isEmpty else { throw CoreError("SECRET_EMPTY") }
        guard secret.count <= 255 else { throw CoreError("SECRET_TOO_LONG") }
        return secret
    }

    private static func decodeLabel(_ raw: String) throws -> (value: String, strippedControls: Bool) {
        guard let decoded = raw.removingPercentEncoding else { throw CoreError("URI_MALFORMED") }
        return normalizedText(decoded)
    }

    private static func parseQuery(_ raw: String?) throws -> [String: String] {
        var result = [String: String]()
        for pair in (raw ?? "").split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard let key = decodeQuery(parts[0]),
                  let value = decodeQuery(parts.count == 2 ? parts[1] : "") else {
                throw CoreError("URI_MALFORMED")
            }
            if result[key] == nil { result[key] = value }
        }
        return result
    }

    private static func decodeQuery(_ value: Substring) -> String? {
        value.replacingOccurrences(of: "+", with: " ").removingPercentEncoding
    }

    private static func parseCounter(_ raw: String) throws -> UInt64 {
        if raw.hasPrefix("-") { throw CoreError("COUNTER_NEGATIVE") }
        guard !raw.isEmpty, raw.utf8.allSatisfy({ (48...57).contains($0) }) else { throw CoreError("COUNTER_INVALID") }
        guard let counter = UInt64(raw) else { throw CoreError("COUNTER_OVERFLOW") }
        return counter
    }
}
