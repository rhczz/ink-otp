import Combine
import Foundation
import InkOTPCore
import LocalAuthentication
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ImportPreview {
    fileprivate let id: UUID
    let format: InterchangeFormat
    let warnings: [String]
    let added: Int
    let duplicates: Int
    let items: [VaultItem]
}

struct ExportPayload {
    let data: Data
    let warnings: [String]
    let filename: String
}

@MainActor
final class AppState: ObservableObject {
    enum Phase: Equatable { case privacy, setup, locked, unlocked, unavailable }

    @Published private(set) var phase: Phase = .privacy
    @Published private(set) var vault: VaultModel?
    @Published private(set) var busy = false
    @Published private(set) var sessionID = UUID()
    @Published private(set) var recoveryConfigured = false
    @Published private(set) var systemAuthenticationEnabled = false
    @Published private(set) var systemRecoveryRequired = false
    @Published private(set) var clockDriftDetected = false
    @Published private(set) var lastBackupAt: String?
    @Published var error: String?

    var systemAuthenticationAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    var backupReminder: Bool {
        guard let revision = vault?.updatedAt else { return false }
        return defaults.string(forKey: Preference.backupRevision) != revision
    }

    private enum Preference {
        static let privacy = "PrivacyPolicyConsent.1"
        static let backupRevision = "BackupRevision"
        static let lastBackup = "LastBackup"
    }

    private struct PendingImport {
        let id: UUID
        let revision: UUID
        let vault: VaultModel
    }

    private struct CachedCode {
        let step: Int64
        let value: OtpCode
    }

    private let repository: VaultRepository
    private let defaults: UserDefaults
    private let clock = ContinuousClock()
    private var session: VaultSession?
    private var sessionRevision = UUID()
    private var driftDetector = ClockDriftDetector()
    private var entryByID: [String: VaultItem] = [:]
    private var cachedCodes: [String: CachedCode] = [:]
    private var pendingImport: PendingImport?
    private var pendingExportRevision: String?
    private var backgroundDeadline: ContinuousClock.Instant?
    private var backgroundTask: Task<Void, Never>?
    private var pickerDeadline: ContinuousClock.Instant?
    private var authenticationContext: LAContext?
    private var scene = ScenePhase.inactive
    private var activeOperation = UUID()
    private var attemptedAutomaticUnlock = false
    private var allowDeviceAutoUnlock = false
    private var clipboardChangeCount: Int?

    init(repository: VaultRepository, defaults: UserDefaults = .standard) {
        self.repository = repository
        self.defaults = defaults
        lastBackupAt = defaults.string(forKey: Preference.lastBackup)
    }

    func start() async {
        guard defaults.bool(forKey: Preference.privacy), phase == .privacy else { return }
        await refreshMetadata()
    }

    func acceptPrivacy() {
        guard phase == .privacy, !busy else { return }
        defaults.set(true, forKey: Preference.privacy)
        Task { await start() }
    }

    func withdrawPrivacy() {
        defaults.removeObject(forKey: Preference.privacy)
        discardSession(clearClipboard: true)
        phase = .privacy
    }

    func setup(password: String?) async {
        guard phase == .setup else { return }
        await perform { token in
            let session = try await repository.create(model: VaultModel(), password: password)
            guard current(token) else { return }
            recoveryConfigured = password != nil
            systemAuthenticationEnabled = false
            install(session)
        }
    }

    func requestAutomaticUnlock() async {
        guard scene == .active, phase == .locked, !busy, !systemRecoveryRequired, !attemptedAutomaticUnlock,
              pickerDeadline == nil, pendingImport == nil, systemAuthenticationEnabled || allowDeviceAutoUnlock else { return }
        attemptedAutomaticUnlock = true
        await unlock()
    }

