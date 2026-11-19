import Darwin
import Foundation

enum VaultOperation: String {
    case load = "Open encrypted vault"
    case setup = "Create encrypted vault"
    case unlock = "Unlock"
    case save = "Save"
    case erase = "Erase vault"
    case recovery = "Change recovery password"
    case authentication = "Verify identity"
    case importBackup = "Import backup"
    case exportBackup = "Export backup"
}

struct VaultOperationTiming {
    let kind: VaultOperation
    let duration: Duration
}

struct VaultResourceSample {
    let residentMemoryBytes: Int64?
    let vaultBytes: Int?
    let duration: Duration
    let lastOperation: VaultOperationTiming?

    static func residentMemory() -> Int64? {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.stride / MemoryLayout<integer_t>.stride)
        let capacity = Int(count)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: capacity) { raw in
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), raw, &count)
            }
        }
        return result == KERN_SUCCESS ? Int64(exactly: info.resident_size) : nil
    }
}
