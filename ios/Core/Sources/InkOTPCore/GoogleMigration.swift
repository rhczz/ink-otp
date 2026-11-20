import Foundation
import GoogleOTPTransfer

public struct GoogleMigrationImport {
    private var batch = GoogleOTPBatch()

    public init() {}

    public var receivedCount: Int { batch.receivedCount }
    public var totalCount: Int? { batch.totalCount }

    public mutating func append(_ uri: String) throws -> [VaultItem]? {
        do {
            return try batch.add(GoogleOTPTransferCodec.decode(uri))?.enumerated().map { index, account in
                VaultItem(type: account.type == .hotp ? .hotp : .totp, issuer: account.issuer,
                          accountName: account.name, secret: account.secret, algorithm: algorithm(account.algorithm),
                          digits: account.digits, period: account.period, counter: account.counter,
                          sortIndex: (index + 1) * 1000, origin: "import")
            }
        } catch let failure as GoogleOTPTransferError {
            throw GoogleMigrationError(failure)
        }
    }

    private func algorithm(_ value: GoogleOTPAlgorithm) -> OtpAlgorithm {
        switch value {
        case .sha1: .sha1
        case .sha256: .sha256
        case .sha512: .sha512
        }
    }
}

public struct GoogleMigrationExport: Sendable {
    public struct Omission: Identifiable, Sendable {
        public let id: String
        public let name: String
        public let reason: String
    }

    public let pages: [String]
    public let supportedCount: Int
    public let omitted: [Omission]

    public static func prepare(_ items: [VaultItem]) throws -> Self {
        var compatible: [GoogleOTPAccount] = []
        var omitted: [Omission] = []
        for item in items.sorted(by: { ($0.sortIndex, $0.id) < ($1.sortIndex, $1.id) }) {
            let account = GoogleOTPAccount(
                secret: item.secret, name: item.accountName, issuer: item.issuer,
                algorithm: googleAlgorithm(item.algorithm), digits: item.digits,
                type: item.type == .totp ? .totp : .hotp, period: item.period, counter: item.counter
            )
            let reason = GoogleOTPTransferCodec.exportIssue(account).map(issueMessage)
                ?? (compatible.count == GoogleOTPTransferCodec.maxAccounts
                    ? migrationText("单批最多迁移 500 个账号。", "A transfer can include at most 500 accounts.") : nil)
            if let reason {
                let label = item.issuer.map { "\($0) · \(item.accountName)" } ?? item.accountName
                omitted.append(Omission(id: item.id, name: label, reason: reason))
            } else {
                compatible.append(account)
            }
        }
        do {
            let pages = compatible.isEmpty ? [] : try GoogleOTPTransferCodec.encode(compatible)
            return Self(pages: pages, supportedCount: compatible.count, omitted: omitted)
        } catch let failure as GoogleOTPTransferError {
            throw GoogleMigrationError(failure)
        }
    }

    private static func googleAlgorithm(_ algorithm: OtpAlgorithm) -> GoogleOTPAlgorithm {
        switch algorithm {
        case .sha1: .sha1
        case .sha256: .sha256
        case .sha512: .sha512
        }
    }

    private static func issueMessage(_ issue: GoogleOTPExportIssue) -> String {
        switch issue {
        case .algorithm: migrationText("算法必须为 SHA1。", "The algorithm must be SHA1.")
        case .digits: migrationText("验证码必须为 6 位。", "Codes must use 6 digits.")
        case .period: migrationText("TOTP 周期必须为 30 秒。", "TOTP must use a 30-second period.")
        case .counter: migrationText("计数器不受支持。", "The counter is unsupported.")
        case .accountName: migrationText("账号名称无法原样迁移。", "The account label would change.")
        case .secret: migrationText("密钥长度不受支持。", "The key length is unsupported.")
        case .oversized: migrationText("账号无法放入一张可扫描的二维码。", "The account exceeds the QR code size limit.")
        }
    }
}

public struct GoogleMigrationError: LocalizedError, Sendable {
    private let cause: GoogleOTPTransferError

    fileprivate init(_ cause: GoogleOTPTransferError) { self.cause = cause }

    public var errorDescription: String? {
        switch cause {
        case .invalidURI, .malformed:
            migrationText("Google Authenticator 二维码无效或内容损坏。", "This Google Authenticator QR code is invalid or damaged.")
        case .unsupportedVersion:
            migrationText("此 Google Authenticator 迁移版本暂不支持。", "This Google Authenticator transfer version is unsupported.")
        case .unsupportedAccount(let index), .incompatibleAccount(let index, _):
            migrationText("二维码中第 \(index) 个账号的数据不受支持。", "The QR code contains an unsupported account at position \(index).")
        case .mixedBatch:
            migrationText("请继续扫描同一次导出生成的二维码。", "Scan a QR code from the same Google Authenticator export.")
        case .conflictingPage:
            migrationText("同一页的二维码内容冲突，请核对后重扫。", "This page conflicts with one already scanned. Check the export and scan it again.")
        case .limitExceeded:
            migrationText("迁移内容过多，请分批操作。", "This transfer is too large. Use smaller batches.")
        }
    }
}

public func isGoogleMigrationURI(_ input: String) -> Bool {
    GoogleOTPTransferCodec.isMigrationURI(input)
}

private func migrationText(_ chinese: String, _ english: String) -> String {
    Locale.preferredLanguages.first?.lowercased().hasPrefix("zh") == true ? chinese : english
}
