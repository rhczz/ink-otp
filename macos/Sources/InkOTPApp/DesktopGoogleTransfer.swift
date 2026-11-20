import AppKit
import CoreImage.CIFilterBuiltins
import GoogleOTPTransfer
import InkOTPCore
import SwiftUI
import UniformTypeIdentifiers

struct DesktopGoogleTransferError: LocalizedError {
    let error: GoogleOTPTransferError

    var errorDescription: String? {
        switch error {
        case .invalidURI:
            tr("Choose a transfer QR code from Google Authenticator.", "请选择 Google Authenticator 生成的迁移二维码。")
        case .unsupportedVersion:
            tr("This Google Authenticator transfer version is unsupported.", "暂不支持此 Google Authenticator 迁移版本。")
        case .unsupportedAccount(let index):
            tr("Account \(index) uses invalid or unsupported verification settings.", "第 \(index) 个账号的验证参数无效或不受支持。")
        case .mixedBatch:
            tr("This QR code belongs to a different transfer. Continue with codes from the same transfer.",
               "该二维码属于另一批迁移。请继续读取当前批次的二维码。")
        case .conflictingPage:
            tr("This QR code conflicts with a previously read page. Start the transfer again if needed.",
               "该二维码和已读取的同序号页冲突。如需重新迁移，请取消后再开始。")
        case .limitExceeded:
            tr("The transfer exceeds the supported account or QR code limits.", "迁移超出了支持的账号数或二维码页数。")
        case .incompatibleAccount(let index, let issue):
            tr("Account \(index) cannot be transferred. \(issue.transferDescription)", "第 \(index) 个账号无法迁移。\(issue.transferDescription)")
        case .malformed:
            tr("The Google Authenticator transfer QR code is incomplete or damaged.", "Google Authenticator 迁移二维码不完整或已损坏。")
        }
    }
}

private extension GoogleOTPExportIssue {
    var transferDescription: String {
        switch self {
        case .algorithm: tr("Requires SHA1.", "需要 SHA1。")
        case .digits: tr("Requires 6-digit codes.", "需要 6 位验证码。")
        case .period: tr("Requires a 30-second period.", "需要 30 秒周期。")
        case .counter: tr("The HOTP counter is outside the supported range.", "HOTP 计数器超出支持范围。")
        case .accountName: tr("The account or service name cannot be transferred.", "无法迁移该账号或服务名称。")
        case .secret: tr("The setup key is outside the supported size range.", "设置密钥长度超出支持范围。")
        case .oversized: tr("This account is too large for one transfer QR code.", "该账号超出单张迁移二维码的容量。")
        }
    }
}

@MainActor
struct DesktopGoogleImportSheet: View {
    @ObservedObject var store: VaultStore
    @EnvironmentObject private var activity: DesktopActivity
    let initialURI: String?
    let close: () -> Void
    @State private var batch = GoogleOTPBatch()
    @State private var items: [VaultItem]?
    @State private var conflictPolicy = ImportConflictChoice.keepLocal
    @State private var camera = false
    @State private var imagePicker = false
    @State private var readingImage = false
    @State private var imageTask: Task<Void, Never>?
    @State private var receivedInitial = false