    func unlock() async {
        guard phase == .locked else { return }
        await perform { token in
            let context = LAContext()
            if systemAuthenticationEnabled {
                do { try await authenticate(context, reason: "Verify your identity to open InkOTP.", token: token) }
                catch {
                    if !systemAuthenticationAvailable && current(token) { systemRecoveryRequired = true }
                    throw error
                }
            }
            do {
                let opened = try await repository.open(context: context, authorized: systemAuthenticationEnabled)
                if current(token) { install(opened) }
            } catch VaultFailure.deviceKeyUnavailable {
                if current(token) { systemRecoveryRequired = true }
                throw VaultFailure.deviceKeyUnavailable
            }
        }
    }

    func unlock(password: String) async {
        guard phase == .locked, recoveryConfigured else { return }
        await perform { token in
            if systemAuthenticationEnabled {
                guard systemRecoveryRequired else { throw VaultFailure.systemAuthenticationRequired }
                try await authenticate(LAContext(), reason: "Verify your identity to reconnect InkOTP.", token: token)
            }
            let opened = try await repository.open(context: nil, password: password, authorized: systemAuthenticationEnabled)
            if current(token) { install(opened) }
        }
    }

    func lock() {
        guard phase == .unlocked || (busy && phase == .locked) else { return }
        endSession(clearClipboard: true)
    }

    func erase() async {
        guard phase == .unlocked else { return }
        await perform { token in
            if systemAuthenticationEnabled {
                try await authenticate(LAContext(), reason: "Verify your identity to erase the vault.", token: token)
            }
            try await repository.erase()
            guard current(token) else { return }
            defaults.removeObject(forKey: Preference.lastBackup)
            defaults.removeObject(forKey: Preference.backupRevision)
            lastBackupAt = nil
            discardSession(clearClipboard: true)
            recoveryConfigured = false
            systemAuthenticationEnabled = false
            phase = .setup
        }
    }

    func setRecovery(password: String, currentPassword: String? = nil) async {
        guard let session else { return }
        await perform { token in
            if systemAuthenticationEnabled {
                try await authenticate(LAContext(), reason: "Verify your identity to change the recovery password.", token: token)
            }
            let updated = try await repository.setRecovery(password, current: currentPassword, session: session, authorized: systemAuthenticationEnabled)
            if current(token) { recoveryConfigured = true; install(updated) }
        }
    }

    func clearRecovery() async {
        guard let session else { return }
        await perform { token in
            try await authenticate(LAContext(), reason: "Verify your identity to remove the recovery password.", token: token)
            let updated = try await repository.clearRecovery(session: session, authorized: true)
            if current(token) { recoveryConfigured = false; install(updated) }
        }
    }

    func setSystemAuthentication(_ enabled: Bool) async {
        guard let session, enabled != systemAuthenticationEnabled else { return }
        if enabled && !recoveryConfigured {
            error = VaultFailure.recoveryNotSet.rawValue
            return
        }
        await perform { token in
            try await authenticate(LAContext(), reason: "Verify your identity to change device authentication.", token: token)
            try await repository.setSystemAuthentication(enabled, session: session, authorized: true)
            if current(token) { systemAuthenticationEnabled = enabled }
        }
    }

    func add(_ item: VaultItem) async {
        await mutate { model in
            var new = item
            new.sortIndex = (model.items.count + 1) * 1000
            model.items.append(new)
        }
    }

    func update(_ item: VaultItem) async {
        await mutate { model in
            guard let index = model.items.firstIndex(where: { $0.id == item.id }) else { throw VaultFailure.stale }
            model.items[index].issuer = item.issuer
            model.items[index].accountName = item.accountName
            model.items[index].updatedAt = VaultJson.isoTimestamp()
        }
    }

    func delete(id: String) async {
        await mutate { model in
            model.items.removeAll { $0.id == id }
            Self.normalizeOrder(&model)
        }
    }

