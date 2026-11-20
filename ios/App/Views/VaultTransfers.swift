import InkOTPCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum TransferRequest: Equatable {
    case importBackup, exportBackup, exportGoogle, recoveryPassword
}

@MainActor
struct VaultTransfers: ViewModifier {
    @ObservedObject var state: AppState
    @Binding var request: TransferRequest?
    @Binding var notice: LocalizedStringKey?

    @State private var route: Route?
    @State private var operation = UUID()
    @State private var temporaryExport: URL?
    @State private var googleExportTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .onChange(of: request) { _, next in begin(next) }
            .sheet(item: $route) { destination in
                Group {
                    switch destination {
                    case .importPicker:
                        SystemDocumentPicker(mode: .open, complete: importSelected)
                    case .exportPicker(let file):
                        SystemDocumentPicker(mode: .export(file), complete: exportSelected)
                    default:
                        NavigationStack { form(destination) }
                    }
                }
                .interactiveDismissDisabled()
                .tint(Ink.accent)
            }
    }

    @ViewBuilder
    private func form(_ destination: Route) -> some View {
        switch destination {
        case .loading:
            InkPage {
                ProgressView("Reading backup…").frame(maxWidth: .infinity, minHeight: 120)
                Button("Cancel", action: cancel).buttonStyle(InkButtonStyle(prominent: false))
            }
            .navigationTitle("Import backup")
            .navigationBarTitleDisplayMode(.inline)
        case .importPassword(let bytes, let format):
            TransferPasswordForm(
                title: "Open encrypted backup",
                message: "Enter the password used to create this backup.",
                format: format, mode: .current, state: state, onCancel: cancel
            ) { _, password in
                decode(bytes, password: password)
            }
        case .importPreview(let preview):
            ImportReview(preview: preview, state: state, cancel: cancel, restore: { route = .restore(preview) }) { policy, replace in
                commit(preview, policy: policy, replace: replace)
            }
        case .restore(let preview):
            let replacing = state.phase == .locked || state.phase == .unavailable
            TransferPasswordForm(
                title: replacing ? "Replace this vault and restore?" : "Set a new recovery password",
                message: replacing
                    ? "The backup will establish a new vault on this device. You will need to verify your identity before replacing the current vault."
                    : "Set a new password for the restored vault. The backup still uses its original password.",
                mode: .new, state: state, onCancel: cancel
            ) { _, password in
                commit(preview, replace: replacing, recovery: password)
            }
        case .exportFormat:
            ExportFormatForm(cancel: cancel) { format in route = .exportPassword(format) }
        case .exportPassword(let format):
            TransferPasswordForm(
                title: format.plaintext ? "Export migration file" : "Export encrypted backup",
                message: "Enter the current recovery password to verify this export.",
                format: format, mode: .current, state: state, onCancel: cancel
            ) { _, password in
                export(format, password: password)
            }
        case .exportReview(let payload, let format):
            InkPage {
                InkHeading(title: format.plaintext ? "Export unencrypted account keys?" : "Review backup compatibility",
                           message: format.plaintext
                               ? "Anyone who can open this file can generate your verification codes."
                               : "Check the compatibility notes before saving the file.",
                           symbol: format.plaintext ? "exclamationmark.triangle" : "checkmark.shield")
                TransferWarnings(warnings: payload.warnings)
                Button("Continue to save", role: format.plaintext ? .destructive : nil) { save(payload) }
                    .buttonStyle(InkButtonStyle())
                Button("Cancel", action: cancel).buttonStyle(InkButtonStyle(prominent: false))
            }
            .navigationTitle("Review export")
            .navigationBarTitleDisplayMode(.inline)
        case .googlePassword:
            TransferPasswordForm(
                title: "Transfer to Google Authenticator",
                message: "Enter your current recovery password to prepare the transfer.",
                mode: .current, state: state, cancelWhileBusy: true, onCancel: cancel
            ) { _, password in
                let token = operation
                googleExportTask = Task {
                    let preview = await state.exportGoogle(password: password)
                    guard operation == token, !Task.isCancelled else { return }
                    googleExportTask = nil
                    if let preview { route = .googleReview(preview) }
                }
            }
        case .googleReview(let preview):
            GoogleExportReview(preview: preview, cancel: cancel) { route = .googlePages(preview.pages) }
        case .googlePages(let pages):
            GoogleExportPages(pages: pages, done: finish)
        case .recoveryPassword:
            TransferPasswordForm(
                title: state.recoveryConfigured ? "Change recovery password" : "Set recovery password",
                message: "Use at least eight characters. Existing backups still use their original password.",
                mode: state.recoveryConfigured ? .change : .new,
                state: state, onCancel: cancel
            ) { current, password in
                let token = operation
                Task {
                    let committed = await state.setRecovery(password: password, currentPassword: current)
                    guard operation == token, committed else { return }
                    finish()
                    notice = "Recovery password saved. Export a fresh backup to use this password when restoring."
                }
            }
        case .importPicker, .exportPicker:
            EmptyView()
        }
    }

    private func begin(_ next: TransferRequest?) {
        if next == nil {
            googleExportTask?.cancel()
            googleExportTask = nil
            removeTemporaryFile()
            route = nil
            operation = UUID()
            return
        }
        guard route == nil else { return }
        state.error = nil
        operation = UUID()
        switch next {
        case .importBackup:
            state.beginPicker()
            route = .importPicker
        case .exportBackup:
            route = .exportFormat
        case .exportGoogle:
            route = .googlePassword
        case .recoveryPassword:
            route = .recoveryPassword
        case nil:
            break
        }
    }

    private func importSelected(_ url: URL?) {
        let accepted = state.endPicker()
        guard let url else { finish(); return }
        guard accepted else {
            finish()
            notice = "The file selection expired. Choose the file again."
            return
        }
        route = .loading
        let token = operation
        Task {
            do {
                let (bytes, format) = try await Task.detached(priority: .userInitiated) {
                    let accessing = url.startAccessingSecurityScopedResource()
                    defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                    let data = try readExternalBackup(url)
                    return (data, try InterchangeCodec.detect(data))
                }.value
                guard operation == token else { return }
                if format.requiresPassword {
                    route = .importPassword(bytes, format)
                } else {
                    decode(bytes, password: nil)
                }
            } catch {
                guard operation == token else { return }
                finish()
                state.error = (error as? InterchangeFailure)?.errorDescription
                    ?? String(localized: "The backup could not be read or is larger than 12 MiB.")
            }
        }
    }

    private func decode(_ bytes: Data, password: String?) {
        let token = operation
        Task {
            let preview = await state.previewImport(data: bytes, password: password)
            guard operation == token else { return }
            if let preview {
                route = .importPreview(preview)
            } else if password == nil {
                finish()
            }
        }
    }

    private func commit(_ preview: ImportPreview, policy: MergeConflictPolicy = .keepLocal,
                        replace: Bool = false, recovery: String? = nil) {
        let token = operation
        Task {
            let committed = await state.commitImport(preview, policy: policy, replace: replace, restorePassword: recovery)
            guard operation == token, committed else { return }
            finish()
            notice = "Accounts imported"
        }
    }

    private func export(_ format: InterchangeFormat, password: String?) {
        let token = operation
        Task {
            let payload = await state.export(format: format, password: password)
            guard operation == token, let payload else { return }
            route = .exportReview(payload, format)
        }
    }

    private func save(_ payload: ExportPayload) {
        let directory = ExportStaging.root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let file = directory.appendingPathComponent(payload.filename)
        temporaryExport = directory
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try payload.data.write(to: file, options: [.completeFileProtection, .atomic])
            state.beginPicker()
            route = .exportPicker(file)
        } catch {
            finish()
            state.error = String(localized: "The backup could not be prepared for saving.")
        }
    }

    private func exportSelected(_ url: URL?) {
        let accepted = state.endPicker()
        finish()
        if url != nil && accepted {
            state.markExportWritten()
            notice = "Backup saved"
        } else if url != nil {
            notice = "The system returned after the export session expired. Check the selected location."
        }
    }

    private func cancel() {
        if request == .importBackup { state.cancelImport() }
        state.error = nil
        finish()
    }

    private func finish() {
        googleExportTask?.cancel()
        googleExportTask = nil
        route = nil
        operation = UUID()
        request = nil
        removeTemporaryFile()
    }

    private func removeTemporaryFile() {
        if let temporaryExport {
            try? FileManager.default.removeItem(at: temporaryExport)
            self.temporaryExport = nil
        }
    }

    private enum Route: Identifiable {
        case importPicker, loading, importPassword(Data, InterchangeFormat), importPreview(ImportPreview), restore(ImportPreview)
        case exportFormat, exportPassword(InterchangeFormat), exportReview(ExportPayload, InterchangeFormat), exportPicker(URL)
        case googlePassword, googleReview(GoogleMigrationExport), googlePages([String])
        case recoveryPassword

        var id: Int {
            switch self {
            case .importPicker: 0
            case .loading: 1
            case .importPassword: 2
            case .importPreview: 3
            case .restore: 4
            case .exportFormat: 5
            case .exportPassword: 6
            case .exportReview: 7
            case .exportPicker: 8
            case .recoveryPassword: 9
            case .googlePassword: 10
            case .googleReview: 11
            case .googlePages: 12
            }
        }
    }
}

