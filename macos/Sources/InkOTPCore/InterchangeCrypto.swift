import Foundation

enum InterchangeCrypto {
    private static let twoFasReference = Data(
        "tRViSsLKzd86Hprh4ceC2OP7xazn4rrt4xhfEUbOjxLX8Rc3mkISXE0lWbmnWfggogbBJhtYgpK6fMl1D6mtsy92R3HkdGfwuXbzLebqVFJsR7IZ2w58t938iymwG4824igYy1wi6n2WDpO1Q1P69zwJGs2F5a1qP4MyIiDSD7NCV2OvidXQCBnDlGfmz0f1BQySRkkt4ryiJeCjD2o4QsveJ9uDBUn8ELyOrESv5R5DMDkD4iAF8TXU7KyoJujd".utf8
    )

    private struct AegisSlot {
        let n: UInt64
        let r: UInt64
        let p: UInt64
        let salt: Data
        let ciphertextAndTag: Data
        let nonce: Data

        init(_ slot: JSONObject) throws {
            guard let n = try UInt64(exactly: slot.requiredInteger("n")),
                  let r = try UInt64(exactly: slot.requiredInteger("r")),
                  let p = try UInt64(exactly: slot.requiredInteger("p")),
                  KDF.validScrypt(n, r, p) else { throw InterchangeError(.unsupportedKDF) }
            self.n = n
            self.r = r
            self.p = p
            salt = try hex(slot.requiredString("salt"), length: 32)
            let params = try slot.requiredObject("key_params")
            nonce = try hex(params.requiredString("nonce"), length: BackupCrypto.nonceLength)
            ciphertextAndTag = try hex(slot.requiredString("key"), length: 32)
                + hex(params.requiredString("tag"), length: BackupCrypto.tagLength)
        }
    }

    static func openAegis(_ root: JSONObject, password: String) throws -> Data {
        let header = try root.requiredObject("header")
        let slots = try header.requiredArray("slots")
        guard slots.count <= 32 else { throw InterchangeError(.unsupportedKDF) }
        let passwordSlots = try slots.compactMap { value -> JSONObject? in
            guard let slot = value as? JSONObject else { throw InterchangeError(.malformedFile) }
            return try slot.requiredInteger("type") == 1 ? slot : nil
        }
        guard !passwordSlots.isEmpty else { throw InterchangeError(.unsupportedEncryption) }
        guard passwordSlots.count <= 4 else { throw InterchangeError(.unsupportedKDF) }
        // Validate all slots and the payload before the first KDF.
        let checked = try passwordSlots.map(AegisSlot.init)
        let params = try header.requiredObject("params")
        let nonce = try hex(params.requiredString("nonce"), length: BackupCrypto.nonceLength)
        let ciphertext = try base64(root.requiredString("db"))
            + hex(params.requiredString("tag"), length: BackupCrypto.tagLength)
        var passwordBytes = try externalPassword(password)
        defer { passwordBytes.eraseInterchangeBytes() }
        for slot in checked {
            var wrappingKey = try KDF.scrypt(
                password: passwordBytes, salt: slot.salt, n: slot.n, r: slot.r, p: slot.p
            )
            defer { wrappingKey.eraseInterchangeBytes() }
            if var master = try? BackupCrypto.open(slot.ciphertextAndTag, key: wrappingKey, nonce: slot.nonce) {
                defer { master.eraseInterchangeBytes() }
                guard master.count == 32 else { throw InterchangeError(.malformedFile) }
                return try authenticatedOpen(ciphertext, key: master, nonce: nonce)
            }
        }
        throw InterchangeError(.authenticationFailed)
    }

    static func sealAegis(_ database: Data, password: String) throws -> JSONObject {
        let salt = try BackupCrypto.random(32)
        var passwordBytes = try externalPassword(password)
        defer { passwordBytes.eraseInterchangeBytes() }
        var master = try BackupCrypto.random(32)
        defer { master.eraseInterchangeBytes() }
        var wrappingKey = try KDF.scrypt(password: passwordBytes, salt: salt, n: 32768, r: 8, p: 1)
        defer { wrappingKey.eraseInterchangeBytes() }
        let wrappingNonce = try BackupCrypto.random(BackupCrypto.nonceLength)
        let wrapped = try BackupCrypto.seal(master, key: wrappingKey, nonce: wrappingNonce)
        let databaseNonce = try BackupCrypto.random(BackupCrypto.nonceLength)
        let encrypted = try BackupCrypto.seal(database, key: master, nonce: databaseNonce)
        let slot: JSONObject = [
            "type": 1,
            "uuid": UUID().uuidString.lowercased(),
            "key": hex(wrapped.prefix(32)),
            "key_params": aegisParameters(nonce: wrappingNonce, ciphertext: wrapped),
            "n": 32768,
            "r": 8,
            "p": 1,
            "salt": hex(salt)
        ]
        return [
            "version": 1,
            "header": ["slots": [slot], "params": aegisParameters(nonce: databaseNonce, ciphertext: encrypted)],
            "db": encrypted.dropLast(BackupCrypto.tagLength).base64EncodedString()
        ]
    }

