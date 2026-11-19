import InkOTPCore
import SwiftUI
import UIKit

@MainActor
struct VaultScreen: View {
    @ObservedObject var state: AppState
    let canShowCodes: Bool
    @Binding var showingPolicy: Bool
    @Binding var transfer: TransferRequest?
    @Binding var alert: LocalizedStringKey?
    @Binding var completingSetup: Bool

    @State private var searching = false
    @State private var query = ""
    @State private var sheet: AddSheet?
    @State private var copied: CopyFeedback?
    @State private var refreshTime = UUID()
    @FocusState private var searchFocused: Bool

    private var items: [VaultItem] {
        let sorted = (state.vault?.items ?? []).sorted {
            ($0.sortIndex, $0.id) < ($1.sortIndex, $1.id)
        }
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return sorted }
        return sorted.filter {
            $0.issuer?.localizedCaseInsensitiveContains(needle) == true ||
                $0.accountName.localizedCaseInsensitiveContains(needle)
        }
    }

    var body: some View {
        content
        .sheet(item: $sheet) { route in
            NavigationStack {
                switch route {
                case .options:
                    AddOptions(onScan: { sheet = .scan }, onManual: { sheet = .editor($0) },
                               onCancel: { sheet = nil }, state: state)
                case .scan:
                    ScannerView(onFound: { sheet = .editor($0) }, onCancel: { sheet = .options },
                                onManual: { sheet = .editor("") }, state: state)
                case .editor(let input):
                    AddAccountView(state: state, input: input) { sheet = nil }
                }
            }
            .tint(Ink.accent)
        }
        .task(id: copied?.id) {
            guard copied != nil else { return }
            try? await Task.sleep(for: .seconds(2))
            if !Task.isCancelled { copied = nil }
        }
        .onChange(of: canShowCodes) { _, canShow in
            if !canShow { copied = nil }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
            refreshTime = UUID()
        }
    }

    @ViewBuilder
    private var content: some View {
        if completingSetup {
            AfterSetupView(
                state: state,
                finish: finishSetup,
                scan: { finishSetup(); sheet = .scan },
                manual: { finishSetup(); sheet = .editor("") }
            )
        } else {
            listContent
        }
    }

    private var listContent: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Ink.m) {
                if searching {
                    InkField(title: "Search accounts", value: $query, identifier: "vault.search")
                        .focused($searchFocused)
                }
                if state.backupReminder && state.vault?.items.isEmpty == false {
                    NavigationLink {
                        settings
                    } label: {
                        banner("No backup yet. Changing devices or reinstalling can erase your accounts.", action: "Settings")
                    }
                    .buttonStyle(.plain)
                }
                if state.clockDriftDetected {
                    NavigationLink {
                        diagnostics
                    } label: {
                        banner("The device time changed. Your codes may be out of sync.", action: "Diagnostics")
                    }
                    .buttonStyle(.plain)
                }
                if !state.recoveryConfigured {
                    NavigationLink {
                        settings
                    } label: {
                        banner("Set a recovery password to back up your accounts.", action: "Settings")
                    }
                    .buttonStyle(.plain)
                }
                if items.isEmpty {
                    InkHeading(
                        title: query.isEmpty ? "No accounts yet" : "No matching accounts",
                        message: query.isEmpty ? "Scan a QR code from your service or enter its key manually." : "Try another search or clear the search field.",
                        symbol: query.isEmpty ? "shield" : "magnifyingglass"
                    )
                    if query.isEmpty {
                        Button("Add account") { sheet = .options }.buttonStyle(InkButtonStyle())
                        Button("Enter manually") { sheet = .editor("") }
                            .buttonStyle(InkButtonStyle(prominent: false))
                    } else {
                        Button("Clear search") { query = "" }.buttonStyle(InkButtonStyle(prominent: false))
                    }
                } else {
                    ForEach(items, id: \.id) { item in
                        OtpClock(enabled: canShowCodes, refresh: refreshTime) { now in
                            accountRow(item, date: now)
                        }
                    }
                }
            }
            .frame(maxWidth: Ink.screenWidth)
            .padding(Ink.l)
            .padding(.bottom, Ink.touch + Ink.xl)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Ink.background)
        .navigationTitle("Codes")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { state.lock() } label: { Label("Lock now", systemImage: "lock") }
                    .accessibilityIdentifier("vault.lock")
                Button {
                    searching.toggle()
                    if searching { searchFocused = true }
                    else { query = "" }
                } label: { Label("Search", systemImage: "magnifyingglass") }
                    .accessibilityIdentifier("vault.searchToggle")
                NavigationLink {
                    settings
                } label: { Label("Settings", systemImage: "gearshape") }
                    .accessibilityIdentifier("vault.settings")
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Button { sheet = .options } label: {
                Image(systemName: "plus").font(.title2.weight(.medium))
                    .frame(width: 56, height: 56)
                    .foregroundStyle(Ink.surface)
                    .background(Ink.text, in: RoundedRectangle(cornerRadius: Ink.cardRadius))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Add account")
            .accessibilityIdentifier("vault.add")
            .padding(Ink.xl)
        }
        .overlay(alignment: .bottom) {
            if let copied {
                Text("Copied · \(copied.seconds) seconds left")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Ink.surface)
                    .padding(Ink.m)
                    .background(Ink.text, in: Capsule())
                    .padding(.bottom, Ink.touch + Ink.xxxl)
                    .allowsHitTesting(false)
            }
        }
    }

    private var settings: some View {
        InkSettingsView(
            state: state, showingPolicy: $showingPolicy, transfer: $transfer, alert: $alert,
            refresh: resetClock
        )
    }

    private var diagnostics: some View {
        DiagnosticsView(state: state, refresh: resetClock)
    }

    private func finishSetup() {
        completingSetup = false
    }

    private func resetClock() {
        state.resetClockDrift()
        refreshTime = UUID()
    }

    private func banner(_ title: LocalizedStringKey, action: LocalizedStringKey) -> some View {
        HStack(alignment: .center, spacing: Ink.s) {
            Image(systemName: "info.circle").accessibilityHidden(true)
            Text(title).frame(maxWidth: .infinity, alignment: .leading)
            Text(action).foregroundStyle(Ink.accent)
        }
        .font(.footnote)
        .foregroundStyle(Ink.secondary)
        .padding(Ink.m)
        .background(Ink.sunken, in: RoundedRectangle(cornerRadius: Ink.controlRadius))
    }

    private func accountRow(_ item: VaultItem, date: Date?) -> some View {
        let hidden = state.vault?.settings.hideCodesByDefault == true
        let colors = Ink.avatar(item.issuer)
        let title = item.issuer ?? item.accountName
        return InkCard(highlighted: copied?.account == item.id) {
            HStack(alignment: .top, spacing: Ink.m) {
                Text(item.issuer?.first?.uppercased() ?? "?")
                    .fontWeight(.semibold)
                    .frame(width: Ink.avatarSize, height: Ink.avatarSize)
                    .foregroundStyle(colors.1)
                    .background(colors.0, in: RoundedRectangle(cornerRadius: Ink.avatarRadius))
                    .accessibilityHidden(true)
                Group {
                    if item.type == .totp {
                        Button {
                            copy(item)
                        } label: {
                            accountCode(item, title: title, hidden: hidden, date: date)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Copy verification code")
                        .accessibilityIdentifier("account.copy")
                        .disabled(date == nil)
                    } else {
                        accountCode(item, title: title, hidden: true, date: nil)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                NavigationLink {
                    AccountDetailView(state: state, id: item.id, canShowCodes: canShowCodes, copied: copy)
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(minWidth: Ink.touch, minHeight: Ink.touch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Account details")
            }
        }
    }

    private func accountCode(_ item: VaultItem, title: String, hidden: Bool, date: Date?) -> some View {
        VStack(alignment: .leading, spacing: Ink.xs) {
            HStack(alignment: .firstTextBaseline, spacing: Ink.s) {
                Text(title).font(.body.weight(.semibold)).lineLimit(1)
                Spacer(minLength: 0)
                if let seconds = secondsRemaining(item, at: date) {
                    CodeCountdown(seconds: seconds, period: item.period)
                }
            }
            Text(item.accountName).font(.caption).foregroundStyle(Ink.secondary).lineLimit(1)
            if item.type == .totp {
                OtpCodeLabel(item: item, code: hidden ? nil : date.flatMap { state.code(id: item.id, at: $0)?.value }, date: date, font: .title2)
            } else {
                Text("HOTP account").font(.caption).foregroundStyle(Ink.secondary)
            }
        }
    }

    private func copy(_ item: VaultItem) {
        guard canShowCodes, let code = state.code(id: item.id, at: Date()) else { return }
        state.copy(code: code.value)
        copied = CopyFeedback(account: item.id, seconds: code.remainingSeconds)
        UIAccessibility.post(notification: .announcement, argument: String(localized: "Code copied"))
    }

    private struct CopyFeedback {
        let id = UUID()
        let account: String
        let seconds: Int
    }

    private enum AddSheet: Identifiable {
        case options, scan, editor(String)

        var id: Int {
            switch self {
            case .options: 1
            case .scan: 2
            case .editor: 3
            }
        }
    }
}

private func secondsRemaining(_ item: VaultItem, at date: Date?) -> Int? {
    guard item.type == .totp, let date, item.period > 0, date.timeIntervalSince1970 >= 0 else { return nil }
    return item.period - Int(date.timeIntervalSince1970) % item.period
}

private struct OtpClock<Content: View>: View {
    let enabled: Bool
    var refresh = UUID()
    @ViewBuilder var content: (Date?) -> Content

    var body: some View {
        if enabled {
            TimelineView(.periodic(from: Date().roundedDown, by: 1)) { timeline in
                content(timeline.date)
            }
            .id(refresh)
        } else {
            content(nil)
        }
    }
}

private extension Date {
    var roundedDown: Date { Date(timeIntervalSince1970: floor(timeIntervalSince1970)) }
}

private struct OtpCodeLabel: View {
    let item: VaultItem
    let code: String?
    let date: Date?
    var font: Font = .largeTitle

    var body: some View {
        let count = item.digits == 8 ? 4 : 3
        let half = String(repeating: "•", count: count)
        let display = code.map { String($0.prefix(count)) + " " + String($0.suffix(count)) } ?? "\(half) \(half)"
        Text(display)
            .font(font.monospacedDigit().weight(.semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.65)
            .foregroundStyle((secondsRemaining(item, at: date) ?? 30) <= 5 ? Ink.warning : Ink.text)
            .accessibilityLabel(code.map { Text($0.map(String.init).joined(separator: " ")) } ?? Text("Code hidden"))
            .privacySensitive()
    }
}

private struct CodeCountdown: View {
    let seconds: Int
    let period: Int

    var body: some View {
        HStack(spacing: Ink.xs) {
            ZStack {
                Circle().strokeBorder(Ink.border, lineWidth: 2)
                Circle()
                    .trim(from: 0, to: CGFloat(seconds) / CGFloat(max(period, 1)))
                    .stroke(seconds <= 5 ? Ink.warning : Ink.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .padding(1)
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: Ink.ringSize, height: Ink.ringSize)
            .accessibilityHidden(true)
            Text("\(seconds)s").font(.caption.monospacedDigit()).foregroundStyle(seconds <= 5 ? Ink.warning : Ink.secondary)
        }
        .accessibilityLabel(Text("\(seconds) seconds left"))
    }
}

@MainActor
private struct AccountDetailView: View {
    @ObservedObject var state: AppState
    let id: String
    let canShowCodes: Bool
    let copied: (VaultItem) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var deleting = false
    @State private var issuer = ""
    @State private var account = ""
    @State private var showCopied = false

    var body: some View {
        if let item = state.vault?.items.first(where: { $0.id == id }) {
            InkPage {
                InkCard {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(item.issuer ?? item.accountName).font(.headline)
                            Text(item.accountName).font(.caption).foregroundStyle(Ink.secondary)
                        }
                        Spacer()
                        OtpClock(enabled: canShowCodes) { now in
                            if let seconds = secondsRemaining(item, at: now) {
                                CodeCountdown(seconds: seconds, period: item.period)
                            }
                        }
                    }
                    if item.type == .totp {
                        OtpClock(enabled: canShowCodes) { now in
                            let visibleCode = state.vault?.settings.hideCodesByDefault == true ? nil :
                                now.flatMap { state.code(id: item.id, at: $0)?.value }
                            OtpCodeLabel(item: item, code: visibleCode, date: now)
                        }
                    } else {
                        Text("This version keeps HOTP accounts for migration. It does not generate HOTP codes.")
                            .font(.footnote).foregroundStyle(Ink.secondary)
                    }
                }
                if editing {
                    InkField(title: "Service name", value: $issuer)
                    InkField(title: "Account", value: $account)
                    Button("Save changes") { save(item) }
                        .buttonStyle(InkButtonStyle())
                        .disabled(account.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.busy)
                    Button("Cancel") { editing = false }
                        .buttonStyle(InkButtonStyle(prominent: false))
                } else {
                    InkCard {
                        LabeledContent("Service name", value: item.issuer ?? "—")
                        Divider()
                        LabeledContent("Account", value: item.accountName)
                        Divider()
                        LabeledContent("Type", value: item.type == .totp ? "TOTP" : "HOTP")
                        Divider()
                        LabeledContent("Algorithm", value: item.algorithm.rawValue)
                        Divider()
                        LabeledContent("Digits", value: String(item.digits))
                        if item.type == .totp {
                            Divider()
                            LabeledContent("Period") { Text("\(item.period) seconds") }
                        }
                    }
                    Button(showCopied ? "Code copied" : "Copy verification code") {
                        copied(item)
                        showCopied = true
                    }
                    .buttonStyle(InkButtonStyle())
                    .disabled(item.type != .totp || !canShowCodes || state.busy)
                    Button("Move up") { move(-1) }.buttonStyle(InkButtonStyle(prominent: false))
                    Button("Move down") { move(1) }.buttonStyle(InkButtonStyle(prominent: false))
                    Button("Delete account", role: .destructive) { deleting = true }
                        .frame(maxWidth: .infinity, minHeight: Ink.touch)
                }
            }
            .navigationTitle("Account details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(editing ? "Cancel" : "Edit") {
                        issuer = item.issuer ?? ""
                        account = item.accountName
                        editing.toggle()
                    }
                }
            }
            .task(id: showCopied) {
                guard showCopied else { return }
                try? await Task.sleep(for: .seconds(2))
                if !Task.isCancelled { showCopied = false }
            }
            .alert("Delete this account?", isPresented: $deleting) {
                Button("Delete", role: .destructive) {
                    Task {
                        await state.delete(id: id)
                        if state.error == nil { dismiss() }
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This removes the account from this device. Existing backups are unaffected.")
            }
        }
    }

    private func save(_ original: VaultItem) {
        var item = original
        let name = vaultName(issuer)
        item.issuer = name.isEmpty ? nil : name
        item.accountName = vaultName(account)
        guard !item.accountName.isEmpty else { return }
        item.updatedAt = VaultJson.isoTimestamp()
        let updating = item
        Task {
            await state.update(updating)
            if state.error == nil { dismiss() }
        }
    }

    private func move(_ distance: Int) {
        var ordered = (state.vault?.items ?? []).sorted { ($0.sortIndex, $0.id) < ($1.sortIndex, $1.id) }.map(\.id)
        guard let index = ordered.firstIndex(of: id), ordered.indices.contains(index + distance), !state.busy else { return }
        ordered.swapAt(index, index + distance)
        let updatedIDs = ordered
        Task { await state.reorder(ids: updatedIDs) }
    }
}

func vaultName(_ name: String) -> String {
    let scalars = name.unicodeScalars.filter {
        !(0...31).contains($0.value) && !(127...159).contains($0.value)
    }
    return String(String.UnicodeScalarView(scalars))
        .precomposedStringWithCanonicalMapping.trimmingCharacters(in: .whitespacesAndNewlines)
}
