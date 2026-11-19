import CryptoKit
import Foundation

public enum VaultWireError: String, Error, Sendable {
    case fileTooLarge = "VLT_E_FILE_TOO_LARGE"
    case truncated = "VLT_E_FILE_TRUNCATED"
    case trailingData = "VLT_E_TRAILING_DATA"
    case magic = "VLT_E_MAGIC"
    case version = "VLT_E_VERSION"
    case flags = "VLT_E_FLAGS"
    case kdfAlgorithm = "VLT_E_KDF_ALGO"
    case kdfParameters = "VLT_E_KDF_PARAMS"
    case wrappedLength = "VLT_E_WRAPPED_LEN"
    case ciphertextLength = "VLT_E_CT_LEN"
    case wrapAuthentication = "VLT_E_WRAP_AUTH"
    case vaultAuthentication = "VLT_E_VAULT_AUTH"
    case json = "VLT_E_JSON"
    case schema = "VLT_E_SCHEMA"
}

public enum VaultCodec {
    public struct Parameters: Equatable, Sendable {
        public let memoryKiB: UInt32
        public let iterations: UInt32
        public let lanes: UInt32

        public static let v1 = Self(memoryKiB: 65_536, iterations: 3, lanes: 2)

        public init(memoryKiB: UInt32, iterations: UInt32, lanes: UInt32) {
            self.memoryKiB = memoryKiB
            self.iterations = iterations
            self.lanes = lanes
        }

        fileprivate var isValid: Bool {
            (8_192...524_288).contains(memoryKiB)
                && (1...10).contains(iterations)
                && (1...8).contains(lanes)
                && memoryKiB >= 8 * lanes
        }
    }

    public struct Recovery: Sendable {
        public let key: SymmetricKey
        public let salt: Data
        public let parameters: Parameters

        public init(key: SymmetricKey, salt: Data, parameters: Parameters = .v1) {
            self.key = key
            self.salt = salt
            self.parameters = parameters
        }
    }

    public struct Header: Equatable, Sendable {
        public let parameters: Parameters?
        public let salt: Data
        public let ciphertextOffset: Int
        public let ciphertextLength: Int
        fileprivate let wrappingNonce: Data
        fileprivate let vaultNonce: Data

        public var hasPassword: Bool { parameters != nil }
    }

    public struct Opened: Sendable {
        public let model: VaultModel
        public let key: SymmetricKey
        public let recovery: Recovery
    }

    public static let maxFileBytes = 12 * 1024 * 1024
    private static let magic = Data("2FAV".utf8)
    private static let prefixLength = 63
    private static let keyLength = 32
    private static let tagLength = 16
    private static let maxCiphertext = 8 * 1024 * 1024

    public static func makeRecovery(password: String, parameters: Parameters = .v1) throws -> Recovery {
        let salt = try InkKDF.randomBytes(count: 16)
        return try deriveRecovery(password: password, salt: salt, parameters: parameters)
    }

    public static func inspect(_ bytes: Data) throws -> Header {
        guard bytes.count <= maxFileBytes else { throw VaultWireError.fileTooLarge }
        guard bytes.count >= prefixLength else { throw VaultWireError.truncated }

        let prefix = Array(bytes.prefix(prefixLength))
        guard prefix.prefix(4).elementsEqual(magic) else { throw VaultWireError.magic }
        guard prefix[4] == 1 else { throw VaultWireError.version }
        let flags = prefix[5]
        guard flags == 0 || flags == 1 else { throw VaultWireError.flags }

        let kdf = Parameters(
            memoryKiB: readUInt32(prefix[7..<11]),
            iterations: readUInt32(prefix[11..<15]),
            lanes: readUInt32(prefix[15..<19])
        )
        let salt = Data(prefix[19..<35])
        let wrappingNonce = Data(prefix[35..<47])
        let vaultNonce = Data(prefix[47..<59])
        let wrappedLength = Int(readUInt32(prefix[59..<63]))
        let hasPassword = flags == 1
        let offset: Int
        if hasPassword {
            guard prefix[6] == 1 else { throw VaultWireError.kdfAlgorithm }
            guard wrappedLength == keyLength else { throw VaultWireError.wrappedLength }
            guard kdf.isValid else { throw VaultWireError.kdfParameters }
            guard salt.contains(where: { $0 != 0 }) else { throw VaultWireError.flags }
            offset = prefixLength + keyLength + tagLength + 4
        } else {
            guard prefix[6] == 0 else { throw VaultWireError.kdfAlgorithm }
            guard wrappedLength == 0 else { throw VaultWireError.wrappedLength }
            guard kdf.memoryKiB == 0, kdf.iterations == 0, kdf.lanes == 0 else {
                throw VaultWireError.kdfParameters
            }
            guard salt.allSatisfy({ $0 == 0 }), wrappingNonce.allSatisfy({ $0 == 0 }) else {
                throw VaultWireError.flags
            }
            offset = prefixLength + 4
        }

        guard bytes.count >= offset else { throw VaultWireError.truncated }
        let length = Int(readUInt32(bytes.prefix(offset).suffix(4)))
        guard (1...maxCiphertext).contains(length) else { throw VaultWireError.ciphertextLength }
        let total = offset + length + tagLength
        guard bytes.count >= total else { throw VaultWireError.truncated }
        guard bytes.count == total else { throw VaultWireError.trailingData }

        return Header(
            parameters: hasPassword ? kdf : nil, salt: salt, ciphertextOffset: offset,
            ciphertextLength: length, wrappingNonce: wrappingNonce, vaultNonce: vaultNonce
        )
    }

