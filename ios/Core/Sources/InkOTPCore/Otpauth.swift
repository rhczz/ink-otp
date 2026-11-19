import Foundation

public enum OtpauthError: String, Error, Sendable {
    case schemeUnsupported = "SCHEME_UNSUPPORTED"
    case hostUnsupported = "HOST_UNSUPPORTED"
    case uriMalformed = "URI_MALFORMED"
    case labelEmpty = "LABEL_EMPTY"
    case labelAccountEmpty = "LABEL_ACCOUNT_EMPTY"
    case labelIssuerTooLong = "LABEL_ISSUER_TOO_LONG"
    case labelAccountTooLong = "LABEL_ACCOUNT_TOO_LONG"
    case issuerTooLong = "ISSUER_TOO_LONG"
    case secretMissing = "SECRET_MISSING"
    case secretEmpty = "SECRET_EMPTY"
    case secretInvalidBase32 = "SECRET_INVALID_BASE32"
    case secretTooLong = "SECRET_TOO_LONG"
    case algorithmUnsupported = "ALGORITHM_UNSUPPORTED"
    case digitsUnsupported = "DIGITS_UNSUPPORTED"
    case periodOutOfRange = "PERIOD_OUT_OF_RANGE"
    case counterInvalid = "COUNTER_INVALID"
    case counterNegative = "COUNTER_NEGATIVE"
    case counterOverflow = "COUNTER_OVERFLOW"
}

public struct OtpFields: Equatable, Sendable {
    public let type: OtpType
    public let issuer: String?
    public let accountName: String?
    public let secret: Data
    public let algorithm: OtpAlgorithm
    public let digits: Int
    public let period: Int?
    public let counter: Int64?
    public let warnings: [String]

    public var needsUserInput: [String] {
        accountName == nil ? ["issuer", "accountName"] : []
    }
}

public enum OtpauthResult: Equatable, Sendable {
    case uri(OtpFields)
    case bareSecret(OtpFields)
    case migration(host: String, dataBase64: String)
}

public enum OtpauthParser {
    private static let escaped = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    public static func parse(_ input: String) throws -> OtpauthResult {
        guard let separator = input.range(of: "://") else {
            return try parseBareSecret(input)
        }
        guard separator.upperBound != input.endIndex else { throw OtpauthError.uriMalformed }
        try validateRawURI(input)
        guard let url = URLComponents(string: input) else { throw OtpauthError.uriMalformed }
        guard let scheme = url.scheme?.lowercased(), scheme == "otpauth" || scheme == "otpauth-migration" else {
            throw OtpauthError.schemeUnsupported
        }
        guard let host = url.host?.lowercased(), !host.isEmpty, url.percentEncodedHost?.contains("%") != true else {
            throw OtpauthError.hostUnsupported
        }

        if scheme == "otpauth-migration" {
            let query = parseQuery(url.percentEncodedQuery)
            guard let data = query["data"], !data.isEmpty else { throw OtpauthError.uriMalformed }
            return .migration(host: host, dataBase64: data)
        }

        guard let type = OtpType(rawValue: host) else { throw OtpauthError.hostUnsupported }
        let path = url.percentEncodedPath
        let label = path.hasPrefix("/") ? path.dropFirst() : path[...]
        guard !label.isEmpty else { throw OtpauthError.labelEmpty }

        // Split before decoding percent escapes so an escaped colon is part of a field.
        let parts = label.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        let rawIssuer = parts.count == 2 ? parts[0] : ""
        let rawAccount = parts.count == 2 ? parts[1] : parts[0]
        let labelIssuer = VaultText.normalize(percentDecode(rawIssuer))
        let account = VaultText.normalize(percentDecode(rawAccount))
        guard !account.value.isEmpty else { throw OtpauthError.labelAccountEmpty }
        guard labelIssuer.value.unicodeScalars.count <= 128 else { throw OtpauthError.labelIssuerTooLong }
        guard account.value.unicodeScalars.count <= 256 else { throw OtpauthError.labelAccountTooLong }

        let query = parseQuery(url.percentEncodedQuery)
        guard let encodedSecret = query["secret"] else { throw OtpauthError.secretMissing }
        let secret = try decodeSecret(encodedSecret, invalid: .secretInvalidBase32)
        let algorithm: OtpAlgorithm
        do {
            algorithm = try OtpAlgorithm.specName(query["algorithm"])
        } catch {
            throw OtpauthError.algorithmUnsupported
        }
        let digits = try number(query["digits"], default: 6, invalid: .digitsUnsupported)
        guard digits == 6 || digits == 8 else { throw OtpauthError.digitsUnsupported }

        let queryIssuer = query["issuer"].flatMap { $0.isEmpty ? nil : VaultText.normalize($0) }
        guard queryIssuer.map({ $0.value.unicodeScalars.count <= 128 }) ?? true else {
            throw OtpauthError.issuerTooLong
        }
        let issuer = queryIssuer?.value ?? (labelIssuer.value.isEmpty ? nil : labelIssuer.value)
        var warnings: [String] = []
        if let queryIssuer, !labelIssuer.value.isEmpty, labelIssuer.value != queryIssuer.value {
            warnings.append("ISSUER_MISMATCH")
        }
        if labelIssuer.strippedControls || account.strippedControls || (queryIssuer?.strippedControls ?? false) {
            warnings.append("CONTROL_CHARS_STRIPPED")
        }

        let period: Int?
        let counter: Int64?
        switch type {
        case .totp:
            let value = try number(query["period"], default: 30, invalid: .periodOutOfRange)
            guard (1...300).contains(value) else { throw OtpauthError.periodOutOfRange }
            period = value
            counter = nil
        case .hotp:
            period = nil
            counter = try parseCounter(query["counter"])
        }

        return .uri(OtpFields(
            type: type,
            issuer: issuer,
            accountName: account.value,
            secret: secret,
            algorithm: algorithm,
            digits: digits,
            period: period,
            counter: counter,
            warnings: warnings
        ))
    }

