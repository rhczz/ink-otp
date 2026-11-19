import CInkKDF
import Foundation
import Security

public enum InkKDF {
    public static func deriveRecoveryKey(password: String, salt: Data) throws -> Data {
        try argon2id(password: password, salt: salt, memoryKiB: 65_536, iterations: 3, lanes: 2)
    }

    static func argon2id(password: String, salt: Data, memoryKiB: UInt32, iterations: UInt32, lanes: UInt32) throws -> Data {
        guard salt.count == 16, password.utf16.count <= 1024 else { throw BackupFailure.kdfParameters }
        return try derive(password: password.precomposedStringWithCanonicalMapping, salt: salt) { pw, pwLen, salt, saltLen, output, length in
            ink_argon2id(memoryKiB, iterations, lanes, pw, pwLen, salt, saltLen, output, length)
        }
    }

    static func scrypt(password: String, salt: Data, n: UInt64, r: UInt32, p: UInt32) throws -> Data {
        try derive(password: password, salt: salt) { pw, pwLen, salt, saltLen, output, length in
            ink_scrypt(pw, pwLen, salt, saltLen, n, r, p, output, length)
        }
    }

    static func pbkdf2(password: String, salt: Data) throws -> Data {
        try derive(password: password, salt: salt) { pw, pwLen, salt, saltLen, output, length in
            ink_pbkdf2_sha256(pw, pwLen, salt, saltLen, 10_000, output, length)
        }
    }

    static func randomBytes(count: Int) throws -> Data {
        var bytes = Data(count: count)
        let status = bytes.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
            guard let address = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, buffer.count, address)
        }
        guard status == errSecSuccess else { throw CryptoFailure.random }
        return bytes
    }

    private static func derive(
        password: String, salt: Data,
        using algorithm: (UnsafePointer<UInt8>?, Int, UnsafePointer<UInt8>?, Int, UnsafeMutablePointer<UInt8>?, Int) -> Int32
    ) throws -> Data {
        var passwordBytes = Data(password.utf8)
        defer { passwordBytes.resetBytes(in: passwordBytes.indices) }
        var key = Data(count: 32)
        let status = passwordBytes.withUnsafeBytes { (pw: UnsafeRawBufferPointer) in
            salt.withUnsafeBytes { (salt: UnsafeRawBufferPointer) in
                key.withUnsafeMutableBytes { (out: UnsafeMutableRawBufferPointer) in
                    algorithm(
                        pw.bindMemory(to: UInt8.self).baseAddress, pw.count,
                        salt.bindMemory(to: UInt8.self).baseAddress, salt.count,
                        out.bindMemory(to: UInt8.self).baseAddress, out.count
                    )
                }
            }
        }
        guard status == 0 else {
            key.resetBytes(in: key.indices)
            throw CryptoFailure.derivation
        }
        return key
    }

    private enum CryptoFailure: Error {
        case random, derivation
    }
}
