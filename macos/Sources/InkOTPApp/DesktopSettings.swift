import AppKit
import CryptoKit
import Darwin
import InkOTPCore
import SwiftUI

@MainActor
struct DesktopSettings: View {
    @ObservedObject var store: VaultStore
    @ObservedObject var commands: DesktopCommands
    @Environment(\.openWindow) private var openWindow
    @AppStorage("export.last-success") private var lastBackup = 0.0
    @State private var confirmingWithdrawal = false
    @State private var selectedTab = SettingsTab.security

    private enum SettingsTab: Hashable { case security, backups, help }

    var body: some View {
        TabView(selection: $selectedTab) {
            security.tabItem { Label(tr("Security", "安全"), systemImage: "lock.shield") }.tag(SettingsTab.security)
            backups.tabItem { Label(tr("Backup", "备份"), systemImage: "externaldrive") }.tag(SettingsTab.backups)
            help.tabItem { Label(tr("Help", "帮助"), systemImage: "questionmark.circle") }.tag(SettingsTab.help)
        }
        .padding(18)
        .frame(width: 530, height: 400)
        .confirmationDialog(tr("Withdraw privacy consent?", "撤回隐私同意？"), isPresented: $confirmingWithdrawal,
                            titleVisibility: .visible) {
            Button(tr("Withdraw and Lock", "撤回并锁定"), role: .destructive) { store.withdrawPrivacy() }
            Button(tr("Cancel", "取消"), role: .cancel) {}
        } message: {
            Text(tr("InkOTP will lock the vault and ask you to review the statement before you use it again. Your existing vault stays on this Mac.",
                    "InkOTP 将锁定验证码库，下次使用前需重新阅读声明。现有验证码库仍保存在这台 Mac 上。"))
        }
        .overlay(alignment: .bottom) {
            if let errorMessage = store.errorMessage {
                DesktopNotice(message: errorMessage) { store.errorMessage = nil }.padding(8)
            }
        }
        .modifier(DesktopPrivacyCurtain())
    }

    private var security: some View {
        Form {
            Section {
                Toggle(tr("Verify my identity each time", "每次进入时验证身份"), isOn: Binding(
                    get: { store.systemAuthentication },
                    set: { newValue in Task { await store.setSystemAuthentication(newValue) } }
                ))
                .disabled(store.model == nil || store.busy || !store.hasRecoveryPassword
                          || (!store.supportsSystemAuthentication && !store.systemAuthentication))
                Text(authenticationHelp).font(.caption).foregroundStyle(.secondary)
                Picker(tr("Lock when leaving the app", "离开应用后的锁定时间"), selection: setting(\.lockTimeoutSeconds, fallback: 30)) {
                    if let value = store.model?.settings.lockTimeoutSeconds, ![0, 30, 60].contains(value) {
                        Text(tr("\(value) seconds", "\(value) 秒")).tag(value)
                    }
                    Text(tr("Immediately", "立即")).tag(0)
                    Text(tr("After 30 Seconds", "30 秒后")).tag(30)
                    Text(tr("After 1 Minute", "1 分钟后")).tag(60)
                }
                .accessibilityIdentifier("settings-lock-timeout")
                .disabled(store.systemAuthentication || store.model == nil || store.busy)
            }
            Section {
                Toggle(tr("Hide verification codes", "隐藏验证码"), isOn: setting(\.hideCodesByDefault, fallback: false))
                    .accessibilityIdentifier("settings-hide-codes")
                Picker(tr("Clear copied codes", "清理已复制的验证码"), selection: setting(\.clipboardClearSeconds, fallback: 30)) {
                    if let value = store.model?.settings.clipboardClearSeconds, ![0, 1, 15, 30, 60].contains(value) {
                        Text(tr("\(value) seconds", "\(value) 秒")).tag(value)
                    }
                    Text(tr("Off", "关闭")).tag(0)
                    Text(tr("Immediately", "立即")).tag(1)
                    Text(tr("After 15 Seconds", "15 秒后")).tag(15)
                    Text(tr("After 30 Seconds", "30 秒后")).tag(30)
                    Text(tr("After 1 Minute", "1 分钟后")).tag(60)
                }
            }
            .disabled(store.model == nil || store.busy)
            Section {
                Button(store.hasRecoveryPassword ? tr("Change Recovery Password…", "修改恢复口令…")
                       : tr("Set Recovery Password…", "设置恢复口令…")) { send(.changePassword) }
                    .disabled(store.model == nil || store.busy)
                Button(tr("Lock Now", "立即锁定")) { store.lock() }.disabled(store.model == nil)
            }
        }
        .formStyle(.grouped)
    }

