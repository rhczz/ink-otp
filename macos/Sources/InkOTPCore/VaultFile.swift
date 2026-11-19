import CryptoKit
import Foundation

public struct VaultPasswordKey: Sendable {
    fileprivate let key: Data
    fileprivate let salt: Data
    fileprivate let memory: UInt32
    fileprivate let iterations: UInt32
    fileprivate let parallelism: UInt32

    public static func new(password: String) throws -> Self {
        let salt = VaultFile.random(count: 16)
        return try derive(password: password, salt: salt, memory: 65_536, iterations: 3, parallelism: 2)
    }

    fileprivate static func derive(password: String, salt: Data, memory: UInt32, iterations: UInt32,
                                   parallelism: UInt32) throws -> Self {
        var bytes = Data(password.precomposedStringWithCanonicalMapping.utf8)
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        let key = try KDF.argon2id(password: bytes, salt: salt, memoryKiB: memory,
                                  iterations: iterations, parallelism: parallelism)
        return Self(key: key, salt: salt, memory: memory, iterations: iterations, parallelism: parallelism)
    }
}

public struct VaultSession: Sendable {
    public let dataKey: Data
    public let passwordKey: VaultPasswordKey?

    public init(dataKey: Data, passwordKey: VaultPasswordKey?) {
        self.dataKey = dataKey
        self.passwordKey = passwordKey
    }

    public var deviceCredential: Data {
        dataKey + (passwordKey?.key ?? Data())
    }

    public static func new(password: String?) throws -> Self {
        let passwordKey = try password.map(VaultPasswordKey.new)
        return Self(dataKey: VaultFile.random(count: 32), passwordKey: passwordKey)
    }
}

public enum VaultFile {
    public static let maximumFileSize = 12 * 1024 * 1024
    private static let maximumCiphertextSize = 8 * 1024 * 1024
    private static let magic = Data("2FAV".utf8)

    private struct Header {
        let memory: UInt32
        let iterations: UInt32
        let parallelism: UInt32
        let salt: Data
        let hasPassword: Bool
        let wrapAAD: Data
        let wrapBox: AES.GCM.SealedBox?
        let vaultAAD: Data
        let vaultBox: AES.GCM.SealedBox
    }

    public static func seal(_ model: VaultModel, session: VaultSession) throws -> Data {
        var plain = try VaultJSON.encode(model)
        defer { plain.resetBytes(in: 0..<plain.count) }
        guard (1...maximumCiphertextSize).contains(plain.count) else { throw CoreError("VLT_E_CT_LEN") }
        guard session.dataKey.count == 32 else { throw CoreError("VLT_E_DEVICE_WRAP_AUTH") }

        let vaultNonce = AES.GCM.Nonce()
        var header = magic + Data([1, 0, 0])
        if let passwordKey = session.passwordKey {
            let wrapNonce = AES.GCM.Nonce()
            header = magic + Data([1, 1, 1])
            append(passwordKey.memory, to: &header)
            append(passwordKey.iterations, to: &header)
            append(passwordKey.parallelism, to: &header)
            header += passwordKey.salt + Data(wrapNonce) + Data(vaultNonce)
            append(32, to: &header)
            let wrapped = try AES.GCM.seal(session.dataKey, using: SymmetricKey(data: passwordKey.key),
                                           nonce: wrapNonce, authenticating: header)
            header += wrapped.ciphertext + wrapped.tag
        } else {
            header += Data(repeating: 0, count: 40) + Data(vaultNonce)
            append(0, to: &header)
        }
        append(UInt32(plain.count), to: &header)
        let sealed = try AES.GCM.seal(plain, using: SymmetricKey(data: session.dataKey),
                                      nonce: vaultNonce, authenticating: header)
        return header + sealed.ciphertext + sealed.tag
    }

    public static func hasPassword(_ bytes: Data) throws -> Bool {
        try parse(bytes).hasPassword
    }

    public static func open(_ bytes: Data, password: String) throws -> (VaultModel, VaultSession) {
        let header = try parse(bytes)
        guard let wrapBox = header.wrapBox else { throw CoreError("VLT_E_WRAP_AUTH") }
        let passwordKey = try VaultPasswordKey.derive(password: password, salt: header.salt,
                                                      memory: header.memory, iterations: header.iterations,
                                                      parallelism: header.parallelism)
        let dek = try unwrap(wrapBox, key: passwordKey.key, aad: header.wrapAAD)
        return try decode(header, session: VaultSession(dataKey: dek, passwordKey: passwordKey))
    }

