import CryptoKit
import Foundation

public enum MergeConflictPolicy: CaseIterable, Sendable {
    case keepLocal
    case overwriteLocal
    case keepBoth
}

public struct MergeConflict: Equatable, Sendable {
    public let local: VaultItem
    public let remote: VaultItem
}

public struct MergeResult: Equatable, Sendable {
    public let items: [VaultItem]
    public let conflicts: [MergeConflict]
}

public enum BackupMerge {
    public struct ItemKey: Hashable, Sendable {
        let type: OtpType
        let issuer: String
        let account: String
        let secretHash: SHA256.Digest
    }

    public static func keyOf(_ item: VaultItem) -> ItemKey {
        ItemKey(
            type: item.type,
            issuer: (item.issuer ?? "").lowercased(),
            account: item.accountName.lowercased(),
            secretHash: SHA256.hash(data: item.secret)
        )
    }

    public static func merge(
        localItems: [VaultItem],
        remoteItems: [VaultItem],
        policy: MergeConflictPolicy = .keepLocal
    ) -> MergeResult {
        var result = localItems
        var firstIndexByKey: [ItemKey: Int] = [:]
        var usedNames = Set<String>()
        for (index, item) in result.enumerated() {
            let key = keyOf(item)
            if firstIndexByKey[key] == nil { firstIndexByKey[key] = index }
            usedNames.insert(item.accountName)
        }

        var conflicts: [MergeConflict] = []
        var nextSuffix: [String: Int] = [:]
        for remote in remoteItems {
            let key = keyOf(remote)
            guard let index = firstIndexByKey[key] else {
                firstIndexByKey[key] = result.count
                result.append(remote)
                usedNames.insert(remote.accountName)
                continue
            }

            let local = result[index]
            conflicts.append(MergeConflict(local: local, remote: remote))
            switch policy {
            case .keepLocal:
                break
            case .overwriteLocal:
                result[index] = remote
            case .keepBoth:
                var unique = remote
                var suffix = nextSuffix[remote.accountName, default: 2]
                var candidate = "\(remote.accountName) (\(suffix))"
                while usedNames.contains(candidate) {
                    suffix += 1
                    candidate = "\(remote.accountName) (\(suffix))"
                }
                nextSuffix[remote.accountName] = suffix + 1
                unique.accountName = candidate
                firstIndexByKey[keyOf(unique)] = result.count
                usedNames.insert(candidate)
                result.append(unique)
            }
        }

        for index in result.indices {
            result[index].sortIndex = (index + 1) * 1000
        }
        return MergeResult(items: result, conflicts: conflicts)
    }
}
