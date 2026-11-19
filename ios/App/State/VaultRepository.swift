import CryptoKit
import Foundation
import InkOTPCore
import LocalAuthentication

enum VaultFailure: String, Error {
    case cancelled = "Operation cancelled."
    case unreadable = "The vault could not be read."
    case unsupportedVersion = "This vault requires a newer version of InkOTP."
    case deviceKeyUnavailable = "The device key is unavailable."
    case systemAuthenticationFailed = "Device authentication failed."
    case systemAuthenticationRequired = "Complete device authentication first."
    case recoveryNotSet = "Set a recovery password first."
    case badPassword = "Incorrect recovery password."
    case passwordShort = "Use at least 8 characters."
    case passwordLong = "Use at most 1024 characters."
    case passwordNumeric = "Use a password that contains more than digits."
    case passwordCommon = "Choose a less common recovery password."
    case noSession = "Unlock the vault first."
    case stale = "The vault changed. Review the import again."
    case duplicateAccount = "An account with the same service, name, and key already exists."
    case exists = "A vault already exists."
}

struct VaultMetadata: Equatable, Sendable {
    let recoveryConfigured: Bool
    let systemAuthenticationEnabled: Bool
}

struct VaultSession: Sendable {
    let key: SymmetricKey
    var recovery: VaultCodec.Recovery?
    var model: VaultModel
}