    public static func open(_ bytes: Data, deviceCredential: Data) throws -> (VaultModel, VaultSession) {
        let header = try parse(bytes)
        guard deviceCredential.count == (header.hasPassword ? 64 : 32) else {
            throw CoreError("VLT_E_DEVICE_WRAP_AUTH")
        }
        let dek = Data(deviceCredential.prefix(32))
        var passwordKey: VaultPasswordKey?
        if let wrapBox = header.wrapBox {
            let key = Data(deviceCredential.suffix(32))
            let unwrapped = try unwrap(wrapBox, key: key, aad: header.wrapAAD)
            let difference = zip(dek, unwrapped).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) }
            guard difference == 0 else { throw CoreError("VLT_E_DEVICE_WRAP_AUTH") }
            passwordKey = VaultPasswordKey(key: key, salt: header.salt, memory: header.memory,
                                           iterations: header.iterations, parallelism: header.parallelism)
        }
        return try decode(header, session: VaultSession(dataKey: dek, passwordKey: passwordKey))
    }

    private static func decode(_ header: Header, session: VaultSession) throws -> (VaultModel, VaultSession) {
        var plain: Data
        do {
            plain = try AES.GCM.open(header.vaultBox, using: SymmetricKey(data: session.dataKey),
                                     authenticating: header.vaultAAD)
        } catch {
            throw CoreError("VLT_E_VAULT_AUTH")
        }
        defer { plain.resetBytes(in: 0..<plain.count) }
        return try (VaultJSON.decode(plain), session)
    }

    private static func unwrap(_ box: AES.GCM.SealedBox, key: Data, aad: Data) throws -> Data {
        do {
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
        } catch {
            throw CoreError("VLT_E_WRAP_AUTH")
        }
    }

    private static func parse(_ bytes: Data) throws -> Header {
        guard bytes.count <= maximumFileSize else { throw CoreError("VLT_E_FILE_TOO_LARGE") }
        guard bytes.count >= 84 else { throw CoreError("VLT_E_FILE_TRUNCATED") }
        // Normalize the index origin for callers passing a Data slice.
        let file = Data(bytes)
        guard file.prefix(4) == magic else { throw CoreError("VLT_E_MAGIC") }
        guard file[4] == 1 else { throw CoreError("VLT_E_VERSION") }
        guard file[5] <= 1 else { throw CoreError("VLT_E_FLAGS") }
        let hasPassword = file[5] == 1
        guard file[6] == (hasPassword ? 1 : 0) else { throw CoreError("VLT_E_KDF_ALGO") }

        let memory = readUInt32(file, at: 7)
        let iterations = readUInt32(file, at: 11)
        let parallelism = readUInt32(file, at: 15)
        let wrappedSize = readUInt32(file, at: 59)
        guard wrappedSize == (hasPassword ? 32 : 0) else { throw CoreError("VLT_E_WRAPPED_LEN") }
        if hasPassword {
            guard (8192...524_288).contains(memory), (1...10).contains(iterations),
                  (1...8).contains(parallelism), memory >= 8 * parallelism else {
                throw CoreError("VLT_E_KDF_PARAMS")
            }
        } else {
            guard file[7..<47].allSatisfy({ $0 == 0 }) else { throw CoreError("VLT_E_KDF_PARAMS") }
        }

        let headerSize = hasPassword ? 115 : 67
        guard file.count >= headerSize + 17 else { throw CoreError("VLT_E_FILE_TRUNCATED") }
        let size = Int(readUInt32(file, at: headerSize - 4))
        guard (1...maximumCiphertextSize).contains(size) else { throw CoreError("VLT_E_CT_LEN") }
        guard file.count >= headerSize + size + 16 else { throw CoreError("VLT_E_FILE_TRUNCATED") }
        guard file.count == headerSize + size + 16 else { throw CoreError("VLT_E_TRAILING_DATA") }

        let wrapBox = hasPassword
            ? try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: file[35..<47]), ciphertext: file[63..<95], tag: file[95..<111])
            : nil
        let vaultBox = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: file[47..<59]),
                                             ciphertext: file[headerSize..<headerSize + size], tag: file.suffix(16))
        return Header(memory: memory, iterations: iterations, parallelism: parallelism,
                      salt: Data(file[19..<35]), hasPassword: hasPassword, wrapAAD: Data(file.prefix(63)),
                      wrapBox: wrapBox, vaultAAD: Data(file.prefix(headerSize)), vaultBox: vaultBox)
    }

    private static func readUInt32(_ data: Data, at offset: Int) -> UInt32 {
        data[offset..<offset + 4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    private static func append(_ value: UInt32, to data: inout Data) {
        var word = value.bigEndian
        withUnsafeBytes(of: &word) { data.append(contentsOf: $0) }
    }

    fileprivate static func random(count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }
}