@MainActor
private struct SystemDocumentPicker: UIViewControllerRepresentable {
    enum Mode { case open, export(URL) }
    let mode: Mode
    let complete: (URL?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(complete: complete) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller: UIDocumentPickerViewController
        switch mode {
        case .open: controller = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
        case .export(let url): controller = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        }
        controller.allowsMultipleSelection = false
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let complete: (URL?) -> Void

        init(complete: @escaping (URL?) -> Void) { self.complete = complete }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { complete(nil) }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            complete(urls.first)
        }
    }
}

private func readExternalBackup(_ url: URL) throws -> Data {
    var error: NSError?
    var result: Result<Data, Error>?
    NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &error) { coordinatedURL in
        result = Result { try readBoundedFile(coordinatedURL, maximum: 12 * 1024 * 1024) }
    }
    if let error { throw error }
    guard let result else { throw CocoaError(.fileReadUnknown) }
    return try result.get()
}

func readBoundedFile(_ url: URL, maximum: Int) throws -> Data {
    let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
    guard values.isRegularFile == true, (values.fileSize ?? 0) <= maximum else {
        throw CocoaError(.fileReadTooLarge)
    }
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var data = Data()
    while data.count <= maximum,
          let chunk = try handle.read(upToCount: min(64 * 1024, maximum - data.count + 1)),
          !chunk.isEmpty {
        data.append(chunk)
    }
    guard data.count <= maximum else { throw CocoaError(.fileReadTooLarge) }
    return data
}

