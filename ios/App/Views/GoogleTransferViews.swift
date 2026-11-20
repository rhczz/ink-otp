import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import InkOTPCore
import SwiftUI

@MainActor
struct GoogleImportView: View {
    let initial: String
    @ObservedObject var state: AppState
    let done: () -> Void

    @State private var batch = GoogleMigrationImport()
    @State private var preview: ImportPreview?
    @State private var failure: String?
    @State private var scanning = false
    @State private var didReadInitial = false

    var body: some View {
        Group {
            if let preview {
                ImportReview(preview: preview, state: state, cancel: cancel, restore: {}) { policy, _ in
                    Task {
                        if await state.commitImport(preview, policy: policy) { done() }
                    }
                }
            } else {
                InkPage {
                    InkHeading(title: "Import from Google Authenticator",
                               message: "Scan each QR code from the same transfer.",
                               symbol: "qrcode.viewfinder")
                    if let total = batch.totalCount {
                        Text("\(batch.receivedCount) of \(total) QR codes scanned")
                            .font(.headline)
                    }
                    if let failure { Text(verbatim: failure).font(.footnote).foregroundStyle(Ink.danger) }
                    Button("Scan QR code") { scanning = true }.buttonStyle(InkButtonStyle())
                    QRPhotoButton(state: state, onFound: receive)
                    PasteButton(payloadType: String.self) { values in
                        if let value = values.first { receive(value) }
                    }
                    .buttonStyle(InkButtonStyle(prominent: false))
                    Button("Cancel", action: cancel).buttonStyle(InkButtonStyle(prominent: false))
                }
                .navigationTitle("Google Authenticator")
                .navigationBarTitleDisplayMode(.inline)
            }
        }
        .task {
            guard !didReadInitial else { return }
            didReadInitial = true
            receive(initial)
        }
        .interactiveDismissDisabled()
        .sheet(isPresented: $scanning) {
            NavigationStack {
                ScannerView(
                    onFound: { scanning = false; receive($0) },
                    onCancel: { scanning = false }, onManual: { scanning = false }, state: state
                )
            }
            .tint(Ink.accent)
        }
    }

    private func receive(_ uri: String) {
        guard state.phase == .unlocked, preview == nil else { return }
        failure = nil
        do {
            if let items = try batch.append(uri) {
                guard let review = state.previewGoogleImport(items) else {
                    batch = GoogleMigrationImport()
                    failure = String(localized: "The transfer expired. Scan the QR codes again.")
                    return
                }
                batch = GoogleMigrationImport()
                preview = review
            }
        } catch {
            failure = error.localizedDescription
        }
    }

    private func cancel() {
        batch = GoogleMigrationImport()
        state.cancelImport()
        done()
    }
}

struct GoogleExportReview: View {
    let preview: GoogleMigrationExport
    let cancel: () -> Void
    let reveal: () -> Void

    var body: some View {
        InkPage {
            InkHeading(title: "Show unencrypted account keys?",
                       message: "Anyone who sees the QR codes can generate your verification codes. Open Transfer accounts in Google Authenticator and scan each page.",
                       symbol: "exclamationmark.triangle")
            if !preview.omitted.isEmpty {
                InkCard {
                    Text("\(preview.omitted.count) accounts cannot be transferred").font(.headline)
                    LazyVStack(alignment: .leading, spacing: Ink.m) {
                        ForEach(preview.omitted) { item in
                            VStack(alignment: .leading, spacing: Ink.xs) {
                                Text(verbatim: item.name).font(.footnote.weight(.semibold)).privacySensitive()
                                Text(verbatim: item.reason).font(.footnote).foregroundStyle(Ink.secondary)
                            }
                        }
                    }
                }
            }
            if preview.supportedCount == 0 {
                Text("There are no compatible accounts to transfer.").foregroundStyle(Ink.secondary)
            } else {
                Text("\(preview.supportedCount) compatible accounts").foregroundStyle(Ink.secondary)
                Button(preview.omitted.isEmpty ? "Show QR codes" : "Show QR codes for compatible accounts",
                       role: .destructive, action: reveal)
                    .buttonStyle(InkButtonStyle())
            }
            Button("Cancel", action: cancel).buttonStyle(InkButtonStyle(prominent: false))
        }
        .navigationTitle("Google Authenticator")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct GoogleExportPages: View {
    let pages: [String]
    let done: () -> Void
    @State private var index = 0

    private static let context = CIContext(options: [.cacheIntermediates: false])

    var body: some View {
        InkPage {
            Text("QR code \(index + 1) of \(pages.count)").font(.headline)
            if let qr = code(pages[index]) {
                Image(decorative: qr, scale: 1)
                    .interpolation(.none).resizable().scaledToFit()
                    .accessibilityLabel("Google Authenticator transfer QR code")
                    .privacySensitive()
            } else {
                Text("The QR code could not be displayed.").foregroundStyle(Ink.danger)
            }
            HStack(spacing: Ink.m) {
                Button("Previous") { index -= 1 }
                    .buttonStyle(InkButtonStyle(prominent: false))
                    .disabled(index == 0)
                Button("Next") { index += 1 }
                    .buttonStyle(InkButtonStyle(prominent: false))
                    .disabled(index == pages.count - 1)
            }
            Button("Done", action: done).buttonStyle(InkButtonStyle())
        }
        .navigationTitle("Google Authenticator")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func code(_ uri: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(uri.utf8)
        filter.correctionLevel = "L"
        guard let code = filter.outputImage else { return nil }
        let area = code.extent.insetBy(dx: -4, dy: -4)
        let white = CIImage(color: .white).cropped(to: area)
        return Self.context.createCGImage(code.composited(over: white), from: area)
    }
}