    static func openTwoFas(_ root: JSONObject, password: String) throws -> Data {
        let services = try TwoFasValue(root.requiredString("servicesEncrypted"))
        let reference: TwoFasValue? = try root.optionalString("reference").flatMap { value in
            try value.isEmpty ? nil : TwoFasValue(value)
        }
        var passwordBytes = try externalPassword(password)
        defer { passwordBytes.eraseInterchangeBytes() }
        if let reference {
            var decrypted = try reference.open(password: passwordBytes)
            defer { decrypted.eraseInterchangeBytes() }
            guard decrypted == twoFasReference else { throw InterchangeError(.malformedFile) }
        }
        return try services.open(password: passwordBytes)
    }

    static func sealTwoFas(_ services: Data, password: String) throws -> (services: String, reference: String) {
        let salt = try BackupCrypto.random(256)
        var passwordBytes = try externalPassword(password)
        defer { passwordBytes.eraseInterchangeBytes() }
        var key = try KDF.twoFas(password: passwordBytes, salt: salt)
        defer { key.eraseInterchangeBytes() }
        return try (TwoFasValue.seal(services, key: key, salt: salt), TwoFasValue.seal(twoFasReference, key: key, salt: salt))
    }

    private struct TwoFasValue {
        let ciphertextAndTag: Data
        let salt: Data
        let nonce: Data

        init(_ encoded: String) throws {
            let parts = encoded.split(separator: ":", maxSplits: 3, omittingEmptySubsequences: false)
            guard parts.count == 3 else { throw InterchangeError(.malformedFile) }
            ciphertextAndTag = try base64(String(parts[0]))
            salt = try base64(String(parts[1]))
            nonce = try base64(String(parts[2]))
            guard (16...512).contains(salt.count), nonce.count == BackupCrypto.nonceLength,
                  ciphertextAndTag.count >= BackupCrypto.tagLength else { throw InterchangeError(.malformedFile) }
        }

        func open(password: Data) throws -> Data {
            var key = try KDF.twoFas(password: password, salt: salt)
            defer { key.eraseInterchangeBytes() }
            return try authenticatedOpen(ciphertextAndTag, key: key, nonce: nonce)
        }

        static func seal(_ plaintext: Data, key: Data, salt: Data) throws -> String {
            let nonce = try BackupCrypto.random(BackupCrypto.nonceLength)
            let encrypted = try BackupCrypto.seal(plaintext, key: key, nonce: nonce)
            return [encrypted, salt, nonce].map { $0.base64EncodedString() }.joined(separator: ":")
        }
    }

    private static func externalPassword(_ value: String) throws -> Data {
        // Source applications treat the input as-is, including Unicode composition.
        guard !value.isEmpty, value.utf16.count <= 1024 else { throw InterchangeError(.passwordRequired) }
        return Data(value.utf8)
    }

    private static func authenticatedOpen(_ ciphertext: Data, key: Data, nonce: Data) throws -> Data {
        do {
            return try BackupCrypto.open(ciphertext, key: key, nonce: nonce)
        } catch {
            throw InterchangeError(.authenticationFailed)
        }
    }

    private static func aegisParameters(nonce: Data, ciphertext: Data) -> [String: String] {
        ["nonce": hex(nonce), "tag": hex(ciphertext.suffix(BackupCrypto.tagLength))]
    }

    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private static func hex(_ string: String, length: Int) throws -> Data {
        guard string.utf8.count == length * 2 else { throw InterchangeError(.malformedFile) }
        var bytes = Data(capacity: length)
        var highNibble: UInt8?
        for byte in string.utf8 {
            let nibble: UInt8
            switch byte {
            case 48...57: nibble = byte - 48
            case 65...70: nibble = byte - 55
            case 97...102: nibble = byte - 87
            default: throw InterchangeError(.malformedFile)
            }
            if let high = highNibble {
                bytes.append((high << 4) | nibble)
                highNibble = nil
            } else {
                highNibble = nibble
            }
        }
        return bytes
    }

    private static func base64(_ value: String) throws -> Data {
        var sanitized = Data(value.utf8.filter { $0 != 9 && $0 != 10 && $0 != 13 && $0 != 32 })
        // Java's standard decoder also accepts omitted padding.
        if !sanitized.contains(61) {
            let missing = (4 - sanitized.count % 4) % 4
            if missing == 1 || missing == 2 {
                sanitized.append(contentsOf: [UInt8](repeating: 61, count: missing))
            }
        }
        guard let decoded = Data(base64Encoded: sanitized) else { throw InterchangeError(.malformedFile) }
        return decoded
    }
}
