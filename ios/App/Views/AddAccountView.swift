import InkOTPCore
import SwiftUI

@MainActor
struct AddOptions: View {
    let onScan: () -> Void
    let onManual: (String) -> Void
    let onCancel: () -> Void
    @ObservedObject var state: AppState

    var body: some View {
        InkPage {
            Button(action: onScan) {
                Label("Scan QR code", systemImage: "qrcode.viewfinder")
            }
            .buttonStyle(InkButtonStyle())
            QRPhotoButton(state: state, onFound: onManual)
            Button {
                onManual("")
            } label: {
                Label("Enter manually", systemImage: "keyboard")
            }
            .buttonStyle(InkButtonStyle(prominent: false))
            .accessibilityIdentifier("account.manual")
        }
        .navigationTitle("Add a code")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
        }
    }
}

@MainActor
struct AddAccountView: View {
    @ObservedObject var state: AppState
    let done: () -> Void
    let onMigration: (String) -> Void

    @State private var issuer = ""
    @State private var account = ""
    @State private var input: String
    @State private var digits = 6
    @State private var period = "30"
    @State private var algorithm = OtpAlgorithm.sha1
    @State private var formError: LocalizedStringKey?
    @State private var issuerMismatch = false
    @State private var controlsStripped = false

    init(state: AppState, input: String, onMigration: @escaping (String) -> Void, done: @escaping () -> Void) {
        self.state = state
        self.done = done
        self.onMigration = onMigration
        _input = State(initialValue: input)
    }

    var body: some View {
        InkPage {
            InkField(title: "Service name (optional)", value: $issuer)
            InkField(title: "Account (optional when included in the link)", value: $account, identifier: "account.name")
            InkField(title: "Base32 key or otpauth link", value: $input, secret: true, identifier: "account.secret")
            Text("Paste an otpauth link or enter a Base32 key. Base32 accepts either case, spaces and hyphens.")
                .font(.footnote).foregroundStyle(Ink.secondary)
            if !input.contains("://") {
                Text("Advanced").font(.headline).accessibilityAddTraits(.isHeader)
                InkCard {
                    Picker("Algorithm", selection: $algorithm) {
                        Text("SHA1").tag(OtpAlgorithm.sha1)
                        Text("SHA256").tag(OtpAlgorithm.sha256)
                        Text("SHA512").tag(OtpAlgorithm.sha512)
                    }
                    Divider()
                    Picker("Digits", selection: $digits) {
                        Text("6").tag(6)
                        Text("8").tag(8)
                    }
                    Divider()
                    InkField(title: "Period (1–300 seconds)", value: $period, keyboard: .numberPad)
                }
            } else {
                if issuerMismatch {
                    Text("The link contains different service names. The query service takes precedence.")
                        .font(.footnote).foregroundStyle(Ink.warning)
                }
                if controlsStripped {
                    Text("Control characters were removed from the link’s text.")
                        .font(.footnote).foregroundStyle(Ink.warning)
                }
            }
            if let formError {
                Text(formError).font(.footnote).foregroundStyle(Ink.danger)
            }
            if let error = state.error { inkText(error).font(.footnote).foregroundStyle(Ink.danger) }
        }
        .navigationTitle("Enter account")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel", action: done)
                    .disabled(state.busy)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save", action: save)
                    .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.busy)
                    .accessibilityIdentifier("account.save")
            }
        }
        .onAppear { inspectLink() }
        .onChange(of: input) { _, _ in
            formError = nil
            state.error = nil
            inspectLink()
        }
    }

    private func inspectLink() {
        issuerMismatch = false
        controlsStripped = false
        guard input.contains("://"), input.utf16.count <= 16_384,
              let result = try? OtpauthParser.parse(input), case .uri(let fields) = result else { return }
        issuerMismatch = fields.warnings.contains("ISSUER_MISMATCH")
        controlsStripped = fields.warnings.contains("CONTROL_CHARS_STRIPPED")
    }

    private func save() {
        state.error = nil
        formError = nil
        var accountName = vaultName(account)
        var service = vaultName(issuer)
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.utf16.count <= 16_384 else {
            formError = "The key or link is too long."
            return
        }
        if isGoogleMigrationURI(value) {
            input = ""
            onMigration(value)
            return
        }
        do {
            let item: VaultItem
            switch try OtpauthParser.parse(value) {
            case .migration:
                formError = "The Base32 key or otpauth link is invalid. Check the original and try again."
                return
            case .uri(let fields):
                guard fields.type == .totp else {
                    formError = "This version only adds TOTP accounts. Existing HOTP accounts can be imported from backups."
                    return
                }
                if accountName.isEmpty { accountName = fields.accountName ?? "" }
                if service.isEmpty { service = fields.issuer ?? "" }
                item = VaultItem(issuer: service.isEmpty ? nil : service, accountName: accountName,
                                 secret: fields.secret, algorithm: fields.algorithm, digits: fields.digits,
                                 period: fields.period ?? 30, origin: "manual")
            case .bareSecret(let fields):
                guard let seconds = Int(period), (1...300).contains(seconds) else {
                    formError = "Enter a period between 1 and 300 seconds."
                    return
                }
                item = VaultItem(issuer: service.isEmpty ? nil : service, accountName: accountName,
                                 secret: fields.secret, algorithm: algorithm, digits: digits,
                                 period: seconds, origin: "manual")
            }
            guard !item.accountName.isEmpty else { formError = "Enter an account name."; return }
            guard item.accountName.unicodeScalars.count <= 256,
                  (item.issuer?.unicodeScalars.count ?? 0) <= 128 else {
                formError = "The service name or account name is too long."
                return
            }
            Task {
                await state.add(item)
                if state.error == nil {
                    input = ""
                    done()
                }
            }
        } catch let error as OtpauthError {
            switch error.rawValue {
            case "ALGORITHM_UNSUPPORTED":
                formError = "This link uses an unsupported algorithm."
            case "DIGITS_UNSUPPORTED":
                formError = "Enter a code length of 6 or 8 digits."
            case "PERIOD_OUT_OF_RANGE":
                formError = "Enter a period between 1 and 300 seconds."
            default:
                formError = "The Base32 key or otpauth link is invalid. Check the original and try again."
            }
        } catch {
            formError = "The Base32 key or otpauth link is invalid. Check the original and try again."
        }
    }
}
