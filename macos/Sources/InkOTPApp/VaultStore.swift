import AppKit
import Combine
import Foundation
import InkOTPCore
import LocalAuthentication

@MainActor
final class VaultStore: ObservableObject {
    @Published private(set) var model: VaultModel?
    @Published private(set) var initialized = false
    @Published private(set) var consent = false
    @Published private(set) var systemAuthentication = false
    @Published private(set) var busy = false
    @Published private(set) var ready = false
    @Published private(set) var hasRecoveryPassword = false
    @Published private(set) var lockRevision = 0
    @Published var errorMessage: String?
    let supportsSystemAuthentication = Bundle.main.object(forInfoDictionaryKey: "InkOTPProtectedKeychainEnabled") as? Bool == true

    private struct CachedCode {
        let step: Int64
        let text: String
    }

    private let disk = VaultDisk()
    private let consentKey = "privacy-consent-revision"
    private var record: VaultRecord?
    private var session: VaultSession?
    private var cache: [String: CachedCode] = [:]
    private var observations = Set<AnyCancellable>()
    private var activeAuthentication: LAContext?
    private var backgroundLock: Task<Void, Never>?
    private var clipboardClear: Task<Void, Never>?
    private var clipboardChangeCount: Int?
    private var requiresForegroundAuthentication = false

    init() {
        consent = DesktopPrivacy.available && UserDefaults.standard.string(forKey: consentKey) == DesktopPrivacy.revision
        listenForLifecycle()
        Task { await loadStatus() }
    }

    func acceptPrivacy() {
        guard DesktopPrivacy.available else { return }
        UserDefaults.standard.set(DesktopPrivacy.revision, forKey: consentKey)
        consent = true
    }

    func withdrawPrivacy() {
        lock()
        UserDefaults.standard.removeObject(forKey: consentKey)
        consent = false
    }

    @discardableResult
    func initialize(password: String?, systemAuthentication: Bool) async -> Bool {
        await operation { [self] epoch in
            guard !initialized else { throw VaultAccessError.alreadyCreated }
            let password = try acceptedPassword(password)
            if systemAuthentication {
                guard supportsSystemAuthentication else { throw VaultAccessError.signing }
                guard password != nil else { throw VaultAccessError.noPassword }
                try await authenticateDevice(reason: tr("Protect your InkOTP vault", "保护 InkOTP 验证库"))
            }
            try check(epoch)
            let session = try await makeSession(password)
            try check(epoch)
            var initial = VaultModel.new()
            initial.settings.lockTimeoutSeconds = 0
            let output = try await disk.replace(initial, session: session, systemAuthentication: systemAuthentication,
                                                expected: nil)
            return publish(output, epoch: epoch)
        } ?? false
    }

    @discardableResult
    func unlock(password: String? = nil) async -> Bool {
        await operation { [self] epoch in
            guard initialized, model == nil, record != nil else { throw VaultAccessError.locked }
            let password = password == "" ? nil : password
            if password != nil, systemAuthentication {
                try await authenticateDevice(reason: tr("Recover access to your InkOTP vault", "恢复 InkOTP 验证库访问"))
            }
            try check(epoch)
            let output = try await disk.unlock(password: password)
            return publish(output, epoch: epoch)
        } ?? false
    }

    func lock(clearClipboard: Bool = true) {
        lockRevision &+= 1
        requiresForegroundAuthentication = false
        activeAuthentication?.invalidate()
        backgroundLock?.cancel()
        backgroundLock = nil
        model = nil
        session = nil
        cache.removeAll(keepingCapacity: false)
        if clearClipboard { clearOwnedClipboard() }
    }

    @discardableResult
    func setSystemAuthentication(_ enabled: Bool) async -> Bool {
        await operation { [self] epoch in
            let (model, session, record) = try requireOpen()
            guard record.systemAuthentication != enabled else { return true }
            if enabled, !supportsSystemAuthentication { throw VaultAccessError.signing }
            guard session.passwordKey != nil else { throw VaultAccessError.noPassword }
            try await authenticateDevice(reason: tr("Change the InkOTP security setting", "更改 InkOTP 安全设置"))
            try check(epoch)
            let rotated = try VaultSession.new(password: nil)
            let replacement = VaultSession(dataKey: rotated.dataKey, passwordKey: session.passwordKey)
            let output = try await disk.replace(model, session: replacement, systemAuthentication: enabled,
                                                expected: record)
            return publish(output, epoch: epoch)
        } ?? false
    }

