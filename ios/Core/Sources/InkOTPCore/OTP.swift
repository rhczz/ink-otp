import CryptoKit
import Foundation

public enum OtpType: String, Codable, CaseIterable, Sendable {
    case totp
    case hotp
}

public enum OtpAlgorithm: String, Codable, CaseIterable, Sendable {
    case sha1 = "SHA1"
    case sha256 = "SHA256"
    case sha512 = "SHA512"

    public static func specName(_ value: String?) throws -> Self {
        guard let value, !value.isEmpty else { return .sha1 }
        guard let algorithm = Self(rawValue: value.uppercased()) else {
            throw OtpError.algorithmUnsupported
        }
        return algorithm
    }
}

public enum OtpError: String, Error, Sendable {
    case digitsUnsupported = "DIGITS_UNSUPPORTED"
    case periodOutOfRange = "PERIOD_OUT_OF_RANGE"
    case algorithmUnsupported = "ALGORITHM_UNSUPPORTED"
    case secretEmpty = "SECRET_EMPTY"
    case secretTooLong = "SECRET_TOO_LONG"
    case counterNegative = "COUNTER_NEGATIVE"
    case counterOverflow = "COUNTER_OVERFLOW"
    case timeOutOfRange = "TIME_OUT_OF_RANGE"
    case kindUnsupported = "KIND_UNSUPPORTED"
}

public struct OtpCode: Equatable, Sendable {
    public let value: String
    public let remainingSeconds: Int
    public let counter: Int64

    public init(value: String, remainingSeconds: Int, counter: Int64) {
        self.value = value
        self.remainingSeconds = remainingSeconds
        self.counter = counter
    }
}

public enum OtpEngine {
    public static func hotp(
        secret: Data,
        counter: Int64,
        algorithm: OtpAlgorithm = .sha1,
        digits: Int = 6
    ) throws -> String {
        try validateSecret(secret)
        try validateDigits(digits)
        guard counter >= 0 else { throw OtpError.counterNegative }

        var encodedCounter = counter.bigEndian
        let message = withUnsafeBytes(of: &encodedCounter) { Data($0) }
        let key = SymmetricKey(data: secret)
        let digest: [UInt8]
        switch algorithm {
        case .sha1:
            digest = Array(HMAC<Insecure.SHA1>.authenticationCode(for: message, using: key))
        case .sha256:
            digest = Array(HMAC<SHA256>.authenticationCode(for: message, using: key))
        case .sha512:
            digest = Array(HMAC<SHA512>.authenticationCode(for: message, using: key))
        }

        let offset = Int(digest[digest.count - 1] & 0x0F)
        let truncated = UInt32(digest[offset] & 0x7F) << 24
            | UInt32(digest[offset + 1]) << 16
            | UInt32(digest[offset + 2]) << 8
            | UInt32(digest[offset + 3])
        let modulus: UInt32 = digits == 6 ? 1_000_000 : 100_000_000
        let code = String(truncated % modulus)
        return String(repeating: "0", count: digits - code.count) + code
    }

    public static func totp(
        secret: Data,
        epochSeconds: Int64,
        algorithm: OtpAlgorithm = .sha1,
        digits: Int = 6,
        period: Int = 30
    ) throws -> OtpCode {
        try validateSecret(secret)
        try validateDigits(digits)
        try validatePeriod(period)
        guard epochSeconds >= 0 else { throw OtpError.timeOutOfRange }

        let counter = epochSeconds / Int64(period)
        let value = try hotp(secret: secret, counter: counter, algorithm: algorithm, digits: digits)
        return OtpCode(
            value: value,
            remainingSeconds: period - Int(epochSeconds % Int64(period)),
            counter: counter
        )
    }

    public static func validateSecret(_ secret: Data) throws {
        guard !secret.isEmpty else { throw OtpError.secretEmpty }
        guard secret.count <= 255 else { throw OtpError.secretTooLong }
    }

    public static func validateDigits(_ digits: Int) throws {
        guard digits == 6 || digits == 8 else { throw OtpError.digitsUnsupported }
    }

    public static func validatePeriod(_ period: Int) throws {
        guard (1...300).contains(period) else { throw OtpError.periodOutOfRange }
    }
}

public enum Base32Error: String, Error, Sendable {
    case invalidCharacter = "BASE32_INVALID_CHAR"
    case invalidLength = "BASE32_INVALID_LENGTH"
    case invalidPadding = "BASE32_INVALID_PADDING"
    case invalidTrailingBits = "BASE32_INVALID_TRAILING_BITS"
}

public enum Base32 {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".utf8)
    private static let canonicalPadding = [0: 0, 2: 6, 4: 4, 5: 3, 7: 1]

    public static func encode(_ bytes: Data, padding: Bool = true) -> String {
        var output: [UInt8] = []
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
        if bits > 0 {
            output.append(alphabet[Int((buffer << (5 - bits)) & 31)])
        }
        if padding {
            let paddingLength = (8 - output.count % 8) % 8
            output.append(contentsOf: repeatElement(61, count: paddingLength))
        }
        return String(decoding: output, as: UTF8.self)
    }

    public static func decode(_ text: String) throws -> Data {
        var buffer: UInt32 = 0
        var bits = 0
        var characterCount = 0
        var paddingCount = 0
        var result = Data()

        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 9...13, 32, 45:
                continue
            case 61:
                paddingCount += 1
                continue
            default:
                break
            }
            guard paddingCount == 0 else { throw Base32Error.invalidPadding }

            let value: UInt32
            switch scalar.value {
            case 65...90:
                value = scalar.value - 65
            case 97...122:
                value = scalar.value - 97
            case 50...55:
                value = scalar.value - 24
            case 0x0131:
                value = 8 // Kotlin uppercaseChar maps dotless i to I.
            case 0x017F:
                value = 18 // Kotlin uppercaseChar maps long s to S.
            default:
                throw Base32Error.invalidCharacter
            }

            characterCount += 1
            buffer = (buffer << 5) | value
            bits += 5
            if bits >= 8 {
                bits -= 8
                result.append(UInt8(truncatingIfNeeded: buffer >> bits))
            }
        }

        let expectedPadding = canonicalPadding[characterCount % 8]
        if paddingCount == 0 {
            guard expectedPadding != nil else { throw Base32Error.invalidLength }
        } else {
            guard paddingCount == expectedPadding else { throw Base32Error.invalidPadding }
        }
        let trailingBitsMask = (UInt32(1) << bits) - 1
        guard buffer & trailingBitsMask == 0 else { throw Base32Error.invalidTrailingBits }
        return result
    }
}
