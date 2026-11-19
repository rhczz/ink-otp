import Accessibility
import AppKit
import Combine
import InkOTPCore
import SwiftUI
import UniformTypeIdentifiers

@main
@MainActor
struct InkOTPDesktop: App {
    @StateObject private var store = VaultStore()
    @StateObject private var commands = DesktopCommands()
    @StateObject private var activity = DesktopActivity()

    init() {
        if CommandLine.arguments.contains("--ci-vault-smoke") { VaultSmoke.runAndExit() }
    }

    private var canCopySelection: Bool {
        guard activity.isActive, let id = commands.selectedAccountID else { return false }
        return store.model?.items.contains { $0.id == id && $0.type == .totp } == true
    }

    var body: some Scene {
        Window("InkOTP", id: "vault") {
            DesktopVaultView(store: store, commands: commands)
                .environmentObject(activity)
                .frame(minWidth: 720, minHeight: 470)
        }
        .defaultSize(width: 940, height: 640)
        .windowResizability(.contentMinSize)
        .commands {
            SidebarCommands()
            CommandGroup(replacing: .newItem) {
                Button(tr("Add Account…", "添加账号…")) { commands.send(.add) }
                    .keyboardShortcut("n", modifiers: .command)
                    .disabled(store.model == nil || store.busy)
                Button(tr("Import Backup…", "导入备份…")) { commands.send(.importBackup) }
                    .keyboardShortcut("o", modifiers: .command)
                    .disabled(!store.consent || store.busy)
                Button(tr("Export Backup…", "导出备份…")) { commands.send(.exportBackup) }
                    .keyboardShortcut("s", modifiers: [.command, .shift])
                    .disabled(store.model == nil || store.busy)
            }
            CommandGroup(after: .pasteboard) {
                Button(tr("Copy Verification Code", "复制验证码")) { commands.send(.copy) }
                    .keyboardShortcut("c", modifiers: [.command, .shift])
                    .disabled(!canCopySelection)
                Button(tr("Edit Account…", "编辑账号…")) { commands.send(.edit) }
                    .keyboardShortcut("e", modifiers: .command)
                    .disabled(store.model == nil || commands.selectedAccountID == nil || store.busy)
            }
            CommandMenu(tr("Vault", "验证码库")) {
                Button(tr("Lock Now", "立即锁定")) { store.lock() }
                    .keyboardShortcut("l", modifiers: [.command, .shift])
                    .disabled(store.model == nil)
                Divider()
                Button(tr("Diagnostics…", "诊断…")) { commands.send(.diagnostics) }
                Button(tr("Privacy Statement…", "隐私声明…")) { commands.send(.privacy) }
                Button(tr("About and Licenses…", "关于与开源许可…")) { commands.send(.about) }
            }
        }

        Settings {
            DesktopSettings(store: store, commands: commands)
                .modifier(DesktopPrivacyCurtain())
                .environmentObject(activity)
        }
    }
}

enum DesktopRequest {
    case add, copy, edit, importBackup, exportBackup, diagnostics, privacy, about, changePassword, onboarding
}

@MainActor
final class DesktopCommands: ObservableObject {
    let requests = CurrentValueSubject<DesktopRequest?, Never>(nil)
    @Published var selectedAccountID: String?

    func send(_ request: DesktopRequest) {
        requests.send(request)
    }
}

@MainActor
final class DesktopActivity: ObservableObject {
    @Published private(set) var isActive = NSApplication.shared.isActive
    private var observations = Set<AnyCancellable>()

    init() {
        NotificationCenter.default.publisher(for: NSApplication.willResignActiveNotification)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.isActive = false } }
            .store(in: &observations)
        NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.isActive = true } }
            .store(in: &observations)
    }
}

