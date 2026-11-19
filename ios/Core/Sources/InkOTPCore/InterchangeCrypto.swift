import CryptoKit
import Foundation

enum InterchangeCrypto {
    private static let twoFasReference = Data(
        "tRViSsLKzd86Hprh4ceC2OP7xazn4rrt4xhfEUbOjxLX8Rc3mkISXE0lWbmnWfggogbBJhtYgpK6fMl1D6mtsy92R3HkdGfwuXbzLebqVFJsR7IZ2w58t938iymwG4824igYy1wi6n2WDpO1Q1P69zwJGs2F5a1qP4MyIiDSD7NCV2OvidXQCBnDlGfmz0f1BQySRkkt4ryiJeCjD2o4QsveJ9uDBUn8ELyOrESv5R5DMDkD4iAF8TXU7KyoJujd".utf8
    )

    private struct Slot {
        let n: UInt64
        let r: UInt32
        let p: UInt32
        let salt: Data
        let wrapped: Data
        let nonce: Data

        init(_ object: InterchangeObject) throws {
            let cost = try object.integer("n")
            let blockSize = try object.integer("r")
            let parallelism = try object.integer("p")
            guard cost >= 2, (cost & (cost - 1)) == 0,
                  (1...32).contains(blockSize), (1...4).contains(parallelism),
                  128 * cost * blockSize <= 64 * 1024 * 1024,
                  cost * blockSize * parallelism <= 524_288 else {
                throw InterchangeFailure(.unsupportedKDF)
            }

            n = UInt64(cost)
            r = UInt32(blockSize)
            p = UInt32(parallelism)
            let params = try object.object("key_params")
            salt = try hex(object.requiredText("salt"), count: 32)
            wrapped = try hex(object.requiredText("key"), count: 32) + hex(params.requiredText("tag"), count: 16)
            nonce = try hex(params.requiredText("nonce"), count: 12)
        }
    }

    static func openAegis(_ root: InterchangeObject, password: String) throws -> Data {
        let header = try root.object("header")
        let rawSlots = try header.array("slots")
        guard rawSlots.count <= 32 else { throw InterchangeFailure(.unsupportedKDF) }
        let passwordSlots = try rawSlots.compactMap { raw -> InterchangeObject? in
            guard let slot = raw as? InterchangeObject else { throw InterchangeFailure(.malformedFile) }
            return try slot.integer("type") == 1 ? slot : nil
        }
        guard !passwordSlots.isEmpty else { throw InterchangeFailure(.unsupportedEncryption) }
        guard passwordSlots.count <= 4 else { throw InterchangeFailure(.unsupportedKDF) }

        // Validate all advertised costs and ciphertext framing before starting any KDF.
        let slots = try passwordSlots.map(Slot.init)
        let params = try header.object("params")
        let nonce = try hex(params.requiredText("nonce"), count: 12)
        let ciphertext = try base64(root.requiredText("db")) + hex(params.requiredText("tag"), count: 16)
        for slot in slots {
            var key = try InkKDF.scrypt(password: password, salt: slot.salt, n: slot.n, r: slot.r, p: slot.p)
            defer { key.resetBytes(in: key.indices) }
            guard var master = try? open(slot.wrapped, key: key, nonce: slot.nonce) else { continue }
            defer { master.resetBytes(in: master.indices) }
            return try open(ciphertext, key: master, nonce: nonce)
        }
        throw InterchangeFailure(.authenticationFailed)
    }

    static func sealAegis(_ db: Data, password: String) throws -> InterchangeObject {
        let salt = try InkKDF.randomBytes(count: 32)
        var master = try InkKDF.randomBytes(count: 32)
        defer { master.resetBytes(in: master.indices) }
        var key = try InkKDF.scrypt(password: password, salt: salt, n: 32_768, r: 8, p: 1)
        defer { key.resetBytes(in: key.indices) }

        let nonce = try InkKDF.randomBytes(count: 12)
        let dbNonce = try InkKDF.randomBytes(count: 12)
        let wrapped = try seal(master, key: key, nonce: nonce)
        let encrypted = try seal(db, key: master, nonce: dbNonce)
        let slot: InterchangeObject = [
            "type": 1, "uuid": UUID().uuidString.lowercased(), "key": toHex(wrapped.ciphertext),
            "key_params": parameters(nonce: nonce, tag: wrapped.tag),
            "n": 32_768, "r": 8, "p": 1, "salt": toHex(salt)
        ]
        return [
            "version": 1,
            "header": ["slots": [slot], "params": parameters(nonce: dbNonce, tag: encrypted.tag)] as InterchangeObject,
            "db": encrypted.ciphertext.base64EncodedString()
        ]
    }