    func reorder(ids: [String]) async {
        await mutate { model in
            let byID = Dictionary(uniqueKeysWithValues: model.items.map { ($0.id, $0) })
            guard ids.count == byID.count, Set(ids) == Set(byID.keys) else { throw VaultFailure.stale }
            model.items = ids.compactMap { byID[$0] }
            Self.normalizeOrder(&model)
        }
    }

    func updateSettings(_ settings: VaultSettings) async {
        await mutate { $0.settings = settings }
    }

    func code(id: String, at date: Date) -> OtpCode? {
        guard scene == .active, let item = entryByID[id], item.type == .totp else { return nil }
        guard let seconds = Int64(exactly: date.timeIntervalSince1970.rounded(.down)), seconds >= 0, item.period > 0 else { return nil }
        let step = seconds / Int64(item.period)
        if let cached = cachedCodes[id], cached.step == step {
            return OtpCode(
                value: cached.value.value, remainingSeconds: item.period - Int(seconds % Int64(item.period)), counter: cached.value.counter
            )
        }
        let value = try? OtpEngine.totp(secret: item.secret, epochSeconds: seconds, algorithm: item.algorithm, digits: item.digits, period: item.period)
        if let value, cachedCodes.count < 256 || cachedCodes[id] != nil {
            cachedCodes[id] = CachedCode(step: step, value: value)
        }
        return value
    }

    func copy(code: String) {
        guard scene == .active, phase == .unlocked, code.count == 6 || code.count == 8,
              code.utf8.allSatisfy({ (48...57).contains($0) }) else { return }
        let duration = vault?.settings.clipboardClearSeconds ?? 30
        var options: [UIPasteboard.OptionsKey: Any] = [.localOnly: true]
        if duration > 0 { options[.expirationDate] = Date().addingTimeInterval(TimeInterval(duration)) }
        let clipboard = UIPasteboard.general
        clipboard.setItems([[UTType.plainText.identifier: code]], options: options)
        clipboardChangeCount = clipboard.changeCount
    }

    func previewImport(data: Data, password: String?) async -> ImportPreview? {
        guard phase != .privacy, !busy else { return nil }
        pendingImport = nil
        var preview: ImportPreview?
        await perform { token in
            let imported = try await repository.decodeImport(data, password: password)
            guard current(token) else { return }
            let local = session?.model.items ?? []
            let merged = BackupMerge.merge(localItems: local, remoteItems: imported.model.items)
            let id = UUID()
            pendingImport = PendingImport(id: id, revision: sessionRevision, vault: imported.model)
            preview = ImportPreview(
                id: id, format: imported.format, warnings: imported.warnings.map(\.message),
                added: merged.items.count - local.count, duplicates: merged.conflicts.count,
                items: Self.project(imported.model).items
            )
        }
        return preview
    }

    func commitImport(
        _ preview: ImportPreview, policy: MergeConflictPolicy = .keepLocal, replace: Bool = false,
        restorePassword: String? = nil
    ) async {
        guard !busy else { return }
        guard let pending = pendingImport, pending.id == preview.id, pending.revision == sessionRevision else {
            error = VaultFailure.stale.rawValue
            return
        }
        if session == nil {
            await perform { token in
                guard let restorePassword else { throw VaultFailure.recoveryNotSet }
                let restoringExisting = phase == .locked || phase == .unavailable
                if restoringExisting {
                    try await authenticate(LAContext(), reason: "Verify your identity to replace the vault.", token: token)
                }
                let model = VaultModel(items: Self.importItems(pending.vault.items))
                let restored = try await repository.restore(model: model, password: restorePassword, authorized: restoringExisting)
                guard current(token) else { return }
                pendingImport = nil
                recoveryConfigured = true
                systemAuthenticationEnabled = (try await repository.metadata())?.systemAuthenticationEnabled ?? false
                if current(token) { install(restored) }
            }
            return
        }
        await mutate { model in
            let remote = Self.importItems(pending.vault.items)
            model.items = replace ? remote : BackupMerge.merge(localItems: model.items, remoteItems: remote, policy: policy).items
            Self.normalizeOrder(&model)
        }
        if error == nil, !busy { pendingImport = nil }
    }

