import Foundation
import LocalAuthentication
import Security

protocol VaultKeyStore: Sendable {
    func insert(_ key: Data, id: UUID, requiresAuthentication: Bool) throws
    func read(id: UUID, context: LAContext) throws -> Data
    func delete(id: UUID) throws
    func deleteAll() throws
}

struct DeviceKeychain: VaultKeyStore {
    let service = "io.github.rhczz.inkotp.vault.v1"

    func insert(_ key: Data, id: UUID, requiresAuthentication: Bool) throws {
        var query = item(id: id)
        query[kSecValueData as String] = key
        if requiresAuthentication {
            guard let control = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .userPresence, nil
            ) else { throw VaultFailure.deviceKeyUnavailable }
            query[kSecAttrAccessControl as String] = control
        } else {
            query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        }
        try check(SecItemAdd(query as CFDictionary, nil))
    }

    func read(id: UUID, context: LAContext) throws -> Data {
        var query = item(id: id)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecUseAuthenticationContext as String] = context
        var result: CFTypeRef?
        try check(SecItemCopyMatching(query as CFDictionary, &result))
        guard let data = result as? Data, (data.count == 32 || data.count == 64) else {
            throw VaultFailure.deviceKeyUnavailable
        }
        return data
    }

    func delete(id: UUID) throws {
        let status = SecItemDelete(item(id: id) as CFDictionary)
        if status != errSecItemNotFound { try check(status) }
    }

    func deleteAll() throws {
        let status = SecItemDelete(item(id: nil) as CFDictionary)
        if status != errSecItemNotFound { try check(status) }
    }

    private func item(id: UUID?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false
        ]
        if let id { query[kSecAttrAccount as String] = id.uuidString }
        return query
    }

    private func check(_ status: OSStatus) throws {
        switch status {
        case errSecSuccess: return
        case errSecUserCanceled: throw VaultFailure.cancelled
        case errSecAuthFailed: throw VaultFailure.systemAuthenticationFailed
        case errSecItemNotFound: throw VaultFailure.deviceKeyUnavailable
        default: throw VaultFailure.deviceKeyUnavailable
        }
    }
}
