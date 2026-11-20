import Combine
import Foundation
import InkOTPCore
import LocalAuthentication
import UIKit
import XCTest
@testable import InkOTP

final class VaultRepositoryTests: XCTestCase {
    func testNativeKeychainReadsAndDeletesADeviceOnlyKey() throws {
        let keys = DeviceKeychain(service: "io.github.rhczz.inkotp.tests." + UUID().uuidString)
        defer { try? keys.deleteAll() }
        let id = UUID()
        let bytes = Data(repeating: 0x55, count: 32)
        try keys.insert(bytes, id: id, requiresAuthentication: false)
        XCTAssertEqual(try keys.read(id: id, context: LAContext()), bytes)
        try keys.delete(id: id)
        XCTAssertThrowsError(try keys.read(id: id, context: LAContext()))
    }

    func testEncryptedVaultPersistsAcrossRepositoryInstancesAndRejectsTampering() async throws {
        let (url, store, repository) = makeRepository()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let item = VaultItem(accountName: "Local test account", secret: Data("public-test-seed".utf8))
        let model = VaultModel(items: [item])
        _ = try await repository.create(model: model, password: "public fixture password")

        let raw = try Data(contentsOf: url)
        XCTAssertNil(raw.range(of: Data(item.accountName.utf8)))
        XCTAssertNil(raw.range(of: item.secret))
        let second = VaultRepository(url: url, keys: store)
        let deviceOpened = try await second.open(context: LAContext())
        XCTAssertEqual(deviceOpened.model, model)

        await expect(.badPassword) { _ = try await second.open(context: nil, password: "wrong") }
        let recovered = try await second.open(context: nil, password: "public fixture password")
        XCTAssertEqual(recovered.model, model)

        var file = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        file["systemAuthentication"] = true
        try JSONSerialization.data(withJSONObject: file).write(to: url)
        await expect(.unreadable) { _ = try await second.open(context: LAContext(), authorized: true) }
    }

    func testPolicyCannotBeDisabledOrBypassedByRecoveryPassword() async throws {
        let (url, _, repository) = makeRepository()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let password = "public fixture password"
        let session = try await repository.create(model: VaultModel(), password: password)
        await expect(.systemAuthenticationRequired) {
            try await repository.setSystemAuthentication(true, session: session, authorized: false)
        }
        try await repository.setSystemAuthentication(true, session: session, authorized: true)
        let metadata = try await repository.metadata()
        XCTAssertEqual(metadata, VaultMetadata(recoveryConfigured: true, systemAuthenticationEnabled: true))
        await expect(.systemAuthenticationRequired) { _ = try await repository.open(context: nil, password: password) }
        await expect(.systemAuthenticationRequired) {
            try await repository.setSystemAuthentication(false, session: session, authorized: false)
        }
        await expect(.systemAuthenticationRequired) {
            _ = try await repository.restore(model: VaultModel(), password: password, authorized: false)
        }
    }

    func testConcurrentCreationIsSerializedAndUnsupportedVersionIsPreserved() async throws {
        let (url, _, repository) = makeRepository()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let created = await withTaskGroup(of: Bool.self) { group in
            for _ in 0..<4 {
                group.addTask {
                    (try? await repository.create(model: VaultModel(), password: nil)) != nil
                }
            }
            return await group.reduce(0) { $0 + ($1 ? 1 : 0) }
        }
        XCTAssertEqual(created, 1)
        var file = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        file["version"] = 99
        let future = try JSONSerialization.data(withJSONObject: file)
        try future.write(to: url)
        await expect(.unsupportedVersion) { _ = try await repository.open(context: LAContext()) }
        await expect(.unsupportedVersion) { _ = try await repository.create(model: VaultModel(), password: nil) }
        XCTAssertEqual(try Data(contentsOf: url), future)
    }

    func testFailedKeyReplacementRetainsPreviousVault() async throws {
        let (url, store, repository) = makeRepository()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let model = VaultModel()
        let session = try await repository.create(model: model, password: "public fixture password")
        store.rejectWrites = true
        await expect(.deviceKeyUnavailable) {
            try await repository.setSystemAuthentication(true, session: session, authorized: true)
        }
        let opened = try await repository.open(context: LAContext())
        XCTAssertEqual(opened.model, model)
        let metadata = try await repository.metadata()
        XCTAssertEqual(metadata?.systemAuthenticationEnabled, false)
    }