    @discardableResult
    func changePassword(current: String?, new: String) async -> Bool {
        await operation { [self] epoch in
            let (model, _, record) = try requireOpen()
            guard let password = try acceptedPassword(new) else { throw VaultAccessError.invalidPassword }
            if record.hasPassword {
                guard let current else { throw VaultAccessError.authentication }
                try await disk.checkPassword(current, record: record)
                try check(epoch)
                if record.systemAuthentication {
                    try await authenticateDevice(reason: tr("Change the InkOTP recovery password", "修改 InkOTP 恢复口令"))
                }
            } else {
                try await authenticateDevice(reason: tr("Set the InkOTP recovery password", "设置 InkOTP 恢复口令"))
            }
            try check(epoch)
            let replacement = try await makeSession(password)
            try check(epoch)
            let output = try await disk.replace(model, session: replacement, systemAuthentication: record.systemAuthentication,
                                                expected: record)
            return publish(output, epoch: epoch)
        } ?? false
    }

    @discardableResult
    func save(_ item: VaultItem) async -> Bool {
        await mutate { model in
            var item = item
            item.updatedAt = VaultJSON.timestamp()
            if let position = model.items.firstIndex(where: { $0.id == item.id }) {
                item.sortIndex = model.items[position].sortIndex
                model.items[position] = item
            } else {
                model.items.sort { $0.sortIndex < $1.sortIndex }
                item.sortIndex = (model.items.count + 1) * 1000
                model.items.append(item)
                for index in model.items.indices { model.items[index].sortIndex = (index + 1) * 1000 }
            }
        }
    }

    @discardableResult
    func delete(_ id: String) async -> Bool {
        await mutate { $0.items.removeAll { $0.id == id } }
    }

    @discardableResult
    func move(_ id: String, direction: Int) async -> Bool {
        await mutate { model in
            model.items.sort { $0.sortIndex < $1.sortIndex }
            guard direction == -1 || direction == 1,
                  let index = model.items.firstIndex(where: { $0.id == id }),
                  model.items.indices.contains(index + direction) else { return }
            model.items.swapAt(index, index + direction)
            for offset in model.items.indices { model.items[offset].sortIndex = (offset + 1) * 1000 }
        }
    }

    @discardableResult
    func advance(_ id: String) async -> Bool {
        await mutate { model in
            guard let index = model.items.firstIndex(where: { $0.id == id }),
                  model.items[index].type == .hotp, let counter = model.items[index].counter else { return }
            guard counter < Int64.max else { throw VaultAccessError.counterExhausted }
            model.items[index].counter = counter + 1
            model.items[index].updatedAt = VaultJSON.timestamp()
        }
    }

    @discardableResult
    func updateSettings(_ settings: VaultSettings) async -> Bool {
        await mutate { $0.settings = settings }
    }

    func code(_ item: VaultItem, at date: Date = .now) -> String? {
        guard model != nil, item.period > 0, let time = Int64(exactly: date.timeIntervalSince1970.rounded(.down)),
              time >= 0 else { return nil }
        let step = item.type == .totp ? time / Int64(item.period) : (item.counter ?? -1)
        guard step >= 0 else { return nil }
        if let cached = cache[item.id], cached.step == step { return cached.text }

        let value = try? OTP.hotp(secret: item.secret, algorithm: item.algorithm, digits: item.digits,
                                  counter: UInt64(step))
        if let value {
            if cache.count >= 1024 { cache.removeAll(keepingCapacity: true) }
            cache[item.id] = CachedCode(step: step, text: value)
        }
        return value
    }

    @discardableResult
    func copy(_ item: VaultItem, at date: Date = .now) -> Bool {
        guard let model, let item = model.items.first(where: { $0.id == item.id }),
              let code = code(item, at: date) else { return false }
        clearOwnedClipboard()
        let clipboard = NSPasteboard.general
        let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
        let transient = NSPasteboard.PasteboardType("org.nspasteboard.TransientType")
        let content = NSPasteboardItem()
        content.setData(Data(), forType: concealed)
        content.setData(Data(), forType: transient)
        content.setString(code, forType: .string)
        clipboard.clearContents()
        guard clipboard.writeObjects([content]) else { return false }
        clipboardChangeCount = clipboard.changeCount
        let seconds = model.settings.clipboardClearSeconds
        if seconds > 0 {
            clipboardClear = Task { [weak self] in
                try? await Task.sleep(for: .seconds(seconds))
                guard !Task.isCancelled else { return }
                self?.clearOwnedClipboard()
            }
        }
        return true
    }

    func diskBytes() async -> Int? {
        guard let record else { return nil }
        return await disk.diskBytes(record: record)
    }

    func detectImport(_ url: URL) async -> InterchangeFormat? {
        await operation { [self] epoch in
            let bytes = try await disk.readImport(url)
            try check(epoch)
            return try await Task.detached { try Interchange.detect(bytes) }.value
        }
    }