    func cancelImport() {
        pendingImport = nil
        if busy {
            activeOperation = UUID()
            authenticationContext?.invalidate()
        }
    }

    func export(format: InterchangeFormat, password: String?) async -> ExportPayload? {
        guard let session else { return nil }
        var payload: ExportPayload?
        await perform { token in
            let output = try await repository.export(session, format: format, password: password)
            guard current(token) else { return }
            pendingExportRevision = session.model.updatedAt
            let date = String(VaultJson.isoTimestamp().prefix(10))
            payload = ExportPayload(data: output.bytes, warnings: output.warnings.map(\.message), filename: "InkOTP-\(date).\(format.fileExtension)")
        }
        return payload
    }

    func markExportWritten() {
        guard let revision = pendingExportRevision, session?.model.updatedAt == revision else { return }
        lastBackupAt = VaultJson.isoTimestamp()
        defaults.set(lastBackupAt, forKey: Preference.lastBackup)
        defaults.set(revision, forKey: Preference.backupRevision)
        pendingExportRevision = nil
    }

    func beginPicker() {
        pickerDeadline = clock.now.advanced(by: .seconds(60))
    }

    @discardableResult
    func endPicker() -> Bool {
        guard let pickerDeadline else { return false }
        self.pickerDeadline = nil
        if clock.now >= pickerDeadline {
            if phase == .unlocked { endSession(clearClipboard: false) }
            return false
        }
        return scene == .active
    }

    func sceneChanged(_ value: ScenePhase) {
        scene = value
        switch value {
        case .active:
            updateClockDrift()
            if let backgroundDeadline, clock.now >= backgroundDeadline, phase == .unlocked {
                endSession(clearClipboard: false)
            }
            backgroundDeadline = nil
            backgroundTask?.cancel()
            Task { await requestAutomaticUnlock() }
        case .inactive:
            break
        case .background:
            cachedCodes.removeAll()
            if authenticationContext != nil {
                scheduleLock(at: clock.now.advanced(by: .seconds(120)))
            } else if let pickerDeadline {
                scheduleLock(at: pickerDeadline)
            } else if let vault {
                let timeout = systemAuthenticationEnabled ? 0 : vault.settings.lockTimeoutSeconds
                if timeout <= 0 { endSession(clearClipboard: false) }
                else { scheduleLock(at: clock.now.advanced(by: .seconds(timeout))) }
            } else if busy {
                activeOperation = UUID()
            }
        @unknown default: break
        }
    }

    func privateStorageByteCount() async -> Int? { await repository.byteCount() }

    func updateClockDrift() {
        if driftDetector.sample().drifted { clockDriftDetected = true }
    }

    func resetClockDrift() {
        driftDetector.reset()
        clockDriftDetected = false
        updateClockDrift()
    }

    private func scheduleLock(at deadline: ContinuousClock.Instant) {
        backgroundDeadline = deadline
        backgroundTask?.cancel()
        backgroundTask = Task { [weak self] in
            guard let self else { return }
            do { try await clock.sleep(until: deadline) }
            catch { return }
            if scene == .background { endSession(clearClipboard: false) }
        }
    }

    private func refreshMetadata() async {
        await perform { token in
            do {
                let metadata = try await repository.metadata()
                guard current(token) else { return }
                recoveryConfigured = metadata?.recoveryConfigured ?? false
                systemAuthenticationEnabled = metadata?.systemAuthenticationEnabled ?? false
                allowDeviceAutoUnlock = metadata != nil
                phase = metadata == nil ? .setup : .locked
                if metadata != nil {
                    Task { await requestAutomaticUnlock() }
                }
            } catch let failure as VaultFailure {
                guard current(token) else { return }
                phase = .unavailable
                error = failure.rawValue
            }
        }
    }