actor VaultRepository {
    private static let maxStoredBytes = 18 * 1024 * 1024

    private struct File: Codable {
        let version: Int
        let keyID: UUID
        let systemAuthentication: Bool
        let vault: Data
        let authentication: Data

        var authenticatedMetadata: Data {
            Data("InkOTP/iOS/vault/v1\u{0}\(keyID.uuidString)\u{0}\(systemAuthentication)\u{0}".utf8)
                + Data(SHA256.hash(data: vault))
        }
    }

    private let url: URL
    private let keys: any VaultKeyStore

    init(url: URL, keys: any VaultKeyStore = DeviceKeychain()) {
        self.url = url
        self.keys = keys
    }

    func metadata() throws -> VaultMetadata? {
        guard let file = try readIfPresent() else { return nil }
        return .init(recoveryConfigured: try VaultCodec.inspect(file.vault).hasPassword,
                     systemAuthenticationEnabled: file.systemAuthentication)
    }

    func byteCount() -> Int? {
        try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize
    }

    func create(model: VaultModel, password: String?) throws -> VaultSession {
        guard try readIfPresent() == nil else { throw VaultFailure.exists }
        let session = try newSession(model: model, password: password)
        // iOS can retain a Keychain item after uninstall. A missing vault never reuses it.
        try keys.deleteAll()
        try replaceKey(for: session, systemAuthentication: false, oldKeyID: nil)
        return session
    }

    func open(context: LAContext?, password: String? = nil, authorized: Bool = false) throws -> VaultSession {
        let file = try read()
        if file.systemAuthentication && !authorized { throw VaultFailure.systemAuthenticationRequired }
        if let password {
            let opened = try openWithPassword(file, password: password)
            let session = VaultSession(key: opened.key, recovery: opened.recovery, model: opened.model)
            try replaceKey(for: session, systemAuthentication: file.systemAuthentication, oldKeyID: file.keyID)
            return session
        }
        guard let context else { throw VaultFailure.systemAuthenticationRequired }
        var rawKeys = try keys.read(id: file.keyID, context: context)
        defer { rawKeys.resetBytes(in: rawKeys.indices) }
        let header = try VaultCodec.inspect(file.vault)
        guard rawKeys.count == (header.hasPassword ? 64 : 32) else { throw VaultFailure.deviceKeyUnavailable }
        let key = SymmetricKey(data: rawKeys.prefix(32))
        try authenticateMetadata(file, key: key)
        let recovery = header.parameters.map {
            VaultCodec.Recovery(key: SymmetricKey(data: rawKeys.suffix(32)), salt: header.salt, parameters: $0)
        }
        let model = try VaultCodec.open(file.vault, key: key)
        return VaultSession(key: key, recovery: recovery, model: model)
    }

    func save(_ session: VaultSession) throws -> VaultSession {
        let file = try read()
        try authenticateMetadata(file, key: session.key)
        var validated = session
        validated.model = try VaultJson.validate(session.model)
        try write(seal(validated, keyID: file.keyID, systemAuthentication: file.systemAuthentication))
        return validated
    }

    func setRecovery(_ password: String, current: String?, session: VaultSession, authorized: Bool) throws -> VaultSession {
        let file = try read()
        try authenticateMetadata(file, key: session.key)
        if file.systemAuthentication && !authorized { throw VaultFailure.systemAuthenticationRequired }
        if session.recovery != nil {
            guard let current, try openWithPassword(file, password: current).key == session.key else {
                throw VaultFailure.badPassword
            }
        }
        try Self.validateRecovery(password)
        var changed = session
        changed.recovery = try VaultCodec.makeRecovery(password: password)
        try replaceKey(for: changed, systemAuthentication: file.systemAuthentication, oldKeyID: file.keyID)
        return changed
    }

    func clearRecovery(session: VaultSession, authorized: Bool) throws -> VaultSession {
        let file = try read()
        try authenticateMetadata(file, key: session.key)
        guard authorized, !file.systemAuthentication else { throw VaultFailure.systemAuthenticationRequired }
        var changed = session
        changed.recovery = nil
        try replaceKey(for: changed, systemAuthentication: false, oldKeyID: file.keyID)
        return changed
    }

    func setSystemAuthentication(_ enabled: Bool, session: VaultSession, authorized: Bool) throws {
        guard authorized else { throw VaultFailure.systemAuthenticationRequired }
        let file = try read()
        try authenticateMetadata(file, key: session.key)
        guard file.systemAuthentication != enabled else { return }
        if enabled && session.recovery == nil { throw VaultFailure.recoveryNotSet }
        try replaceKey(for: session, systemAuthentication: enabled, oldKeyID: file.keyID)
    }

    func export(_ session: VaultSession, format: InterchangeFormat, password: String?) throws -> ExportedVault {
        let file = try read()
        guard let password, try openWithPassword(file, password: password).key == session.key else {
            throw VaultFailure.badPassword
        }
        return try InterchangeCodec.encode(
            session.model, format: format,
            password: format.requiresPassword ? password.precomposedStringWithCanonicalMapping : nil
        )
    }

    func decodeImport(_ data: Data, password: String?) throws -> ImportedVault {
        try InterchangeCodec.decode(data, password: password)
    }

    func restore(model: VaultModel, password: String, authorized: Bool) throws -> VaultSession {
        let exists = try hasStorageFile()
        if exists && !authorized { throw VaultFailure.systemAuthenticationRequired }
        let previous = try? readIfPresent()
        let requireAuthentication = previous?.systemAuthentication ?? exists
        let session = try newSession(model: model, password: password)
        if exists {
            try preservePreviousVault()
        } else {
            try keys.deleteAll()
        }
        try replaceKey(for: session, systemAuthentication: requireAuthentication, oldKeyID: previous?.keyID)
        return session
    }

    func erase() throws {
        if FileManager.default.fileExists(atPath: previousVaultURL.path) {
            try FileManager.default.removeItem(at: previousVaultURL)
        }
        if try hasStorageFile() { try FileManager.default.removeItem(at: url) }
        try keys.deleteAll()
    }

    private var previousVaultURL: URL {
        url.deletingLastPathComponent().appendingPathComponent("vault-before-restore.json")
    }

    private func preservePreviousVault() throws {
        guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size <= Self.maxStoredBytes else { throw VaultFailure.unreadable }
        let bytes = try Data(contentsOf: url)
        guard bytes.count <= Self.maxStoredBytes else { throw VaultFailure.unreadable }
        try bytes.write(to: previousVaultURL, options: [.atomic, .completeFileProtection])
    }

    private func newSession(model: VaultModel, password: String?) throws -> VaultSession {
        let validated = try VaultJson.validate(model)
        let recovery = try password.map { password in
            try Self.validateRecovery(password)
            return try VaultCodec.makeRecovery(password: password)
        }
        return VaultSession(key: SymmetricKey(size: .bits256), recovery: recovery, model: validated)
    }

    private func replaceKey(for session: VaultSession, systemAuthentication: Bool, oldKeyID: UUID?) throws {
        let identifier = UUID()
        var rawKeys = session.key.withUnsafeBytes { Data($0) }
        if let recovery = session.recovery { recovery.key.withUnsafeBytes { rawKeys.append(contentsOf: $0) } }
        defer { rawKeys.resetBytes(in: rawKeys.indices) }
        let sealed = try seal(session, keyID: identifier, systemAuthentication: systemAuthentication)
        try keys.insert(rawKeys, id: identifier, requiresAuthentication: systemAuthentication)
        do { try write(sealed) }
        catch {
            try? keys.delete(id: identifier)
            throw error
        }
        if let oldKeyID { try? keys.delete(id: oldKeyID) }
    }

    private func openWithPassword(_ file: File, password: String) throws -> VaultCodec.Opened {
        guard password.utf16.count <= 1024 else { throw VaultFailure.badPassword }
        guard try VaultCodec.inspect(file.vault).hasPassword else { throw VaultFailure.recoveryNotSet }
        do {
            let opened = try VaultCodec.open(file.vault, password: password)
            try authenticateMetadata(file, key: opened.key)
            return opened
        } catch VaultWireError.wrapAuthentication { throw VaultFailure.badPassword }
    }

    private func seal(_ session: VaultSession, keyID: UUID, systemAuthentication: Bool) throws -> File {
        let bytes = try VaultCodec.seal(session.model, key: session.key, recovery: session.recovery)
        let file = File(version: 1, keyID: keyID, systemAuthentication: systemAuthentication, vault: bytes, authentication: Data())
        let tag = HMAC<SHA256>.authenticationCode(for: file.authenticatedMetadata, using: metadataKey(from: session.key))
        return File(version: file.version, keyID: keyID, systemAuthentication: systemAuthentication, vault: bytes, authentication: Data(tag))
    }

    private func authenticateMetadata(_ file: File, key: SymmetricKey) throws {
        guard HMAC<SHA256>.isValidAuthenticationCode(file.authentication, authenticating: file.authenticatedMetadata, using: metadataKey(from: key)) else {
            throw VaultFailure.unreadable
        }
    }

    private func metadataKey(from key: SymmetricKey) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: key, info: Data("InkOTP/iOS/metadata/v1".utf8), outputByteCount: 32)
    }

    private func hasStorageFile() throws -> Bool {
        do {
            _ = try FileManager.default.attributesOfItem(atPath: url.path)
            return true
        } catch let error as NSError where error.domain == NSCocoaErrorDomain
            && [NSFileNoSuchFileError, NSFileReadNoSuchFileError].contains(error.code) {
            return false
        } catch {
            throw VaultFailure.unreadable
        }
    }

    private func readIfPresent() throws -> File? {
        guard try hasStorageFile() else { return nil }
        return try read()
    }

    private func read() throws -> File {
        do {
            guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  size <= Self.maxStoredBytes else { throw VaultFailure.unreadable }
            let data = try Data(contentsOf: url)
            guard data.count <= Self.maxStoredBytes else { throw VaultFailure.unreadable }
            let file = try JSONDecoder().decode(File.self, from: data)
            guard file.version == 1 else { throw VaultFailure.unsupportedVersion }
            guard file.authentication.count == 32 else { throw VaultFailure.unreadable }
            _ = try VaultCodec.inspect(file.vault)
            return file
        } catch let error as VaultFailure { throw error }
        catch { throw VaultFailure.unreadable }
    }

    private func write(_ file: File) throws {
        var directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        try directory.setResourceValues(resourceValues)
        let bytes = try JSONEncoder().encode(file)
        guard bytes.count <= Self.maxStoredBytes else { throw VaultFailure.unreadable }
        try bytes.write(to: url, options: [.atomic, .completeFileProtection])
    }

    static func validateRecovery(_ password: String) throws {
        guard password.unicodeScalars.count >= 8 else { throw VaultFailure.passwordShort }
        guard password.utf16.count <= 1024 else { throw VaultFailure.passwordLong }
        guard !password.unicodeScalars.allSatisfy(CharacterSet.decimalDigits.contains) else { throw VaultFailure.passwordNumeric }
        guard !["password", "password1", "password123", "qwertyui", "qwerty123", "abcdefgh", "letmein123"].contains(password.lowercased()) else {
            throw VaultFailure.passwordCommon
        }
    }
}
