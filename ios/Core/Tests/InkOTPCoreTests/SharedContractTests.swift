import CryptoKit
import Foundation
import XCTest
@testable import InkOTPCore

final class SharedContractTests: XCTestCase {
    private struct TOTPVector: Decodable {
        let time: Int64
        let algorithm: OtpAlgorithm
        let digits: Int
        let period: Int
        let secretBase32: String
        let expected: String
    }

    private struct HOTPVector: Decodable {
        let counter: Int64
        let algorithm: OtpAlgorithm
        let digits: Int
        let secretBase32: String
        let expected: String
    }

    private struct Base32Vector: Decodable {
        let input: String
        let expectedHex: String?
        let expectedBase32: String?
        let expectedError: String?
    }

    private struct BoundaryVector: Decodable {
        struct Request: Decodable {
            let kind: String
            let time: Decimal?
            let counter: Decimal?
            let secretHex: String
            let algorithm: String
            let digits: Int
            let period: Int?
        }

        let description: String
        let request: Request
        let expectedError: String
    }

    private struct Vectors: Decodable {
        let version: Int
        let totp: [TOTPVector]
        let random_totp: [TOTPVector]
        let hotp: [HOTPVector]
        let base32: [Base32Vector]
        let boundary: [BoundaryVector]
    }

    private struct URIExpectation: Decodable {
        let kind: String?
        let type: OtpType?
        let issuer: String?
        let accountName: String?
        let secretBase64: String?
        let algorithm: OtpAlgorithm?
        let digits: Int?
        let period: Int?
        let counter: Int64?
        let warnings: [String]?
        let needsUserInput: [String]?
        let host: String?
        let dataBase64: String?
    }

    private struct URIVector: Decodable {
        let name: String
        let input: String
        let accept: Bool
        let error: String?
        let expected: URIExpectation?
    }

    func testRFCAndGeneratedCodes() throws {
        let vectors = try JSONDecoder().decode(Vectors.self, from: spec("vectors.json"))
        XCTAssertEqual(vectors.version, 1)
        XCTAssertEqual(vectors.totp.count, 18)
        XCTAssertEqual(vectors.random_totp.count, 72)
        XCTAssertEqual(vectors.hotp.count, 10)
        for vector in vectors.totp + vectors.random_totp {
            let code = try OtpEngine.totp(
                secret: Base32.decode(vector.secretBase32),
                epochSeconds: vector.time,
                algorithm: vector.algorithm,
                digits: vector.digits,
                period: vector.period
            )
            XCTAssertEqual(code.value, vector.expected, "\(vector.algorithm) T=\(vector.time)")
            XCTAssertEqual(code.counter, vector.time / Int64(vector.period))
            XCTAssertEqual(code.remainingSeconds, vector.period - Int(vector.time % Int64(vector.period)))
        }
        for vector in vectors.hotp {
            let code = try OtpEngine.hotp(
                secret: Base32.decode(vector.secretBase32),
                counter: vector.counter,
                algorithm: vector.algorithm,
                digits: vector.digits
            )
            XCTAssertEqual(code, vector.expected, "HOTP counter \(vector.counter)")
        }
    }

    func testBase32Contract() throws {
        let cases = try JSONDecoder().decode(Vectors.self, from: spec("vectors.json")).base32
        XCTAssertEqual(cases.count, 29)
        for vector in cases {
            if let code = vector.expectedError {
                XCTAssertThrowsError(try Base32.decode(vector.input)) { error in
                    XCTAssertEqual((error as? Base32Error)?.rawValue, code)
                }
            } else {
                let decoded = try Base32.decode(vector.input)
                XCTAssertEqual(hex(decoded), vector.expectedHex)
                XCTAssertEqual(Base32.encode(decoded), vector.expectedBase32)
            }
        }
    }

    func testOTPBoundaries() throws {
        let cases = try JSONDecoder().decode(Vectors.self, from: spec("vectors.json")).boundary
        XCTAssertEqual(cases.count, 12)
        for vector in cases {
            XCTAssertThrowsError(try evaluate(vector.request), vector.description) { error in
                XCTAssertEqual((error as? OtpError)?.rawValue, vector.expectedError, vector.description)
            }
        }
    }

