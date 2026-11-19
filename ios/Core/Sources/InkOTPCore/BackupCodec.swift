import CryptoKit
import Foundation

public enum BackupFailure: String, Error, Equatable {
    case fileTooLarge = "BAK_E_FILE_TOO_LARGE"
    case truncated = "BAK_E_FILE_TRUNCATED"
    case trailingData = "BAK_E_TRAILING_DATA"
    case magic = "BAK_E_MAGIC"
    case version = "BAK_E_VERSION"
    case kdfAlgorithm = "BAK_E_KDF_ALGO"
    case kdfParameters = "BAK_E_KDF_PARAMS"
    case ciphertextLength = "BAK_E_CT_LEN"
    case authentication = "BAK_E_AUTH"
    case json = "BAK_E_JSON"
    case schema = "BAK_E_SCHEMA"
}

public enum BackupCodec {
    public struct Header: Equatable, Sendable {
        public let memoryKiB: UInt32
        public let iterations: UInt32
        public let lanes: UInt32
        public let salt: Data
        public let nonce: Data
        public let ciphertextLength: Int
    }

    static let magic = Data("2FAB".utf8)
    private static let headerLength = 50
    private static let tagLength = 16
    private static let maxCiphertext = 8 * 1024 * 1024

    public static func inspect(_ input: Data) throws -> Header {
        guard input.count <= InterchangeCodec.maxFileBytes else { throw BackupFailure.fileTooLarge }
        guard input.count >= headerLength else { throw BackupFailure.truncated }
        let header = Array(input.prefix(headerLength))
        guard header.prefix(4).elementsEqual(magic) else { throw BackupFailure.magic }
        guard header[4] == 1 else { throw BackupFailure.version }
        guard header[5] == 1 else { throw BackupFailure.kdfAlgorithm }

        func readUInt32(at offset: Int) -> UInt32 {
            header[offset..<(offset + 4)].reduce(0) { ($0 << 8) | UInt32($1) }
        }

        let memory = readUInt32(at: 6)
        let iterations = readUInt32(at: 10)
        let lanes = readUInt32(at: 14)
        guard (8_192...524_288).contains(memory), (1...10).contains(iterations),
              (1...8).contains(lanes), memory >= 8 * lanes else { throw BackupFailure.kdfParameters }
        let length = Int(readUInt32(at: 46))
        guard (1...maxCiphertext).contains(length) else { throw BackupFailure.ciphertextLength }
        let total = headerLength + tagLength + length
        guard input.count >= total else { throw BackupFailure.truncated }
        guard input.count == total else { throw BackupFailure.trailingData }
        return Header(
            memoryKiB: memory, iterations: iterations, lanes: lanes,
            salt: Data(header[18..<34]), nonce: Data(header[34..<46]), ciphertextLength: length
        )
    }

    public static func seal(_ model: VaultModel, password: String) throws -> Data {
        var plaintext = try VaultJson.encode(model)
        defer { plaintext.resetBytes(in: plaintext.indices) }
        guard (1...maxCiphertext).contains(plaintext.count) else { throw BackupFailure.ciphertextLength }
        let salt = try InkKDF.randomBytes(count: 16)
        let nonce = try InkKDF.randomBytes(count: 12)

        var header = magic + Data([1, 1])
        appendUInt32(65_536, to: &header)
        appendUInt32(3, to: &header)
        appendUInt32(2, to: &header)
        header.append(salt)
        header.append(nonce)
        appendUInt32(UInt32(plaintext.count), to: &header)

        var key = try InkKDF.deriveRecoveryKey(password: password, salt: salt)
        defer { key.resetBytes(in: key.indices) }
        let box = try AES.GCM.seal(
            plaintext, using: SymmetricKey(data: key),
            nonce: AES.GCM.Nonce(data: nonce), authenticating: header
        )
        return header + box.ciphertext + box.tag
    }

    public static func open(_ bytes: Data, password: String) throws -> VaultModel {
        let header = try inspect(bytes)
        var key = try InkKDF.argon2id(
            password: password, salt: header.salt,
            memoryKiB: header.memoryKiB, iterations: header.iterations, lanes: header.lanes
        )
        defer { key.resetBytes(in: key.indices) }

        var plain: Data
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: header.nonce),
                ciphertext: bytes.dropFirst(headerLength).dropLast(tagLength),
                tag: bytes.suffix(tagLength)
            )
            plain = try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: bytes.prefix(headerLength))
        } catch {
            throw BackupFailure.authentication
        }
        defer { plain.resetBytes(in: plain.indices) }

        do {
            return try VaultJson.decode(plain)
        } catch let error as VaultJsonError {
            switch error {
            case .invalidUTF8, .invalidJSON:
                throw BackupFailure.json
            case .unsupportedSchema, .invalidField, .duplicateItemID:
                throw BackupFailure.schema
            }
        } catch {
            throw BackupFailure.json
        }
    }

    private static func appendUInt32(_ value: UInt32, to data: inout Data) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }
}
