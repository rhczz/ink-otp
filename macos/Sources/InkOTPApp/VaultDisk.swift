import Darwin
import Foundation
import InkOTPCore
import LocalAuthentication
import Security

enum VaultAccessError: LocalizedError {
    case corrupt, locked, alreadyCreated, unavailable, authentication, signing, cancelled, noPassword
    case invalidPassword, tooLarge, needsConfirmation, counterExhausted, keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .corrupt: return String(localized: "InkOTP couldn't open the local vault. Your existing data is still on disk.")
        case .locked: return String(localized: "Unlock the vault and try again.")
        case .alreadyCreated: return String(localized: "A local vault already exists.")
        case .unavailable: return String(localized: "The local vault is in use or inaccessible.")
        case .authentication: return String(localized: "The password or local authentication could not unlock the vault.")
        case .signing: return String(localized: "This build cannot use a system-protected Keychain item. Your previous security setting is still active.")
        case .cancelled: return nil
        case .noPassword: return String(localized: "Set a recovery password before using this action.")
        case .invalidPassword: return String(localized: "Use at least 8 characters for your recovery password.")
        case .tooLarge: return String(localized: "The selected file exceeds the 12 MiB size limit.")
        case .needsConfirmation: return String(localized: "Confirm that you want to replace the locked vault.")
        case .counterExhausted: return String(localized: "This account has reached its maximum HOTP counter.")
        case .keychain: return String(localized: "The Mac Keychain could not complete the operation.")
        }
    }
}

struct VaultRecord: Codable, Equatable, Sendable {
    let id: UUID
    let systemAuthentication: Bool
    let hasPassword: Bool
}

struct VaultOpenResult: Sendable {
    let model: VaultModel
    let session: VaultSession
    let record: VaultRecord
}

struct VaultDiskStatus: Sendable {
    let initialized: Bool
    let record: VaultRecord?
}