    func testURIContract() throws {
        let cases = try JSONDecoder().decode([URIVector].self, from: spec("otpauth-cases.json"))
        XCTAssertEqual(cases.count, 40)
        for vector in cases {
            if !vector.accept {
                XCTAssertThrowsError(try OtpauthParser.parse(vector.input), vector.name) { error in
                    XCTAssertEqual((error as? OtpauthError)?.rawValue, vector.error, vector.name)
                }
                continue
            }

            let expected = try XCTUnwrap(vector.expected, vector.name)
            switch try OtpauthParser.parse(vector.input) {
            case .migration(let host, let data):
                XCTAssertEqual(expected.kind, "migration", vector.name)
                XCTAssertEqual(host, expected.host, vector.name)
                XCTAssertEqual(data, expected.dataBase64, vector.name)
            case .bareSecret(let fields):
                XCTAssertEqual(expected.kind, "bare-secret", vector.name)
                XCTAssertEqual(fields.needsUserInput, expected.needsUserInput, vector.name)
                assertFields(fields, expected, label: vector.name)
            case .uri(let fields):
                XCTAssertNil(expected.kind, vector.name)
                assertFields(fields, expected, label: vector.name)
            }
        }
    }

    func testURIProjectionPreservesParametersAndReservedCharacters() throws {
        let item = VaultItem(
            type: .hotp,
            issuer: "代:码 & +",
            accountName: "a+b@example.com:测试",
            secret: Data([0xAB, 0xCD]),
            algorithm: .sha512,
            digits: 8,
            counter: 42
        )
        let result = try OtpauthParser.parse(OtpauthParser.encode(item))
        guard case .uri(let fields) = result else {
            return XCTFail("Expected an OTP URI")
        }
        XCTAssertEqual(fields.type, item.type)
        XCTAssertEqual(fields.issuer, item.issuer)
        XCTAssertEqual(fields.accountName, item.accountName)
        XCTAssertEqual(fields.secret, item.secret)
        XCTAssertEqual(fields.algorithm, item.algorithm)
        XCTAssertEqual(fields.digits, item.digits)
        XCTAssertEqual(fields.counter, item.counter)
    }

    func testVaultSchemaNormalizesAndIgnoresUnknownFields() throws {
        let data = try spec("fixtures/backup-v1.expected.json")
        let expected = try VaultJson.decode(data)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        json["futureField"] = [1, 2]
        XCTAssertEqual(try VaultJson.decode(JSONSerialization.data(withJSONObject: json)), expected)
        XCTAssertEqual(try VaultJson.decode(VaultJson.encode(expected)), expected)
        XCTAssertEqual(expected.items.map(\.accountName), ["me@example.com", "alice@example.com"])

        json["schemaVersion"] = 2
        XCTAssertThrowsError(try VaultJson.decode(JSONSerialization.data(withJSONObject: json))) { error in
            XCTAssertEqual(error as? VaultJsonError, .unsupportedSchema)
        }
    }

    func testVaultTimestampPreservesFractionAndRejectsTrailingInput() throws {
        let timestamp = VaultJson.isoTimestamp(Date(timeIntervalSince1970: 1_500_000_000.125))
        var model = VaultModel(createdAt: timestamp, updatedAt: "2026-11-04T18:00:00+08:00")
        XCTAssertEqual(try VaultJson.validate(model).createdAt, timestamp)
        XCTAssertTrue(timestamp.contains(".125Z"))
        model.updatedAt += "garbage"
        XCTAssertThrowsError(try VaultJson.validate(model)) { error in
            XCTAssertEqual(error as? VaultJsonError, .invalidField("updatedAt"))
        }
    }