    /// Projects a validated vault item into a portable single-account URI.
    public static func encode(_ item: VaultItem) -> String {
        func encodeComponent(_ input: String) -> String {
            // UTF-8 Strings and an ASCII-only allowed set form a valid URL component.
            input.addingPercentEncoding(withAllowedCharacters: escaped) ?? ""
        }

        var label = encodeComponent(item.accountName)
        var query = [
            "secret=" + Base32.encode(item.secret, padding: false),
            "algorithm=" + item.algorithm.rawValue,
            "digits=" + String(item.digits)
        ]
        if let issuer = item.issuer, !issuer.isEmpty {
            let encodedIssuer = encodeComponent(issuer)
            label = encodedIssuer + ":" + label
            query.append("issuer=" + encodedIssuer)
        }
        switch item.type {
        case .totp:
            query.append("period=" + String(item.period))
        case .hotp:
            if let counter = item.counter { query.append("counter=" + String(counter)) }
        }
        return "otpauth://\(item.type.rawValue)/\(label)?\(query.joined(separator: "&"))"
    }

    private static func parseBareSecret(_ input: String) throws -> OtpauthResult {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw OtpauthError.uriMalformed }
        return .bareSecret(OtpFields(
            type: .totp,
            issuer: nil,
            accountName: nil,
            secret: try decodeSecret(text, invalid: .uriMalformed),
            algorithm: .sha1,
            digits: 6,
            period: 30,
            counter: nil,
            warnings: []
        ))
    }

    private static func decodeSecret(_ encoded: String, invalid: OtpauthError) throws -> Data {
        let secret: Data
        do {
            secret = try Base32.decode(encoded)
        } catch {
            throw invalid
        }
        guard !secret.isEmpty else { throw OtpauthError.secretEmpty }
        guard secret.count <= 255 else { throw OtpauthError.secretTooLong }
        return secret
    }

    private static func parseCounter(_ raw: String?) throws -> Int64 {
        guard let raw else { return 0 }
        guard !raw.isEmpty else { throw OtpauthError.counterInvalid }
        guard !raw.hasPrefix("-") else { throw OtpauthError.counterNegative }
        guard let digits = asciiDigits(raw[...]) else { throw OtpauthError.counterInvalid }
        guard let counter = Int64(digits) else { throw OtpauthError.counterOverflow }
        return counter
    }

    private static func number(_ raw: String?, default defaultValue: Int, invalid: OtpauthError) throws -> Int {
        guard let raw else { return defaultValue }
        let sign = raw.first == "-" || raw.first == "+"
        guard let digits = asciiDigits(sign ? raw.dropFirst() : raw[...]),
              let value = Int(sign ? String(raw.prefix(1)) + digits : digits) else {
            throw invalid
        }
        return value
    }

    private static func asciiDigits(_ text: Substring) -> String? {
        guard !text.isEmpty else { return nil }
        var bytes: [UInt8] = []
        for scalar in text.unicodeScalars {
            guard scalar.value <= 0xFFFF, scalar.properties.numericType == .decimal,
                  let digit = scalar.properties.numericValue else { return nil }
            bytes.append(48 + UInt8(digit))
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    private static func parseQuery(_ raw: String?) -> [String: String] {
        guard let raw else { return [:] }
        var result = [String: String]()
        for part in raw.split(separator: "&") {
            let pair = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = percentDecode(pair[0], plusAsSpace: true)
            if !key.isEmpty, result[key] == nil {
                result[key] = pair.count > 1 ? percentDecode(pair[1], plusAsSpace: true) : ""
            }
        }
        return result
    }

    private static func validateRawURI(_ uri: String) throws {
        // URLComponents can repair malformed escapes or unescaped whitespace on recent iOS versions.
        let forbidden = CharacterSet.whitespacesAndNewlines
            .union(CharacterSet(charactersIn: "\"<>\\^`{|}"))
        guard uri.unicodeScalars.allSatisfy({
            !forbidden.contains($0) && !(0x00...0x1F).contains($0.value) && !(0x7F...0x9F).contains($0.value)
        }) else {
            throw OtpauthError.uriMalformed
        }
        var bytes = uri.utf8.makeIterator()
        while let next = bytes.next() {
            if next == 37 {
                guard let high = bytes.next(), let low = bytes.next(),
                      high.isASCIIHexDigit, low.isASCIIHexDigit else {
                    throw OtpauthError.uriMalformed
                }
            }
        }
    }

    private static func percentDecode(_ raw: Substring, plusAsSpace: Bool = false) -> String {
        var result: [UInt8] = []
        var bytes = raw.utf8.makeIterator()
        while let byte = bytes.next() {
            if byte == 37, let high = bytes.next(), let low = bytes.next() {
                result.append((hexNibble(high) << 4) | hexNibble(low))
            } else {
                result.append(plusAsSpace && byte == 43 ? 32 : byte)
            }
        }
        return String(decoding: result, as: UTF8.self)
    }

    private static func hexNibble(_ value: UInt8) -> UInt8 {
        switch value {
        case 48...57:
            value - 48
        case 65...70:
            value - 55
        default:
            value - 87
        }
    }
}
