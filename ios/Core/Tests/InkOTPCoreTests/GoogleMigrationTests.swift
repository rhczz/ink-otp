import Foundation
import GoogleOTPTransfer
import XCTest
@testable import InkOTPCore

final class GoogleMigrationTests: XCTestCase {
    private struct Fixture: Decodable {
        struct Row: Decodable {
            let name: String
            let uri: String
            let expected: Page?
        }

        struct Page: Decodable {
            let version: Int
            let batchId: Int32
            let batchSize: Int
            let batchIndex: Int
            let entries: [Entry]
        }

        struct Entry: Decodable {
            let issuer: String?
            let accountName: String
            let secretBase64: String
            let algorithm: String
            let digits: Int
            let type: String
            let period: Int?
            let counter: Int64?
        }

        let cases: [Row]
    }

    func testGoogleAuthenticatorPublicTransferFixture() throws {
        let repository = (0..<5).reduce(URL(fileURLWithPath: #filePath)) { url, _ in
            url.deletingLastPathComponent()
        }
        let file = repository.appendingPathComponent("spec/fixtures/google-authenticator-v1.json")
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: file))
        XCTAssertFalse(fixture.cases.isEmpty)
        for row in fixture.cases {
            guard let expected = row.expected else {
                XCTAssertThrowsError(try GoogleOTPTransferCodec.decode(row.uri), row.name)
                continue
            }
            let actual = try GoogleOTPTransferCodec.decode(row.uri)
            XCTAssertEqual(actual.version, expected.version, row.name)
            XCTAssertEqual(actual.batchID, expected.batchId, row.name)
            XCTAssertEqual(actual.total, expected.batchSize, row.name)
            XCTAssertEqual(actual.index, expected.batchIndex, row.name)
            XCTAssertEqual(actual.accounts.count, expected.entries.count, row.name)
            for (entry, reference) in zip(actual.accounts, expected.entries) {
                XCTAssertEqual(entry.name, reference.accountName, row.name)
                XCTAssertEqual(entry.issuer, reference.issuer, row.name)
                XCTAssertEqual(entry.secret.base64EncodedString(), reference.secretBase64, row.name)
                XCTAssertEqual(String(describing: entry.algorithm).uppercased(), reference.algorithm.uppercased(), row.name)
                XCTAssertEqual(entry.digits, reference.digits, row.name)
                XCTAssertEqual(String(describing: entry.type).lowercased(), reference.type.lowercased(), row.name)
                let period: Int? = entry.type == GoogleOTPType.hotp ? nil : entry.period
                XCTAssertEqual(period, reference.period, row.name)
                XCTAssertEqual(entry.counter, reference.counter, row.name)
            }
        }
    }

    func testTransferRequiresExplicitSubsetAndDoesNotChangeOTPParameters() throws {
        let compatible = VaultItem(issuer: "Lab", accountName: "example", secret: Data([65, 66, 67]))
        var incompatible = compatible
        incompatible.id = UUID().uuidString.lowercased()
        incompatible.period = 60
        let plan = try GoogleMigrationExport.prepare([compatible, incompatible])
        XCTAssertEqual(plan.supportedCount, 1)
        XCTAssertEqual(plan.omitted.map(\.id), [incompatible.id])
        let uri = try XCTUnwrap(plan.pages.first)
        var batch = GoogleMigrationImport()
        let items = try XCTUnwrap(batch.append(uri))
        let imported = try XCTUnwrap(items.first)
        XCTAssertEqual(imported.issuer, compatible.issuer)
        XCTAssertEqual(imported.accountName, compatible.accountName)
        XCTAssertEqual(imported.secret, compatible.secret)
        XCTAssertEqual(imported.period, compatible.period)
    }
}