@MainActor
struct DesktopPrivacyCurtain: ViewModifier {
    @EnvironmentObject private var activity: DesktopActivity
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        content
            // Preserve the view's identity and draft values while the app is inactive.
            .opacity(activity.isActive ? 1 : 0)
            .allowsHitTesting(activity.isActive)
            .accessibilityHidden(!activity.isActive)
            .overlay {
                if !activity.isActive {
                    let ink = QuietInk(dark: scheme == .dark)
                    Rectangle()
                        .fill(ink.background)
                        .overlay {
                            Label(tr("InkOTP is inactive", "InkOTP 已切换至后台"), systemImage: "lock.shield")
                                .foregroundStyle(ink.secondary)
                                .accessibilityIdentifier("desktop-privacy-curtain")
                        }
                        .transition(.identity)
                        .allowsHitTesting(false)
                }
            }
            .transaction { transaction in
                if !activity.isActive {
                    transaction.disablesAnimations = true
                    transaction.animation = nil
                }
            }
    }
}

struct DesktopSheet: Identifiable {
    enum Content {
        case setup
        case account(String?, OTPDraft?)
        case importBackup(URL, InterchangeFormat)
        case exportBackup
        case camera
        case changePassword
        case privacy
        case diagnostics
        case about
        case onboarding
    }

    let id = UUID()
    let content: Content
}