    func testVaultRejectsInvalidDataAndDuplicateCanonicalIDs() throws {
        XCTAssertThrowsError(try VaultJson.decode(Data([0xFF, 0xFE]))) { error in
            XCTAssertEqual(error as? VaultJsonError, .invalidUTF8)
        }
        XCTAssertThrowsError(try VaultJson.decode(Data("not json".utf8))) { error in
            XCTAssertEqual(error as? VaultJsonError, .invalidJSON)
        }
        let item = VaultItem(accountName: "Cafe\u{301}\u{001F}", secret: Data([1]))
        var model = VaultModel(items: [item])
        XCTAssertEqual(try VaultJson.validate(model).items.first?.accountName, "Café")

        var duplicate = item
        duplicate.id = item.id.uppercased()
        model.items.append(duplicate)
        XCTAssertThrowsError(try VaultJson.validate(model)) { error in
            XCTAssertEqual(error as? VaultJsonError, .duplicateItemID)
        }
    }

    func testMergePoliciesPreserveSequenceAndConflictEvidence() throws {
        let local = VaultItem(issuer: "Service", accountName: "Alice", secret: Data([1]))
        let remote = VaultItem(issuer: "service", accountName: "Alice", secret: Data([1]), algorithm: .sha256)
        let occupied = VaultItem(accountName: "Alice (2)", secret: Data([2]))
        let localItems = [local, occupied]

        let kept = BackupMerge.merge(localItems: localItems, remoteItems: [remote])
        XCTAssertEqual(kept.conflicts, [MergeConflict(local: local, remote: remote)])
        XCTAssertEqual(kept.items.map(\.id), [local.id, occupied.id])
        let overwritten = BackupMerge.merge(localItems: localItems, remoteItems: [remote], policy: .overwriteLocal)
        XCTAssertEqual(overwritten.items.map(\.id), [remote.id, occupied.id])
        let both = BackupMerge.merge(localItems: localItems, remoteItems: [remote], policy: .keepBoth)
        XCTAssertEqual(both.items.map(\.accountName), ["Alice", "Alice (2)", "Alice (3)"])
        XCTAssertEqual(both.items.map(\.sortIndex), [1000, 2000, 3000])
        XCTAssertNoThrow(try VaultJson.validate(VaultModel(items: both.items)))
    }

    func testReadsIndependentVaultWireExample() throws {
        let format = try XCTUnwrap(String(data: spec("vault-format.md"), encoding: .utf8))
        let hexRange = try XCTUnwrap(format.range(of: "fileHex =\n"))
        let remainder = format[hexRange.upperBound...]
        let end = try XCTUnwrap(remainder.range(of: "\n```"))
        let bytes = try dataFromHex(String(remainder[..<end.lowerBound].filter { !$0.isWhitespace }))
        let header = try VaultCodec.inspect(bytes)
        XCTAssertEqual(header.ciphertextOffset, 115)
        XCTAssertEqual(header.ciphertextLength, 240)
        let opened = try VaultCodec.open(bytes, password: "correct horse battery staple")
        XCTAssertEqual(opened.model.vaultId, "6f1c2a6e-6f4d-4c1b-9d4e-2a7f0f3f9b21")
        XCTAssertEqual(opened.model.items, [])
        XCTAssertEqual(try VaultCodec.open(bytes, key: opened.key), opened.model)
        XCTAssertThrowsError(try VaultCodec.open(bytes, password: "wrong fixture password")) { error in
            XCTAssertEqual(error as? VaultWireError, .wrapAuthentication)
        }
    }

    func testPasswordlessVaultHasShortHeaderAndRejectsTampering() throws {
        let fixture = try VaultJson.decode(spec("fixtures/backup-v1.expected.json"))
        let key = SymmetricKey(size: .bits256)
        let bytes = try VaultCodec.seal(fixture, key: key)
        let header = try VaultCodec.inspect(bytes)
        XCTAssertFalse(header.hasPassword)
        XCTAssertEqual(header.ciphertextOffset, 67)
        XCTAssertEqual(bytes.count, 67 + header.ciphertextLength + 16)
        XCTAssertEqual(try VaultCodec.open(bytes, key: key), fixture)

        var corrupted = bytes
        corrupted[corrupted.startIndex + 47] ^= 1
        XCTAssertThrowsError(try VaultCodec.open(corrupted, key: key)) { error in
            XCTAssertEqual(error as? VaultWireError, .vaultAuthentication)
        }
        XCTAssertThrowsError(try VaultCodec.inspect(bytes + Data([0]))) { error in
            XCTAssertEqual(error as? VaultWireError, .trailingData)
        }
        var incompatible = bytes
        incompatible[incompatible.startIndex + 4] = 2
        XCTAssertThrowsError(try VaultCodec.inspect(incompatible)) { error in
            XCTAssertEqual(error as? VaultWireError, .version)
        }
    }

