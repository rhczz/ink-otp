import Foundation
import GoogleOTPTransfer

public extension VaultItem {
    var googleTransferAccount: GoogleOTPAccount {
        let googleAlgorithm: GoogleOTPAlgorithm
        switch algorithm {
        case .sha1: googleAlgorithm = .sha1
        case .sha256: googleAlgorithm = .sha256
        case .sha512: googleAlgorithm = .sha512
        }
        return GoogleOTPAccount(secret: secret, name: accountName, issuer: issuer, algorithm: googleAlgorithm,
                                digits: digits, type: type == .hotp ? .hotp : .totp, period: period, counter: counter)
    }

    static func importingGoogleAccounts(_ accounts: [GoogleOTPAccount]) -> [VaultItem] {
        accounts.enumerated().map { index, account in
            let algorithm: OTPAlgorithm
            switch account.algorithm {
            case .sha1: algorithm = .sha1
            case .sha256: algorithm = .sha256
            case .sha512: algorithm = .sha512
            }
            return .make(type: account.type == .hotp ? .hotp : .totp, issuer: account.issuer,
                         accountName: account.name, secret: account.secret, algorithm: algorithm,
                         digits: account.digits, period: account.period, counter: account.counter,
                         sortIndex: (index + 1) * 1000, origin: "import")
        }
    }
}
