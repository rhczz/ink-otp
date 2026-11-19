import InkOTPCore
import SwiftUI
import UIKit

@MainActor
struct RootView: View {
    @ObservedObject var state: AppState
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("appearance") private var appearance = Appearance.system.rawValue
    @State private var showingPolicy = false
    @State private var isCaptured = UIScreen.main.isCaptured
    @State private var transfer: TransferRequest?
    @State private var alert: LocalizedStringKey?
    @State private var completingSetup = false

    private var canShowContent: Bool { scenePhase == .active && !isCaptured }

    var body: some View {
        protectedNavigation
            .alert(alertTitle, isPresented: alertPresented) {
                Button("OK", role: .cancel, action: clearAlert)
            } message: {
                alertMessage
            }
    }

    private var protectedNavigation: some View {
        presentedNavigation
            .onChange(of: state.sessionID) { _, _ in resetPresentation() }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
                isCaptured = UIScreen.main.isCaptured
            }
            .overlay { screenCover }
            .accessibilityHidden(!canShowContent && !isCaptured)
    }

    private var presentedNavigation: some View {
        navigation
            .modifier(VaultTransfers(state: state, request: $transfer, notice: $alert))
            .sheet(isPresented: $showingPolicy) { policySheet }
    }

    private var navigation: some View {
        NavigationStack {
            phaseContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Ink.background)
        }
        .id(state.sessionID)
        .foregroundStyle(Ink.text)
        .tint(Ink.accent)
        .preferredColorScheme(Appearance(rawValue: appearance)?.scheme)
    }

    @ViewBuilder
    private var phaseContent: some View {
        switch state.phase {
        case .privacy:
            PrivacyGate(state: state, showingPolicy: $showingPolicy)
        case .setup:
            OnboardingView(
                state: state, showingPolicy: $showingPolicy,
                restore: chooseRestore, completeSetup: prepareVault
            )
        case .locked:
            UnlockView(state: state, restore: chooseRestore)
        case .unlocked:
            VaultScreen(
                state: state, canShowCodes: canShowContent,
                showingPolicy: $showingPolicy, transfer: $transfer,
                alert: $alert, completingSetup: $completingSetup
            )
            .id(state.sessionID)
        case .unavailable:
            unavailableView
        }
    }

    private var unavailableView: some View {
        InkPage {
            InkHeading(
                title: "Vault unavailable",
                message: "InkOTP could not open the vault. Try again or restore a backup.",
                symbol: "lock.trianglebadge.exclamationmark"
            )
            Button("Restore from backup", action: chooseRestore)
                .buttonStyle(InkButtonStyle())
        }
    }

    private var policySheet: some View {
        NavigationStack {
            PrivacyPolicyView()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { showingPolicy = false }
                    }
                }
        }
        .tint(Ink.accent)
    }

    @ViewBuilder
    private var screenCover: some View {
        if !canShowContent {
            Ink.surface.ignoresSafeArea()
                .overlay {
                    if isCaptured {
                        InkHeading(title: "Screen sharing active",
                                   message: "InkOTP hides vault content while the screen is being recorded or mirrored.",
                                   symbol: "eye.slash").padding(Ink.xxl)
                    }
                }
        }
    }

    private var alertTitle: LocalizedStringKey {
        state.error == nil ? "Notice" : "Operation incomplete"
    }

    private var alertPresented: Binding<Bool> {
        Binding {
            transfer == nil && (alert != nil || (state.error != nil && state.phase != .locked))
        } set: { presented in
            if !presented { clearAlert() }
        }
    }

    @ViewBuilder
    private var alertMessage: some View {
        if let error = state.error {
            inkText(error)
        } else if let alert {
            Text(alert)
        }
    }

    private func chooseRestore() {
        transfer = .importBackup
    }

    private func prepareVault(password: String?) async {
        completingSetup = true
        await state.setup(password: password)
        if state.phase != .unlocked { completingSetup = false }
    }

    private func resetPresentation() {
        showingPolicy = false
        transfer = nil
        alert = nil
        completingSetup = false
    }

    private func clearAlert() {
        alert = nil
        state.error = nil
    }
}

enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var scheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    var label: LocalizedStringKey {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }
}

@MainActor
private struct PrivacyGate: View {
    @ObservedObject var state: AppState
    @Binding var showingPolicy: Bool
    @State private var declined = false

    var body: some View {
        InkPage {
            InkHeading(title: "InkOTP privacy notice",
                       message: "InkOTP generates codes on this device. You can read the privacy notice before deciding whether to continue.",
                       symbol: "shield")
            Button("Read the full privacy notice") { showingPolicy = true }
                .buttonStyle(InkButtonStyle(prominent: false))
            Button("Agree and continue") {
                declined = false
                state.acceptPrivacy()
            }
            .buttonStyle(InkButtonStyle())
            .accessibilityIdentifier("privacy.agree")
            if declined {
                Text("The app will stay locked until you agree. You can review the notice at any time.")
                    .font(.footnote).foregroundStyle(Ink.secondary)
            } else {
                Button("Decline") { declined = true }
                    .frame(maxWidth: .infinity, minHeight: Ink.touch)
            }
        }
    }
}