    var body: some View {
        VStack(spacing: 0) {
            if camera {
                CameraSheet(store: store, close: { camera = false }) { value in
                    camera = false
                    accept(value)
                }
            } else {
                DesktopDialog(store: store, title: tr("Import from Google Authenticator", "从 Google Authenticator 导入"), close: close) {
                    content
                } actions: {
                    if let items {
                        Button(tr("Import Accounts", "导入账号")) {
                            let policy = conflictPolicy.policy
                            Task { if await store.importGoogleAccounts(items, policy: policy) { close() } }
                        }
                        .accessibilityIdentifier("google-import-commit")
                        .keyboardShortcut(.defaultAction)
                    }
                }
            }
        }
        .fileImporter(isPresented: $imagePicker, allowedContentTypes: [.image]) { result in
            switch result {
            case .success(let url): readImage(url)
            case .failure(let error) where (error as? CocoaError)?.code == .userCancelled: break
            case .failure:
                store.errorMessage = tr("Could not open the selected image.", "无法打开所选图片。")
            }
        }
        .task {
            if !receivedInitial, let initialURI {
                receivedInitial = true
                accept(initialURI)
            }
        }
        .onDisappear {
            imageTask?.cancel()
            batch = GoogleOTPBatch()
            items = nil
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let items {
                Text(tr("\(items.count) accounts ready to import", "已读取 \(items.count) 个账号"))
                    .font(.headline)
                    .accessibilityIdentifier("google-import-review")
                ImportAccountList(items: items)
                if items.contains(where: { $0.type == .hotp }) {
                    Text(tr("HOTP accounts keep their counters for export. Generate these codes in an authenticator that supports HOTP.",
                            "HOTP 账号会保留计数器以便再次导出。请使用支持 HOTP 的验证器生成此类验证码。"))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                let duplicates = VaultMerge.conflictCount(local: store.model?.items ?? [], incoming: items)
                if duplicates > 0 {
                    Text(tr("\(duplicates) matching accounts", "\(duplicates) 个账号重复"))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    ImportConflictPicker(choice: $conflictPolicy)
                }
            } else {
                Text(tr("Open Transfer accounts in Google Authenticator, then read each transfer QR code.",
                        "在 Google Authenticator 中打开「转移账号」，然后逐张读取迁移二维码。"))
                    .foregroundStyle(.secondary)
                if let total = batch.totalCount {
                    Text(tr("\(batch.receivedCount) of \(total) QR codes read", "已读取 \(batch.receivedCount)／\(total) 张二维码"))
                        .monospacedDigit()
                        .accessibilityIdentifier("google-import-progress")
                }
                HStack {
                    Button(tr("Scan with Camera…", "使用相机扫描…")) {
                        store.errorMessage = nil
                        camera = true
                    }
                    Button(tr("Read QR Image…", "读取二维码图片…")) { imagePicker = true }
                    Button(tr("Paste QR Link", "粘贴二维码链接")) {
                        accept(NSPasteboard.general.string(forType: .string) ?? "")
                    }
                }
                .disabled(readingImage)
            }
        }
    }

    private func accept(_ value: String) {
        guard activity.isActive, store.model != nil, items == nil, !store.busy else { return }
        store.errorMessage = nil
        do {
            if let accounts = try batch.add(GoogleOTPTransferCodec.decode(value)) {
                items = VaultItem.importingGoogleAccounts(accounts)
                batch = GoogleOTPBatch()
            }
        } catch let error as GoogleOTPTransferError {
            store.errorMessage = DesktopGoogleTransferError(error: error).errorDescription
        } catch {
            store.errorMessage = DesktopGoogleTransferError(error: .malformed).errorDescription
        }
    }

    private func readImage(_ url: URL) {
        guard !readingImage, items == nil else { return }
        readingImage = true
        store.errorMessage = nil
        let revision = store.lockRevision
        imageTask = Task {
            defer { readingImage = false }
            do {
                let value = try await Task.detached(priority: .userInitiated) { try QRImage.read(url) }.value
                guard !Task.isCancelled, store.lockRevision == revision else { return }
                accept(value)
            } catch {
                guard !Task.isCancelled, store.lockRevision == revision, store.model != nil else { return }
                store.errorMessage = (error as? QRFailure)?.errorDescription
                    ?? tr("Could not read the selected image.", "无法读取所选图片。")
            }
        }
    }
}

@MainActor
struct DesktopGoogleExportSheet: View {
    @ObservedObject var store: VaultStore
    let close: () -> Void
    @State private var password = ""
    @State private var plaintextAcknowledged = false
    @State private var compatibleOnly = false
    @State private var pages: [String] = []
    @State private var pageIndex = 0
    @State private var issues = [ExportProblem]()
    @State private var revealTask: Task<Void, Never>?

    private struct ExportProblem: Identifiable {
        let id: String
        let accountName: String
        let issue: GoogleOTPExportIssue
    }

    private var supportedCount: Int { (store.model?.items.count ?? 0) - issues.count }
    private var overTransferLimit: Bool { supportedCount > GoogleOTPTransferCodec.maxAccounts }

