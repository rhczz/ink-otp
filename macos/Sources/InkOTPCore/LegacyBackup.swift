import Foundation

/// InkOTP's portable .2fab v1, specified in spec/backup-format.md.
public enum LegacyBackup {
    public enum Failure: String, Error {
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

    static let magic = Data("2FAB".utf8)
    private static let headerLength = 50
    private static let maximumCiphertext = 8 * 1024 * 1024

    public static func decode(_ bytes: Data, password: String) throws -> VaultModel {
        guard bytes.count <= Interchange.maximumFileBytes else { throw Failure.fileTooLarge }
        guard bytes.count >= headerLength else { throw Failure.truncated }
        let header = Data(bytes.prefix(headerLength))
        let start = header.startIndex
        guard header.starts(with: magic) else { throw Failure.magic }
        guard header[start + 4] == 1 else { throw Failure.version }
        guard header[start + 5] == 1 else { throw Failure.kdfAlgorithm }
        let memory = integer(header, at: 6)
        let iterations = integer(header, at: 10)
        let parallelism = integer(header, at: 14)
        guard KDF.validArgon2(memory, iterations, parallelism) else { throw Failure.kdfParameters }
        let salt = Data(header[(start + 18)..<(start + 34)])
        let nonce = Data(header[(start + 34)..<(start + 46)])
        let length = Int(integer(header, at: 46))
        guard (1...maximumCiphertext).contains(length) else { throw Failure.ciphertextLength }
        let expectedLength = headerLength + length + BackupCrypto.tagLength
        guard bytes.count >= expectedLength else { throw Failure.truncated }
        guard bytes.count == expectedLength else { throw Failure.trailingData }

        var passwordBytes = Data(password.precomposedStringWithCanonicalMapping.utf8)
        defer { passwordBytes.eraseInterchangeBytes() }
        var key = try KDF.argon2id(
            password: passwordBytes, salt: salt, memoryKiB: memory, iterations: iterations, parallelism: parallelism
        )
        defer { key.eraseInterchangeBytes() }
        var plaintext: Data
        do {
            plaintext = try BackupCrypto.open(Data(bytes.dropFirst(headerLength)), key: key, nonce: nonce, aad: header)
        } catch {
            throw Failure.authentication
        }
        defer { plaintext.eraseInterchangeBytes() }
        do {
            // Frozen backups have their own 8 MiB limit and can store full local metadata.
            _ = try InterchangeJSON.parse(plaintext, allowBOM: false, interchangeBudget: false)
        } catch {
            throw Failure.json
        }
        do {
            return try VaultJSON.decode(plaintext)
        } catch {
            throw Failure.schema
        }
    }

    public static func encode(_ model: VaultModel, password: String) throws -> Data {
        var plaintext = try VaultJSON.encode(model)
        defer { plaintext.eraseInterchangeBytes() }
        guard (1...maximumCiphertext).contains(plaintext.count) else { throw Failure.ciphertextLength }
        let salt = try BackupCrypto.random(16)
        let nonce = try BackupCrypto.random(BackupCrypto.nonceLength)
        var header = magic
        header.append(contentsOf: [1, 1])
        append(65536, to: &header)
        append(3, to: &header)
        append(2, to: &header)
        header.append(salt)
        header.append(nonce)
        append(UInt32(plaintext.count), to: &header)

        var passwordBytes = Data(password.precomposedStringWithCanonicalMapping.utf8)
        defer { passwordBytes.eraseInterchangeBytes() }
        var key = try KDF.argon2id(password: passwordBytes, salt: salt)
        defer { key.eraseInterchangeBytes() }
        return header + (try BackupCrypto.seal(plaintext, key: key, nonce: nonce, aad: header))
    }

    private static func integer(_ header: Data, at offset: Int) -> UInt32 {
        let start = header.startIndex + offset
        return header[start..<(start + 4)].reduce(0) { ($0 << 8) | UInt32($1) }
    }

    private static func append(_ value: UInt32, to data: inout Data) {
        data.append(contentsOf: withUnsafeBytes(of: value.bigEndian) { Array($0) })
    }
}