    static func openTwoFas(_ root: InterchangeObject, password: String) throws -> Data {
        let services = try twoFasParts(root.requiredText("servicesEncrypted"))
        if let raw = try root.optionalText("reference"), !raw.isEmpty {
            var reference = try openTwoFasValue(twoFasParts(raw), password: password)
            defer { reference.resetBytes(in: reference.indices) }
            guard reference == twoFasReference else { throw InterchangeFailure(.malformedFile) }
        }
        return try openTwoFasValue(services, password: password)
    }

    static func sealTwoFas(_ services: Data, password: String) throws -> (services: String, reference: String) {
        let salt = try InkKDF.randomBytes(count: 256)
        var key = try InkKDF.pbkdf2(password: password, salt: salt)
        defer { key.resetBytes(in: key.indices) }
        return (
            try sealTwoFasValue(services, key: key, salt: salt),
            try sealTwoFasValue(twoFasReference, key: key, salt: salt)
        )
    }

    private struct TwoFasParts {
        let ciphertext: Data
        let salt: Data
        let nonce: Data
    }

    private static func twoFasParts(_ raw: String) throws -> TwoFasParts {
        let parts = raw.split(separator: ":", maxSplits: 3, omittingEmptySubsequences: false)
        guard parts.count == 3 else { throw InterchangeFailure(.malformedFile) }
        let ciphertext = try base64(parts[0])
        let salt = try base64(parts[1])
        let nonce = try base64(parts[2])
        guard ciphertext.count >= 16, (16...512).contains(salt.count), nonce.count == 12 else {
            throw InterchangeFailure(.malformedFile)
        }
        return TwoFasParts(ciphertext: ciphertext, salt: salt, nonce: nonce)
    }

    private static func openTwoFasValue(_ parts: TwoFasParts, password: String) throws -> Data {
        var key = try InkKDF.pbkdf2(password: password, salt: parts.salt)
        defer { key.resetBytes(in: key.indices) }
        return try open(parts.ciphertext, key: key, nonce: parts.nonce)
    }

    private static func sealTwoFasValue(_ value: Data, key: Data, salt: Data) throws -> String {
        let nonce = try InkKDF.randomBytes(count: 12)
        let box = try seal(value, key: key, nonce: nonce)
        return [box.ciphertext + box.tag, salt, nonce].map { $0.base64EncodedString() }.joined(separator: ":")
    }

    private static func open(_ encrypted: Data, key: Data, nonce: Data) throws -> Data {
        guard encrypted.count >= 16 else { throw InterchangeFailure(.malformedFile) }
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: encrypted.dropLast(16), tag: encrypted.suffix(16))
            return try AES.GCM.open(box, using: SymmetricKey(data: key))
        } catch {
            throw InterchangeFailure(.authenticationFailed)
        }
    }

    private static func seal(_ data: Data, key: Data, nonce: Data) throws -> AES.GCM.SealedBox {
        try AES.GCM.seal(data, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce))
    }

    private static func parameters(nonce: Data, tag: Data) -> [String: String] {
        ["nonce": toHex(nonce), "tag": toHex(tag)]
    }

    private static func toHex(_ bytes: Data) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func hex(_ string: String, count: Int) throws -> Data {
        guard string.utf8.count == 2 * count else { throw InterchangeFailure(.malformedFile) }
        let characters = Array(string.utf8)
        var data = Data(capacity: count)
        for index in stride(from: 0, to: characters.count, by: 2) {
            let high = try nibble(characters[index])
            let low = try nibble(characters[index + 1])
            data.append(high << 4 | low)
        }
        return data
    }

    private static func nibble(_ character: UInt8) throws -> UInt8 {
        switch character {
        case 48...57: return character - 48
        case 65...70: return character - 55
        case 97...102: return character - 87
        default: throw InterchangeFailure(.malformedFile)
        }
    }

    private static func base64<S: StringProtocol>(_ value: S) throws -> Data {
        var bytes = value.utf8.filter {
            switch $0 {
            case 9, 10, 13, 32: false
            default: true
            }
        }
        let remainder = bytes.count % 4
        if remainder != 0 {
            guard remainder != 1, !bytes.contains(61) else { throw InterchangeFailure(.malformedFile) }
            bytes.append(contentsOf: repeatElement(UInt8(61), count: 4 - remainder))
        }
        guard let result = Data(base64Encoded: Data(bytes)) else { throw InterchangeFailure(.malformedFile) }
        return result
    }
}
