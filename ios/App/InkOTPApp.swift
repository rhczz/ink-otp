import SwiftUI
import UIKit

@main
@MainActor
struct InkOTPApp: App {
    @Environment(\.scenePhase) private var phase
    @StateObject private var state: AppState

    @MainActor
    init() {
        ExportStaging.clear()
        let path = URL.applicationSupportDirectory
            .appending(path: "InkOTP", directoryHint: .isDirectory)
            .appending(path: "vault-v1.json", directoryHint: .notDirectory)
        _state = StateObject(wrappedValue: AppState(repository: VaultRepository(url: path)))
    }

    var body: some Scene {
        WindowGroup {
            RootView(state: state)
                .task { await state.start() }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
                    state.updateClockDrift()
                }
                .onChange(of: phase, initial: true) { _, value in state.sceneChanged(value) }
        }
    }
}
