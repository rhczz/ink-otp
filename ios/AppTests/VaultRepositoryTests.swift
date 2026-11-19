import Foundation
import InkOTPCore
import LocalAuthentication
import XCTest
@testable import InkOTP

final class VaultRepositoryTests: XCTestCase {
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
        let restored = VaultModel(items: [
            VaultItem(accountName: "Restored test account", secret: Data("public-test-seed".utf8))
        ])
        _ = try await repository.restore(model: restored, password: password, authorized: true)
        let predecessor = url.deletingLastPathComponent().appendingPathComponent("vault-before-restore.json")
        XCTAssertEqual(try Data(contentsOf: predecessor), original)
        let opened = try await repository.open(context: LAContext())
        XCTAssertEqual(opened.model, restored)
        try await repository.erase()
        XCTAssertFalse(FileManager.default.fileExists(atPath: predecessor.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
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