    func previewImport(_ url: URL, password: String?) async -> ImportedVault? {
        await operation { [self] epoch in
            let bytes = try await disk.readImport(url)
            try check(epoch)
            return try await Task.detached(priority: .userInitiated) {
                try Interchange.decode(bytes, password: password)
            }.value
        }
    }

    @discardableResult
    func commitImport(_ preview: ImportedVault, replace: Bool = false, initialPassword: String? = nil,
                      systemAuthentication: Bool = false, policy: MergePolicy = .keepLocal) async -> Bool {
        await operation { [self] epoch in
            if var current = model, let record, let session {
                guard record.hasPassword else { throw VaultAccessError.noPassword }
                if record.systemAuthentication {
                    try await authenticateDevice(reason: tr("Import an InkOTP backup", "导入 InkOTP 备份"))
                    try check(epoch)
                }
                let existing = current.items.sorted { $0.sortIndex < $1.sortIndex }
                let incoming = preview.model.items.sorted { $0.sortIndex < $1.sortIndex }
                current.items = VaultMerge.merge(local: existing, incoming: incoming, policy: policy)
                current.updatedAt = VaultJSON.timestamp()
                let saved = try await disk.write(current, session: session, record: record)
                try check(epoch)
                cache.removeAll(keepingCapacity: true)
                model = saved
                return true
            }
            if initialized {
                guard replace else { throw VaultAccessError.needsConfirmation }
                guard record != nil else { throw VaultAccessError.corrupt }
            }
            guard let password = try acceptedPassword(initialPassword) else { throw VaultAccessError.noPassword }
            if systemAuthentication, !supportsSystemAuthentication { throw VaultAccessError.signing }
            if initialized || systemAuthentication {
                try await authenticateDevice(reason: tr("Restore an InkOTP backup", "恢复 InkOTP 备份"))
            }
            try check(epoch)
            let replacement = try await makeSession(password)
            try check(epoch)
            var restored = VaultModel.new()
            restored.settings.lockTimeoutSeconds = 0
            restored.items = preview.model.items.map { item in
                var item = item
                item.id = UUID().uuidString.lowercased()
                return item
            }
            let output = try await disk.replace(restored, session: replacement, systemAuthentication: systemAuthentication,
                                                expected: record, rescue: initialized)
            return publish(output, epoch: epoch)
        } ?? false
    }

    @discardableResult
    func export(to url: URL, format: InterchangeFormat, password: String?) async -> Bool {
        await operation { [self] epoch in
            let (model, _, record) = try requireOpen()
            guard record.hasPassword else { throw VaultAccessError.noPassword }
            guard let password, !password.isEmpty else { throw VaultAccessError.noPassword }
            try await disk.checkPassword(password, record: record)
            try check(epoch)
            let normalizedPassword = password.precomposedStringWithCanonicalMapping
            let exported = try await Task.detached(priority: .userInitiated) {
                try Interchange.encode(model, format: format, password: format.requiresPassword ? normalizedPassword : nil)
            }.value
            try check(epoch)
            try await disk.writeExport(exported.bytes, to: url)
            return true
        } ?? false
    }

    private func mutate(_ update: (inout VaultModel) throws -> Void) async -> Bool {
        await operation { [self] epoch in
            var (model, session, record) = try requireOpen()
            try update(&model)
            model.updatedAt = VaultJSON.timestamp()
            let updated = try await disk.write(model, session: session, record: record)
            try check(epoch)
            cache.removeAll(keepingCapacity: true)
            self.model = updated
            return true
        } ?? false
    }

    private func operation<Result>(_ work: (Int) async throws -> Result) async -> Result? {
        guard ready, consent, !busy else { return nil }
        busy = true
        errorMessage = nil
        let epoch = lockRevision
        defer {
            busy = false
            if requiresForegroundAuthentication && NSApplication.shared.isActive {
                Task { await enteredForeground() }
            }
        }
        do {
            let result = try await work(epoch)
            try check(epoch)
            return result
        } catch VaultAccessError.cancelled {
            return nil
        } catch is CancellationError {
            return nil
        } catch {
            guard lockRevision == epoch else { return nil }
            if let safeError = error as? InterchangeError {
                errorMessage = safeError.localizedDescription
            } else if let error = error as? VaultAccessError {
                errorMessage = error.errorDescription
            } else {
                errorMessage = tr("InkOTP couldn’t complete this operation. Check the account details or file and try again.",
                                  "InkOTP 无法完成此操作。请检查账号信息或文件后重试。")
            }
            return nil
        }
    }

    private func loadStatus() async {
        do {
            let status = try await disk.status()
            initialized = status.initialized
            updateRecord(status.record)
            if initialized, status.record == nil { throw VaultAccessError.corrupt }
        } catch {
            errorMessage = tr("The local vault is inaccessible. Your data will not be replaced.", "无法访问本地验证库。现有数据不会被覆盖。")
        }
        ready = true
    }