    func testAuthorizedRestorePreservesOneEncryptedPredecessorAndEraseRemovesIt() async throws {
        let (url, _, repository) = makeRepository()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let password = "public fixture password"
        _ = try await repository.create(model: VaultModel(), password: password)
        let original = try Data(contentsOf: url)
        let originalByteCount = await repository.byteCount()
        XCTAssertEqual(originalByteCount, original.count)
        let restored = VaultModel(items: [
            VaultItem(accountName: "Restored test account", secret: Data("public-test-seed".utf8))
        ])
        _ = try await repository.restore(model: restored, password: password, authorized: true)
        let predecessor = url.deletingLastPathComponent().appendingPathComponent("vault-before-restore.json")
        XCTAssertEqual(try Data(contentsOf: predecessor), original)
        let restoredByteCount = try Data(contentsOf: url).count
        XCTAssertNotEqual(restoredByteCount, original.count)
        let byteCount = await repository.byteCount()
        XCTAssertEqual(byteCount, restoredByteCount + original.count)
        let opened = try await repository.open(context: LAContext())
        XCTAssertEqual(opened.model, restored)
        try await repository.erase()
        XCTAssertFalse(FileManager.default.fileExists(atPath: predecessor.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        let erasedByteCount = await repository.byteCount()
        XCTAssertNil(erasedByteCount)
    }

    func testImportRotatesKeyAndHonorsSystemAuthenticationPolicy() async throws {
        let (url, _, repository) = makeRepository()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        var session = try await repository.create(model: VaultModel(), password: "public fixture password")
        try await repository.setSystemAuthentication(true, session: session, authorized: true)
        let previousVaultID = session.model.vaultId
        session.model.items.append(VaultItem(accountName: "Imported", secret: Data("public-test-seed".utf8)))
        await expect(.systemAuthenticationRequired) {
            _ = try await repository.commitImport(session, authorized: false)
        }
        let imported = try await repository.commitImport(session, authorized: true)
        XCTAssertNotEqual(imported.key, session.key)
        XCTAssertNotEqual(imported.model.vaultId, previousVaultID)
        let opened = try await repository.open(context: LAContext(), authorized: true)
        XCTAssertEqual(opened.model, imported.model)
    }

    @MainActor
    func testGoogleTransferRequiresPasswordAndCannotCommitAcrossLock() async throws {
        let (url, _, repository) = makeRepository()
        let suite = "InkOTP.Tests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }
        defaults.set(true, forKey: "PrivacyPolicyConsent.1")
        let state = AppState(repository: repository, defaults: defaults)
        await state.start()
        state.sceneChanged(.active)
        let password = "public fixture password"
        await state.setup(password: password)
        let item = VaultItem(issuer: "Lab", accountName: "Public", secret: Data([65, 66, 67]))
        let preview = try XCTUnwrap(state.previewGoogleImport([item]))
        XCTAssertEqual(preview.added, 1)
        XCTAssertTrue(preview.items.allSatisfy { $0.secret.isEmpty })
        XCTAssertTrue(state.vault?.items.isEmpty == true)

        let committed = await state.commitImport(preview)
        XCTAssertTrue(committed)
        XCTAssertEqual(state.vault?.items.first?.accountName, item.accountName)
        let wrongPassword = await state.exportGoogle(password: "wrong")
        XCTAssertNil(wrongPassword)
        let result = await state.exportGoogle(password: password)
        XCTAssertEqual(result?.supportedCount, 1)
        XCTAssertEqual(result?.pages.count, 1)
        XCTAssertNil(state.lastBackupAt)

        let stale = try XCTUnwrap(state.previewGoogleImport([item]))
        state.lock()
        let committedAcrossLock = await state.commitImport(stale)
        XCTAssertFalse(committedAcrossLock)
    }

    @MainActor
    func testCancelledPreviewCannotBeCommitted() async throws {
        let (url, _, repository) = makeRepository()
        let suite = "InkOTP.Tests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }
        defaults.set(true, forKey: "PrivacyPolicyConsent.1")
        let state = AppState(repository: repository, defaults: defaults)
        await state.start()
        XCTAssertEqual(state.phase, .setup)
        await state.setup(password: "public fixture password")
        let uri = "otpauth://totp/Fixture:Public?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Fixture"
        let result = await state.previewImport(data: Data(uri.utf8), password: nil)
        let preview = try XCTUnwrap(result)
        XCTAssertEqual(preview.added, 1)
        XCTAssertTrue(preview.items.allSatisfy { $0.secret.isEmpty })
        state.cancelImport()
        let committed = await state.commitImport(preview)
        XCTAssertFalse(committed)
        XCTAssertEqual(state.vault?.items.count, 0)
    }

    @MainActor
    func testImmediateClipboardClearKeepsUserReplacementAndClearsOwnedCode() async {
        let pasteboard = UIPasteboard.withUniqueName()
        defer { UIPasteboard.remove(withName: pasteboard.name) }
        let clipboard = CodeClipboard(pasteboard: pasteboard)

        clipboard.copy("123456", clearAfter: 1)
        XCTAssertEqual(pasteboard.string, "123456")
        pasteboard.string = "User replacement"
        await nextMainQueueTurn()
        XCTAssertEqual(pasteboard.string, "User replacement")

        clipboard.copy("654321", clearAfter: 1)
        XCTAssertEqual(pasteboard.string, "654321")
        await nextMainQueueTurn()
        XCTAssertNil(pasteboard.string)
    }

    @MainActor
    func testAutomaticLockKeepsCopyUntilAnExplicitLock() async throws {
        let pasteboard = UIPasteboard.withUniqueName()
        defer { UIPasteboard.remove(withName: pasteboard.name) }
        let state = try await unlockedState(clipboard: CodeClipboard(pasteboard: pasteboard))
        await state.updateSettings(VaultSettings(lockTimeoutSeconds: 0, clipboardClearSeconds: 0))
        state.copy(code: "123456")

        state.sceneChanged(.background)
        XCTAssertEqual(state.phase, .locked)
        XCTAssertEqual(pasteboard.string, "123456")

        let reopened = expectation(description: "Vault reopened on foreground entry")
        let observation = state.$phase.sink { if $0 == .unlocked { reopened.fulfill() } }
        defer { observation.cancel() }
        state.sceneChanged(.active)
        await fulfillment(of: [reopened], timeout: 5)
        state.lock()
        XCTAssertNil(pasteboard.string)
    }

    @MainActor
    func testDiagnosticsMeasureSampleAndMostRecentOperationOnlyWhileUnlocked() async throws {
        let state = try await unlockedState()
        let initialSample = await state.sampleResources()
        let initial = try XCTUnwrap(initialSample)
        XCTAssertEqual(initial.lastOperation?.kind, .setup)
        XCTAssertGreaterThan(initial.vaultBytes ?? 0, 0)
        XCTAssertGreaterThanOrEqual(initial.duration, .zero)

        await state.updateSettings(VaultSettings(hideCodesByDefault: true))
        let updatedSample = await state.sampleResources()
        let recent = try XCTUnwrap(updatedSample?.lastOperation)
        XCTAssertEqual(recent.kind, .save)
        XCTAssertGreaterThanOrEqual(recent.duration, .zero)

        let failedImport = await state.previewImport(data: Data(), password: nil)
        XCTAssertNil(failedImport)
        let failedSample = await state.sampleResources()
        XCTAssertEqual(failedSample?.lastOperation?.kind, .importBackup)

        state.lock()
        let lockedSample = await state.sampleResources()
        XCTAssertNil(lockedSample)
    }

    @MainActor
    private func unlockedState(clipboard: CodeClipboard? = nil) async throws -> AppState {
        let (url, _, repository) = makeRepository()
        let suite = "InkOTP.Tests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }
        defaults.set(true, forKey: "PrivacyPolicyConsent.1")
        let state = AppState(repository: repository, defaults: defaults, clipboard: clipboard)
        state.sceneChanged(.active)
        await state.start()
        await state.setup(password: nil)
        XCTAssertEqual(state.phase, .unlocked)
        return state
    }

    @MainActor
    private func nextMainQueueTurn() async {
        await withCheckedContinuation { continuation in
            DispatchQueue.main.async { continuation.resume() }
        }
    }

    private func makeRepository() -> (URL, MemoryKeys, VaultRepository) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathComponent("vault.json")
        let store = MemoryKeys()
        return (url, store, VaultRepository(url: url, keys: store))
    }

    private func expect(_ failure: VaultFailure, operation: () async throws -> Void) async {
        do {
            try await operation()
            XCTFail("Expected \(failure)")
        } catch {
            XCTAssertEqual(error as? VaultFailure, failure)
        }
    }
}

private final class MemoryKeys: VaultKeyStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [UUID: Data] = [:]
    private var failing = false

    var rejectWrites: Bool {
        get { lock.withLock { failing } }
        set { lock.withLock { failing = newValue } }
    }

    func insert(_ key: Data, id: UUID, requiresAuthentication: Bool) throws {
        try lock.withLock {
            guard !failing else { throw VaultFailure.deviceKeyUnavailable }
            items[id] = key
        }
    }

    func read(id: UUID, context: LAContext) throws -> Data {
        try lock.withLock {
            guard let value = items[id] else { throw VaultFailure.deviceKeyUnavailable }
            return value
        }
    }

    func delete(id: UUID) throws {
        _ = lock.withLock { items.removeValue(forKey: id) }
    }

    func deleteAll() throws {
        lock.withLock { items.removeAll() }
    }
}
