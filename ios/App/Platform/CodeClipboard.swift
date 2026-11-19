import UIKit
import UniformTypeIdentifiers

@MainActor
final class CodeClipboard {
    private let providedPasteboard: UIPasteboard?
    private var ownedType: String?

    init(pasteboard: UIPasteboard? = nil) {
        providedPasteboard = pasteboard
    }

    private var pasteboard: UIPasteboard { providedPasteboard ?? .general }

    func copy(_ code: String, clearAfter seconds: Int) {
        var options: [UIPasteboard.OptionsKey: Any] = [.localOnly: true]
        if seconds > 0 {
            options[.expirationDate] = Date().addingTimeInterval(TimeInterval(seconds))
        }
        let marker = "io.github.rhczz.inkotp.clipboard." + UUID().uuidString.lowercased()
        pasteboard.setItems([[UTType.plainText.identifier: code, marker: Data([1])]], options: options)
        ownedType = marker
        if seconds == 1 {
            DispatchQueue.main.async { [weak self] in
                guard let self, self.ownedType == marker else { return }
                self.clearOwnedCode()
            }
        }
    }

    func clearOwnedCode() {
        guard let marker = ownedType else { return }
        ownedType = nil
        // UIKit can batch changeCount updates. The type identifies this write without reading the clipboard text.
        let clipboard = pasteboard
        if clipboard.numberOfItems == 1, clipboard.contains(pasteboardTypes: [marker]) {
            clipboard.items = []
        }
    }
}
