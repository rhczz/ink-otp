import Foundation

enum ExportStaging {
    static var root: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("InkOTP-Export", isDirectory: true)
    }

    static func clear() {
        try? FileManager.default.removeItem(at: root)
    }
}
