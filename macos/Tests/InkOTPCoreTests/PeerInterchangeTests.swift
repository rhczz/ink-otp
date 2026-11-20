import Foundation
import InkOTPCore
import XCTest

final class PeerInterchangeTests: XCTestCase {
    private let fixturePassword = "correct horse battery staple"

    func testProductionInterchangeAgreement() throws {
        let expected = try publicExample()
        let environment = ProcessInfo.processInfo.environment
        if let path = environment["INKOTP_TEST_PEER_EXPORTS"] {
            try readAndroidExports(at: URL(fileURLWithPath: path, isDirectory: true), expected: expected)
        } else {
            guard environment["INKOTP_TEST_PEER_REQUIRED"] != "1" else {
                XCTFail("Android exports are required")
                return
            }
            let output = environment["INKOTP_TEST_EXPORTS"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            try writeMacOSExports(to: output, expected: expected)
        }
    }

    private func publicExample() throws -> VaultModel {
        let spec = ProcessInfo.processInfo.environment["INKOTP_TEST_SPEC"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? (0..<4).reduce(URL(fileURLWithPath: #filePath)) { url, _ in url.deletingLastPathComponent() }
                .appendingPathComponent("spec", isDirectory: true)
        var model = try VaultJSON.decode(Data(contentsOf: spec.appendingPathComponent("fixtures/backup-v1.expected.json")))
        let secret = try XCTUnwrap(model.items.first).secret
        var counter = VaultItem.make(type: .hotp, issuer: "Public Counter", accountName: "sample", secret: secret,
                                     counter: 10_300, sortIndex: 3000, origin: "import")
        counter.id = "00000000-0000-4000-8000-000000000003"
        counter.createdAt = model.createdAt
        counter.updatedAt = model.updatedAt
        counter.tags = ["public-fixture", "示例"]
        counter.icon = VaultIcon(kind: "letter", value: "P", colorHex: "#123456")
        counter.website = "https://example.com/inkotp-fixture"
        model.settings.lockTimeoutSeconds = 120
        model.settings.hideCodesByDefault = true
        model.settings.clipboardClearSeconds = 45
        model.items.append(counter)
        return model
    }

    private func writeMacOSExports(to directory: URL?, expected: VaultModel) throws {
        if let directory {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try VaultJSON.encode(expected).write(to: directory.appendingPathComponent("expected.json"))
        }
        for format in InterchangeFormat.allCases {
            let output = try Interchange.encode(expected, format: format, password: format.requiresPassword ? fixturePassword : nil)
            try assertMatches(format, bytes: output.bytes, expected: expected)
            if let directory {
                try output.bytes.write(to: directory.appendingPathComponent(filename("swift", format)))
            }
        }
    }

    private func readAndroidExports(at directory: URL, expected: VaultModel) throws {
        let formats = InterchangeFormat.allCases
        let expectedNames = Set(formats.map { filename("kotlin", $0) } + ["expected.json"])
        let names = Set(try FileManager.default.contentsOfDirectory(atPath: directory.path))
        XCTAssertEqual(names, expectedNames, "Android export inventory")
        let claimedInput = try VaultJSON.decode(Data(contentsOf: directory.appendingPathComponent("expected.json")))
        XCTAssertTrue(claimedInput == expected, "Android producer input must match the independent public example")
        for format in formats {
            let bytes = try Data(contentsOf: directory.appendingPathComponent(filename("kotlin", format)))
            try assertMatches(format, bytes: bytes, expected: expected)
        }
    }

    private func assertMatches(_ format: InterchangeFormat, bytes: Data, expected: VaultModel) throws {
        XCTAssertEqual(try Interchange.detect(bytes), format)
        let decoded = try Interchange.decode(bytes, password: format.requiresPassword ? fixturePassword : nil)
        XCTAssertEqual(decoded.format, format)
        let expectedProfiles = expected.items.map(Profile.init)
        let decodedProfiles = decoded.model.items.map(Profile.init)
        XCTAssertTrue(decodedProfiles == expectedProfiles, "\(format.rawValue): OTP profiles or order changed")
    }

    private func filename(_ prefix: String, _ format: InterchangeFormat) -> String {
        "\(prefix)-\(format.rawValue).\(format.fileExtension)"
    }

    private struct Profile: Equatable {
        let type: OTPType
        let issuer: String?
        let account: String
        let secret: Data
        let algorithm: OTPAlgorithm
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
}