    private func updateRecord(_ record: VaultRecord?) {
        self.record = record
        systemAuthentication = record?.systemAuthentication ?? false
        hasRecoveryPassword = record?.hasPassword ?? false
    }

    private func publish(_ output: VaultOpenResult, epoch: Int) -> Bool {
        initialized = true
        updateRecord(output.record)
        guard lockRevision == epoch, consent else { return false }
        guard NSApplication.shared.isActive else {
            requiresForegroundAuthentication = output.record.systemAuthentication
            return false
        }
        cache.removeAll(keepingCapacity: true)
        session = output.session
        model = output.model
        return true
    }

    private func requireOpen() throws -> (VaultModel, VaultSession, VaultRecord) {
        guard let model, let session, let record else { throw VaultAccessError.locked }
        return (model, session, record)
    }

    private func check(_ epoch: Int) throws {
        guard lockRevision == epoch, consent, !Task.isCancelled else { throw VaultAccessError.cancelled }
    }

    private func makeSession(_ password: String?) async throws -> VaultSession {
        return try await Task.detached(priority: .userInitiated) { try VaultSession.new(password: password) }.value
    }

    private func acceptedPassword(_ password: String?) throws -> String? {
        guard let password, !password.isEmpty else { return nil }
        let normalized = password.precomposedStringWithCanonicalMapping
        let blocked: Set<String> = ["password", "password1", "password123", "qwertyui", "qwerty123",
                                    "letmein123", "abcdefgh"]
        guard normalized.unicodeScalars.count >= 8, normalized.utf16.count <= 1024,
              !normalized.unicodeScalars.allSatisfy({ CharacterSet.decimalDigits.contains($0) }),
              !blocked.contains(normalized.lowercased()) else {
            throw VaultAccessError.invalidPassword
        }
        return normalized
    }

    private func authenticateDevice(reason: String) async throws {
        let context = LAContext()
        activeAuthentication = context
        defer {
            context.invalidate()
            activeAuthentication = nil
        }

        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else {
            throw VaultAccessError.authentication
        }
        do {
            let authenticated = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
            guard authenticated else { throw VaultAccessError.authentication }
        } catch let error as LAError where [.userCancel, .appCancel, .systemCancel].contains(error.code) {
            throw VaultAccessError.cancelled
        } catch {
            throw VaultAccessError.authentication
        }
    }

    private func listenForLifecycle() {
        let workspace = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.sessionDidResignActiveNotification] {
            workspace.publisher(for: name)
                .sink { [weak self] _ in Task { @MainActor in
                    self?.lockForSessionPause()
                } }
                .store(in: &observations)
        }
        for name in [NSWorkspace.didWakeNotification, NSWorkspace.sessionDidBecomeActiveNotification] {
            workspace.publisher(for: name)
                .sink { [weak self] _ in Task { @MainActor in await self?.enteredForeground() } }
                .store(in: &observations)
        }
        NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)
            .sink { [weak self] _ in Task { @MainActor in self?.enteredBackground() } }
            .store(in: &observations)
        NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)
            .sink { [weak self] _ in Task { @MainActor in await self?.enteredForeground() } }
            .store(in: &observations)
        NotificationCenter.default.publisher(for: NSApplication.willTerminateNotification)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.lock() } }
            .store(in: &observations)
    }

    private func enteredBackground() {
        guard let model, !NSApplication.shared.isActive else { return }
        if activeAuthentication != nil { return }
        if systemAuthentication {
            lock(clearClipboard: false)
            requiresForegroundAuthentication = true
            return
        }
        backgroundLock?.cancel()
        let timeout = model.settings.lockTimeoutSeconds
        backgroundLock = Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            guard !Task.isCancelled, !NSApplication.shared.isActive else { return }
            self?.lock(clearClipboard: false)
        }
    }

    private func lockForSessionPause() {
        let shouldAuthenticate = requiresForegroundAuthentication || (systemAuthentication && model != nil)
        lock()
        requiresForegroundAuthentication = shouldAuthenticate
    }

    private func enteredForeground() async {
        guard NSApplication.shared.isActive else { return }
        backgroundLock?.cancel()
        guard requiresForegroundAuthentication, ready, consent, !busy else { return }
        requiresForegroundAuthentication = false
        await unlock()
    }

    private func clearOwnedClipboard() {
        clipboardClear?.cancel()
        clipboardClear = nil
        let clipboard = NSPasteboard.general
        if let clipboardChangeCount, clipboard.changeCount == clipboardChangeCount {
            clipboard.clearContents()
        }
        clipboardChangeCount = nil
    }
}
