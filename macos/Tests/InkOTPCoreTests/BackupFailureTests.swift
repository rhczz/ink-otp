import Foundation
import XCTest
@testable import InkOTPCore

final class BackupFailureTests: XCTestCase {
    private let testPassword = "correct horse battery staple"

    func testImportRejectsFormerBackupHeader() throws {
        let removedFormat = Data("2FAB".utf8) + Data(repeating: 0, count: 60)
        XCTAssertThrowsError(try Interchange.detect(removedFormat))
        XCTAssertThrowsError(try Interchange.decode(removedFormat, password: testPassword))
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
