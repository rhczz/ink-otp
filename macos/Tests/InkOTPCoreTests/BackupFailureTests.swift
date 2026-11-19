import Foundation
import XCTest
@testable import InkOTPCore

final class BackupFailureTests: XCTestCase {
    private let testPassword = "correct horse battery staple"

    func testLegacyReadsDataSlicesAndRejectsShortHeaders() throws {
        let fixture = try readFixture("backup-v1.2fab")
        let known = try readFixture("backup-v1.expected.json")
        let sliced = (Data([0xAA]) + fixture).dropFirst()
        XCTAssertEqual(try LegacyBackup.decode(sliced, password: testPassword), try VaultJSON.decode(known))

        let invalidMagic = Data([0]) + fixture.dropFirst()
        XCTAssertThrowsError(try LegacyBackup.decode(invalidMagic, password: "invalid")) {
            XCTAssertEqual($0 as? LegacyBackup.Failure, .magic)
        }
        XCTAssertThrowsError(try LegacyBackup.decode(Data(fixture.prefix(49)), password: "invalid")) {
            XCTAssertEqual($0 as? LegacyBackup.Failure, .truncated)
        }
    }

    func testLegacySeparatesInvalidJSONAndIncompatibleSchemasAfterAuthentication() throws {
        let cases: [(Data, LegacyBackup.Failure)] = [
            (Data([0xC3, 0x28]), .json),
            (Data("{".utf8), .json),
            (Data("[]".utf8), .schema),
            (Data("{\"schemaVersion\":2}".utf8), .schema)
        ]
        for (plaintext, expected) in cases {
            let bytes = try sealAuthenticatedFixture(plaintext)
            XCTAssertThrowsError(try LegacyBackup.decode(bytes, password: testPassword)) {
                XCTAssertEqual($0 as? LegacyBackup.Failure, expected)
            }
        }
    }

    func testAegisValidatesAllAdvertisedPasswordCosts() throws {
        let fixture = try readFixture("interoperability/aegis_encrypted.json")
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture) as? [String: Any])
        var mutated = root
        var header = try XCTUnwrap(root["header"] as? [String: Any])
        var slots = try XCTUnwrap(header["slots"] as? [[String: Any]])
        var tooExpensive = try XCTUnwrap(slots.first { $0["type"] as? Int == 1 })
        tooExpensive["n"] = Int32.max
        slots.append(tooExpensive)
        header["slots"] = slots
        mutated["header"] = header

        let bytes = try JSONSerialization.data(withJSONObject: mutated)
        XCTAssertThrowsError(try Interchange.decode(bytes, password: "test")) { error in
            XCTAssertEqual(error as? InterchangeError, InterchangeError(.unsupportedKDF))
        }
    }

    func testURITextRejectsWholeImportAndPreservesSourceLineIndexes() throws {
        let fixture = try readFixture("interoperability/plain.txt")
        let firstURI = try XCTUnwrap(String(data: fixture, encoding: .utf8)?.split(whereSeparator: \.isNewline).first)
        let malformed = Data((String(firstURI) + "\r\n\r\ninvalid\rotpauth://totp/Invalid?secret=!!!\n").utf8)
        XCTAssertThrowsError(try Interchange.decode(malformed)) { error in
            XCTAssertEqual(error as? InterchangeError, InterchangeError(.unsupportedEntries, entryIndexes: [3, 4]))
        }
    }

    func testTwoFasRejectsCounterOutsideSigned32BitRange() throws {
        var model = VaultModel.new()
        model.items = [
            VaultItem.make(type: .hotp, issuer: "Fixture", accountName: "Counter",
                           secret: Data([1]), counter: Int64(Int32.max) + 1)
        ]
        for format in [InterchangeFormat.twofasPlaintext, .twofasEncrypted] {
            XCTAssertThrowsError(try Interchange.encode(model, format: format, password: testPassword)) { error in
                XCTAssertEqual(error as? InterchangeError, InterchangeError(.unsupportedEntries, entryIndexes: [1]))
            }
        }
        let uri = try Interchange.encode(model, format: .otpauthText)
        XCTAssertEqual(try Interchange.decode(uri.bytes).model.items.first?.counter, Int64(Int32.max) + 1)
    }

    private func sealAuthenticatedFixture(_ plaintext: Data) throws -> Data {
        let salt = try BackupCrypto.random(16)
        let nonce = try BackupCrypto.random(12)
        let key = try KDF.argon2id(password: Data(testPassword.utf8), salt: salt, memoryKiB: 8192, iterations: 1, parallelism: 1)
        var header = Data("2FAB".utf8)
        header.append(contentsOf: [1, 1, 0, 0, 32, 0, 0, 0, 0, 1, 0, 0, 0, 1])
        header.append(salt)
        header.append(nonce)
        header.append(contentsOf: withUnsafeBytes(of: UInt32(plaintext.count).bigEndian) { Array($0) })
        return header + (try BackupCrypto.seal(plaintext, key: key, nonce: nonce, aad: header))
    }

    private func readFixture(_ name: String) throws -> Data {
        let spec: URL
        if let root = ProcessInfo.processInfo.environment["INKOTP_TEST_SPEC"] {
            spec = URL(filePath: root, directoryHint: .isDirectory)
        } else {
            spec = (0..<4).reduce(URL(filePath: #filePath)) { url, _ in url.deletingLastPathComponent() }
                .appending(path: "spec", directoryHint: .isDirectory)
        }
        return try Data(contentsOf: spec.appending(path: "fixtures", directoryHint: .isDirectory).appendingPathComponent(name))
    }
}
