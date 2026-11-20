import Foundation
import InkOTPCore
import XCTest

final class PublicConformanceTests: XCTestCase {
    private struct Contract: Decodable {
        struct InterchangeCase: Decodable {
            let file: String
            let format: String
            let password: String?
            let error: String?
            let indexes: [Int]?
            let count: Int?
            let warnings: [String]?
        }

        let version: Int
        let interchange: [InterchangeCase]
    }

    private struct InterchangeManifest: Decodable {
        struct Fixture: Decodable {
            let file: String
            let password: String?
        }

        let fixtures: [Fixture]
    }

    func testPublicInterchangeConformance() throws {
        let cases = try readContract().interchange
        let manifest = try JSONDecoder().decode(
            InterchangeManifest.self,
            from: spec("fixtures/interoperability/sources.json")
        )
        XCTAssertFalse(cases.isEmpty)
        XCTAssertEqual(Set(cases.map(\.file)), Set(manifest.fixtures.map(\.file)))
        XCTAssertEqual(Set(cases.map(\.file)).count, cases.count)
        XCTAssertEqual(cases.count, manifest.fixtures.count)

        for row in cases {
            let source = try XCTUnwrap(manifest.fixtures.first { $0.file == row.file }, row.file)
            XCTAssertEqual(row.password, source.password, row.file)
            let data = try spec("fixtures/interoperability/" + row.file)
            XCTAssertEqual(try InterchangeCodec.detect(data).rawValue, row.format, row.file)
            if row.password != nil {
                assertInterchangeError("PASSWORD_REQUIRED", indexes: [], file: row.file) {
                    try InterchangeCodec.decode(data)
                }
                assertInterchangeError("AUTHENTICATION_FAILED", indexes: [], file: row.file) {
                    try InterchangeCodec.decode(data, password: "incorrect-public-test")
                }
            }

            if let error = row.error {
                let indexes = try XCTUnwrap(row.indexes, row.file)
                assertInterchangeError(error, indexes: indexes, file: row.file) {
                    try InterchangeCodec.decode(data, password: row.password)
                }
            } else {
                let imported = try InterchangeCodec.decode(data, password: row.password)
                let count = try XCTUnwrap(row.count, row.file)
                XCTAssertEqual(imported.format.rawValue, row.format, row.file)
                XCTAssertEqual(imported.model.items.count, count, row.file)
                let warningCodes = Set(imported.warnings.map(\.code.rawValue))
                XCTAssertTrue(Set(row.warnings ?? []).isSubset(of: warningCodes), row.file)
            }
        }
    }

    private func assertInterchangeError(
        _ expected: String,
        indexes: [Int],
        file: String,
        decode: () throws -> ImportedVault
    ) {
        XCTAssertThrowsError(try decode(), file) { error in
            let failure = error as? InterchangeFailure
            XCTAssertEqual(failure?.code.rawValue, expected, file)
            XCTAssertEqual(failure?.entryIndexes, indexes, file)
        }
    }

    private func readContract() throws -> Contract {
        let contract = try JSONDecoder().decode(Contract.self, from: spec("conformance-cases.json"))
        XCTAssertEqual(contract.version, 1)
        return contract
    }

    private func spec(_ path: String) throws -> Data {
        let directory: URL
        if let explicit = ProcessInfo.processInfo.environment["INKOTP_TEST_SPEC"] {
            directory = URL(fileURLWithPath: explicit, isDirectory: true)
        } else {
            let repository = (0..<5).reduce(URL(fileURLWithPath: #filePath)) { url, _ in
                url.deletingLastPathComponent()
            }
            directory = repository.appendingPathComponent("spec", isDirectory: true)
        }
        return try Data(contentsOf: directory.appendingPathComponent(path))
    }
}
