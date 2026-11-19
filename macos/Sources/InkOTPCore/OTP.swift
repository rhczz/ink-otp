import CryptoKit
import Foundation

public struct CoreError: Error, LocalizedError, Equatable, Sendable {
    public let code: String
    public init(_ code: String) { self.code = code }
    public var errorDescription: String? { code }
}

public enum OTPType: String, CaseIterable, Sendable {
    case totp = "TOTP"
    case hotp = "HOTP"
}

public enum OTPAlgorithm: String, CaseIterable, Sendable {
    case sha1 = "SHA1"
    case sha256 = "SHA256"
    case sha512 = "SHA512"
}

public enum OTP {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".utf8)
    private static let paddingLengths = [0: 0, 2: 6, 4: 4, 5: 3, 7: 1]

    public static func base32Decode(_ value: String) throws -> Data {
        var output = Data()
        var dataCount = 0
        var paddingCount = 0
        var buffer: UInt32 = 0
        var bits = 0

        for byte in value.utf8 {
            switch byte {
            case 9...13, 32, 45: continue
            case 61:
                paddingCount += 1
                continue
            default: break
            }
            guard paddingCount == 0 else { throw CoreError("BASE32_INVALID_PADDING") }
            let digit: UInt8
            switch byte {
            case 65...90: digit = byte - 65
            case 97...122: digit = byte - 97
            case 50...55: digit = byte - 24
            default: throw CoreError("BASE32_INVALID_CHAR")
            }
            dataCount += 1
            buffer = (buffer << 5) | UInt32(digit)
            bits += 5
            if bits >= 8 {
                bits -= 8
                output.append(UInt8(truncatingIfNeeded: buffer >> bits))
            }
        }

        let expectedPadding = paddingLengths[dataCount % 8]
        if paddingCount > 0 && expectedPadding != paddingCount { throw CoreError("BASE32_INVALID_PADDING") }
        guard expectedPadding != nil else { throw CoreError("BASE32_INVALID_LENGTH") }
        guard buffer & ((1 << bits) - 1) == 0 else { throw CoreError("BASE32_INVALID_TRAILING_BITS") }
        return output
    }

    public static func base32Encode(_ bytes: Data, padding: Bool = true) -> String {
        var output = [UInt8]()
        var buffer: UInt32 = 0
        var bits = 0
        for byte in bytes {
            buffer = (buffer << 8) | UInt32(byte)
            bits += 8
            while bits >= 5 {
                bits -= 5
                output.append(alphabet[Int((buffer >> bits) & 31)])
            }
        }
        if bits > 0 { output.append(alphabet[Int((buffer << (5 - bits)) & 31)]) }
        if padding {
            while output.count % 8 != 0 { output.append(61) }
        }
        return String(decoding: output, as: UTF8.self)
    }

    public static func totp(secret: Data, algorithm: OTPAlgorithm, digits: Int, period: Int, time: Int64) throws -> String {
        guard (1...300).contains(period) else { throw CoreError("PERIOD_OUT_OF_RANGE") }
        guard time >= 0 else { throw CoreError("TIME_OUT_OF_RANGE") }
        return try hotp(secret: secret, algorithm: algorithm, digits: digits, counter: UInt64(time / Int64(period)))
    }

    public static func hotp(secret: Data, algorithm: OTPAlgorithm, digits: Int, counter: UInt64) throws -> String {
        guard !secret.isEmpty else { throw CoreError("SECRET_EMPTY") }
        guard secret.count <= 255 else { throw CoreError("SECRET_TOO_LONG") }
        guard digits == 6 || digits == 8 else { throw CoreError("DIGITS_UNSUPPORTED") }
        let key = SymmetricKey(data: secret)
        var bigEndian = counter.bigEndian
        let challenge = withUnsafeBytes(of: &bigEndian) { Data($0) }
        let mac: [UInt8]
        switch algorithm {
        case .sha1: mac = Array(HMAC<Insecure.SHA1>.authenticationCode(for: challenge, using: key))
        case .sha256: mac = Array(HMAC<SHA256>.authenticationCode(for: challenge, using: key))
        case .sha512: mac = Array(HMAC<SHA512>.authenticationCode(for: challenge, using: key))
        }
        let offset = Int(mac[mac.count - 1] & 15)
        let value = mac[offset..<offset + 4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } & 0x7fff_ffff
        let code = String(value % (digits == 6 ? 1_000_000 : 100_000_000))
        return String(repeating: "0", count: digits - code.count) + code
    }
}

func normalizedText(_ value: String) -> (value: String, strippedControls: Bool) {
    let nfc = value.precomposedStringWithCanonicalMapping
    let filtered = nfc.unicodeScalars.filter { !(0...31).contains($0.value) && !(127...159).contains($0.value) }
    return (String(String.UnicodeScalarView(filtered)), nfc.unicodeScalars.count != filtered.count)
}
