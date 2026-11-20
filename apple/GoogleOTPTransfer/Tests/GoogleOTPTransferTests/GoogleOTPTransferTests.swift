import Foundation
import XCTest
@testable import GoogleOTPTransfer

final class GoogleOTPTransferTests: XCTestCase {
    func testReadsProtoDefaultsAndSignedBatchID() throws {
        // One entry with bytes "ABC" and name "Lab:example", no enums, batch ID -1.
        let bytes = Data([
            10, 18, 10, 3, 65, 66, 67, 18, 11, 76, 97, 98, 58, 101, 120, 97, 109, 112, 108, 101,
            16, 1, 24, 1, 40, 255, 255, 255, 255, 255, 255, 255, 255, 255, 1
        ])
        let query = bytes.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let page = try GoogleOTPTransferCodec.decode("otpauth-migration://offline?data=\(query)")
        XCTAssertEqual(page.total, 1)
        XCTAssertEqual(page.batchID, -1)
        XCTAssertEqual(page.accounts, [account(name: "example", issuer: "Lab")])
    }

    func testCollectsOutOfOrderPagesWithoutDuplicatingOrMixing() throws {
        let expected = (0..<11).map { account(name: "account\($0)") }
        let pages = try GoogleOTPTransferCodec.encode(expected, batchID: 23).map(GoogleOTPTransferCodec.decode)
        XCTAssertEqual(pages.count, 2)
        var batch = GoogleOTPBatch()
        XCTAssertNil(try batch.add(pages[1]))
        XCTAssertNil(try batch.add(pages[1]))
        XCTAssertEqual(batch.receivedCount, 1)
        let unrelatedURI = try XCTUnwrap(GoogleOTPTransferCodec.encode([account()], batchID: 24).first)
        XCTAssertThrowsError(try batch.add(GoogleOTPTransferCodec.decode(unrelatedURI))) {
            XCTAssertEqual($0 as? GoogleOTPTransferError, .mixedBatch)
        }
        let changed = try GoogleOTPTransferCodec.encode((0..<11).map { account(name: "changed\($0)") }, batchID: 23)
        XCTAssertThrowsError(try batch.add(GoogleOTPTransferCodec.decode(changed[1]))) {
            XCTAssertEqual($0 as? GoogleOTPTransferError, .conflictingPage)
        }
        XCTAssertEqual(try batch.add(pages[0]), expected)
    }

    func testRejectsUnsupportedAndOversizedAccountsAndPreservesLargeCounter() throws {
        var candidate = account()
        candidate.algorithm = .sha256
        XCTAssertEqual(GoogleOTPTransferCodec.exportIssue(candidate), .algorithm)
        candidate.algorithm = .sha1
        candidate.digits = 8
        XCTAssertEqual(GoogleOTPTransferCodec.exportIssue(candidate), .digits)
        candidate.digits = 6
        candidate.period = 45
        XCTAssertEqual(GoogleOTPTransferCodec.exportIssue(candidate), .period)
        candidate.period = 30
        candidate.type = .hotp
        candidate.counter = Int64.max
        let pages = try GoogleOTPTransferCodec.encode([candidate], batchID: 11)
        XCTAssertEqual(try GoogleOTPTransferCodec.decode(XCTUnwrap(pages.first)).accounts, [candidate])
        let long = account(name: String(repeating: "🧪", count: 256), issuer: String(repeating: "🧪", count: 128))
        XCTAssertEqual(GoogleOTPTransferCodec.exportIssue(long), .oversized)
        XCTAssertThrowsError(try GoogleOTPTransferCodec.encode([candidate, long])) {
            XCTAssertEqual($0 as? GoogleOTPTransferError, .incompatibleAccount(2, .oversized))
        }
    }

    func testRejectsMalformedURIAndTruncatedOrOversizedProtobuf() throws {
        let uri = "otpauth-migration://offline?data="
        for malformed in [uri, uri + "%%%%", uri + "CTAB&data=CTAB", uri + "EgQA",
                          uri + Data(repeating: 255, count: 10).base64EncodedString(),
                          uri + String(repeating: "A", count: 16_385)] {
            XCTAssertThrowsError(try GoogleOTPTransferCodec.decode(malformed))
        }
    }

    private func account(name: String = "example", issuer: String? = nil) -> GoogleOTPAccount {
        GoogleOTPAccount(secret: Data("ABC".utf8), name: name, issuer: issuer,
                         algorithm: .sha1, digits: 6, type: .totp)
    }
}