@MainActor
struct DesktopVaultView: View {
    @ObservedObject var store: VaultStore
    @ObservedObject var commands: DesktopCommands
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openSettings) private var openSettings
    @EnvironmentObject private var activity: DesktopActivity
    @AppStorage("export.last-revision") private var lastBackupRevision = ""
    @State private var selection: String?
    @State private var query = ""
    @State private var sheet: DesktopSheet?
    @State private var confirmDelete: String?
    @State private var showRecovery = false
    @State private var unlockPassword = ""
    @State private var codeFeedback: String?
    @State private var feedbackTask: Task<Void, Never>?
    @State private var fileTask: Task<Void, Never>?
    @State private var openPanel: NSOpenPanel?
    @State private var fileBusy = false
    @State private var clockChanged = false

    private var appActive: Bool { activity.isActive }
    private var ink: QuietInk { QuietInk(dark: scheme == .dark) }
    private var items: [VaultItem] {
        (store.model?.items ?? []).sorted { ($0.sortIndex, $0.id) < ($1.sortIndex, $1.id) }
    }
    private var selected: VaultItem? { items.first { $0.id == selection } }
    private var visible: [VaultItem] {
        let search = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !search.isEmpty else { return items }
        return items.filter {
            [$0.issuer ?? "", $0.accountName].contains { $0.localizedStandardContains(search) }
        }
    }
    private var isBusy: Bool { store.busy || fileBusy }
    private var defaultHidden: Bool { store.model?.settings.hideCodesByDefault == true }

    var body: some View {
        Group {
            if !store.ready {
                ProgressView(tr("Opening InkOTP…", "正在打开 InkOTP…"))
            } else if !store.consent {
                gate(icon: "hand.raised", title: tr("Privacy Statement", "隐私声明"),
                     message: tr("InkOTP generates verification codes on this Mac. Review the privacy statement to continue.", "InkOTP 在这台 Mac 上生成验证码。请阅读隐私声明后继续。")) {
                    Button(tr("Read Privacy Statement", "阅读隐私声明")) { present(.privacy) }
                        .accessibilityIdentifier("privacy-read")
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                }
            } else if store.model != nil {
                vault
            } else if store.initialized {
                locked
            } else {
                gate(icon: "key.horizontal", title: tr("Set Up InkOTP", "设置 InkOTP"),
                     message: tr("Create an encrypted vault or restore accounts from a backup.", "新建加密库，或从备份恢复账号。")) {
                    HStack {
                        Button(tr("Restore from Backup…", "从备份恢复…"), action: chooseBackup)
                        Button(tr("Create Vault…", "新建验证码库…")) { present(.setup) }
                            .accessibilityIdentifier("vault-create")
                            .buttonStyle(.borderedProminent)
                            .keyboardShortcut(.defaultAction)
                    }
                    .disabled(isBusy)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(ink.background)
        .background(PrivateWindowConfiguration())
        .tint(ink.accent)
        .overlay(alignment: .bottom) {
            if sheet == nil, let message = store.errorMessage {
                DesktopNotice(message: message) { store.errorMessage = nil }.padding(16)
            } else if let codeFeedback, sheet == nil {
                Label(codeFeedback, systemImage: "checkmark.circle")
                    .foregroundStyle(ink.success)
                    .padding(10)
                    .background(.regularMaterial, in: Capsule())
                    .padding(16)
            }
        }
        .overlay(alignment: .topTrailing) {
            if isBusy && sheet == nil {
                DesktopBusyIndicator().padding(16)
            }
        }
        .modifier(DesktopPrivacyCurtain())
        .sheet(item: $sheet) { current in
            sheetView(current.content, sheetID: current.id)
                .modifier(DesktopPrivacyCurtain())
                .environmentObject(activity)
        }
        .confirmationDialog(tr("Delete account?", "删除账号？"), isPresented: Binding(
            get: { confirmDelete != nil },
            set: { if !$0 { confirmDelete = nil } }
        ), titleVisibility: .visible) {
            if let id = confirmDelete {
                Button(tr("Delete Account", "删除账号"), role: .destructive) {
                    confirmDelete = nil
                    Task { await store.delete(id) }
                }
            }
            Button(tr("Cancel", "取消"), role: .cancel) { confirmDelete = nil }
        } message: {
            Text(tr("After deleting the account, InkOTP can no longer generate its codes. Verify that you have another recovery method.", "删除后，InkOTP 将无法再为该账号生成验证码。请确认你还有其他恢复方式。"))
        }
        .onReceive(commands.requests.compactMap { $0 }) { request in
            commands.requests.send(nil)
            handle(request)
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSSystemClockDidChange)) { _ in clockChanged = true }
        .onChange(of: selection) { _, newValue in commands.selectedAccountID = newValue }
        .onChange(of: visible.map(\.id)) { _, ids in
            if let selection, !ids.contains(selection) { self.selection = nil }
        }
        .onChange(of: store.lockRevision) { _, _ in
            selection = nil
            query = ""
            confirmDelete = nil
            sheet = nil
            fileTask?.cancel()
            openPanel?.cancel(nil)
            codeFeedback = nil
            unlockPassword = ""
            showRecovery = false
        }
        .onDisappear {
            fileTask?.cancel()
            openPanel?.cancel(nil)
            feedbackTask?.cancel()
            commands.selectedAccountID = nil
            store.lock()
        }
    }

    private var locked: some View {
        gate(icon: "lock.shield", title: tr("Vault Locked", "验证码库已锁定"),
             message: tr("Unlock to view your accounts and copy verification codes.", "解锁后可以查看账号及复制验证码。")) {
            VStack(spacing: 14) {
                if showRecovery {
                    SecureField(tr("Recovery password", "恢复口令"), text: $unlockPassword)
                        .accessibilityIdentifier("vault-recovery-password")
                        .textFieldStyle(.roundedBorder)
                        .frame(maxWidth: 320)
                        .onSubmit(unlockWithPassword)
                    Button(tr("Unlock with Recovery Password", "使用恢复口令解锁"), action: unlockWithPassword)
                        .accessibilityIdentifier("vault-recovery-submit")
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                        .disabled(unlockPassword.isEmpty || isBusy)
                    Button(tr("Use System Unlock", "使用系统解锁")) {
                        unlockPassword = ""
                        showRecovery = false
                    }
                    .buttonStyle(.link)
                } else {
                    Button(tr("Unlock Vault", "解锁验证码库")) {
                        Task { await store.unlock() }
                    }
                    .accessibilityIdentifier("vault-unlock")
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(isBusy)
                    if store.hasRecoveryPassword {
                        Button(tr("Use Recovery Password…", "使用恢复口令…")) { showRecovery = true }
                            .accessibilityIdentifier("vault-recovery-open")
                            .buttonStyle(.link)
                    }
                }
                Button(tr("Restore from Backup…", "从备份恢复…"), action: chooseBackup)
                    .buttonStyle(.link)
                    .disabled(isBusy)
            }
        }
    }

    private func gate<Actions: View>(icon: String, title: String, message: String,
                                     @ViewBuilder actions: () -> Actions) -> some View {
        VStack(spacing: 15) {
            Image(systemName: icon)
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(ink.accent)
                .frame(width: 72, height: 72)
                .background(ink.accentSoft, in: RoundedRectangle(cornerRadius: 18))
                .accessibilityHidden(true)
            Text(title).font(.system(.largeTitle, design: .default).weight(.semibold))
            Text(message)
                .foregroundStyle(ink.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 390)
            actions().padding(.top, 6)
        }
        .padding(28)
        .foregroundStyle(ink.foreground)
    }

    private var vault: some View {
        NavigationSplitView {
            List(selection: $selection) {
                ForEach(visible, id: \.id) { item in
                    accountRow(item)
                        .tag(item.id)
                        .contextMenu { accountActions(item) }
                }
            }
            .listStyle(.sidebar)
            .safeAreaInset(edge: .bottom) {
                Text(visible.count == items.count
                     ? tr("\(items.count) accounts", "\(items.count) 个账号")
                     : tr("\(visible.count) of \(items.count) accounts", "\(visible.count) 个匹配，共 \(items.count) 个账号"))
                    .font(.caption)
                    .foregroundStyle(ink.secondary)
                    .monospacedDigit()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 9)
                    .background(.bar)
            }
            .searchable(text: $query, placement: .sidebar, prompt: tr("Search Accounts", "搜索账号"))
            .navigationSplitViewColumnWidth(min: 250, ideal: 310, max: 390)
            .navigationTitle(tr("Accounts", "账号"))
        } detail: {
            VStack(spacing: 0) {
                if appActive {
                    reminders
                }
                Group {
                    if let selected {
                        if appActive {
                            accountDetail(selected)
                        } else {
                            ContentUnavailableView(tr("Vault Paused", "验证码已暂停"), systemImage: "lock.shield")
                        }
                    } else if items.isEmpty {
                        ContentUnavailableView {
                            Label(tr("No Accounts", "没有账号"), systemImage: "key.horizontal")
                        } description: {
                            Text(tr("Add a service to start generating codes.", "添加服务后即可生成验证码。"))
                        } actions: {
                            Button(tr("Add Account…", "添加账号…")) { present(.account(nil, nil)) }
                                .accessibilityIdentifier("vault-add-account")
                                .buttonStyle(.borderedProminent)
                                .disabled(isBusy)
                        }
                    } else if visible.isEmpty {
                        ContentUnavailableView.search(text: query)
                    } else {
                        ContentUnavailableView(tr("Select an Account", "选择一个账号"), systemImage: "key.horizontal")
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .background(ink.background)
        }
        .navigationSplitViewStyle(.balanced)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Menu {
                    Button(tr("Enter a Setup Key…", "输入设置密钥…")) { present(.account(nil, nil)) }
                    Button(tr("Paste QR Link…", "粘贴二维码链接…"), action: pasteSetup)
                    Button(tr("Read QR Image…", "读取二维码图片…"), action: chooseQRImage)
                    Button(tr("Scan with Camera…", "使用相机扫描…")) { present(.camera) }
                } label: {
                    Label(tr("Add Account", "添加账号"), systemImage: "plus")
                }
                .accessibilityIdentifier("vault-add-menu")
                .disabled(isBusy)
                Button { openSettings() } label: {
                    Label(tr("Settings", "设置"), systemImage: "gearshape")
                }
                Button { store.lock() } label: {
                    Label(tr("Lock Vault", "锁定验证码库"), systemImage: "lock")
                }
                .accessibilityIdentifier("vault-lock")
            }
        }
        .dropDestination(for: URL.self) { urls, _ in
            guard urls.count == 1, let url = urls.first, url.isFileURL, !isBusy, sheet == nil else {
                return false
            }
            if UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true {
                readQR(url)
            } else {
                inspectBackup(url)
            }
            return true
        }
    }

    private var secondBoundary: Date {
        Date(timeIntervalSince1970: Date.now.timeIntervalSince1970.rounded(.down))
    }

    @ViewBuilder
    private var reminders: some View {
        if let model = store.model {
            VStack(alignment: .leading, spacing: 6) {
                if !store.hasRecoveryPassword {
                    reminder(tr("No recovery password is set.", "尚未设置恢复口令。"),
                             actionTitle: tr("Set Password…", "设置口令…")) { present(.changePassword) }
                } else if !model.items.isEmpty && model.updatedAt != lastBackupRevision {
                    reminder(tr("The current vault has no recent backup.", "当前验证码库尚未备份。"),
                             actionTitle: tr("Export Backup…", "导出备份…")) { present(.exportBackup) }
                }
                if clockChanged {
                    reminder(tr("The Mac clock changed. Check it if a code is rejected.", "Mac 时钟已变化。验证码不匹配时请检查时间。"),
                             actionTitle: tr("Diagnostics…", "诊断…")) { present(.diagnostics) }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
        }
    }

    private func reminder(_ message: String, actionTitle: String, action: @escaping () -> Void) -> some View {
        HStack {
            Label(message, systemImage: "info.circle")
                .font(.callout)
                .foregroundStyle(ink.secondary)
            Spacer(minLength: 8)
            Button(actionTitle, action: action).buttonStyle(.link)
                .disabled(isBusy)
        }
        .padding(9)
        .background(ink.surface, in: RoundedRectangle(cornerRadius: 10))
    }

    private func accountRow(_ item: VaultItem) -> some View {
        HStack(spacing: 10) {
            Avatar(title: item.issuer ?? item.accountName, ink: ink, size: 30)
            VStack(alignment: .leading, spacing: 3) {
                Text(item.issuer ?? item.accountName).fontWeight(.medium).lineLimit(1)
                if item.issuer != nil {
                    Text(item.accountName).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 6)
            if appActive && item.type == .totp {
                TimelineView(.periodic(from: secondBoundary, by: 1)) { context in
                    let warning = secondsRemaining(item, at: context.date) <= 5
                    let code = defaultHidden ? String(repeating: "•", count: item.digits)
                        : store.code(item, at: context.date) ?? "—"
                    Text(grouped(code))
                        .font(.system(.caption, design: .monospaced).weight(.medium))
                        .foregroundStyle(warning ? ink.warningText : Color.primary)
                        .accessibilityLabel(Text(defaultHidden ? tr("Code hidden", "验证码已隐藏") : code))
                }
            } else if appActive {
                Text("HOTP").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private func accountDetail(_ item: VaultItem) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 14) {
                    Avatar(title: item.issuer ?? item.accountName, ink: ink, size: 46)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.issuer ?? item.accountName)
                            .font(.system(.title, design: .default).weight(.semibold))
                            .textSelection(.enabled)
                            .accessibilityIdentifier(item.issuer == nil ? "account-detail-name" : "account-detail-issuer")
                        if item.issuer != nil {
                            Text(item.accountName)
                                .foregroundStyle(ink.secondary)
                                .textSelection(.enabled)
                                .accessibilityIdentifier("account-detail-name")
                        }
                    }
                    Spacer()
                    Menu {
                        accountActions(item)
                    } label: {
                        Label(tr("Account Actions", "账号操作"), systemImage: "ellipsis.circle")
                            .labelStyle(.iconOnly)
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }

                if item.type == .totp {
                    codeCard(item)
                } else {
                    Label(tr("This version preserves HOTP accounts without generating verification codes.",
                             "此版本暂不生成 HOTP 验证码，账号信息会保留。"),
                          systemImage: "info.circle")
                        .foregroundStyle(ink.secondary)
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text(tr("Account Details", "账号详情")).font(.headline)
                    metadata(tr("Type", "类型"), item.type.rawValue)
                    metadata(tr("Algorithm", "算法"), item.algorithm.rawValue)
                    metadata(tr("Digits", "位数"), "\(item.digits)")
                    if item.type == .totp {
                        metadata(tr("Period", "周期"), tr("\(item.period) seconds", "\(item.period) 秒"))
                    } else {
                        metadata(tr("Counter", "计数器"), "\(item.counter ?? 0)")
                    }
                    if !item.tags.isEmpty {
                        metadata(tr("Tags", "标签"), item.tags.joined(separator: ", "))
                    }
                    if let website = item.website {
                        metadata(tr("Website", "网站"), website)
                    }
                }
                .padding(.horizontal, 2)
            }
            .frame(maxWidth: 586, alignment: .leading)
            .padding(30)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .foregroundStyle(ink.foreground)
    }

    private func codeCard(_ item: VaultItem) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            TimelineView(.periodic(from: secondBoundary, by: 1)) { context in
                let remaining = secondsRemaining(item, at: context.date)
                let expiring = remaining <= 5
                let rawCode = defaultHidden ? nil : store.code(item, at: context.date)
                VStack(alignment: .leading, spacing: 14) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(tr("VERIFICATION CODE", "验证码"))
                            .font(.system(.caption, design: .default).weight(.semibold))
                            .tracking(1.8)
                            .foregroundStyle(ink.secondary)
                        Spacer()
                        Text(tr("\(remaining)s remaining", "剩余 \(remaining) 秒"))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(expiring ? ink.warningText : ink.secondary)
                    }
                    Text(defaultHidden
                         ? grouped(String(repeating: "•", count: item.digits))
                         : grouped(rawCode ?? "—"))
                        .font(.system(size: 42, weight: .semibold, design: .monospaced))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(expiring ? ink.warningText : ink.foreground)
                        .accessibilityLabel(Text(defaultHidden ? tr("Verification code hidden", "验证码已隐藏") : tr("Verification code", "验证码")))
                        .accessibilityValue(Text(rawCode ?? ""))
                        .accessibilityIdentifier("account-code")
                    ProgressView(value: Double(remaining), total: Double(max(1, item.period)))
                        .tint(expiring ? ink.warning : ink.accent)
                        .accessibilityHidden(true)
                }
            }

            Button { copy(item) } label: {
                Label(tr("Copy Code", "复制验证码"), systemImage: "document.on.document")
            }
            .accessibilityIdentifier("account-copy")
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .padding(22)
        .background(ink.surface, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(ink.border))
    }

    private func metadata(_ label: String, _ value: String) -> some View {
        LabeledContent(label) { Text(value).textSelection(.enabled) }
            .foregroundStyle(ink.secondary)
    }

    @ViewBuilder
    private func accountActions(_ item: VaultItem) -> some View {
        Button(tr("Copy Verification Code", "复制验证码")) { copy(item) }
            .disabled(!appActive || item.type != .totp)
        Button(tr("Edit Account…", "编辑账号…")) { present(.account(item.id, nil)) }.disabled(isBusy || !appActive)
        Divider()
        Button(tr("Move Up", "上移")) { Task { await store.move(item.id, direction: -1) } }
            .disabled(isBusy || items.first?.id == item.id)
        Button(tr("Move Down", "下移")) { Task { await store.move(item.id, direction: 1) } }
            .disabled(isBusy || items.last?.id == item.id)
        Divider()
        Button(tr("Delete…", "删除…"), role: .destructive) { confirmDelete = item.id }.disabled(isBusy)
    }

    private func secondsRemaining(_ item: VaultItem, at date: Date) -> Int {
        guard item.type == .totp, item.period > 0 else { return 0 }
        return item.period - Int(max(0, date.timeIntervalSince1970)) % item.period
    }

    private func grouped(_ value: String) -> String {
        guard value.count > 4 else { return value }
        let index = value.index(value.startIndex, offsetBy: value.count / 2)
        return String(value[..<index]) + " " + String(value[index...])
    }

    private func copy(_ item: VaultItem) {
        guard appActive, item.type == .totp, store.copy(item) else { return }
        codeFeedback = tr("Code copied", "验证码已复制")
        AccessibilityNotification.Announcement(tr("Verification code copied", "验证码已复制")).post()
        feedbackTask?.cancel()
        feedbackTask = Task {
            do {
                try await Task.sleep(for: .seconds(2))
                codeFeedback = nil
            } catch {}
        }
    }

    private func unlockWithPassword() {
        guard !unlockPassword.isEmpty, !isBusy else { return }
        let password = unlockPassword
        unlockPassword = ""
        Task { await store.unlock(password: password) }
    }

    private func handle(_ request: DesktopRequest) {
        guard appActive, sheet == nil, confirmDelete == nil, openPanel == nil else { return }
        switch request {
        case .add: if store.model != nil { present(.account(nil, nil)) }
        case .copy: if let selected { copy(selected) }
        case .edit: if let selected { present(.account(selected.id, nil)) }
        case .importBackup: if store.consent { chooseBackup() }
        case .exportBackup: if store.model != nil { present(.exportBackup) }
        case .diagnostics: present(.diagnostics)
        case .privacy: present(.privacy)
        case .about: present(.about)
        case .changePassword: if store.model != nil { present(.changePassword) }
        case .onboarding: present(.onboarding)
        }
    }

    private func present(_ content: DesktopSheet.Content) {
        guard !isBusy else { return }
        store.errorMessage = nil
        NSApplication.shared.windows.first { $0.identifier?.rawValue == "vault" }?.makeKeyAndOrderFront(nil)
        sheet = DesktopSheet(content: content)
    }

    @ViewBuilder
    private func sheetView(_ content: DesktopSheet.Content, sheetID: UUID) -> some View {
        let close: () -> Void = { if sheet?.id == sheetID { sheet = nil } }
        switch content {
        case .setup: SetupSheet(store: store, close: close)
        case let .account(id, draft):
            AccountEditor(store: store, existing: items.first { $0.id == id }, draft: draft) {
                selection = $0
                close()
            } close: { close() }
        case let .importBackup(url, format):
            ImportSheet(store: store, url: url, format: format, close: close)
        case .exportBackup:
            if store.hasRecoveryPassword {
                ExportSheet(store: store, close: close)
            } else {
                PasswordSheet(store: store, close: close)
            }
        case .camera:
            CameraSheet(close: close) { value in
                close()
                // Open the editor on the next run loop after dismissing the camera.
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(250))
                    guard store.model != nil, appActive, sheet == nil else { return }
                    openDraft(value)
                }
            }
        case .changePassword: PasswordSheet(store: store, close: close)
        case .privacy: PrivacySheet(store: store, close: close)
        case .diagnostics: DiagnosticsSheet(store: store, clockChanged: $clockChanged, close: close)
        case .about: AboutSheet(close: close)
        case .onboarding: OnboardingSheet(close: close)
        }
    }

    private func pasteSetup() {
        guard let setup = NSPasteboard.general.string(forType: .string), !setup.isEmpty else {
            store.errorMessage = tr("The clipboard contains no setup link or key.", "剪贴板没有设置链接或密钥。")
            return
        }
        openDraft(setup)
    }

    private func openDraft(_ setup: String) {
        store.errorMessage = nil
        do {
            present(.account(nil, try AccountSetupInput.parse(setup)))
        } catch let error as AccountSetupInput {
            store.errorMessage = error.errorDescription
        } catch {
            store.errorMessage = AccountSetupInput.invalid.errorDescription
        }
    }

    private func chooseBackup() {
        if store.model != nil && !store.hasRecoveryPassword {
            present(.changePassword)
            return
        }
        openFile(prompt: tr("Choose Backup", "选取备份"), types: [.data]) { url in inspectBackup(url) }
    }

    private func chooseQRImage() {
        openFile(prompt: tr("Choose QR Image", "选取二维码图片"), types: [.image]) { url in readQR(url) }
    }

    private func openFile(prompt: String, types: [UTType], receive: @escaping (URL) -> Void) {
        guard !isBusy, sheet == nil, openPanel == nil else { return }
        store.errorMessage = nil
        let panel = NSOpenPanel()
        panel.prompt = prompt
        panel.allowedContentTypes = types
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        let revision = store.lockRevision
        openPanel = panel
        let didChoose: (NSApplication.ModalResponse) -> Void = { response in
            openPanel = nil
            guard response == .OK, store.lockRevision == revision, let url = panel.url else { return }
            receive(url)
        }
        if let window = NSApplication.shared.keyWindow {
            panel.beginSheetModal(for: window, completionHandler: didChoose)
        } else {
            panel.begin(completionHandler: didChoose)
        }
    }

    private func inspectBackup(_ url: URL) {
        guard store.consent, !isBusy, sheet == nil else { return }
        if store.model != nil && !store.hasRecoveryPassword {
            present(.changePassword)
            return
        }
        let requiredUnlocked = store.model != nil
        fileBusy = true
        fileTask = Task {
            let format = await store.detectImport(url)
            fileBusy = false
            guard !Task.isCancelled, (!requiredUnlocked || store.model != nil), let format else { return }
            present(.importBackup(url, format))
        }
    }

    private func readQR(_ url: URL) {
        guard store.model != nil, !isBusy, sheet == nil else { return }
        fileBusy = true
        fileTask = Task {
            defer { fileBusy = false }
            do {
                let raw = try await Task.detached(priority: .userInitiated) { try QRImage.read(url) }.value
                guard !Task.isCancelled, store.model != nil else { return }
                fileBusy = false
                openDraft(raw)
            } catch {
                if !Task.isCancelled && store.model != nil {
                    store.errorMessage = (error as? QRFailure)?.errorDescription
                        ?? tr("Could not read the selected image.", "无法读取所选图片。")
                }
            }
        }
    }
}