    public static func seal(_ model: VaultModel, key: SymmetricKey, recovery: Recovery? = nil) throws -> Data {
        guard key.bitCount == keyLength * 8 else { throw VaultWireError.vaultAuthentication }
        var plain = try VaultJson.encode(model)
        defer { plain.resetBytes(in: plain.indices) }
        guard (1...maxCiphertext).contains(plain.count) else { throw VaultWireError.ciphertextLength }

        let vaultNonce = try InkKDF.randomBytes(count: 12)
        var header = magic + Data([1])
        if let recovery {
            guard recovery.parameters.isValid, recovery.key.bitCount == keyLength * 8,
                  recovery.salt.count == 16, recovery.salt.contains(where: { $0 != 0 }) else {
                throw VaultWireError.kdfParameters
            }
            let wrappingNonce = try InkKDF.randomBytes(count: 12)
            header.append(contentsOf: [1, 1])
            appendUInt32(recovery.parameters.memoryKiB, to: &header)
            appendUInt32(recovery.parameters.iterations, to: &header)
            appendUInt32(recovery.parameters.lanes, to: &header)
            header.append(recovery.salt)
            header.append(wrappingNonce)
            header.append(vaultNonce)
            appendUInt32(UInt32(keyLength), to: &header)

            var rawKey = key.withUnsafeBytes { Data($0) }
            defer { rawKey.resetBytes(in: rawKey.indices) }
            let wrapped = try AES.GCM.seal(
                rawKey, using: recovery.key, nonce: AES.GCM.Nonce(data: wrappingNonce), authenticating: header
            )
            header.append(wrapped.ciphertext)
            header.append(wrapped.tag)
        } else {
            header.append(Data(count: 2 + 12 + 16 + 12))
            header.append(vaultNonce)
            appendUInt32(0, to: &header)
        }

        appendUInt32(UInt32(plain.count), to: &header)
        let sealed = try AES.GCM.seal(
            plain, using: key, nonce: AES.GCM.Nonce(data: vaultNonce), authenticating: header
        )
        return header + sealed.ciphertext + sealed.tag
    }

    public static func open(_ bytes: Data, key: SymmetricKey) throws -> VaultModel {
        try open(bytes, header: inspect(bytes), key: key)
    }

    public static func open(_ bytes: Data, password: String) throws -> Opened {
        let header = try inspect(bytes)
        guard let parameters = header.parameters else { throw VaultWireError.flags }
        let recovery = try deriveRecovery(password: password, salt: header.salt, parameters: parameters)
        let key = try unwrapKey(bytes, header: header, recovery: recovery)
        let model = try open(bytes, header: header, key: key)
        return Opened(model: model, key: key, recovery: recovery)
    }

    public static func unwrapKey(_ bytes: Data, recovery: Recovery) throws -> SymmetricKey {
        try unwrapKey(bytes, header: inspect(bytes), recovery: recovery)
    }

    private static func deriveRecovery(password: String, salt: Data, parameters: Parameters) throws -> Recovery {
        guard parameters.isValid else { throw VaultWireError.kdfParameters }
        var bytes = try InkKDF.argon2id(
            password: password, salt: salt, memoryKiB: parameters.memoryKiB,
            iterations: parameters.iterations, lanes: parameters.lanes
        )
        defer { bytes.resetBytes(in: bytes.indices) }
        return Recovery(key: SymmetricKey(data: bytes), salt: salt, parameters: parameters)
    }

    private static func open(_ bytes: Data, header: Header, key: SymmetricKey) throws -> VaultModel {
        guard key.bitCount == keyLength * 8 else { throw VaultWireError.vaultAuthentication }
        var plain: Data
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: header.vaultNonce),
                ciphertext: bytes.dropFirst(header.ciphertextOffset).prefix(header.ciphertextLength),
                tag: bytes.suffix(tagLength)
            )
            plain = try AES.GCM.open(box, using: key, authenticating: bytes.prefix(header.ciphertextOffset))
        } catch {
            throw VaultWireError.vaultAuthentication
        }
        defer { plain.resetBytes(in: plain.indices) }

        do {
            return try VaultJson.decode(plain)
        } catch let error as VaultJsonError {
            switch error {
            case .invalidUTF8, .invalidJSON:
                throw VaultWireError.json
            case .unsupportedSchema, .invalidField, .duplicateItemID:
                throw VaultWireError.schema
            }
        }
    }

    private static func unwrapKey(_ bytes: Data, header: Header, recovery: Recovery) throws -> SymmetricKey {
        guard header.hasPassword else { throw VaultWireError.flags }
        guard header.salt == recovery.salt, header.parameters == recovery.parameters,
              recovery.key.bitCount == keyLength * 8 else {
            throw VaultWireError.wrapAuthentication
        }
        do {
            let wrapped = bytes.dropFirst(prefixLength).prefix(keyLength + tagLength)
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: header.wrappingNonce),
                ciphertext: wrapped.prefix(keyLength),
                tag: wrapped.suffix(tagLength)
            )
            var rawKey = try AES.GCM.open(box, using: recovery.key, authenticating: bytes.prefix(prefixLength))
            defer { rawKey.resetBytes(in: rawKey.indices) }
            return SymmetricKey(data: rawKey)
        } catch {
            throw VaultWireError.wrapAuthentication
        }
    }

    private static func readUInt32(_ bytes: some Collection<UInt8>) -> UInt32 {
        bytes.reduce(0) { ($0 << 8) | UInt32($1) }
    }

    private static func appendUInt32(_ value: UInt32, to bytes: inout Data) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { bytes.append(contentsOf: $0) }
    }
}