    private func assertFields(_ fields: OtpFields, _ expected: URIExpectation, label: String) {
        XCTAssertEqual(fields.type, expected.type, label)
        XCTAssertEqual(fields.issuer, expected.issuer, label)
        XCTAssertEqual(fields.accountName, expected.accountName, label)
        XCTAssertEqual(fields.secret.base64EncodedString(), expected.secretBase64, label)
        XCTAssertEqual(fields.algorithm, expected.algorithm, label)
        XCTAssertEqual(fields.digits, expected.digits, label)
        XCTAssertEqual(fields.period, expected.period, label)
        XCTAssertEqual(fields.counter, expected.counter, label)
        XCTAssertEqual(fields.warnings, expected.warnings, label)
    }

    private func evaluate(_ request: BoundaryVector.Request) throws {
        let algorithm = try OtpAlgorithm.specName(request.algorithm)
        let secret = try dataFromHex(request.secretHex)
        switch request.kind {
        case "hotp":
            let counter = try XCTUnwrap(request.counter)
            guard counter >= 0 else { throw OtpError.counterNegative }
            guard let value = Int64(NSDecimalNumber(decimal: counter).stringValue) else {
                throw OtpError.counterOverflow
            }
            _ = try OtpEngine.hotp(secret: secret, counter: value, algorithm: algorithm, digits: request.digits)
        case "totp":
            let time = try XCTUnwrap(request.time)
            guard let value = Int64(NSDecimalNumber(decimal: time).stringValue) else {
                throw OtpError.timeOutOfRange
            }
            _ = try OtpEngine.totp(
                secret: secret,
                epochSeconds: value,
                algorithm: algorithm,
                digits: request.digits,
                period: try XCTUnwrap(request.period)
            )
        default:
            throw OtpError.kindUnsupported
        }
    }

    private func spec(_ path: String) throws -> Data {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        return try Data(contentsOf: root.appendingPathComponent("spec").appendingPathComponent(path))
    }

    private func hex(_ bytes: Data) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    private func dataFromHex(_ hex: String) throws -> Data {
        XCTAssertTrue(hex.count.isMultiple(of: 2))
        let chars = Array(hex)
        return try Data(stride(from: 0, to: chars.count, by: 2).map {
            try XCTUnwrap(UInt8(String(chars[$0...($0 + 1)]), radix: 16))
        })
    }
}

final class ClockDriftTests: XCTestCase {
    func testDriftUsesMonotonicElapsedTimeAndResetsAnchor() {
        var detector = ClockDriftDetector()
        let wall = Date(timeIntervalSince1970: 100)
        let monotonic = ContinuousClock().now
        XCTAssertFalse(detector.sample(wall: wall, monotonic: monotonic).drifted)
        XCTAssertFalse(detector.sample(
            wall: wall.addingTimeInterval(12),
            monotonic: monotonic.advanced(by: .seconds(10))
        ).drifted, "The Android threshold triggers only above two seconds")
        let jump = detector.sample(
            wall: wall.addingTimeInterval(7),
            monotonic: monotonic.advanced(by: .seconds(10))
        )
        XCTAssertTrue(jump.drifted)
        XCTAssertEqual(jump.wallDelta, 7)
        XCTAssertEqual(jump.monotonicDelta, 10)
        detector.reset()
        XCTAssertFalse(detector.sample(wall: .now, monotonic: monotonic).drifted)
    }
}
