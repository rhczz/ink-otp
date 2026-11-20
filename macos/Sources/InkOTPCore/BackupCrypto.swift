import CInkOTPCrypto
import CryptoKit
import Foundation
import Security

public enum KDF {
    public enum Failure: Error {
        case invalidParameters
        case derivationFailed
    }

    public static func argon2id(
        password: Data,
        salt: Data,
        memoryKiB: UInt32 = 65536,
        iterations: UInt32 = 3,
        parallelism: UInt32 = 2
    ) throws -> Data {
        guard salt.count == 16, validArgon2(memoryKiB, iterations, parallelism) else {
            throw Failure.invalidParameters
        }
        return try derive(password, salt) { password, passwordCount, salt, saltCount, key in
            inkotp_argon2id(password, passwordCount, salt, saltCount, memoryKiB, iterations, parallelism, key)
        }
    }

    static func validArgon2(_ memory: UInt32, _ iterations: UInt32, _ parallelism: UInt32) -> Bool {
        (8192...524288).contains(memory) && (1...10).contains(iterations)
            && (1...8).contains(parallelism) && memory >= 8 * parallelism
    }

    static func validScrypt(_ n: UInt64, _ r: UInt64, _ p: UInt64) -> Bool {
        n >= 2 && n <= 524288 && (n & (n - 1)) == 0
            && (1...32).contains(r) && (1...4).contains(p)
            && n * r <= 524288 && n * r * p <= 524288
    }

    static func scrypt(password: Data, salt: Data, n: UInt64, r: UInt64, p: UInt64) throws -> Data {
        guard salt.count == 32, validScrypt(n, r, p) else { throw Failure.invalidParameters }
        return try derive(password, salt) { password, passwordCount, salt, saltCount, key in
            inkotp_scrypt(password, passwordCount, salt, saltCount, n, r, p, key)
        }
    }

    static func twoFas(password: Data, salt: Data) throws -> Data {
        guard (16...512).contains(salt.count) else { throw Failure.invalidParameters }
        return try derive(password, salt, using: inkotp_pbkdf2_sha256)
    }

    private static func derive(
        _ password: Data,
        _ salt: Data,
        using operation: (UnsafePointer<UInt8>?, Int, UnsafePointer<UInt8>?, Int, UnsafeMutablePointer<UInt8>?) -> Int32
    ) throws -> Data {
        var key = Data(count: 32)
        let status = key.withUnsafeMutableBytes { keyBuffer in
            password.withUnsafeBytes { passwordBuffer in
                salt.withUnsafeBytes { saltBuffer in
                    operation(
                        passwordBuffer.bindMemory(to: UInt8.self).baseAddress, passwordBuffer.count,
                        saltBuffer.bindMemory(to: UInt8.self).baseAddress, saltBuffer.count,
                        keyBuffer.bindMemory(to: UInt8.self).baseAddress
                    )
                }
            }
        }
        guard status == 1 else {
            key.eraseInterchangeBytes()
            throw Failure.derivationFailed
        }
        return key
    }
}

enum BackupCrypto {
    enum Failure: Error {
        case randomnessUnavailable
        case invalidCiphertext
    }

    static let tagLength = 16
    static let nonceLength = 12

    static func random(_ count: Int) throws -> Data {
        precondition(count > 0)
        var output = Data(count: count)
        let status = output.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
        }
        guard status == errSecSuccess else { throw Failure.randomnessUnavailable }
        return output
    }

    static func seal(_ plaintext: Data, key: Data, nonce: Data) throws -> Data {
        let box = try AES.GCM.seal(
            plaintext, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce)
        )
        return box.ciphertext + box.tag
    }

    static func open(_ ciphertextAndTag: Data, key: Data, nonce: Data) throws -> Data {
        guard ciphertextAndTag.count >= tagLength else { throw Failure.invalidCiphertext }
        let box = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.dropLast(tagLength),
            tag: ciphertextAndTag.suffix(tagLength)
        )
        return try AES.GCM.open(box, using: SymmetricKey(data: key))
    }
}

extension Data {
    mutating func eraseInterchangeBytes() {
        resetBytes(in: startIndex..<endIndex)
    }
}