actor VaultDisk {
    private let root: URL
    private let keychainService: String
    private var lockDescriptor: Int32 = -1

    init(root: URL? = nil, keychainService: String = "io.github.rhczz.inkotp.macos.vault") {
        self.root = root ?? URL.applicationSupportDirectory.appending(path: "InkOTP", directoryHint: .isDirectory)
        self.keychainService = keychainService
    }

    deinit {
        if lockDescriptor >= 0 { Darwin.close(lockDescriptor) }
    }

    private var recordURL: URL { root.appending(path: "current.json", directoryHint: .notDirectory) }

    func status() throws -> VaultDiskStatus {
        try prepare()
        guard FileManager.default.fileExists(atPath: recordURL.path) else {
            return VaultDiskStatus(initialized: false, record: nil)
        }
        return VaultDiskStatus(initialized: true, record: try? readRecord())
    }

    func unlock(password: String?) throws -> VaultOpenResult {
        try prepare()
        let record = try readRecord()
        let bytes = try Self.read(file(for: record))
        do {
            guard try VaultFile.hasPassword(bytes) == record.hasPassword else { throw VaultAccessError.corrupt }
            let result: (VaultModel, VaultSession)
            if let password {
                result = try VaultFile.open(bytes, password: password)
                // The state owner authenticates the Mac before calling this recovery path.
                // A recovery-password unlock can repair a lost device credential.
                try? repairCredential(result.1, record: record)
            } else {
                let context = LAContext()
                defer { context.invalidate() }
                result = try VaultFile.open(bytes, deviceCredential: readCredential(record, context: context))
            }
            return VaultOpenResult(model: result.0, session: result.1, record: record)
        } catch let error as VaultAccessError {
            throw error
        } catch let error as CoreError where ["VLT_E_WRAP_AUTH", "VLT_E_DEVICE_WRAP_AUTH"].contains(error.code) {
            throw VaultAccessError.authentication
        } catch {
            throw VaultAccessError.corrupt
        }
    }

    func checkPassword(_ password: String, record: VaultRecord) throws {
        do {
            let bytes = try Self.read(file(for: record))
            _ = try VaultFile.open(bytes, password: password)
        } catch {
            throw VaultAccessError.authentication
        }
    }

    func write(_ model: VaultModel, session: VaultSession, record: VaultRecord) throws -> VaultModel {
        guard try readRecord() == record else { throw VaultAccessError.unavailable }
        let normalized = try VaultJSON.validate(model)
        try atomicWrite(VaultFile.seal(normalized, session: session), to: file(for: record))
        return normalized
    }

    // Writes a complete new generation before publishing its pointer. The previous
    // generation stays usable across interruption until the pointer is replaced.
    func replace(_ model: VaultModel, session: VaultSession, systemAuthentication: Bool,
                 expected: VaultRecord?, rescue: Bool = false) throws -> VaultOpenResult {
        try prepare()
        let previous: VaultRecord?
        if FileManager.default.fileExists(atPath: recordURL.path) {
            previous = try readRecord()
        } else {
            previous = nil
        }
        guard previous == expected else { throw VaultAccessError.alreadyCreated }
        if systemAuthentication, session.passwordKey == nil { throw VaultAccessError.noPassword }
        let normalized = try VaultJSON.validate(model)
        let record = VaultRecord(id: UUID(), systemAuthentication: systemAuthentication,
                                 hasPassword: session.passwordKey != nil)
        let bytes = try VaultFile.seal(normalized, session: session)
        let directory = directory(for: record)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                                 attributes: [.posixPermissions: 0o700])

        var insertedCredential = false
        do {
            try atomicWrite(bytes, to: file(for: record))
            try addCredential(session.deviceCredential, record: record)
            insertedCredential = true
            if rescue, let previous {
                let saved = try Self.read(file(for: previous))
                try atomicWrite(saved, to: root.appending(path: "vault.pre-restore.bin"))
            }
            try atomicWrite(JSONEncoder().encode(record), to: recordURL)
        } catch {
            if insertedCredential { deleteCredential(record) }
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
        if let previous {
            // A rescue snapshot with no password still requires its old Keychain credential.
            if !rescue || previous.hasPassword { deleteCredential(previous) }
            try? FileManager.default.removeItem(at: self.directory(for: previous))
        }
        return VaultOpenResult(model: normalized, session: session, record: record)
    }

    func diskBytes(record: VaultRecord) -> Int? {
        try? file(for: record).resourceValues(forKeys: [.fileSizeKey]).fileSize
    }

    func clearSmokeCredential(record: VaultRecord) {
        precondition(keychainService.hasPrefix("io.github.rhczz.inkotp.macos.ci-smoke."))
        deleteCredential(record)
    }

    func readImport(_ url: URL) throws -> Data {
        let hasAccess = url.startAccessingSecurityScopedResource()
        defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
        return try Self.read(url)
    }

    func writeExport(_ bytes: Data, to url: URL) throws {
        let hasAccess = url.startAccessingSecurityScopedResource()
        defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
        try bytes.write(to: url, options: .atomic)
    }

    private func prepare() throws {
        guard lockDescriptor < 0 else { return }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        var directory = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)

        let fd = Darwin.open(root.appending(path: "vault.lock").path, O_CREAT | O_CLOEXEC | O_NOFOLLOW | O_RDWR, 0o600)
        guard fd >= 0 else { throw VaultAccessError.unavailable }
        guard Darwin.flock(fd, LOCK_EX | LOCK_NB) == 0 else {
            Darwin.close(fd)
            throw VaultAccessError.unavailable
        }
        lockDescriptor = fd
    }

    private func readRecord() throws -> VaultRecord {
        do {
            return try JSONDecoder().decode(VaultRecord.self, from: Self.read(recordURL, limit: 4_096))
        } catch {
            throw VaultAccessError.corrupt
        }
    }

    private func directory(for record: VaultRecord) -> URL {
        root.appending(path: record.id.uuidString.lowercased(), directoryHint: .isDirectory)
    }

    private func file(for record: VaultRecord) -> URL {
        directory(for: record).appending(path: "vault.bin", directoryHint: .notDirectory)
    }

    private static func read(_ url: URL, limit: Int = VaultFile.maximumFileSize) throws -> Data {
        let fd = Darwin.open(url.path, O_RDONLY | O_CLOEXEC | O_NONBLOCK | O_NOFOLLOW)
        guard fd >= 0 else { throw VaultAccessError.unavailable }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close() }
        var info = stat()
        guard Darwin.fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG else {
            throw VaultAccessError.unavailable
        }
        guard info.st_size <= limit else { throw VaultAccessError.tooLarge }

        var result = Data()
        while let chunk = try handle.read(upToCount: min(65_536, limit + 1 - result.count)), !chunk.isEmpty {
            result.append(chunk)
            guard result.count <= limit else { throw VaultAccessError.tooLarge }
        }
        return result
    }

    private func atomicWrite(_ bytes: Data, to destination: URL) throws {
        let parent = destination.deletingLastPathComponent()
        let temporary = parent.appending(path: "." + UUID().uuidString + ".tmp")
        let fd = Darwin.open(temporary.path, O_CREAT | O_EXCL | O_CLOEXEC | O_WRONLY, 0o600)
        guard fd >= 0 else { throw VaultAccessError.unavailable }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? FileManager.default.removeItem(at: temporary) }
        do {
            try handle.write(contentsOf: bytes)
            try handle.synchronize()
            try handle.close()
        } catch {
            try? handle.close()
            throw error
        }
        guard Darwin.rename(temporary.path, destination.path) == 0 else { throw VaultAccessError.unavailable }
        let parentFD = Darwin.open(parent.path, O_RDONLY | O_CLOEXEC)
        if parentFD >= 0 {
            _ = Darwin.fsync(parentFD)
            Darwin.close(parentFD)
        }
    }

    private func credentialQuery(_ record: VaultRecord) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: record.id.uuidString
        ]
        if record.systemAuthentication {
            query[kSecUseDataProtectionKeychain as String] = true
            query[kSecAttrSynchronizable as String] = false
        }
        return query
    }

    private func addCredential(_ data: Data, record: VaultRecord) throws {
        var query = credentialQuery(record)
        query[kSecValueData as String] = data
        if record.systemAuthentication {
            guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                                                              .userPresence, nil) else {
                throw VaultAccessError.signing
            }
            query[kSecAttrAccessControl as String] = access
        }
        try checkKeychain(SecItemAdd(query as CFDictionary, nil))
    }

    private func repairCredential(_ session: VaultSession, record: VaultRecord) throws {
        // Recreate a missing item. Never destroy a working credential as part of a recovery attempt.
        let status = SecItemCopyMatching(credentialQuery(record) as CFDictionary, nil)
        if status == errSecItemNotFound { try addCredential(session.deviceCredential, record: record) }
    }

    private func readCredential(_ record: VaultRecord, context: LAContext) throws -> Data {
        var query = credentialQuery(record)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        if record.systemAuthentication {
            query[kSecUseAuthenticationContext as String] = context
            context.localizedReason = String(localized: "Unlock your InkOTP vault")
        }
        var result: CFTypeRef?
        try checkKeychain(SecItemCopyMatching(query as CFDictionary, &result))
        guard let data = result as? Data else { throw VaultAccessError.authentication }
        return data
    }

    private func deleteCredential(_ record: VaultRecord) {
        _ = SecItemDelete(credentialQuery(record) as CFDictionary)
    }

    private func checkKeychain(_ status: OSStatus) throws {
        switch status {
        case errSecSuccess: return
        case errSecMissingEntitlement: throw VaultAccessError.signing
        case errSecUserCanceled: throw VaultAccessError.cancelled
        case errSecItemNotFound, errSecAuthFailed: throw VaultAccessError.authentication
        default: throw VaultAccessError.keychain(status)
        }
    }
}