    var body: some View {
        DesktopDialog(store: store, title: tr("Export to Google Authenticator", "导出到 Google Authenticator"), close: close) {
            if store.model != nil, pages.indices.contains(pageIndex) {
                qrPage
            } else {
                exportForm
            }
        } actions: {
            if pages.isEmpty {
                Button(tr("Show Transfer QR Codes", "显示迁移二维码"), action: reveal)
                    .accessibilityIdentifier("google-export-show")
                    .keyboardShortcut(.defaultAction)
                    .disabled(!plaintextAcknowledged || password.isEmpty || supportedCount == 0
                              || overTransferLimit || (!issues.isEmpty && !compatibleOnly))
            } else {
                Button(tr("Done", "完成"), action: close).keyboardShortcut(.defaultAction)
            }
        }
        .task(id: store.model?.updatedAt) {
            issues = (store.model?.items ?? []).compactMap { item in
                guard let issue = GoogleOTPTransferCodec.exportIssue(item.googleTransferAccount) else { return nil }
                return ExportProblem(id: item.id, accountName: item.accountName, issue: issue)
            }
        }
        .onDisappear {
            revealTask?.cancel()
            password = ""
            pages.removeAll()
        }
    }

    private var exportForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !issues.isEmpty {
                Text(tr("\(issues.count) accounts cannot be transferred to Google Authenticator.",
                        "有 \(issues.count) 个账号无法迁移到 Google Authenticator。"))
                    .font(.headline)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(issues) { problem in
                            Text("\(problem.accountName) · \(problem.issue.transferDescription)")
                                .lineLimit(2)
                        }
                    }
                    .padding(.trailing, 8)
                }
                .frame(maxHeight: 112)
                if supportedCount > 0 {
                    Toggle(tr("Transfer only the \(supportedCount) compatible accounts", "仅迁移 \(supportedCount) 个兼容账号"),
                           isOn: $compatibleOnly)
                }
            }
            if overTransferLimit {
                Text(tr("One transfer supports up to \(GoogleOTPTransferCodec.maxAccounts) accounts. For larger vaults, export an Aegis backup.",
                        "单次迁移最多支持 \(GoogleOTPTransferCodec.maxAccounts) 个账号。账号更多时可以导出 Aegis 备份。"))
                    .foregroundStyle(.secondary)
            }
            Label(tr("Anyone who can read these QR codes can generate your verification codes. Show them only to the destination device.",
                     "任何读到这些二维码的人都可以生成验证码。请仅向目标设备展示二维码。"), systemImage: "exclamationmark.triangle")
                .foregroundStyle(.secondary)
            Toggle(tr("I understand the QR codes expose setup keys", "我了解二维码会显示明文设置密钥"), isOn: $plaintextAcknowledged)
            SecureField(tr("Current recovery password", "当前恢复口令"), text: $password)
                .textFieldStyle(.roundedBorder)
        }
    }

    private var qrPage: some View {
        VStack(spacing: 12) {
            Text(tr("Scan each QR code in Google Authenticator under Transfer accounts.",
                    "在 Google Authenticator 的「转移账号」中逐张扫描二维码。"))
            if let image = Self.qrImage(pages[pageIndex]) {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.none)
                    .frame(width: 276, height: 276)
                    .padding(12)
                    .background(.white)
                    .accessibilityHidden(true)
            } else {
                Text(tr("Could not display this QR code.", "无法显示此二维码。"))
            }
            HStack {
                Button(tr("Previous", "上一张")) { pageIndex -= 1 }
                    .disabled(pageIndex == 0)
                Text(tr("\(pageIndex + 1) of \(pages.count)", "第 \(pageIndex + 1)／\(pages.count) 张"))
                    .monospacedDigit()
                    .accessibilityIdentifier("google-export-page")
                Button(tr("Next", "下一张")) { pageIndex += 1 }
                    .disabled(pageIndex + 1 == pages.count)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private static let qrContext = CIContext()

    private static func qrImage(_ uri: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(uri.utf8)
        filter.correctionLevel = "L"
        guard let output = filter.outputImage else { return nil }
        return qrContext.createCGImage(output, from: output.extent)
    }

    private func reveal() {
        guard !store.busy, revealTask == nil, plaintextAcknowledged, !password.isEmpty else { return }
        let revision = store.lockRevision
        let password = password
        let compatibleOnly = compatibleOnly
        revealTask = Task {
            defer { revealTask = nil }
            guard let result = await store.exportGoogleAccounts(password: password, compatibleOnly: compatibleOnly),
                  !Task.isCancelled, store.lockRevision == revision else { return }
            self.password = ""
            pages = result
            pageIndex = 0
        }
    }
}
