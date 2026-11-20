import InkOTPCore
import SwiftUI

@MainActor
struct InkSettingsView: View {
    @ObservedObject var state: AppState
    @Binding var showingPolicy: Bool
    @Binding var transfer: TransferRequest?
    @Binding var alert: LocalizedStringKey?
    let refresh: () -> Void

    @AppStorage("appearance") private var appearance = Appearance.system.rawValue
    @State private var withdrawing = false

    var body: some View {
        InkPage {
            if let settings = state.vault?.settings {
                section("Security") {
                    Toggle("Verify your identity on every entry", isOn: Binding(
                        get: { state.systemAuthenticationEnabled },
                        set: { enabled in Task { await state.setSystemAuthentication(enabled) } }
                    ))
                    .disabled(!state.recoveryConfigured || state.busy)
                    Text(state.recoveryConfigured
                         ? "Use Face ID, Touch ID or your device passcode to enter."
                         : "Set a recovery password before turning on verification.")
                    .font(.caption).foregroundStyle(Ink.secondary)
                    Divider()
                    if state.systemAuthenticationEnabled {
                        LabeledContent("Auto-lock") { Text("Verify on every entry") }
                    } else {
                        Picker("Auto-lock", selection: setting(\.lockTimeoutSeconds, default: 30)) {
                            Text("Immediately").tag(0)
                            Text("30 seconds").tag(30)
                            Text("1 minute").tag(60)
                        }
                    }
                    Divider()
                    Toggle("Hide codes", isOn: setting(\.hideCodesByDefault, default: false))
                    Divider()
                    Picker("Clear copied codes", selection: setting(\.clipboardClearSeconds, default: 30)) {
                        Text("Off").tag(0)
                        Text("Immediately").tag(1)
                        Text("15 seconds").tag(15)
                        Text("30 seconds").tag(30)
                        Text("1 minute").tag(60)
                    }
                    Divider()
                    row(state.recoveryConfigured ? "Change recovery password" : "Set recovery password",
                        symbol: "key") { transfer = .recoveryPassword }
                    if settings.hideCodesByDefault {
                        Text("Tap a hidden code to copy it without revealing it.")
                            .font(.caption).foregroundStyle(Ink.secondary)
                    }
                }
                section("Backups") {
                    row("Export backup", symbol: "square.and.arrow.up") { backup(.exportBackup) }
                    Divider()
                    row("Import backup", symbol: "square.and.arrow.down") { backup(.importBackup) }
                    Divider()
                    row("Transfer to Google Authenticator", symbol: "qrcode") { backup(.exportGoogle) }
                    Divider()
                    LabeledContent("Last backup") {
                        if let lastBackupAt = state.lastBackupAt { Text(lastBackupAt) }
                        else { Text("Never") }
                    }
                }
            }
            section("Appearance") {
                Picker("Theme", selection: $appearance) {
                    ForEach(Appearance.allCases) { value in Text(value.label).tag(value.rawValue) }
                }
            }
            section("Help") {
                NavigationLink {
                    DiagnosticsView(state: state, refresh: refresh)
                } label: {
                    Label("Codes not matching?", systemImage: "questionmark.circle")
                        .frame(maxWidth: .infinity, minHeight: Ink.touch, alignment: .leading)
                }
                Divider()
                row("Getting started", symbol: "arrow.counterclockwise") {
                    alert = "Use Add Account to scan a QR code or enter a key. Set a recovery password to export an encrypted backup for your next device."
                }
                Divider()
                row("Privacy notice", symbol: "shield") { showingPolicy = true }
                Divider()
                row("Withdraw privacy consent", symbol: "hand.raised") { withdrawing = true }
                Divider()
                NavigationLink {
                    AboutView(showPolicy: { showingPolicy = true })
                } label: {
                    Label("About and open-source licenses", systemImage: "info.circle")
                        .frame(maxWidth: .infinity, minHeight: Ink.touch, alignment: .leading)
                }
                Divider()
                row("Lock now", symbol: "lock", action: state.lock)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Withdraw privacy consent?", isPresented: $withdrawing) {
            Button("Withdraw consent", role: .destructive, action: state.withdrawPrivacy)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("InkOTP will lock and stop processing your codes. The encrypted vault and your exported backups are kept. You can agree again to continue.")
        }
    }

    private func setting<Value>(_ keyPath: WritableKeyPath<VaultSettings, Value>, default fallback: Value) -> Binding<Value> {
        Binding(get: { state.vault?.settings[keyPath: keyPath] ?? fallback }, set: { value in
            guard var settings = state.vault?.settings, !state.busy else { return }
            settings[keyPath: keyPath] = value
            let updated = settings
            Task { await state.updateSettings(updated) }
        })
    }

    private func backup(_ kind: TransferRequest) {
        if state.recoveryConfigured {
            transfer = kind
        } else {
            transfer = .recoveryPassword
        }
    }

    private func section<Content: View>(_ title: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Ink.s) {
            Text(title).font(.headline).accessibilityAddTraits(.isHeader)
            InkCard { content() }
        }
    }