@MainActor
private struct TransferPasswordForm: View {
    enum Mode { case current, new, change }

    let title: LocalizedStringKey
    let message: LocalizedStringKey
    var format: InterchangeFormat?
    let mode: Mode
    @ObservedObject var state: AppState
    var cancelWhileBusy = false
    let onCancel: () -> Void
    let onSubmit: (String?, String) -> Void

    @State private var current = ""
    @State private var password = ""
    @State private var confirmation = ""

    var body: some View {
        InkPage {
            Text(message).foregroundStyle(Ink.secondary)
            if let format { Text(format.title).font(.headline) }
            if mode == .change {
                InkField(title: "Current recovery password", value: $current, secret: true)
            }
            InkField(title: mode == .current ? "Password" : "New recovery password", value: $password, secret: true)
            if mode != .current {
                InkField(title: "Enter again", value: $confirmation, secret: true)
                if !confirmation.isEmpty && confirmation != password {
                    Text("Passwords do not match").font(.footnote).foregroundStyle(Ink.danger)
                }
            }
            if let error = state.error {
                inkText(error).font(.footnote).foregroundStyle(Ink.danger)
            }
            if state.busy { ProgressView().frame(maxWidth: .infinity) }
            Button("Confirm") {
                let old = mode == .change ? current : nil
                let next = password
                password = ""; confirmation = ""; current = ""
                onSubmit(old, next)
            }
            .buttonStyle(InkButtonStyle())
            .disabled(state.busy || !valid)
            Button("Cancel", action: onCancel)
                .buttonStyle(InkButtonStyle(prominent: false))
                .disabled(state.busy && format == nil && !cancelWhileBusy)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var valid: Bool {
        switch mode {
        case .current: !password.isEmpty
        case .new: password.count >= 8 && password == confirmation
        case .change: !current.isEmpty && password.count >= 8 && password == confirmation
        }
    }
}

private struct ExportFormatForm: View {
    let cancel: () -> Void
    let select: (InterchangeFormat) -> Void
    @State private var selection = InterchangeFormat.aegisEncrypted

    var body: some View {
        InkPage {
            Text("Choose a format compatible with the app that will open the file.")
                .foregroundStyle(Ink.secondary)
            InkCard {
                Picker("Backup format", selection: $selection) {
                    ForEach(InterchangeFormat.allCases) { format in
                        Text(format.title).tag(format)
                    }
                }
                if selection.plaintext {
                    Text("This format saves unencrypted account keys.")
                        .font(.footnote).foregroundStyle(Ink.warning)
                }
            }
            Button("Continue") { select(selection) }.buttonStyle(InkButtonStyle())
            Button("Cancel", action: cancel).buttonStyle(InkButtonStyle(prominent: false))
        }
        .navigationTitle("Export backup")
        .navigationBarTitleDisplayMode(.inline)
    }
}

@MainActor
struct ImportReview: View {
    let preview: ImportPreview
    @ObservedObject var state: AppState
    let cancel: () -> Void
    let restore: () -> Void
    let commit: (MergeConflictPolicy, Bool) -> Void
    @State private var replacing = false

    private var isGoogle: Bool {
        if case .google = preview.source { return true }
        return false
    }

    private var sourceTitle: LocalizedStringKey {
        switch preview.source {
        case .file(let format): format.title
        case .google: "Google Authenticator"
        }
    }

    var body: some View {
        InkPage {
            InkCard {
                Text(sourceTitle).font(.headline)
                if isGoogle {
                    Text("\(preview.items.count) accounts from Google Authenticator")
                    if preview.items.contains(where: { $0.type == .hotp }) {
                        Label("HOTP parameters are kept for future exports. Use another authenticator to generate HOTP codes.",
                              systemImage: "info.circle")
                            .font(.footnote).foregroundStyle(Ink.warning)
                    }
                } else {
                    Text("\(preview.items.count) accounts in this backup")
                }
                if state.phase == .unlocked {
                    Text("\(preview.duplicates) duplicate accounts. Merging keeps your local security settings and recovery password.")
                }
                TransferWarnings(warnings: preview.warnings)
                ForEach(Array(preview.items.prefix(50).enumerated()), id: \.offset) { _, item in
                    Text(item.issuer.map { "\($0) · \(item.accountName)" } ?? item.accountName)
                        .font(.footnote).privacySensitive()
                }
                if preview.items.count > 50 {
                    Text("\(preview.items.count - 50) more accounts").font(.footnote)
                }
            }
            if let error = state.error { inkText(error).font(.footnote).foregroundStyle(Ink.danger) }
            if state.phase == .unlocked {
                VStack(spacing: Ink.m) {
                    Button("Merge and keep local duplicates") { commit(.keepLocal, false) }
                        .buttonStyle(InkButtonStyle())
                    Button(isGoogle ? "Merge and use incoming duplicates" : "Merge and use backup duplicates") {
                        commit(.overwriteLocal, false)
                    }
                    .buttonStyle(InkButtonStyle(prominent: false))
                    Button("Merge and keep both duplicates") { commit(.keepBoth, false) }
                        .buttonStyle(InkButtonStyle(prominent: false))
                }
                .disabled(state.busy)
                if !isGoogle {
                    Divider().padding(.top, Ink.s)
                    Button("Replace all local accounts…", role: .destructive) { replacing = true }
                        .frame(maxWidth: .infinity, minHeight: Ink.touch)
                        .disabled(state.busy)
                }
            } else {
                Button("Continue to password setup", action: restore)
                    .buttonStyle(InkButtonStyle())
                    .disabled(state.busy)
            }
            Button("Cancel", action: cancel).buttonStyle(InkButtonStyle(prominent: false))
                .disabled(state.busy)
        }
        .navigationTitle("Review import")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Replace all local accounts?", isPresented: $replacing) {
            Button("Replace", role: .destructive) { commit(.keepLocal, true) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This backup will replace the accounts currently on this device.")
        }
    }
}

private struct TransferWarnings: View {
    let warnings: [String]

    var body: some View {
        ForEach(Array(warnings.enumerated()), id: \.offset) { _, warning in
            Label { Text(verbatim: warning) } icon: { Image(systemName: "info.circle") }
                .font(.footnote).foregroundStyle(Ink.warning)
        }
    }
}

private extension InterchangeFormat {
    var title: LocalizedStringKey {
        switch self {
        case .aegisEncrypted: "Aegis · encrypted (default)"
        case .aegisPlaintext: "Aegis · plaintext"
        case .twofasEncrypted: "2FAS · encrypted"
        case .twofasPlaintext: "2FAS · plaintext"
        case .otpauthText: "otpauth text · plaintext"
        }
    }
}
