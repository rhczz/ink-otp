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
        case .corrupt: return tr("InkOTP couldn't open the local vault. Your existing data is still on disk.", "InkOTP 无法打开本地验证码库。原数据仍保存在磁盘上。")
        case .locked: return tr("Unlock the vault and try again.", "请解锁验证码库后重试。")
        case .alreadyCreated: return tr("A local vault already exists.", "本地验证码库已经存在。")
        case .unavailable: return tr("The local vault is in use or inaccessible.", "本地验证码库正在使用或无法访问。")
        case .authentication: return tr("The password or local authentication could not unlock the vault.", "恢复口令或本地身份验证无法解锁验证码库。")
        case .signing: return tr("This build cannot use a system-protected Keychain item. Your previous security setting is still active.", "当前版本无法使用受系统保护的钥匙串项目。原有安全设置仍然生效。")
        case .cancelled: return nil
        case .noPassword: return tr("Set a recovery password before using this action.", "请先设置恢复口令。")
        case .invalidPassword: return tr("Use 8 to 1024 characters and avoid all-numeric or commonly used recovery passwords.", "请使用 8 至 1024 个字符的恢复口令，避免纯数字或常见口令。")
        case .tooLarge: return tr("The selected file exceeds the 12 MiB size limit.", "所选文件超过 12 MiB。")
        case .needsConfirmation: return tr("Confirm that you want to replace the locked vault.", "请确认替换已锁定的验证码库。")
        case .counterExhausted: return tr("This account has reached its maximum HOTP counter.", "此账号的 HOTP 计数器已达到上限。")
        case .keychain: return tr("The Mac Keychain could not complete the operation.", "Mac 钥匙串无法完成此操作。")
        }
    }
}

struct VaultGeneration: Codable, Equatable, Sendable {
    let id: UUID
    let systemAuthentication: Bool
    let hasPassword: Bool
}

struct VaultRecord: Codable, Equatable, Sendable {
    let active: VaultGeneration
    let rescue: VaultGeneration?

    var systemAuthentication: Bool { active.systemAuthentication }
    var hasPassword: Bool { active.hasPassword }
}

struct VaultOpenResult: Sendable {
    let model: VaultModel
    let session: VaultSession
    let record: VaultRecord
    let deviceCredentialRepairFailed: Bool

    init(model: VaultModel, session: VaultSession, record: VaultRecord, deviceCredentialRepairFailed: Bool = false) {
        self.model = model
        self.session = session
        self.record = record
        self.deviceCredentialRepairFailed = deviceCredentialRepairFailed
    }
}

struct VaultDiskStatus: Sendable {
    let initialized: Bool
    let record: VaultRecord?
}