struct QuietInk {
    let dark: Bool

    var background: Color { color(0xF6F7F9, 0x090A0C) }
    var surface: Color { color(0xFFFFFF, 0x121418) }
    var foreground: Color { color(0x0E1116, 0xF4F6F9) }
    var secondary: Color { color(0x56606E, 0xA7AFBC) }
    var border: Color { color(0xE2E6EB, 0x232830) }
    var accent: Color { color(0x2B4EFF, 0x7C93FF) }
    var accentSoft: Color { color(0xE9EEFF, 0x1B2140) }
    var warning: Color { color(0xF79009, 0xFDB022) }
    var warningText: Color { color(0x8A5A10, 0xFDB022) }
    var success: Color { color(0x0B6B5B, 0x3DDC97) }

    private func color(_ light: UInt32, _ darkColor: UInt32) -> Color {
        let rgb = dark ? darkColor : light
        return Color(red: Double((rgb >> 16) & 255) / 255,
                     green: Double((rgb >> 8) & 255) / 255,
                     blue: Double(rgb & 255) / 255)
    }
}

private struct Avatar: View {
    let title: String
    let ink: QuietInk
    let size: CGFloat

    var body: some View {
        Text(String(title.prefix(1)).uppercased())
            .font(.system(size: size * 0.43, weight: .semibold))
            .foregroundStyle(ink.accent)
            .frame(width: size, height: size)
            .background(ink.accentSoft, in: RoundedRectangle(cornerRadius: 10))
            .accessibilityHidden(true)
    }
}

struct DesktopNotice: View {
    let message: String
    let dismiss: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 9) {
            Image(systemName: "exclamationmark.circle").accessibilityHidden(true)
            Text(message).fixedSize(horizontal: false, vertical: true)
            Button(tr("Dismiss", "关闭"), systemImage: "xmark", action: dismiss).labelStyle(.iconOnly)
                .buttonStyle(.borderless)
        }
        .foregroundStyle(.primary)
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
    }
}

func tr(_ english: String, _ chinese: String) -> String {
    Locale.preferredLanguages.first?.hasPrefix("zh") == true ? chinese : english
}

private struct PrivateWindowConfiguration: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { PrivateWindowView() }
    func updateNSView(_ view: NSView, context: Context) {}

    private final class PrivateWindowView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.sharingType = .none
        }
    }
}


struct DesktopBusyIndicator: View {
    @State private var visible = false

    var body: some View {
        Color.clear
            .frame(width: 20, height: 20)
            .overlay {
                if visible {
                    ProgressView().controlSize(.small).accessibilityLabel(tr("Working", "正在处理"))
                }
            }
            .task {
                do {
                    try await Task.sleep(for: .milliseconds(300))
                    if NSApplication.shared.isActive { visible = true }
                } catch {}
            }
    }
}