    private func install(_ newSession: VaultSession) {
        session = newSession
        sessionRevision = UUID()
        var projection = Self.project(newSession.model)
        projection.items.sort { ($0.sortIndex, $0.id) < ($1.sortIndex, $1.id) }
        vault = projection
        entryByID = Dictionary(uniqueKeysWithValues: newSession.model.items.map { ($0.id, $0) })
        cachedCodes.removeAll()
        systemRecoveryRequired = false
        phase = .unlocked
    }

    private func endSession(clearClipboard: Bool) {
        discardSession(clearClipboard: clearClipboard)
        phase = .locked
        allowDeviceAutoUnlock = !clearClipboard
        // Immediate system-entry prompt after an explicit Lock would undo that action.
        attemptedAutomaticUnlock = clearClipboard
    }

    private func discardSession(clearClipboard: Bool) {
        activeOperation = UUID()
        authenticationContext?.invalidate()
        authenticationContext = nil
        sessionID = UUID()
        session = nil
        sessionRevision = UUID()
        entryByID.removeAll()
        cachedCodes.removeAll()
        vault = nil
        pendingImport = nil
        pendingExportRevision = nil
        pickerDeadline = nil
        backgroundDeadline = nil
        backgroundTask?.cancel()
        error = nil
        if clearClipboard, let clipboardChangeCount, clipboardChangeCount == UIPasteboard.general.changeCount {
            UIPasteboard.general.items = []
        }
        clipboardChangeCount = nil
    }

    private func mutate(_ change: (inout VaultModel) throws -> Void) async {
        guard var snapshot = session else { return }
        await perform { token in
            try change(&snapshot.model)
            snapshot.model.updatedAt = VaultJson.isoTimestamp()
            let saved = try await repository.save(snapshot)
            if current(token) { install(saved) }
        }
    }

    private func perform(_ body: (UUID) async throws -> Void) async {
        guard !busy else { return }
        busy = true
        error = nil
        let token = UUID()
        activeOperation = token
        defer { busy = false }
        do { try await body(token) }
        catch VaultFailure.cancelled { }
        catch is CancellationError { }
        catch let failure as VaultFailure {
            if current(token) { error = failure.rawValue }
        } catch let failure as InterchangeFailure {
            if current(token) { error = failure.localizedDescription }
        } catch {
            if current(token) { self.error = "InkOTP could not complete this operation." }
        }
    }

    private func current(_ token: UUID) -> Bool {
        activeOperation == token && defaults.bool(forKey: Preference.privacy) && !Task.isCancelled
    }

    private func authenticate(_ context: LAContext, reason: String, token: UUID) async throws {
        var failure: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &failure) else {
            throw VaultFailure.deviceKeyUnavailable
        }
        let localized = String(localized: String.LocalizationValue(reason))
        context.localizedReason = localized
        authenticationContext = context
        defer { if authenticationContext === context { authenticationContext = nil } }
        do {
            let result = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: localized)
            guard current(token), result else { throw VaultFailure.cancelled }
        } catch let error as LAError {
            switch error.code {
            case .userCancel, .appCancel, .systemCancel, .userFallback: throw VaultFailure.cancelled
            default: throw VaultFailure.systemAuthenticationFailed
            }
        }
    }

    private static func project(_ model: VaultModel) -> VaultModel {
        var safe = model
        safe.items = model.items.map {
            var item = $0
            item.secret = Data()
            return item
        }
        return safe
    }

    private static func importItems(_ items: [VaultItem]) -> [VaultItem] {
        items.map {
            var item = $0
            item.id = UUID().uuidString.lowercased()
            return item
        }
    }

    private static func normalizeOrder(_ model: inout VaultModel) {
        for index in model.items.indices { model.items[index].sortIndex = (index + 1) * 1000 }
    }
}
