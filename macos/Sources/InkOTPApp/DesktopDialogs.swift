import AppKit
import InkOTPCore
import SwiftUI
import UniformTypeIdentifiers

@MainActor
struct DesktopDialog<Content: View, Actions: View>: View {
    @ObservedObject var store: VaultStore
    let title: String
    let close: () -> Void
    let cancelBusy: (() -> Void)?
    let content: Content
    let actions: Actions

    init(store: VaultStore, title: String, close: @escaping () -> Void, cancelBusy: (() -> Void)? = nil,
         @ViewBuilder content: () -> Content, @ViewBuilder actions: () -> Actions) {
        self.store = store
        self.title = title
        self.close = close
        self.cancelBusy = cancelBusy
        self.content = content()
        self.actions = actions()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
            content.disabled(store.busy)
            if let message = store.errorMessage {
                DesktopNotice(message: message) { store.errorMessage = nil }
            }
            HStack {
                if store.busy {
                    DesktopBusyIndicator()
                }
                Spacer()
                Button(tr("Cancel", "取消"), action: store.busy ? (cancelBusy ?? close) : close)
                    .keyboardShortcut(.cancelAction)
                    .disabled(store.busy && cancelBusy == nil)
                actions.disabled(store.busy)
            }
        }
        .padding(24)
        .frame(width: 540)
        .interactiveDismissDisabled(store.busy)
    }
}

@MainActor
struct SetupSheet: View {
    @ObservedObject var store: VaultStore
    let close: () -> Void
    @State private var password = ""
    @State private var confirmation = ""
    @State private var systemAuthentication = false

    var body: some View {
        DesktopDialog(store: store, title: tr("Create Vault", "新建验证码库"), close: close) {
            VStack(alignment: .leading, spacing: 12) {
                Text(tr("Set a recovery password to unlock the vault if your Mac’s device key becomes unavailable.", "设置恢复口令，以便本机设备密钥不可用时解锁验证码库。"))
                    .foregroundStyle(.secondary)
                Form {
                    SecureField(tr("Recovery password (optional)", "恢复口令（可选）"), text: $password)
                        .accessibilityIdentifier("vault-setup-password")
                    SecureField(tr("Confirm password", "确认口令"), text: $confirmation)
                        .accessibilityIdentifier("vault-setup-password-confirm")
                    if store.supportsSystemAuthentication {
                        Toggle(tr("Verify my identity each time", "每次进入时验证身份"), isOn: $systemAuthentication)
                            .disabled(password.isEmpty)
                    }
                }
                .textFieldStyle(.roundedBorder)
                if !password.isEmpty && !confirmation.isEmpty && password != confirmation {
                    Text(tr("Passwords do not match.", "两次输入的口令不一致。"))
                        .font(.callout).foregroundStyle(.red)
                }
            }
        } actions: {
            Button(tr("Create Vault", "创建验证码库")) {
                let initialPassword = password.isEmpty ? nil : password
                let authentication = systemAuthentication && initialPassword != nil
                Task {
                    if await store.initialize(password: initialPassword, systemAuthentication: authentication) { close() }
                }
            }
            .accessibilityIdentifier("vault-setup-submit")
            .keyboardShortcut(.defaultAction)
            .disabled(password != confirmation)
        }
        .onDisappear { password = ""; confirmation = "" }
    }
}

enum AccountSetupInput: LocalizedError {
    case invalid, migration, manualHOTP

    var errorDescription: String? {
        switch self {
        case .invalid:
            tr("Enter a valid Base32 setup key or otpauth link.", "请输入有效的 Base32 设置密钥或 otpauth 链接。")
        case .migration:
            tr("Use a setup link for one account. Google Authenticator migration links are unsupported.",
               "请使用单个账号的设置链接。暂不支持 Google Authenticator 迁移链接。")
        case .manualHOTP:
            tr("Use a TOTP setup link to add an account. Existing HOTP accounts can be imported from a backup.",
               "添加账号时请使用 TOTP 设置链接。现有 HOTP 账号可以从备份导入。")
        }
    }