    private func row(_ title: LocalizedStringKey, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .frame(maxWidth: .infinity, minHeight: Ink.touch, alignment: .leading)
        }
        .buttonStyle(.plain)
    }
}

@MainActor
struct AfterSetupView: View {
    @ObservedObject var state: AppState
    let finish: () -> Void
    let scan: () -> Void
    let manual: () -> Void
    @State private var adding = false

    var body: some View {
        InkPage {
            if adding || !state.recoveryConfigured || state.systemAuthenticationEnabled {
                InkHeading(title: "Add your first account",
                           message: "Scan a QR code from your service or enter its key manually.",
                           symbol: "plus")
                Button("Scan QR code", action: scan).buttonStyle(InkButtonStyle())
                Button("Enter manually", action: manual).buttonStyle(InkButtonStyle(prominent: false))
                Button("Add later", action: finish).frame(maxWidth: .infinity, minHeight: Ink.touch)
                    .accessibilityIdentifier("onboarding.addLater")
            } else {
                InkHeading(title: "Verify your identity on every entry",
                           message: "Use Face ID, Touch ID or your device passcode to enter.",
                           symbol: "touchid")
                if !state.systemAuthenticationAvailable {
                    Text("Set a device passcode in Settings to turn on verification.").foregroundStyle(Ink.secondary)
                }
                if let error = state.error { inkText(error).font(.footnote).foregroundStyle(Ink.danger) }
                Button("Turn on") {
                    Task {
                        await state.setSystemAuthentication(true)
                        if state.systemAuthenticationEnabled { adding = true }
                    }
                }
                .buttonStyle(InkButtonStyle())
                .disabled(!state.systemAuthenticationAvailable || state.busy)
                Button("Not now") { adding = true }.frame(maxWidth: .infinity, minHeight: Ink.touch)
                    .accessibilityIdentifier("onboarding.authLater")
            }
        }
        .navigationTitle("Getting started")
        .navigationBarTitleDisplayMode(.inline)
    }
}

@MainActor
struct DiagnosticsView: View {
    @ObservedObject var state: AppState
    let refresh: () -> Void
    @Environment(\.scenePhase) private var scenePhase
    @State private var displayedTime = Date()
    @State private var resources: VaultResourceSample?
    @State private var recalculated = false
    @State private var sampling = false