actor VaultDisk {
    private static let legacyInteractionLock = NSLock()

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

    func unlock(password: String?, context: LAContext = LAContext()) throws -> VaultOpenResult {
        try prepare()
        let record = try readRecord()
        let bytes = try Self.read(file(for: record.active))
        do {
            guard try VaultFile.hasPassword(bytes) == record.hasPassword else { throw VaultAccessError.corrupt }
            if let password {
                let (model, session) = try VaultFile.open(bytes, password: password)
                return recoverDeviceCredential(model, session: session, record: record, context: context)
            }

            let credential = try readCredential(record.active, context: context)
            let (model, session) = try VaultFile.open(bytes, deviceCredential: credential)
            return VaultOpenResult(model: model, session: session, record: record)
        } catch let error as VaultAccessError {
            throw error
        } catch let error as CoreError where ["VLT_E_WRAP_AUTH", "VLT_E_DEVICE_WRAP_AUTH"].contains(error.code) {
            throw VaultAccessError.authentication
        } catch {
            throw VaultAccessError.corrupt
        }
    }

    private func recoverDeviceCredential(_ model: VaultModel, session: VaultSession,
                                         record: VaultRecord, context: LAContext) -> VaultOpenResult {
        if record.systemAuthentication {
            // The caller performed system authentication before verifying this recovery password.
            try? restoreMissingProtectedCredential(session, generation: record.active)
            return VaultOpenResult(model: model, session: session, record: record)
        }
        if var credential = try? readCredential(record.active, context: context) {
            var verified = session.deviceCredential
            defer {
                credential.resetBytes(in: 0..<credential.count)
                verified.resetBytes(in: 0..<verified.count)
            }
            if credential.count == verified.count,
               zip(credential, verified).reduce(UInt8(0), { $0 | ($1.0 ^ $1.1) }) == 0 {
                return VaultOpenResult(model: model, session: session, record: record)
            }
        }

        // Only a verified password reaches here. Keep the password derivation and
        // model intact, and give the new build its own device key and identity.
        do {
            let generated = try VaultSession.new(password: nil)
            let replacement = VaultSession(dataKey: generated.dataKey, passwordKey: session.passwordKey)
            return try replace(model, session: replacement, systemAuthentication: false, expected: record,
                               allowLegacyKeychainUI: false)
        } catch {
            return VaultOpenResult(model: model, session: session, record: record, deviceCredentialRepairFailed: true)
        }
    }

    func checkPassword(_ password: String, record: VaultRecord) throws {
        do {
            let bytes = try Self.read(file(for: record.active))
            _ = try VaultFile.open(bytes, password: password)
        } catch {
            throw VaultAccessError.authentication
        }
    }

    func write(_ model: VaultModel, session: VaultSession, record: VaultRecord) throws -> VaultModel {
        guard try readRecord() == record else { throw VaultAccessError.unavailable }
        let normalized = try VaultJSON.validate(model)
        try atomicWrite(VaultFile.seal(normalized, session: session), to: file(for: record.active))
        return normalized
    }

    // Writes a complete new generation before publishing its pointer. The previous
    // generation stays usable across interruption until the pointer is replaced.
    func replace(_ model: VaultModel, session: VaultSession, systemAuthentication: Bool,
                 expected: VaultRecord?, rescue: Bool = false, allowLegacyKeychainUI: Bool = true) throws -> VaultOpenResult {
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
        let generation = VaultGeneration(id: UUID(), systemAuthentication: systemAuthentication,
                                         hasPassword: session.passwordKey != nil)
        let record = VaultRecord(active: generation, rescue: rescue ? previous?.active : previous?.rescue)
        if rescue, let previous {
            _ = try Self.read(file(for: previous.active))
        }
        let bytes = try VaultFile.seal(normalized, session: session)
        let directory = directory(for: generation)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                                 attributes: [.posixPermissions: 0o700])

        var insertedCredential = false
        do {
            try atomicWrite(bytes, to: file(for: record.active))
            try addCredential(session.deviceCredential, generation: generation, allowLegacyUI: allowLegacyKeychainUI)
            insertedCredential = true
            try atomicWrite(JSONEncoder().encode(record), to: recordURL)
        } catch {
            if insertedCredential { deleteCredential(generation) }
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
        // The pointer commits both the active vault and the single retained rescue generation.
        if let previous {
            for retired in [previous.active, previous.rescue].compactMap({ $0 }) where retired != record.rescue {
                deleteCredential(retired)
                try? FileManager.default.removeItem(at: self.directory(for: retired))
            }
        }
        return VaultOpenResult(model: normalized, session: session, record: record)
    }

    func diskBytes(record: VaultRecord) -> Int? {
        try? file(for: record.active).resourceValues(forKeys: [.fileSizeKey]).fileSize
    }

    func clearSmokeCredential(record: VaultRecord) {
        precondition(keychainService.hasPrefix("io.github.rhczz.inkotp.macos.ci-smoke."))
        deleteCredential(record.active)
        if let rescue = record.rescue { deleteCredential(rescue) }
    }

    func replaceSmokeCredential(_ data: Data, record: VaultRecord) throws {
        precondition(keychainService.hasPrefix("io.github.rhczz.inkotp.macos.ci-smoke."))
        precondition(!record.systemAuthentication)
        deleteCredential(record.active)
        try addCredential(data, generation: record.active)
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

        let path = root.appending(path: "vault.lock").path
        let fd = Darwin.open(path, O_CREAT | O_CLOEXEC | O_NOFOLLOW | O_RDWR | O_EXLOCK | O_NONBLOCK, 0o600)
        guard fd >= 0 else { throw VaultAccessError.unavailable }
        lockDescriptor = fd
    }

    private func readRecord() throws -> VaultRecord {
        do {
            return try JSONDecoder().decode(VaultRecord.self, from: Self.read(recordURL, limit: 4_096))
        } catch {
            throw VaultAccessError.corrupt
        }
    }

    private func directory(for generation: VaultGeneration) -> URL {
        root.appending(path: generation.id.uuidString.lowercased(), directoryHint: .isDirectory)
    }

    private func file(for generation: VaultGeneration) -> URL {
        directory(for: generation).appending(path: "vault.bin", directoryHint: .notDirectory)
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

    private func credentialQuery(_ generation: VaultGeneration) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: generation.id.uuidString
        ]
        if generation.systemAuthentication {
            query[kSecUseDataProtectionKeychain as String] = true
            query[kSecAttrSynchronizable as String] = false
        }
        return query
    }

    private func addCredential(_ data: Data, generation: VaultGeneration, allowLegacyUI: Bool = true) throws {
        var query = credentialQuery(generation)
        query[kSecValueData as String] = data
        if generation.systemAuthentication {
            guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                                                              .userPresence, nil) else {
                throw VaultAccessError.signing
            }
            query[kSecAttrAccessControl as String] = access
        }
        let status = generation.systemAuthentication || allowLegacyUI
            ? SecItemAdd(query as CFDictionary, nil)
            : try withLegacyInteractionDisabled { SecItemAdd(query as CFDictionary, nil) }
        try checkKeychain(status)
    }

    private func restoreMissingProtectedCredential(_ session: VaultSession, generation: VaultGeneration) throws {
        // Keep the existing protected group and policy. A denied authentication
        // is different from a missing item and must never remove the existing credential.
        var query = credentialQuery(generation)
        let context = LAContext()
        defer { context.invalidate() }
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = context
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        if status == errSecItemNotFound { try addCredential(session.deviceCredential, generation: generation) }
    }

    private func readCredential(_ generation: VaultGeneration, context: LAContext) throws -> Data {
        var query = credentialQuery(generation)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status: OSStatus
        if generation.systemAuthentication {
            query[kSecUseAuthenticationContext as String] = context
            context.localizedReason = tr("Unlock your InkOTP vault", "解锁 InkOTP 验证码库")
            status = SecItemCopyMatching(query as CFDictionary, &result)
        } else {
            status = try withLegacyInteractionDisabled { SecItemCopyMatching(query as CFDictionary, &result) }
        }
        try checkKeychain(status)
        guard let data = result as? Data else { throw VaultAccessError.authentication }
        return data
    }

    private func deleteCredential(_ generation: VaultGeneration) {
        var query = credentialQuery(generation)
        if generation.systemAuthentication {
            // Retiring a generation is best effort and must not present another prompt.
            let context = LAContext()
            defer { context.invalidate() }
            context.interactionNotAllowed = true
            query[kSecUseAuthenticationContext as String] = context
            _ = SecItemDelete(query as CFDictionary)
        } else {
            _ = try? withLegacyInteractionDisabled { SecItemDelete(query as CFDictionary) }
        }
    }

    private func withLegacyInteractionDisabled<Result>(_ action: () -> Result) throws -> Result {
        // The ordinary vault uses the file-based Keychain. Suppress its legacy
        // ACL UI only around this synchronous operation and restore the prior mode.
        Self.legacyInteractionLock.lock()
        defer { Self.legacyInteractionLock.unlock() }
        var previous = DarwinBoolean(false)
        try checkKeychain(SecKeychainGetUserInteractionAllowed(&previous))
        guard previous.boolValue else { return action() }
        try checkKeychain(SecKeychainSetUserInteractionAllowed(false))
        defer { _ = SecKeychainSetUserInteractionAllowed(true) }
        return action()
    }

    private func checkKeychain(_ status: OSStatus) throws {
        switch status {
        case errSecSuccess: return
        case errSecMissingEntitlement: throw VaultAccessError.signing
        case errSecUserCanceled: throw VaultAccessError.cancelled
        case errSecItemNotFound, errSecAuthFailed, errSecInteractionNotAllowed: throw VaultAccessError.authentication
        default: throw VaultAccessError.keychain(status)
        }
    }
}
