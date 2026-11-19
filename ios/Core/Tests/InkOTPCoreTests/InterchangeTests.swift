import Foundation
import XCTest
@testable import InkOTPCore

final class InterchangeTests: XCTestCase {
    private let fixturePassword = "correct horse battery staple"
    private let repo = (0..<5).reduce(URL(fileURLWithPath: #filePath)) { url, _ in
        url.deletingLastPathComponent()
    }

    func testIndependentBackupDecryptsWithFullMetadata() throws {
        let expected = try VaultJson.decode(nativeFixture("backup-v1.expected.json"))
        let bytes = try nativeFixture("backup-v1.2fab")
        let header = try BackupCodec.inspect(bytes)
        XCTAssertEqual(1410, header.ciphertextLength)
        XCTAssertEqual(1476, bytes.count)
        XCTAssertEqual(expected, try BackupCodec.open(bytes, password: fixturePassword))
    }

    func testBackupRejectsWrongPasswordTamperingAndInvalidHeader() throws {
        let bytes = try nativeFixture("backup-v1.2fab")
        XCTAssertThrowsError(try BackupCodec.open(bytes, password: "wrong")) {
            XCTAssertEqual(.authentication, $0 as? BackupFailure)
        }
        var corrupted = bytes
        corrupted[50] ^= 1
        XCTAssertThrowsError(try BackupCodec.open(corrupted, password: fixturePassword)) {
            XCTAssertEqual(.authentication, $0 as? BackupFailure)
        }
        for (offset, replacement, failure) in [
            (0, 0, BackupFailure.magic),
            (4, 2, .version),
            (5, 2, .kdfAlgorithm),
            (6, 255, .kdfParameters),
            (46, 255, .ciphertextLength)
        ] {
            var modified = bytes
            modified[offset] = UInt8(replacement)
            XCTAssertThrowsError(try BackupCodec.inspect(modified)) {
                XCTAssertEqual(failure, $0 as? BackupFailure)
            }
        }
        XCTAssertThrowsError(try BackupCodec.inspect(bytes.dropLast())) {
            XCTAssertEqual(.truncated, $0 as? BackupFailure)
        }
        XCTAssertThrowsError(try BackupCodec.inspect(bytes + Data([0]))) {
            XCTAssertEqual(.trailingData, $0 as? BackupFailure)
        }
    }

    func testAegisAndTwoFasRejectAllUnsupportedProfilesWithOriginalPositions() throws {
        let pairs: [(String, InterchangeFormat, [Int])] = [
            ("aegis_plain.json", .aegisPlaintext, [2, 5, 7]),
            ("aegis_encrypted.json", .aegisEncrypted, [2, 5, 7]),
            ("plain.txt", .otpauthText, [2, 5, 7]),
            ("2fas_authenticator_plain_v3.2fas", .twofasPlaintext, [2, 5]),
            ("2fas_authenticator_encrypted_v3.2fas", .twofasEncrypted, [2, 5]),
            ("2fas_authenticator_plain_v4.2fas", .twofasPlaintext, [3, 5]),
            ("2fas_authenticator_encrypted_v4.2fas", .twofasEncrypted, [3, 5])
        ]
        for (filename, kind, indexes) in pairs {
            let data = try fixture(filename)
            XCTAssertEqual(kind, try InterchangeCodec.detect(data), filename)
            XCTAssertThrowsError(try InterchangeCodec.decode(data, password: "test"), filename) {
                XCTAssertEqual(.unsupportedEntries, ($0 as? InterchangeFailure)?.code)
                XCTAssertEqual(indexes, ($0 as? InterchangeFailure)?.entryIndexes)
            }
        }
    }

    func testOfficialTwoFasLegacyDefaultsAndEncryptedProfiles() throws {
        let legacy = try InterchangeCodec.decode(fixture("2fas_authenticator.json"))
        XCTAssertEqual(6, legacy.model.items.count)
        XCTAssertTrue(legacy.model.items.allSatisfy { $0.type == .totp && $0.digits == 6 && $0.period == 30 })
        let plain = try InterchangeCodec.decode(fixture("2fas_authenticator_plain.2fas"))
        let encrypted = try InterchangeCodec.decode(fixture("2fas_authenticator_encrypted.2fas"), password: "test")
        XCTAssertEqual(["Mason", "Elijah", "James", "Mason"], plain.model.items.map(\.accountName))
        XCTAssertEqual([30, 50, 30, 30], plain.model.items.map(\.period))
        XCTAssertEqual(try profiles(plain.model), try profiles(encrypted.model))
    }

    func testBoundsAndStrictExternalPassword() throws {
        let model = try VaultJson.decode(nativeFixture("backup-v1.expected.json"))
        for format in [InterchangeFormat.aegisEncrypted, .twofasEncrypted] {
            let supplied = "cafe\u{0301}-public-test"
            let nfc = "caf\u{00E9}-public-test"
            let data = try InterchangeCodec.encode(model, format: format, password: supplied).bytes
            assertFailure(.passwordRequired) { _ = try InterchangeCodec.decode(data) }
            assertFailure(.authenticationFailed) { _ = try InterchangeCodec.decode(data, password: nfc) }
            XCTAssertEqual(try profiles(model), try profiles(InterchangeCodec.decode(data, password: supplied).model))
        }

        let blob = Data(repeating: 0, count: InterchangeCodec.maxFileBytes + 1)
        assertFailure(.fileTooLarge) { _ = try InterchangeCodec.detect(blob) }
        let oversized = "{\"schemaVersion\":4,\"services\":[],\"extra\":[" + String(repeating: "0,", count: 500_001) + "0]}"
        assertFailure(.malformedFile) { _ = try InterchangeCodec.detect(Data(oversized.utf8)) }
        let excessiveDepth = String(repeating: "[", count: 33) + String(repeating: "]", count: 33)
        assertFailure(.malformedFile) { _ = try InterchangeCodec.detect(Data(excessiveDepth.utf8)) }
        assertFailure(.malformedFile) { _ = try InterchangeCodec.detect(Data([0xc3, 0x28])) }
    }

    func testAegisRejectsExcessiveCostsBeforeUsingThePassword() throws {
        let bytes = try fixture("aegis_encrypted.json")
        var file = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        var header = try XCTUnwrap(file["header"] as? [String: Any])
        var slots = try XCTUnwrap(header["slots"] as? [[String: Any]])
        XCTAssertFalse(slots.isEmpty)
        slots[0]["n"] = 1 << 25
        header["slots"] = slots
        file["header"] = header
        assertFailure(.unsupportedKDF) {
            _ = try InterchangeCodec.decode(JSONSerialization.data(withJSONObject: file), password: "wrong")
        }
        file["version"] = 999
        assertFailure(.unsupportedVersion) {
            _ = try InterchangeCodec.decode(JSONSerialization.data(withJSONObject: file), password: "wrong")
        }
    }

    func testTwoFasCounterLimitDoesNotTruncateExport() throws {
        var model = try VaultJson.decode(nativeFixture("backup-v1.expected.json"))
        model.items[0].type = .hotp
        model.items[0].counter = Int64(Int32.max) + 1
        XCTAssertThrowsError(try InterchangeCodec.encode(model, format: .twofasPlaintext)) {
            XCTAssertEqual(.unsupportedEntries, ($0 as? InterchangeFailure)?.code)
            XCTAssertEqual([1], ($0 as? InterchangeFailure)?.entryIndexes)
        }
        let data = try InterchangeCodec.encode(model, format: .otpauthText).bytes
        XCTAssertEqual(model.items[0].counter, try InterchangeCodec.decode(data).model.items.first?.counter)
    }

    func testURIErrorReportsPhysicalLineAndRejectsTheWholeFile() throws {
        let model = try VaultJson.decode(nativeFixture("backup-v1.expected.json"))
        let uri = OtpauthParser.encode(model.items[0])
        let input = "\u{FEFF}\n\n" + uri + "\r\n" + "not an otp URI\n"
        XCTAssertThrowsError(try InterchangeCodec.decode(Data(input.utf8))) {
            XCTAssertEqual(.unsupportedEntries, ($0 as? InterchangeFailure)?.code)
            XCTAssertEqual([4], ($0 as? InterchangeFailure)?.entryIndexes)
        }
    }

    #if os(macOS)
    func testExportsForAndroidAndPythonAndRoundTrips() throws {
        var model = try VaultJson.decode(nativeFixture("backup-v1.expected.json"))
        model.items.append(VaultItem(
            id: "00000000-0000-4000-8000-000000000003",
            type: .hotp, issuer: "Public Counter", accountName: "sample",
            secret: Data("12345678901234567890".utf8), counter: 10_300,
            sortIndex: 3000, createdAt: model.createdAt, updatedAt: model.updatedAt
        ))
        let directory = repo.appendingPathComponent("ios/Core/.contract-exports")
        let crosscheck = directory.appendingPathComponent("crosscheck")
        let interoperability = directory.appendingPathComponent("interoperability")
        for folder in [crosscheck, interoperability] {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        }
        let expected = try VaultJson.encode(model)
        try expected.write(to: crosscheck.appendingPathComponent("swift-backup.expected.json"), options: .atomic)
        try expected.write(to: interoperability.appendingPathComponent("expected.json"), options: .atomic)
        for format in InterchangeFormat.allCases {
            let result = try InterchangeCodec.encode(model, format: format, password: fixturePassword)
            let decoded = try InterchangeCodec.decode(result.bytes, password: fixturePassword).model
            XCTAssertEqual(try profiles(model), try profiles(decoded), format.rawValue)
            try result.bytes.write(to: interoperability.appendingPathComponent(format.filename(prefix: "swift")), options: .atomic)
            if format == .inkotp2fab {
                XCTAssertEqual(model, decoded)
                try result.bytes.write(to: crosscheck.appendingPathComponent("swift-backup.2fab"), options: .atomic)
            }
        }
    }

    func testReadsAndroidExports() throws {
        let directory = repo.appendingPathComponent("ios/Core/.android-contract-inputs")
        if !FileManager.default.fileExists(atPath: directory.path) &&
            ProcessInfo.processInfo.environment["INKOTP_REQUIRE_ANDROID_CONTRACT"] != "1" {
            throw XCTSkip("Android exports are supplied by the cross-platform Actions workflow")
        }
        let crosscheck = directory.appendingPathComponent("crosscheck")
        let expected = try VaultJson.decode(Data(contentsOf: crosscheck.appendingPathComponent("kotlin-backup.expected.json")))
        let backup = try Data(contentsOf: crosscheck.appendingPathComponent("kotlin-backup.2fab"))
        XCTAssertEqual(expected, try BackupCodec.open(backup, password: fixturePassword))

        let interchange = directory.appendingPathComponent("interoperability")
        let profilesExpected = try profiles(VaultJson.decode(Data(contentsOf: interchange.appendingPathComponent("expected.json"))))
        for format in InterchangeFormat.allCases {
            let data = try Data(contentsOf: interchange.appendingPathComponent(format.filename(prefix: "kotlin")))
            XCTAssertEqual(format, try InterchangeCodec.detect(data))
            XCTAssertEqual(profilesExpected, try profiles(InterchangeCodec.decode(data, password: fixturePassword).model), format.rawValue)
        }
    }
    #endif

    private func nativeFixture(_ path: String) throws -> Data {
        try Data(contentsOf: repo.appendingPathComponent("spec/fixtures/" + path))
    }

    private func fixture(_ path: String) throws -> Data {
        try nativeFixture("interoperability/" + path)
    }

    private func profiles(_ model: VaultModel) throws -> [String] {
        model.items.sorted { $0.sortIndex < $1.sortIndex }.map { OtpauthParser.encode($0) }
    }

    private func assertFailure(_ code: InterchangeError, _ action: () throws -> Void) {
        XCTAssertThrowsError(try action()) { XCTAssertEqual(code, ($0 as? InterchangeFailure)?.code) }
    }
}