    private var authenticationHelp: String {
        if !store.supportsSystemAuthentication {
            return tr("Protected Keychain access requires a Developer ID build with the matching Apple provisioning profile.",
                      "系统保护的钥匙串访问需要带有对应 Apple 描述文件的 Developer ID 版本。")
        }
        if !store.hasRecoveryPassword {
            return tr("Set a recovery password to enable entry authentication.", "设置恢复口令后，可开启每次进入验证。")
        }
        return tr("Uses Touch ID or your Mac login password in the system dialogue.", "通过系统界面使用 Touch ID 或 Mac 登录密码。")
    }

    private var backups: some View {
        Form {
            Section {
                Button(tr("Export Backup…", "导出备份…")) { send(.exportBackup) }
                    .disabled(store.model == nil || store.busy)
                Button(tr("Import Backup…", "导入备份…")) { send(.importBackup) }
                    .disabled(!store.consent || store.busy)
            }
            Section("Google Authenticator") {
                Button(tr("Import Transfer QR Codes…", "导入迁移二维码…")) { send(.importGoogle) }
                Button(tr("Export Transfer QR Codes…", "导出迁移二维码…")) { send(.exportGoogle) }
            }
            .disabled(store.model == nil || store.busy)
            Section {
                LabeledContent(tr("Last export", "上次导出"), value: lastBackup == 0
                               ? tr("Never", "从未") : Date(timeIntervalSince1970: lastBackup).formatted(date: .abbreviated, time: .shortened))
                Text(tr("Backups are saved into a location you choose. InkOTP recommends the encrypted Aegis format.",
                        "备份保存到你选择的位置。InkOTP 推荐使用 Aegis 加密格式。"))
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    private var help: some View {
        Form {
            Section {
                Button(tr("Diagnostics…", "诊断…")) { send(.diagnostics) }
                Button(tr("Getting Started…", "首次启动引导…")) { send(.onboarding) }
                Button(tr("About and Licenses…", "关于与开源许可…")) { send(.about) }
            }
            Section {
                Button(tr("Privacy Statement…", "隐私声明…")) { send(.privacy) }
                Button(tr("Withdraw Privacy Consent…", "撤回隐私同意…")) { confirmingWithdrawal = true }
                    .disabled(!store.consent)
            }
        }
        .formStyle(.grouped)
    }

    private func setting<Value>(_ keyPath: WritableKeyPath<VaultSettings, Value>, fallback: Value) -> Binding<Value> {
        Binding(get: { store.model?.settings[keyPath: keyPath] ?? fallback }, set: { value in
            Task {
                guard var settings = store.model?.settings else { return }
                settings[keyPath: keyPath] = value
                await store.updateSettings(settings)
            }
        })
    }

    private func send(_ request: DesktopRequest) {
        openWindow(id: "vault")
        commands.send(request)
    }
}

enum DesktopPrivacy {
    private static let english = bundledText("Privacy.en", extension: "md")
    private static let chinese = bundledText("Privacy.zh-Hans", extension: "md")

    static var available: Bool { !english.isEmpty && !chinese.isEmpty }
    static var statement: String { tr(english, chinese) }
    static var revision: String {
        guard available else { return "" }
        return SHA256.hash(data: Data((english + "\u{0000}" + chinese).utf8))
            .map { String(format: "%02x", $0) }.joined()
    }
}

@MainActor
struct PrivacySheet: View {
    @ObservedObject var store: VaultStore
    let close: () -> Void
    var allowConsent = true

    var body: some View {
        InformationDialog(title: tr("Privacy Statement", "隐私声明"), close: close, width: 658,
                          titleIdentifier: "privacy-document") {
            if DesktopPrivacy.available {
                let paragraphs = DesktopPrivacy.statement.components(separatedBy: "\n\n")
                let firstHeading = paragraphs.firstIndex { $0.hasPrefix("## ") }
                ScrollView {
                    VStack(alignment: .leading, spacing: 13) {
                        ForEach(Array(paragraphs.enumerated()), id: \.offset) { index, paragraph in
                            if paragraph.hasPrefix("## ") {
                                let heading = String(paragraph.dropFirst(3)).trimmingCharacters(in: .whitespacesAndNewlines)
                                Text(heading)
                                    .font(.headline)
                                    .accessibilityLabel(Text(heading))
                                    .accessibilityAddTraits(.isHeader)
                                    .accessibilityIdentifier(index == firstHeading ? "privacy-first-section" : "privacy-section-\(index)")
                            } else {
                                Text(paragraph.trimmingCharacters(in: .whitespacesAndNewlines))
                                    .textSelection(.enabled)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.trailing, 12)
                }
                .frame(height: 430)
            } else {
                Label(tr("The privacy statement is missing from this application package.", "当前应用包缺少隐私声明。"),
                      systemImage: "exclamationmark.triangle")
            }
            if allowConsent && !store.consent && DesktopPrivacy.available {
                Button(tr("Continue", "同意并继续")) {
                    store.acceptPrivacy()
                    close()
                }
                .accessibilityIdentifier("privacy-accept")
                .buttonStyle(.borderedProminent)
            }
        }
    }
}

@MainActor
struct DiagnosticsSheet: View {
    @ObservedObject var store: VaultStore
    @Binding var clockChanged: Bool
    let close: () -> Void
    @State private var sampledTime = Date.now
    @State private var recalculatedCount: Int?
    @State private var recalculation: Task<Void, Never>?
    @State private var residentBytes: UInt64?
    @State private var privateBytes: Int?
    @State private var refreshed = false
    @State private var resourceSampling: Task<Void, Never>?

    var body: some View {
        InformationDialog(title: tr("Diagnostics", "诊断"), close: close) {
            clockDetails
            Divider()
            resourceDetails
        }
        .onDisappear {
            recalculation?.cancel()
            resourceSampling?.cancel()
        }
    }

    private var clockDetails: some View {
        VStack(alignment: .leading, spacing: 11) {
            LabeledContent(tr("Mac time", "Mac 时间"), value: sampledTime.formatted(date: .abbreviated, time: .standard))
            LabeledContent(tr("Time zone", "时区"), value: TimeZone.autoupdatingCurrent.identifier)
            if clockChanged {
                Label(tr("The system clock changed while InkOTP was open. Confirm the time before copying a new code.",
                         "InkOTP 运行期间系统时钟发生变更。请确认时间正确后复制新验证码。"),
                      systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            }
            Text(tr("Check your Mac’s date, time and time zone, then verify that your service, account and setup key match the requesting site.",
                    "请核对 Mac 的日期、时间和时区，再确认验证码对应的服务、账号及设置密钥。"))
                .foregroundStyle(.secondary)
            HStack {
                Button(tr("Refresh Time and Codes", "刷新时间与验证码"), action: recalculate)
                    .accessibilityIdentifier("diagnostics-recalculate")
                    .disabled(store.model == nil || recalculation != nil)
                Button(tr("Open System Settings", "打开系统设置")) {
                    if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.systempreferences") {
                        NSWorkspace.shared.open(url)
                    }
                }
            }
            if let recalculatedCount {
                let text = recalculatedCount == 1 ? "Recalculated 1 TOTP code" : "Recalculated \(recalculatedCount) TOTP codes"
                Text(tr(text, "已重新计算 \(recalculatedCount) 个 TOTP 验证码"))
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("diagnostics-recalculated")
            }
            Text(tr("Automatic time is managed in System Settings → General → Date & Time.",
                    "自动设置时间由系统设置 → 通用 → 日期与时间管理。"))
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private var resourceDetails: some View {
        VStack(alignment: .leading, spacing: 11) {
            Text(tr("Resources", "资源")).font(.headline).accessibilityAddTraits(.isHeader)
            if refreshed {
                LabeledContent(tr("Process resident memory", "进程常驻内存"), value: formatBytes(residentBytes))
                    .accessibilityIdentifier("diagnostics-resource-result")
                LabeledContent(tr("Private vault files", "私有库文件"), value: formatBytes(privateBytes.flatMap { UInt64(exactly: $0) }))
                LabeledContent(tr("Unlocked entries", "已解锁账号数"), value: store.model.map { "\($0.items.count)" } ?? "—")
            }
            Button(tr("Sample Resources", "采样资源"), action: sampleResources)
                .accessibilityIdentifier("diagnostics-sample-resources")
                .disabled(resourceSampling != nil)
        }
    }

    private func recalculate() {
        guard recalculation == nil else { return }
        let timestamp = Date.now
        recalculation = Task {
            defer { recalculation = nil }
            guard let count = await store.recalculateCodes(at: timestamp), !Task.isCancelled else { return }
            sampledTime = timestamp
            clockChanged = false
            recalculatedCount = count
        }
    }

    private func sampleResources() {
        guard resourceSampling == nil, NSApplication.shared.isActive else { return }
        let revision = store.lockRevision
        let resident = Self.sampleResidentBytes()
        resourceSampling = Task {
            defer { resourceSampling = nil }
            let bytes = await store.diskBytes()
            guard !Task.isCancelled, store.lockRevision == revision else { return }
            residentBytes = resident
            privateBytes = bytes
            refreshed = true
        }
    }

    private func formatBytes(_ count: UInt64?) -> String {
        guard let count, let signed = Int64(exactly: count) else { return "—" }
        return ByteCountFormatter.string(fromByteCount: signed, countStyle: .file)
    }

    private static func sampleResidentBytes() -> UInt64? {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<integer_t>.size)
        let status = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        return status == KERN_SUCCESS ? UInt64(info.resident_size) : nil
    }
}

@MainActor
struct AboutSheet: View {
    @ObservedObject var store: VaultStore
    let close: () -> Void
    @State private var selectedLicense = 0
    @State private var showingPrivacy = false
    private let version = (Bundle.main.object(forInfoDictionaryKey: "InkOTPReleaseVersion") as? String)
        ?? (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "—"

    var body: some View {
        if showingPrivacy {
            PrivacySheet(store: store, close: { showingPrivacy = false }, allowConsent: false)
        } else {
            about
        }
    }

    private var about: some View {
        InformationDialog(title: tr("About and Licenses", "关于与开源许可"), close: close, width: 628) {
            HStack(spacing: 15) {
                Image(nsImage: NSApplication.shared.applicationIconImage)
                    .resizable().frame(width: 58, height: 58).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("InkOTP").font(.title.weight(.semibold))
                    Text(tr("Version \(version) · Offline authenticator", "版本 \(version) · 离线验证器"))
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            HStack(spacing: 16) {
                Link(tr("Source Code", "查看源代码"), destination: URL(string: "https://github.com/rhczz/ink-otp")!)
                Button(tr("Privacy Statement", "隐私声明")) { showingPrivacy = true }
                    .buttonStyle(.link)
                    .accessibilityIdentifier("about-privacy")
            }
            Picker(tr("License", "许可证"), selection: $selectedLicense) {
                Text("GPL-3.0").tag(0)
                Text(tr("Third-Party Notices", "第三方声明")).tag(1)
            }
            .pickerStyle(.segmented)
            ScrollView {
                let license = selectedLicense == 0 ? bundledText("LICENSE", extension: nil)
                    : bundledText("ThirdPartyNotices", extension: "txt")
                Text(license.isEmpty ? tr("License text is unavailable in this package.", "当前应用包未包含许可证文本。") : license)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(height: 230)
        }
    }
}

struct OnboardingSheet: View {
    let close: () -> Void

    var body: some View {
        InformationDialog(title: tr("Getting Started", "首次启动引导"), close: close) {
            Label(tr("Add an account with a setup key, QR image or camera scan.", "使用设置密钥、二维码图片或相机扫码添加账号。"),
                  systemImage: "plus.circle")
            Label(tr("Select an account to copy its code, even if you hide it from the screen.", "选择账号后即可复制验证码，隐藏时也可以复制。"),
                  systemImage: "document.on.document")
            Label(tr("Set a recovery password and export an encrypted backup to a location you control.",
                     "设置恢复口令，并将加密备份保存到由你管理的位置。"),
                  systemImage: "lock.doc")
        }
    }
}

struct InformationDialog<Content: View>: View {
    let title: String
    let close: () -> Void
    var width: CGFloat = 530
    var titleIdentifier = "info-title"
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 17) {
            Text(title)
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier(titleIdentifier)
            content
            HStack {
                Spacer()
                Button(tr("Done", "完成"), action: close)
                    .keyboardShortcut(.cancelAction)
                    .accessibilityIdentifier("info-done")
            }
        }
        .padding(24)
        .frame(width: width)
        .modifier(DesktopPrivacyCurtain())
    }
}

private func bundledText(_ name: String, extension fileExtension: String?) -> String {
    guard let url = Bundle.main.url(forResource: name, withExtension: fileExtension) else { return "" }
    return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
}
