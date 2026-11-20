import Foundation
import InkOTPCore
import XCTest

final class SharedContractTests: XCTestCase {
    typealias JSONObject = [String: Any]

    private var spec: URL {
        if let path = ProcessInfo.processInfo.environment["INKOTP_TEST_SPEC"] {
            return URL(fileURLWithPath: path, isDirectory: true)
        }
        return (1...4).reduce(URL(fileURLWithPath: #filePath)) { path, _ in path.deletingLastPathComponent() }
            .appendingPathComponent("spec", isDirectory: true)
    }

    private func data(_ path: String) throws -> Data {
        try Data(contentsOf: spec.appendingPathComponent(path))
    }

    private func json(_ path: String) throws -> Any {
        try JSONSerialization.jsonObject(with: data(path))
    }

    private func hex(_ input: String) throws -> Data {
        let characters = Array(input)
        XCTAssertEqual(characters.count % 2, 0)
        return try Data(stride(from: 0, to: characters.count, by: 2).map {
            try XCTUnwrap(UInt8(String(characters[$0..<$0 + 2]), radix: 16))
        })
    }

    func testStandardAndGeneratedOTPAndBase32Vectors() throws {
        let vectors = try XCTUnwrap(try json("vectors.json") as? JSONObject)
        for section in ["totp", "random_totp", "hotp"] {
            let cases = try XCTUnwrap(vectors[section] as? [JSONObject])
            XCTAssertFalse(cases.isEmpty, section)
            for (index, row) in cases.enumerated() {
                let algorithmString = try XCTUnwrap(row["algorithm"] as? String)
                let algorithm = try XCTUnwrap(OTPAlgorithm(rawValue: algorithmString))
                let secret = try OTP.base32Decode(XCTUnwrap(row["secretBase32"] as? String))
                let digits = try XCTUnwrap(row["digits"] as? Int)
                let code: String
                if section == "hotp" {
                    let counter = try XCTUnwrap(row["counter"] as? UInt64)
                    code = try OTP.hotp(secret: secret, algorithm: algorithm, digits: digits, counter: counter)
                } else {
                    let time = try XCTUnwrap(row["time"] as? Int64)
                    let period = try XCTUnwrap(row["period"] as? Int)
                    code = try OTP.totp(secret: secret, algorithm: algorithm, digits: digits, period: period, time: time)
                }
                XCTAssertEqual(code, row["expected"] as? String, "\(section) \(index + 1)")
            }
        }
        let examples = try XCTUnwrap(vectors["base32"] as? [JSONObject])
        XCTAssertFalse(examples.isEmpty)
        for (index, row) in examples.enumerated() {
            let input = try XCTUnwrap(row["input"] as? String)
            if let expected = row["expectedError"] as? String {
                XCTAssertThrowsError(try OTP.base32Decode(input), "Base32 case \(index + 1)") {
                    XCTAssertEqual(($0 as? CoreError)?.code, expected)
                }
            } else {
                let bytes = try OTP.base32Decode(input)
                XCTAssertEqual(bytes, try hex(XCTUnwrap(row["expectedHex"] as? String)))
                XCTAssertEqual(OTP.base32Encode(bytes), row["expectedBase32"] as? String)
            }
        }
    }

    func testOTPBoundaries() throws {
        let vectors = try XCTUnwrap(try json("vectors.json") as? JSONObject)
        let cases = try XCTUnwrap(vectors["boundary"] as? [JSONObject])
        XCTAssertFalse(cases.isEmpty)
        for row in cases {
            let request = try XCTUnwrap(row["request"] as? JSONObject)
            let expected = try XCTUnwrap(row["expectedError"] as? String)
            XCTAssertThrowsError(try compute(request), row["description"] as? String ?? "OTP boundary") {
                XCTAssertEqual(($0 as? CoreError)?.code, expected)
            }
        }
    }

    private func compute(_ request: JSONObject) throws -> String {
        let algorithm = try XCTUnwrap(request["algorithm"] as? String)
        guard let algorithmValue = OTPAlgorithm(rawValue: algorithm) else { throw CoreError("ALGORITHM_UNSUPPORTED") }
        let digits = try XCTUnwrap(request["digits"] as? Int)
        let secret = try hex(XCTUnwrap(request["secretHex"] as? String))
        switch request["kind"] as? String {
        case "hotp":
            let counter = try XCTUnwrap(request["counter"] as? NSNumber).stringValue
            guard !counter.hasPrefix("-") else { throw CoreError("COUNTER_NEGATIVE") }
            guard let value = UInt64(counter) else { throw CoreError("COUNTER_OVERFLOW") }
            return try OTP.hotp(secret: secret, algorithm: algorithmValue, digits: digits, counter: value)
        case "totp":
            let time = try XCTUnwrap(request["time"] as? NSNumber).stringValue
            guard let value = Int64(time) else { throw CoreError("TIME_OUT_OF_RANGE") }
            let period = try XCTUnwrap(request["period"] as? Int)
            return try OTP.totp(secret: secret, algorithm: algorithmValue, digits: digits, period: period, time: value)
        default:
            throw CoreError("KIND_UNSUPPORTED")
        }
    }

    func testCompleteOtpauthSpec() throws {
        let cases = try XCTUnwrap(try json("otpauth-cases.json") as? [JSONObject])
        XCTAssertFalse(cases.isEmpty)
        for row in cases {
            let name = try XCTUnwrap(row["name"] as? String)
            let input = try XCTUnwrap(row["input"] as? String)
            if let error = row["error"] as? String {
                XCTAssertThrowsError(try Otpauth.parse(input), name) {
                    XCTAssertEqual(($0 as? CoreError)?.code, error, name)
                }
                continue
            }
            let expected = try XCTUnwrap(row["expected"] as? JSONObject)
            let result: OTPDraft
            do {
                result = try Otpauth.parse(input)
            } catch {
                XCTFail("\(name): \(error.localizedDescription)")
                continue
            }
            if expected["kind"] as? String == "migration" {
                XCTAssertEqual(result.kind, .migration, name)
                XCTAssertEqual(result.host, expected["host"] as? String, name)
                XCTAssertEqual(result.dataBase64, expected["dataBase64"] as? String, name)
                continue
            }
            XCTAssertEqual(result.kind, expected["kind"] as? String == "bare-secret" ? .bareSecret : .entry, name)
            XCTAssertEqual(result.type.rawValue.lowercased(), expected["type"] as? String, name)
            XCTAssertEqual(result.issuer, expected["issuer"] as? String, name)
            XCTAssertEqual(result.accountName, expected["accountName"] as? String, name)
            XCTAssertEqual(result.secret.base64EncodedString(), expected["secretBase64"] as? String, name)
            XCTAssertEqual(result.algorithm.rawValue, expected["algorithm"] as? String, name)
            XCTAssertEqual(result.digits, expected["digits"] as? Int, name)
            XCTAssertEqual(result.type == .totp ? result.period : nil, expected["period"] as? Int, name)
            XCTAssertEqual(result.counter, expected["counter"] as? UInt64, name)
            XCTAssertEqual(result.warnings, expected["warnings"] as? [String], name)
        }
    }

    private struct Contract: Decodable {
        struct Fixture: Decodable {
            let file: String
            let format: String
            let password: String?
            let error: String?
            let indexes: [Int]?
            let count: Int?
            let warnings: [String]?
        }

        let version: Int
        let interchange: [Fixture]
    }

    private var contract: Contract {
        get throws {
            let result = try JSONDecoder().decode(Contract.self, from: data("conformance-cases.json"))
            XCTAssertEqual(result.version, 1)
            return result
        }
    }

    func testPublishedInterchangeFiles() throws {
        let cases = try contract.interchange
        let manifest = try XCTUnwrap(try json("fixtures/interoperability/sources.json") as? JSONObject)
        let fixturePaths = try XCTUnwrap(manifest["fixtures"] as? [JSONObject])
        XCTAssertEqual(Set(cases.map(\.file)), Set(fixturePaths.compactMap { $0["file"] as? String }))
        XCTAssertEqual(cases.count, fixturePaths.count)
        for row in cases {
            let bytes = try data("fixtures/interoperability/" + row.file)
            XCTAssertEqual(try Interchange.detect(bytes).rawValue, row.format, row.file)
            if row.password != nil {
                assertInterchangeError("PASSWORD_REQUIRED", [], row.file) { try Interchange.decode(bytes) }
                assertInterchangeError("AUTHENTICATION_FAILED", [], row.file) {
                    try Interchange.decode(bytes, password: "incorrect-public-test")
                }
            }
            if let code = row.error {
                assertInterchangeError(code, row.indexes ?? [], row.file) {
                    try Interchange.decode(bytes, password: row.password)
                }
            } else {
                let imported = try Interchange.decode(bytes, password: row.password)
                XCTAssertEqual(imported.format.rawValue, row.format, row.file)
                XCTAssertEqual(imported.model.items.count, row.count, row.file)
                XCTAssertTrue(Set(row.warnings ?? []).isSubset(of: Set(imported.warningCodes.map(\.rawValue))), row.file)
            }
        }
    }

    func testEncryptedLimitsAndStrictParsing() throws {
        let encrypted = try data("fixtures/interoperability/aegis_encrypted.json")
        var input = try XCTUnwrap(JSONSerialization.jsonObject(with: encrypted) as? JSONObject)
        let original = input
        input["version"] = 999
        let future = try JSONSerialization.data(withJSONObject: input)
        assertInterchangeError("UNSUPPORTED_VERSION", [], "version") {
            try Interchange.decode(future, password: "test")
        }
        input = original
        var header = try XCTUnwrap(input["header"] as? JSONObject)
        var slots = try XCTUnwrap(header["slots"] as? [JSONObject])
        slots[0]["n"] = 1 << 25
        header["slots"] = slots
        input["header"] = header
        let largeKDF = try JSONSerialization.data(withJSONObject: input)
        assertInterchangeError("UNSUPPORTED_KDF", [], "KDF parameters") {
            try Interchange.decode(largeKDF, password: "test")
        }
        assertInterchangeError("FILE_TOO_LARGE", [], "file budget") {
            try Interchange.detect(Data(count: 12 * 1024 * 1024 + 1))
        }
        assertInterchangeError("MALFORMED_FILE", [], "UTF-8") {
            try Interchange.detect(Data([0xc3, 0x28]))
        }
        let deep = String(repeating: "[", count: 40) + String(repeating: "]", count: 40)
        assertInterchangeError("MALFORMED_FILE", [], "JSON depth") {
            try Interchange.detect(Data(deep.utf8))
        }
        let flat = "{\"schemaVersion\":4,\"services\":[],\"extra\":[" + String(repeating: "0,", count: 500_001) + "0]}"
        assertInterchangeError("MALFORMED_FILE", [], "JSON element budget") {
            try Interchange.detect(Data(flat.utf8))
        }
    }

    func testPublishedPlatformNeutralVaultExample() throws {
        let document = try String(decoding: data("vault-format.md"), as: UTF8.self)
        let fileHex = try XCTUnwrap(document.components(separatedBy: "fileHex =\n").dropFirst().first)
            .components(separatedBy: "\n```")[0]
            .filter { !$0.isWhitespace }
        let jsonMarker = "### 6.1 明文 JSON（240 字节）\n\n```json\n"
        let modelSource = try XCTUnwrap(document.components(separatedBy: jsonMarker).dropFirst().first)
            .components(separatedBy: "\n```")[0]
        let fixture = try hex(fileHex)
        XCTAssertEqual(fixture.count, 371)
        let decoded = try VaultFile.open(fixture, password: Self.fixturePassword)
        let expected = try VaultJSON.decode(Data(modelSource.utf8))
        XCTAssertEqual(decoded.0, expected)
        XCTAssertEqual(try VaultFile.open(fixture, deviceCredential: decoded.1.deviceCredential).0, expected)

        let session = try VaultSession.new(password: nil)
        let deviceOnly = try VaultFile.seal(expected, session: session)
        XCTAssertFalse(try VaultFile.hasPassword(deviceOnly))
        XCTAssertEqual(try VaultFile.open(deviceOnly, deviceCredential: session.deviceCredential).0, expected)
        XCTAssertThrowsError(try VaultFile.open(deviceOnly, password: Self.fixturePassword))
        var future = deviceOnly
        future[4] = 2
        XCTAssertThrowsError(try VaultFile.open(future, deviceCredential: session.deviceCredential)) {
            XCTAssertEqual(($0 as? CoreError)?.code, "VLT_E_VERSION")
        }
    }

    func testVaultUpgradeCompatibilityAndAuthenticatedWrites() throws {
        let model = try VaultJSON.decode(data("fixtures/backup-v1.expected.json"))
        let password = "cafe\u{0301}-public-fixture"
        let session = try VaultSession.new(password: password)
        let bytes = try VaultFile.seal(model, session: session)
        XCTAssertTrue(try VaultFile.hasPassword(bytes))
        XCTAssertEqual(try VaultFile.open(bytes, password: "caf\u{00e9}-public-fixture").0, model)
        XCTAssertEqual(try VaultFile.open(bytes, deviceCredential: session.deviceCredential).0, model)
        XCTAssertThrowsError(try VaultFile.open(bytes, password: "incorrect-public-test")) {
            XCTAssertEqual(($0 as? CoreError)?.code, "VLT_E_WRAP_AUTH")
        }
        var damaged = bytes
        damaged[damaged.count - 1] ^= 1
        XCTAssertThrowsError(try VaultFile.open(damaged, deviceCredential: session.deviceCredential)) {
            XCTAssertEqual(($0 as? CoreError)?.code, "VLT_E_VAULT_AUTH")
        }
        var future = try XCTUnwrap(JSONSerialization.jsonObject(with: VaultJSON.encode(model)) as? JSONObject)
        future["schemaVersion"] = 2
        XCTAssertThrowsError(try VaultJSON.decode(JSONSerialization.data(withJSONObject: future)))
    }

    private static let fixturePassword = "correct horse battery staple"

    private func assertInterchangeError<T>(_ code: String, _ indexes: [Int], _ label: String, _ action: () throws -> T) {
        XCTAssertThrowsError(try action(), label) { error in
            XCTAssertEqual((error as? InterchangeError)?.code.rawValue, code, label)
            XCTAssertEqual((error as? InterchangeError)?.entryIndexes, indexes, label)
        }
    }
}
