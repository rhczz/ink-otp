import Foundation
import XCTest
@testable import InkOTPCore

final class InterchangeTests: XCTestCase {
    private let fixturePassword = "correct horse battery staple"
    private let repo = (0..<5).reduce(URL(fileURLWithPath: #filePath)) { url, _ in
        url.deletingLastPathComponent()
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
        XCTAssertEqual(profiles(plain.model), profiles(encrypted.model))
    }

    func testBoundsAndStrictExternalPassword() throws {
        let model = try VaultJson.decode(nativeFixture("backup-v1.expected.json"))
        for format in [InterchangeFormat.aegisEncrypted, .twofasEncrypted] {
            let supplied = "cafe\u{0301}-public-test"
            let nfc = "caf\u{00E9}-public-test"
            let data = try InterchangeCodec.encode(model, format: format, password: supplied).bytes
            assertFailure(.passwordRequired) { _ = try InterchangeCodec.decode(data) }
            assertFailure(.authenticationFailed) { _ = try InterchangeCodec.decode(data, password: nfc) }
            XCTAssertEqual(profiles(model), profiles(try InterchangeCodec.decode(data, password: supplied).model))
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
        let model = try publicInterchangeModel()
        let interoperability = repo.appendingPathComponent("ios/Core/.contract-exports/interoperability")
        try FileManager.default.createDirectory(at: interoperability, withIntermediateDirectories: true)
        let expected = try VaultJson.encode(model)
        try expected.write(to: interoperability.appendingPathComponent("expected.json"), options: .atomic)
        for format in InterchangeFormat.allCases {
            let password = format.requiresPassword ? fixturePassword : nil
            let result = try InterchangeCodec.encode(model, format: format, password: password)
            try assertInterchange(result.bytes, format: format, expected: model)
            try result.bytes.write(to: interoperability.appendingPathComponent(format.filename(prefix: "swift")), options: .atomic)
        }
    }

    func testReadsAndroidExports() throws {
        let directory = repo.appendingPathComponent("ios/Core/.android-contract-inputs")
        if !FileManager.default.fileExists(atPath: directory.path) &&
            ProcessInfo.processInfo.environment["INKOTP_REQUIRE_ANDROID_CONTRACT"] != "1" {
            throw XCTSkip("Android exports are supplied by the cross-platform Actions workflow")
        }
        let expected = try publicInterchangeModel()
        let filenames = InterchangeFormat.allCases.map { $0.filename(prefix: "kotlin") }
        let files = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        XCTAssertEqual(Set(files), Set(filenames + ["expected.json"]), "Android interchange inventory")

        let reported = try VaultJson.decode(Data(contentsOf: directory.appendingPathComponent("expected.json")))
        XCTAssertTrue(reported == expected, "The Android interchange input differs from the independent example")
        for format in InterchangeFormat.allCases {
            let filename = format.filename(prefix: "kotlin")
            let data = try Data(contentsOf: directory.appendingPathComponent(filename))
            try assertInterchange(data, format: format, expected: expected)
        }
    }

    private func publicInterchangeModel() throws -> VaultModel {
        var model = try VaultJson.decode(nativeFixture("backup-v1.expected.json"))
        let secret = try XCTUnwrap(model.items.first).secret
        model.settings = VaultSettings(lockTimeoutSeconds: 120, hideCodesByDefault: true, clipboardClearSeconds: 45)
        model.items.append(VaultItem(
            id: "00000000-0000-4000-8000-000000000003",
            type: .hotp, issuer: "Public Counter", accountName: "sample",
            secret: secret, counter: 10_300, tags: ["public-fixture", "示例"],
            icon: VaultIcon(kind: "letter", value: "P", colorHex: "#123456"),
            sortIndex: 3000, origin: "import", createdAt: model.createdAt, updatedAt: model.updatedAt,
            website: "https://example.com/inkotp-fixture"
        ))
        return model
    }

    private func assertInterchange(_ data: Data, format: InterchangeFormat, expected: VaultModel) throws {
        XCTAssertEqual(try InterchangeCodec.detect(data), format, format.rawValue)
        let imported = try InterchangeCodec.decode(data, password: format.requiresPassword ? fixturePassword : nil)
        XCTAssertEqual(imported.format, format)
        XCTAssertTrue(profiles(imported.model) == profiles(expected), "\(format.rawValue): OTP fields or order changed")
    }
    #endif

    private func nativeFixture(_ path: String) throws -> Data {
        try Data(contentsOf: repo.appendingPathComponent("spec/fixtures/" + path))
    }

    private func fixture(_ path: String) throws -> Data {
        try nativeFixture("interoperability/" + path)
    }

    private func profiles(_ model: VaultModel) -> [Profile] {
        model.items.map(Profile.init)
    }

    private struct Profile: Equatable {
        let type: OtpType
        let issuer: String?
        let account: String
        let secret: Data
        let algorithm: OtpAlgorithm
        let digits: Int
        let period: Int?
        let counter: Int64?

        init(_ item: VaultItem) {
            type = item.type
            issuer = item.issuer
            account = item.accountName
            secret = item.secret
            algorithm = item.algorithm
            digits = item.digits
            period = item.type == .totp ? item.period : nil
            counter = item.counter
        }
    }

    private func assertFailure(_ code: InterchangeError, _ action: () throws -> Void) {
        XCTAssertThrowsError(try action()) { XCTAssertEqual(code, ($0 as? InterchangeFailure)?.code) }
    }
}