    var body: some View {
        InkPage {
            InkCard {
                Text("Current device time").font(.caption).foregroundStyle(Ink.secondary)
                Text(displayedTime.formatted(date: .abbreviated, time: .standard))
                    .font(.title2.monospacedDigit().weight(.semibold))
                Text(verbatim: TimeZone.current.identifier).foregroundStyle(Ink.secondary)
                Text("Check Settings → General → Date & Time on your device.").font(.footnote)
            }
            if state.clockDriftDetected {
                Label("The device time changed. Check the time before recalculating.", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(Ink.warning)
            }
            Text("Codes not matching?").font(.headline).accessibilityAddTraits(.isHeader)
            InkCard {
                Label("Check the device date, time and time zone.", systemImage: "clock")
                Label("Make sure the code belongs to the right service and account.", systemImage: "person.crop.circle")
                Label("Check whether the service expects a verification code or a backup code.", systemImage: "key")
                Label("Check the Base32 key you entered against the one from your service.", systemImage: "checkmark.circle")
            }
            Button("Recalculate all codes") {
                displayedTime = Date()
                refresh()
                recalculated = true
            }
            .buttonStyle(InkButtonStyle())
            if recalculated {
                Text("Codes recalculated").font(.footnote).foregroundStyle(Ink.secondary)
            }
            Text("Performance and resources").font(.headline).accessibilityAddTraits(.isHeader)
            InkCard {
                Text("InkOTP samples these values on your device when you choose Refresh. Memory and storage are measured separately.")
                    .font(.footnote).foregroundStyle(Ink.secondary)
                if let resources {
                    LabeledContent("Resident memory", value: byteLabel(resources.residentMemoryBytes))
                    LabeledContent("Local vault files", value: byteLabel(resources.vaultBytes.map(Int64.init)))
                    LabeledContent("Sample duration") { elapsedLabel(resources.duration) }
                    if let operation = resources.lastOperation {
                        LabeledContent {
                            VStack(alignment: .trailing, spacing: Ink.xs) {
                                inkText(operation.kind.rawValue)
                                elapsedLabel(operation.duration)
                            }
                        } label: {
                            Text("Recent operation (includes verification)")
                        }
                    }
                }
                Button(sampling ? "Sampling…" : "Refresh resource usage") { sampling = true }
                .buttonStyle(InkButtonStyle(prominent: false))
                .disabled(sampling)
            }
        }
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { displayedTime = Date() }
        }
        .task(id: sampling) {
            guard sampling else { return }
            defer { sampling = false }
            if let sample = await state.sampleResources() { resources = sample }
        }
    }

    private func byteLabel(_ bytes: Int64?) -> String {
        guard let bytes else { return "—" }
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .memory)
    }

    private func elapsedLabel(_ duration: Duration) -> some View {
        let components = duration.components
        let milliseconds = components.seconds * 1_000 + components.attoseconds / 1_000_000_000_000_000
        return Text("\(milliseconds) ms").monospacedDigit()
    }
}

struct AboutView: View {
    let showPolicy: () -> Void

    var body: some View {
        InkPage {
            InkHeading(title: "InkOTP", message: "Offline two-factor authenticator", symbol: "shield")
            InkCard {
                let version = Bundle.main.object(forInfoDictionaryKey: "InkOTPReleaseVersion") as? String
                    ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
                LabeledContent("Version", value: version)
                LabeledContent("License", value: "GPL-3.0")
                NavigationLink("Read the license") { LicenseView(resource: "InkOTP-LICENSE", title: "GPL-3.0") }
                if Bundle.main.url(forResource: "Argon2-LICENSE", withExtension: "txt") != nil {
                    NavigationLink("Argon2 license") { LicenseView(resource: "Argon2-LICENSE", title: "Argon2 license") }
                }
                NavigationLink("scrypt license") { LicenseView(resource: "Scrypt-LICENSE", title: "scrypt license") }
                Button("Privacy notice", action: showPolicy)
            }
        }
        .navigationTitle("About and open-source licenses")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct LicenseView: View {
    let resource: String
    let title: LocalizedStringKey

    var body: some View {
        InkPage {
            if let url = Bundle.main.url(forResource: resource, withExtension: "txt"),
               let license = try? String(contentsOf: url, encoding: .utf8) {
                Text(verbatim: license).font(.footnote).textSelection(.enabled)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PrivacyPolicyView: View {
    var body: some View {
        InkPage {
            Text("InkOTP privacy notice").font(.title.weight(.semibold)).accessibilityAddTraits(.isHeader)
            Text("privacy.scope.body")
            policy("privacy.data.heading", "privacy.data.body")
            policy("privacy.retention.heading", "privacy.retention.body")
            policy("privacy.components.heading", "privacy.components.body")
            policy("privacy.website.heading", "privacy.website.body")
            policy("privacy.rights.heading", "privacy.rights.body")
            Text(verbatim: "hi@renhongchang.top").textSelection(.enabled)
            policy("privacy.changes.heading", "privacy.changes.body")
        }
        .navigationTitle("Privacy notice")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func policy(_ heading: LocalizedStringKey, _ body: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: Ink.s) {
            Text(heading).font(.headline).accessibilityAddTraits(.isHeader)
            Text(body)
        }
    }
}
