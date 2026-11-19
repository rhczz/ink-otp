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
            do {
                _ = try await disk.unlock(password: "incorrect public CI passphrase")
                throw CoreError("SMOKE_WRONG_PASSWORD_ACCEPTED")
            } catch VaultAccessError.authentication {}
            await disk.clearSmokeCredential(record: active)
        } catch {
            await disk.clearSmokeCredential(record: active)
            throw error
        }
    }
}
