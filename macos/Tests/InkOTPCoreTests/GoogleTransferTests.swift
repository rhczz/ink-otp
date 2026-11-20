import Foundation
import GoogleOTPTransfer
import InkOTPCore
import XCTest

final class GoogleTransferTests: XCTestCase {
    func testPublishedTransferPagesPreserveMacVaultFields() throws {
        let spec = ProcessInfo.processInfo.environment["INKOTP_TEST_SPEC"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? (0..<4).reduce(URL(fileURLWithPath: #filePath)) { url, _ in url.deletingLastPathComponent() }
                .appendingPathComponent("spec", isDirectory: true)
        let bytes = try Data(contentsOf: spec.appendingPathComponent("fixtures/google-authenticator-v1.json"))
        let fixture = try JSONDecoder().decode(Contract.self, from: bytes)
        XCTAssertFalse(fixture.cases.isEmpty)
        for sample in fixture.cases {
            guard let expected = sample.expected else {
                XCTAssertThrowsError(try GoogleOTPTransferCodec.decode(sample.uri), sample.name)
                continue
            }
            let decoded = try GoogleOTPTransferCodec.decode(sample.uri)
            XCTAssertEqual(decoded.version, expected.version, sample.name)
            XCTAssertEqual(decoded.batchID, expected.batchId, sample.name)
            XCTAssertEqual(decoded.total, expected.batchSize, sample.name)
            XCTAssertEqual(decoded.index, expected.batchIndex, sample.name)
            let projected = VaultItem.importingGoogleAccounts(decoded.accounts)
            XCTAssertEqual(projected.map(Profile.init), expected.entries, sample.name)
            XCTAssertEqual(projected.map(\.googleTransferAccount), decoded.accounts, sample.name)
        }
    }

    func testCompatibleExportSubsetPreservesHOTPAndIdentifiesTOTPPeriod() throws {
        let secret = Data("12345678901234567890".utf8)
        let counter = VaultItem.make(type: .hotp, issuer: "Public Import", accountName: "history@example.test",
                                    secret: secret, counter: Int64.max).googleTransferAccount
        let period = VaultItem.make(accountName: "short-period@example.test", secret: secret, period: 10).googleTransferAccount
        XCTAssertNil(GoogleOTPTransferCodec.exportIssue(counter))
        XCTAssertEqual(GoogleOTPTransferCodec.exportIssue(period), .period)
        XCTAssertThrowsError(try GoogleOTPTransferCodec.encode([counter, period], batchID: 34))
        let checked = [counter, period].filter { GoogleOTPTransferCodec.exportIssue($0) == nil }
        let pages = try GoogleOTPTransferCodec.encode(checked, batchID: 34)
        XCTAssertEqual(pages.count, 1)
        let decoded = try GoogleOTPTransferCodec.decode(try XCTUnwrap(pages.first))
        let items = VaultItem.importingGoogleAccounts(decoded.accounts)
        XCTAssertEqual(items.map(\.counter), [Int64.max])
        XCTAssertEqual(items.map(\.type), [.hotp])
        XCTAssertEqual(items.map(\.accountName), ["history@example.test"])
    }

    private struct Contract: Decodable {
        let cases: [Sample]
    }

    private struct Sample: Decodable {
        let name: String
        let uri: String
        let expected: Expected?
    }

    private struct Expected: Decodable {
        let version: Int
        let batchId: Int32
        let batchSize: Int
        let batchIndex: Int
        let entries: [Profile]
    }

    private struct Profile: Decodable, Equatable, CustomStringConvertible {
        let issuer: String?
        let accountName: String
        let secretBase64: Data
        let algorithm: String
        let digits: Int
        let type: String
        let period: Int?
        let counter: Int64?

        var description: String { "Public transfer profile for \(accountName)" }

        init(_ item: VaultItem) {
            issuer = item.issuer
            accountName = item.accountName
            secretBase64 = item.secret
            algorithm = item.algorithm.rawValue
            digits = item.digits
            type = item.type.rawValue
            period = item.type == .totp ? item.period : nil
            counter = item.counter
        }
    }
}