    static func parse(_ input: String) throws -> OTPDraft {
        guard input.utf8.count <= 16_384 else { throw Self.invalid }
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let result = try? Otpauth.parse(trimmed) else { throw Self.invalid }
        guard result.kind != .migration else { throw Self.migration }
        guard result.type == .totp else { throw Self.manualHOTP }
        return result
    }
}

@MainActor
struct AccountEditor: View {
    @ObservedObject var store: VaultStore
    let existing: VaultItem?
    let saved: (String) -> Void
    let close: () -> Void
    @State private var issuer: String
    @State private var account: String
    @State private var setupInput = ""
    @State private var setupDraft: OTPDraft?
    @State private var algorithm: OTPAlgorithm
    @State private var digits: Int
    @State private var period: String
    @State private var showAdvanced = false
    @FocusState private var focused: EditorField?

    private enum EditorField: Hashable { case account, issuer, setup }

    init(store: VaultStore, existing: VaultItem?, draft: OTPDraft?,
         saved: @escaping (String) -> Void, close: @escaping () -> Void) {
        self.store = store
        self.existing = existing
        self.saved = saved
        self.close = close
        _issuer = State(initialValue: existing?.issuer ?? draft?.issuer ?? "")
        _account = State(initialValue: existing?.accountName ?? draft?.accountName ?? "")
        _setupDraft = State(initialValue: draft)
        _algorithm = State(initialValue: existing?.algorithm ?? .sha1)
        _digits = State(initialValue: existing?.digits ?? 6)
        _period = State(initialValue: String(existing?.period ?? 30))
    }

    private var shouldPreviewLink: Bool {
        existing == nil && setupDraft == nil && setupInput.contains("://")
    }

    var body: some View {
        DesktopDialog(store: store, title: existing == nil ? tr("Add Account", "添加账号") : tr("Edit Account", "编辑账号"), close: close) {
            editorForm
        } actions: {
            editorActions
        }
        .onAppear { focused = existing == nil && setupDraft == nil ? .setup : (account.isEmpty ? .account : .issuer) }
        .onDisappear { setupInput = ""; setupDraft = nil }
    }

    private var editorForm: some View {
        Form {
            TextField(tr("Service (optional)", "服务名称（可选）"), text: $issuer)
                .accessibilityIdentifier("account-issuer")
                .focused($focused, equals: .issuer)
            TextField(tr("Account", "账号"), text: $account)
                .accessibilityIdentifier("account-name")
                .focused($focused, equals: .account)
            if existing == nil {
                setupFields
            }
        }
        .textFieldStyle(.roundedBorder)
    }

