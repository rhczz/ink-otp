import Darwin
import Foundation
import InkOTPCore

enum VaultSmoke {
    static func runAndExit() -> Never {
        // This launch mode only touches a unique CI Keychain service and temporary directory.
        Task.detached {
            do {
                try await exercise()
                print("Sandbox vault and Keychain smoke passed")
                Darwin.exit(EXIT_SUCCESS)
            } catch VaultAccessError.keychain(let status) {
                print("Sandbox vault smoke failed: Keychain status \(status)")
                Darwin.exit(EXIT_FAILURE)
            } catch {
                print("Sandbox vault smoke failed: \(type(of: error)): \(error.localizedDescription)")
                Darwin.exit(EXIT_FAILURE)
            }
        }
        dispatchMain()
    }

    private static func exercise() async throws {
        let id = UUID().uuidString
        let root = URL.temporaryDirectory.appending(path: "InkOTP-CI-" + id, directoryHint: .isDirectory)
        let disk = VaultDisk(root: root, keychainService: "io.github.rhczz.inkotp.macos.ci-smoke." + id)
        defer { try? FileManager.default.removeItem(at: root) }
        guard try await disk.status().initialized == false else { throw CoreError("SMOKE_UNEXPECTED_VAULT") }
        let secondProcess = VaultDisk(root: root)
        do {
            _ = try await secondProcess.status()
            throw CoreError("SMOKE_CONCURRENT_OPEN")
        } catch VaultAccessError.unavailable {}

        var model = VaultModel.new()
        model.items = [VaultItem.make(accountName: "Public CI vector", secret: Data("12345678901234567890".utf8))]
        let first = try await disk.replace(model, session: VaultSession.new(password: nil),
                                           systemAuthentication: false, expected: nil)
        var active = first.record
        do {
            let opened = try await disk.unlock(password: nil)
            guard opened.model == model else { throw CoreError("SMOKE_DEVICE_OPEN") }
            model.items[0].accountName = "Updated CI vector"
            _ = try await disk.write(model, session: opened.session, record: opened.record)
            let updated = try await disk.unlock(password: nil)
            guard updated.model == model else { throw CoreError("SMOKE_WRITE") }

            let recovery = "public CI fixture passphrase"
            let replacement = try await disk.replace(model, session: VaultSession.new(password: recovery),
                                                     systemAuthentication: false, expected: updated.record)
            active = replacement.record
            let reopened = try await disk.unlock(password: recovery)
            let viaDevice = try await disk.unlock(password: nil)
            guard reopened.model == model, viaDevice.model == model else { throw CoreError("SMOKE_RECOVERY") }
            await disk.clearSmokeCredential(record: active)
            try await expectDeviceDenied(disk)
            let oldPath = root.appending(path: active.active.id.uuidString.lowercased()).appending(path: "vault.bin")
            let ciphertext = try Data(contentsOf: oldPath)
            do {
                _ = try await disk.unlock(password: "incorrect public CI passphrase")
                throw CoreError("SMOKE_WRONG_PASSWORD_ACCEPTED")
            } catch VaultAccessError.authentication {}
            guard try await disk.status().record == active, try Data(contentsOf: oldPath) == ciphertext else {
                throw CoreError("SMOKE_WRONG_PASSWORD_MUTATED_VAULT")
            }

            let previousMissing = active
            let reboundMissing = try await disk.unlock(password: recovery)
            active = reboundMissing.record
            try await checkRebound(disk, result: reboundMissing, previous: previousMissing, model: model)

            let previousMismatched = active
            try await disk.replaceSmokeCredential(Data(repeating: 0xA5, count: 64), record: active)
            try await expectDeviceDenied(disk)
            let reboundMismatched = try await disk.unlock(password: recovery)
            active = reboundMismatched.record
            try await checkRebound(disk, result: reboundMismatched, previous: previousMismatched, model: model)

            var rescued = VaultModel.new()
            rescued.items = model.items
            let archived = active.active
            let restored = try await disk.replace(rescued, session: VaultSession.new(password: recovery),
                                                 systemAuthentication: false, expected: active, rescue: true)
            active = restored.record
            guard active.rescue == archived else { throw CoreError("SMOKE_RESCUE_POINTER") }
            let rescuePath = root.appending(path: archived.id.uuidString.lowercased()).appending(path: "vault.bin")
            let snapshot = try VaultFile.open(Data(contentsOf: rescuePath), password: recovery).0
            guard snapshot == model, try await disk.unlock(password: nil).model == rescued else {
                throw CoreError("SMOKE_RESCUE_GENERATIONS")
            }
            await disk.clearSmokeCredential(record: active)
        } catch {
            await disk.clearSmokeCredential(record: active)
            throw error
        }
    }

    private static func expectDeviceDenied(_ disk: VaultDisk) async throws {
        do {
            _ = try await disk.unlock(password: nil)
            throw CoreError("SMOKE_STALE_DEVICE_CREDENTIAL_ACCEPTED")
        } catch VaultAccessError.authentication {}
    }

    private static func checkRebound(_ disk: VaultDisk, result: VaultOpenResult,
                                     previous: VaultRecord, model: VaultModel) async throws {
        guard !result.deviceCredentialRepairFailed, result.model == model,
              result.record.active.id != previous.active.id,
              result.record.active.systemAuthentication == previous.active.systemAuthentication,
              result.record.active.hasPassword == previous.active.hasPassword,
              result.record.rescue == previous.rescue,
              try await disk.unlock(password: nil).model == model else {
            throw CoreError("SMOKE_DEVICE_CREDENTIAL_REBIND")
        }
    }

}