@MainActor
private struct OnboardingView: View {
    @ObservedObject var state: AppState
    @Binding var showingPolicy: Bool
    let restore: () -> Void
    let completeSetup: (String?) async -> Void

    @State private var starting = false
    @State private var skipConfirm = false
    @State private var password = ""
    @State private var confirmation = ""
    @State private var acknowledged = false

    var body: some View {
        InkPage {
            if starting {
                InkHeading(title: "Set a recovery password",
                           message: "This password protects your backups. Keep it somewhere safe so you can restore your accounts.",
                           symbol: "key")
                InkField(title: "Recovery password (at least 8 characters)", value: $password, secret: true, identifier: "onboarding.recovery")
                InkField(title: "Enter again", value: $confirmation, secret: true, identifier: "onboarding.confirm")
                if !confirmation.isEmpty && password != confirmation {
                    Text("Passwords do not match").font(.footnote).foregroundStyle(Ink.danger)
                }
                Toggle("I understand that a forgotten password cannot unlock my backups", isOn: $acknowledged)
                    .font(.footnote)
                    .accessibilityIdentifier("onboarding.acknowledge")
                Button("Set recovery password") { start(password) }
                    .buttonStyle(InkButtonStyle())
                    .accessibilityIdentifier("onboarding.save")
                    .disabled(password.count < 8 || password != confirmation || !acknowledged || state.busy)
                Button("Set up later") { skipConfirm = true }
                    .accessibilityIdentifier("onboarding.skip")
                    .frame(maxWidth: .infinity, minHeight: Ink.touch)
                    .disabled(state.busy)
                Button("Back") {
                    password = ""; confirmation = ""; acknowledged = false; starting = false
                }
                .disabled(state.busy)
            } else {
                InkHeading(title: "Your codes stay on this device",
                           message: "InkOTP generates codes offline, encrypts your vault, and lets you move your accounts with an encrypted backup.",
                           symbol: "shield")
                Button("Continue") { starting = true }.buttonStyle(InkButtonStyle())
                    .accessibilityIdentifier("onboarding.continue")
                Button("Restore from backup", action: restore).buttonStyle(InkButtonStyle(prominent: false))
                    .accessibilityIdentifier("onboarding.restore")
                Button("Read the full privacy notice") { showingPolicy = true }
                    .frame(maxWidth: .infinity, minHeight: Ink.touch)
            }
        }
        .alert("Skip recovery password?", isPresented: $skipConfirm) {
            Button("Skip for now", role: .destructive) { start(nil) }
                .accessibilityIdentifier("onboarding.skipConfirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your vault stays encrypted on this device. Without a recovery password and backup, you cannot recover it after reinstalling or changing devices.")
        }
    }

    private func start(_ submitted: String?) {
        password = ""
        confirmation = ""
        Task { await completeSetup(submitted) }
    }
}

@MainActor
private struct UnlockView: View {
    @ObservedObject var state: AppState
    let restore: () -> Void

    @State private var enteringPassword = false
    @State private var password = ""

    var body: some View {
        InkPage {
            InkHeading(
                title: state.systemRecoveryRequired ? "Reconnect device authentication" : "Unlock to see your codes",
                message: "Your accounts are stored in an encrypted vault.",
                symbol: "lock"
            )
            Button(state.systemAuthenticationEnabled ? "Use device authentication" : "Unlock") {
                Task { await state.unlock() }
            }
                .buttonStyle(InkButtonStyle())
                .disabled(state.busy)
                .accessibilityIdentifier("vault.unlock")
            if enteringPassword || (state.systemRecoveryRequired && state.recoveryConfigured) {
                InkField(title: "Recovery password", value: $password, secret: true)
                Button("Unlock with recovery password") {
                    let submitted = password
                    password = ""
                    Task { await state.unlock(password: submitted) }
                }
                .buttonStyle(InkButtonStyle(prominent: false))
                .disabled(password.isEmpty || state.busy)
            } else if state.recoveryConfigured && !state.systemAuthenticationEnabled {
                Button("Use recovery password") { enteringPassword = true }
                    .frame(maxWidth: .infinity, minHeight: Ink.touch)
                    .disabled(state.busy)
            }
            if !state.recoveryConfigured {
                Text("No recovery password is set. Set one before changing devices or reinstalling the app.")
                    .font(.footnote).foregroundStyle(Ink.warning)
            }
            if let error = state.error {
                inkText(error).font(.footnote).foregroundStyle(Ink.danger)
            }
            Button("Restore from backup", action: restore)
                .frame(maxWidth: .infinity, minHeight: Ink.touch)
                .disabled(state.busy)
        }
    }
}