    @ViewBuilder
    private var setupFields: some View {
        if let parsed = setupDraft {
            if parsed.kind == .entry {
                LabeledContent(tr("Setup link parameters", "设置链接参数")) {
                    Text(parameterSummary(parsed))
                        .accessibilityIdentifier("account-setup-parameters")
                }
            }
            ForEach(parsed.warnings, id: \.self) { warning in
                Label(draftWarning(warning), systemImage: "exclamationmark.triangle")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Button(tr("Change Setup Key or Link", "更换设置密钥或链接")) {
                if issuer == parsed.issuer { issuer = "" }
                if account == parsed.accountName { account = "" }
                setupDraft = nil
                setupInput = ""
                store.errorMessage = nil
                focused = .setup
            }
            .buttonStyle(.link)
        } else {
            SecureField(tr("Setup key or otpauth link", "设置密钥或 otpauth 链接"), text: $setupInput)
                .accessibilityIdentifier("account-setup-input")
                .focused($focused, equals: .setup)
        }
        if !shouldPreviewLink && setupDraft?.kind != .entry {
            advancedParameters
        }
    }

    private var advancedParameters: some View {
        DisclosureGroup(tr("Verification Parameters", "验证码参数"), isExpanded: $showAdvanced) {
            Picker(tr("Algorithm", "算法"), selection: $algorithm) {
                ForEach(OTPAlgorithm.allCases, id: \.self) { algorithm in
                    Text(algorithm.rawValue).tag(algorithm)
                }
            }
            Picker(tr("Digits", "位数"), selection: $digits) {
                Text("6").tag(6)
                Text("8").tag(8)
            }
            TextField(tr("Period in seconds", "周期（秒）"), text: $period)
        }
    }

    @ViewBuilder
    private var editorActions: some View {
        if shouldPreviewLink {
            Button(tr("Preview Setup Link", "预览设置链接"), action: previewSetupLink)
                .accessibilityIdentifier("account-setup-preview")
                .keyboardShortcut(.defaultAction)
        } else {
            Button(tr("Save Account", "保存账号"), action: submit)
                .accessibilityIdentifier("account-save")
                .keyboardShortcut(.defaultAction)
                .disabled(account.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                          || (existing == nil && setupDraft == nil && setupInput.isEmpty))
        }
    }

    private func previewSetupLink() {
        do {
            let parsed = try AccountSetupInput.parse(setupInput)
            guard parsed.kind == .entry else { throw AccountSetupInput.invalid }
            if normalized(issuer).isEmpty { issuer = parsed.issuer ?? "" }
            if normalized(account).isEmpty { account = parsed.accountName ?? "" }
            setupDraft = parsed
            setupInput = ""
            store.errorMessage = nil
            focused = .account
        } catch {
            store.errorMessage = (error as? AccountSetupInput)?.errorDescription
                ?? AccountSetupInput.invalid.errorDescription
        }
    }

    private func submit() {
        let parsedIssuer = normalized(issuer)
        let parsedAccount = normalized(account)
        guard !parsedAccount.isEmpty, parsedIssuer.unicodeScalars.count <= 128,
              parsedAccount.unicodeScalars.count <= 256 else {
            store.errorMessage = tr("Enter a valid service and account name.", "请输入有效的服务和账号名称。")
            return
        }

        do {
            let item: VaultItem
            if var original = existing {
                original.issuer = parsedIssuer.isEmpty ? nil : parsedIssuer
                original.accountName = parsedAccount
                item = original
            } else {
                item = try newItem(issuer: parsedIssuer.isEmpty ? nil : parsedIssuer, account: parsedAccount)
            }
            let id = item.id
            Task {
                if await store.save(item) { saved(id) }
            }
        } catch EditorError.invalidParameters {
            store.errorMessage = tr("Use a period between 1 and 300 seconds.", "周期必须在 1 到 300 秒之间。")
        } catch let error as AccountSetupInput {
            store.errorMessage = error.errorDescription
        } catch {
            store.errorMessage = AccountSetupInput.invalid.errorDescription
        }
    }

    private func newItem(issuer: String?, account: String) throws -> VaultItem {
        let parsed = try setupDraft ?? AccountSetupInput.parse(setupInput)
        guard parsed.type == .totp else { throw AccountSetupInput.manualHOTP }
        if parsed.kind == .entry {
            guard setupDraft != nil else { throw AccountSetupInput.invalid }
            return VaultItem.make(issuer: issuer, accountName: account, secret: parsed.secret,
                                  algorithm: parsed.algorithm, digits: parsed.digits, period: parsed.period,
                                  origin: "manual")
        }

        guard (1...255).contains(parsed.secret.count) else { throw AccountSetupInput.invalid }
        guard let enteredPeriod = Int(period), (1...300).contains(enteredPeriod) else {
            throw EditorError.invalidParameters
        }
        return VaultItem.make(issuer: issuer, accountName: account, secret: parsed.secret,
                              algorithm: algorithm, digits: digits, period: enteredPeriod, origin: "manual")
    }

    private func parameterSummary(_ parsed: OTPDraft) -> String {
        let length = tr("\(parsed.digits) digits", "\(parsed.digits) 位")
        let interval = tr("\(parsed.period) seconds", "\(parsed.period) 秒")
        return [parsed.type.rawValue, parsed.algorithm.rawValue, length, interval].joined(separator: " · ")
    }

    private func normalized(_ value: String) -> String {
        let filtered = value.unicodeScalars.filter { $0.value > 31 && !(127...159).contains($0.value) }
        return String(String.UnicodeScalarView(filtered)).precomposedStringWithCanonicalMapping
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func draftWarning(_ warning: String) -> String {
        switch warning {
        case "ISSUER_MISMATCH": tr("The link supplies two service names. The query name was selected.", "链接含两个不同的服务名称，预览采用查询参数中的名称。")
        case "CONTROL_CHARS_STRIPPED": tr("Control characters were removed from the link’s names.", "链接名称中的控制字符已移除。")
        default: tr("Review the account details before saving.", "请核对账号详情后保存。")
        }
    }

    private enum EditorError: Error { case invalidParameters }
}

@MainActor
struct PasswordSheet: View {
    @ObservedObject var store: VaultStore
    let close: () -> Void
    @State private var current = ""
    @State private var password = ""
    @State private var confirmation = ""

    var body: some View {
        DesktopDialog(store: store, title: store.hasRecoveryPassword
                      ? tr("Change Recovery Password", "修改恢复口令") : tr("Set Recovery Password", "设置恢复口令"), close: close) {
            Form {
                if store.hasRecoveryPassword {
                    SecureField(tr("Current recovery password", "当前恢复口令"), text: $current)
                }
                SecureField(tr("New recovery password", "新恢复口令"), text: $password)
                SecureField(tr("Confirm new password", "确认新口令"), text: $confirmation)
            }
            .textFieldStyle(.roundedBorder)
        } actions: {
            Button(tr("Save Password", "保存口令")) {
                let old = current.isEmpty ? nil : current
                let next = password
                Task {
                    if await store.changePassword(current: old, new: next) { close() }
                }
            }
            .keyboardShortcut(.defaultAction)
            .disabled(password.isEmpty || password != confirmation || (store.hasRecoveryPassword && current.isEmpty))
        }
        .onDisappear { current = ""; password = ""; confirmation = "" }
    }
}

@MainActor
struct ImportSheet: View {
    @ObservedObject var store: VaultStore
    let url: URL
    let format: InterchangeFormat
    let close: () -> Void
    @State private var backupPassword = ""
    @State private var recoveryPassword = ""
    @State private var confirmation = ""
    @State private var preview: ImportedVault?
    @AppStorage("export.last-success") private var lastBackup = 0.0
    @AppStorage("export.last-revision") private var lastBackupRevision = ""
    @State private var decodeTask: Task<Void, Never>?
    @State private var overwriteConfirmed = false
    @State private var duplicateCount = 0
    @State private var conflictChoice = 0
    @State private var authenticate = false

    private var replacing: Bool { store.initialized && store.model == nil }
    private var creating: Bool { store.model == nil }

    private var title: String { creating ? tr("Restore from Backup", "从备份恢复") : tr("Import Backup", "导入备份") }
    private var cancelWhenBusy: (() -> Void)? {
        guard decodeTask != nil else { return nil }
        return { cancelDecode() }
    }
    private var cannotCommit: Bool {
        if replacing && !overwriteConfirmed { return true }
        return creating && (recoveryPassword.isEmpty || recoveryPassword != confirmation)
    }

    var body: some View {
        DesktopDialog(store: store, title: title, close: close, cancelBusy: cancelWhenBusy) {
            content
        } actions: {
            importActions
        }
        .onDisappear {
            backupPassword = ""
            recoveryPassword = ""
            confirmation = ""
            preview = nil
            decodeTask?.cancel()
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            LabeledContent(tr("File", "文件"), value: url.lastPathComponent)
            LabeledContent(tr("Format", "格式"), value: format.title)
            if let preview {
                importPreview(preview)
            } else if format.requiresPassword {
                SecureField(tr("Backup password", "备份口令"), text: $backupPassword)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(decode)
            }
            if format.plaintext {
                Label(tr("This file contains readable setup keys. Keep it in a location you control.",
                         "这个文件含可直接读取的设置密钥。请保存在由你管理的位置。"),
                      systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var importActions: some View {
        if preview != nil {
            Button(creating ? tr("Restore Vault", "恢复验证码库") : tr("Import Accounts", "导入账号"), action: commit)
                .keyboardShortcut(.defaultAction)
                .disabled(cannotCommit)
        } else {
            Button(tr("Preview Backup", "预览备份"), action: decode)
                .keyboardShortcut(.defaultAction)
                .disabled(format.requiresPassword && backupPassword.isEmpty)
        }
    }

    private func importPreview(_ imported: ImportedVault) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(tr("\(imported.model.items.count) accounts in backup", "备份包含 \(imported.model.items.count) 个账号"), systemImage: "tray.and.arrow.down")
                .font(.headline)
            if !replacing && !imported.model.items.isEmpty {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(imported.model.items, id: \.id) { item in
                            Text([item.issuer, item.accountName].compactMap { $0 }.joined(separator: " · "))
                                .lineLimit(2)
                        }
                    }
                    .padding(.trailing, 8)
                }
                .frame(maxHeight: 138)
            }
            ForEach(Array(imported.warnings.enumerated()), id: \.offset) { _, warning in
                Label(warning, systemImage: "exclamationmark.triangle").font(.callout)
            }
            if creating {
                if replacing {
                    Toggle(tr("Replace the existing vault after verifying my identity.", "验证身份后替换现有验证码库。"), isOn: $overwriteConfirmed)
                    Text(tr("The previous encrypted vault is retained as a rescue copy.", "此前的加密库会保留为救援副本。"))
                        .font(.callout).foregroundStyle(.secondary)
                }
                SecureField(tr("New recovery password", "新恢复口令"), text: $recoveryPassword)
                SecureField(tr("Confirm recovery password", "确认恢复口令"), text: $confirmation)
                if store.supportsSystemAuthentication && !(replacing && store.systemAuthentication) {
                    Toggle(tr("Verify my identity each time", "每次进入时验证身份"), isOn: $authenticate)
                        .disabled(recoveryPassword.isEmpty)
                }
            } else {
                Text(tr("\(duplicateCount) matching accounts. Your local settings and password will be kept.",
                        "\(duplicateCount) 个账号重复。合并保留本机设置和恢复口令。"))
                    .font(.callout).foregroundStyle(.secondary)
                Picker(tr("When an account already exists", "已有相同账号时"), selection: $conflictChoice) {
                    Text(tr("Keep Existing", "保留本机")).tag(0)
                    Text(tr("Use Backup Entry", "采用备份")).tag(1)
                    Text(tr("Keep Both", "两者都保留")).tag(2)
                }
            }
        }
        .textFieldStyle(.roundedBorder)
    }

    private func decode() {
        guard !store.busy, decodeTask == nil, preview == nil else { return }
        let password = format.requiresPassword ? backupPassword : nil
        decodeTask = Task {
            defer { decodeTask = nil }
            if let result = await store.previewImport(url, password: password), !Task.isCancelled {
                backupPassword = ""
                if result.format == format {
                    duplicateCount = VaultMerge.conflictCount(local: store.model?.items ?? [], incoming: result.model.items)
                    preview = result
                } else {
                    store.errorMessage = tr("The selected file changed. Close this preview and choose it again.",
                                            "所选文件已变化。请关闭预览并重新选择。")
                }
            }
        }
    }

    private func cancelDecode() {
        decodeTask?.cancel()
        close()
    }

    private func commit() {
        guard let preview, !store.busy else { return }
        let choice: MergePolicy
        switch conflictChoice {
        case 1: choice = .overwriteLocal
        case 2: choice = .keepBoth
        default: choice = .keepLocal
        }
        let replace = replacing
        let password = recoveryPassword.isEmpty ? nil : recoveryPassword
        let authentication = (authenticate || (replace && store.systemAuthentication)) && password != nil
        let restoresVault = creating
        Task {
            if await store.commitImport(preview, replace: replace, initialPassword: password,
                                        systemAuthentication: authentication, policy: choice) {
                if restoresVault { lastBackup = 0; lastBackupRevision = "" }
                close()
            }
        }
    }
}

@MainActor
struct ExportSheet: View {
    @ObservedObject var store: VaultStore
    let close: () -> Void
    @AppStorage("export.last-success") private var lastBackup = 0.0
    @AppStorage("export.last-revision") private var lastBackupRevision = ""
    @State private var format: InterchangeFormat = .aegisEncrypted
    @State private var password = ""
    @State private var plaintextAcknowledged = false
    @State private var choosingLocation = false
    @State private var savePanel: NSSavePanel?

    var body: some View {
        DesktopDialog(store: store, title: tr("Export Backup", "导出备份"), close: close) {
            VStack(alignment: .leading, spacing: 12) {
                Picker(tr("Export format", "导出格式"), selection: $format) {
                    ForEach(InterchangeFormat.allCases) { format in Text(format.title).tag(format) }
                }
                .disabled(choosingLocation || store.busy)
                SecureField(tr("Current recovery password", "当前恢复口令"), text: $password)
                if format.requiresPassword {
                    Text(tr("The exported backup uses your current recovery password.",
                            "加密备份将使用当前恢复口令。"))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if format.plaintext {
                    Toggle(tr("I understand that anyone with this file can generate codes for the exported accounts.",
                              "我了解，任何获得此文件的人都能为导出的账号生成验证码。"),
                           isOn: $plaintextAcknowledged)
                }
                if format != .inkotp2fab {
                    Label(tr("Other authenticator formats preserve verification parameters. App-specific labels, icons, and settings may be omitted.",
                             "其他验证器格式会保留验证码参数，可能省略应用专用标签、图标和设置。"),
                          systemImage: "info.circle")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
            .textFieldStyle(.roundedBorder)
        } actions: {
            Button(tr("Choose Save Location…", "选择保存位置…"), action: chooseLocation)
                .keyboardShortcut(.defaultAction)
                .disabled(choosingLocation || password.isEmpty || (format.plaintext && !plaintextAcknowledged))
        }
        .onChange(of: format) { _, _ in plaintextAcknowledged = false }
        .onDisappear {
            password = ""
            savePanel?.cancel(nil)
        }
    }

    private func chooseLocation() {
        let selectedFormat = format
        let chosenPassword = password
        let panel = NSSavePanel()
        let stamp = Date.now.formatted(.iso8601.year().month().day().dateSeparator(.dash))
        panel.nameFieldStringValue = "InkOTP-\(stamp).\(selectedFormat.fileExtension)"
        if let type = UTType(filenameExtension: selectedFormat.fileExtension) {
            panel.allowedContentTypes = [type]
        }
        panel.canCreateDirectories = true
        panel.isExtensionHidden = false
        choosingLocation = true
        savePanel = panel
        let revision = store.lockRevision

        let completion: (NSApplication.ModalResponse) -> Void = { response in
            choosingLocation = false
            savePanel = nil
            guard response == .OK, store.lockRevision == revision, let url = panel.url else { return }
            Task {
                if await store.export(to: url, format: selectedFormat, password: chosenPassword) {
                    lastBackup = Date.now.timeIntervalSince1970
                    lastBackupRevision = store.model?.updatedAt ?? ""
                    close()
                }
            }
        }
        // The backup options already occupy the vault window’s sheet.
        panel.begin(completionHandler: completion)
    }
}

extension InterchangeFormat {
    var title: String {
        switch self {
        case .aegisEncrypted: tr("Aegis (encrypted, recommended)", "Aegis（加密，推荐）")
        case .aegisPlaintext: tr("Aegis (plaintext)", "Aegis（明文）")
        case .twofasEncrypted: tr("2FAS (encrypted)", "2FAS（加密）")
        case .twofasPlaintext: tr("2FAS (plaintext)", "2FAS（明文）")
        case .otpauthText: tr("Setup links (plaintext)", "设置链接（明文）")
        case .inkotp2fab: tr("InkOTP .2fab (encrypted)", "InkOTP .2fab（加密）")
        }
    }
}
