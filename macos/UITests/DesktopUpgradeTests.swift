import AppKit
import Foundation
import XCTest

final class DesktopUpgradeTests: XCTestCase {
    private let timeout: TimeInterval = 12
    private let legacyIssuer = "Public Upgrade Fixture"
    private let legacyAccount = "beta-one-account"
    private let recoveryPassword = "Public CI recovery password"
    private let publicKey = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private let uriIssuer = "Desk CI"
    private let uriAccount = "advanced-account"
    private let hotpAccount = "counter-only-account"

    @MainActor
    func testPublishedBetaCreatesVaultAndAccount() throws {
        continueAfterFailure = false
        let app = try launchInkOTP()
        defer { app.terminate() }

        click(app.buttons["Read Privacy Statement"].firstMatch)
        click(app.buttons["Continue"].firstMatch)
        click(app.buttons["Create Vault…"].firstMatch)
        let setup = legacyFields(in: app, secure: true, count: 2)
        type(recoveryPassword, into: setup.element(boundBy: 0))
        type(recoveryPassword, into: setup.element(boundBy: 1))
        click(app.buttons["Create Vault"].firstMatch)
        click(app.buttons["Add Account…"].firstMatch)
        let accountFields = legacyFields(in: app, secure: false, count: 2)
        type(legacyIssuer, into: accountFields.element(boundBy: 0))
        type(legacyAccount, into: accountFields.element(boundBy: 1))
        let keyField = legacyFields(in: app, secure: true, count: 1)
        type(publicKey, into: keyField.firstMatch)
        click(app.buttons["Save Account"].firstMatch)
        let saved = account(app, named: legacyAccount)
        if !saved.waitForExistence(timeout: timeout) {
            XCTFail("The Beta 1 account was not found after saving. Accessibility tree:\n\(app.debugDescription)")
        }
    }

    @MainActor
    func testCandidateRecoversAndSeedsAccounts() throws {
        continueAfterFailure = false
        let app = try launchInkOTP()
        defer { app.terminate() }
        // A replacement can need recovery when the login Keychain denies the old identity.
        // The recovery password is entered through InkOTP's normal visible form.
        let unlocked = element("vault-lock", in: app)
        if !unlocked.waitForExistence(timeout: timeout) {
            click(element("vault-recovery-open", in: app))
            type(recoveryPassword, into: element("vault-recovery-password", in: app))
            click(element("vault-recovery-submit", in: app))
        }
        guard unlocked.waitForExistence(timeout: timeout) else {
            XCTFail("The vault stayed locked after recovery. Accessibility tree:\n\(app.debugDescription)")
            return
        }
        let previousAccount = account(app, named: legacyAccount)
        guard previousAccount.waitForExistence(timeout: timeout) else {
            XCTFail("The vault reopened, but the Beta 1 account was not found. Accessibility tree:\n\(app.debugDescription)")
            return
        }

        try addURIAccount(in: app)
    }

    @MainActor
    func testCandidateHiddenCopyUsesClipboardWithoutReveal() throws {
        continueAfterFailure = false
        let app = try launchInkOTP()
        defer { app.terminate() }
        XCTAssertTrue(element("vault-lock", in: app).waitForExistence(timeout: timeout))
        setLockTimeout("After 30 Seconds", in: app)
        verifyHiddenCopy(in: app)
    }

    @MainActor
    func testCandidateImportedHOTPDoesNotGenerate() throws {
        continueAfterFailure = false
        let app = try launchInkOTP()
        defer { app.terminate() }
        XCTAssertTrue(element("vault-lock", in: app).waitForExistence(timeout: timeout))
        setLockTimeout("After 30 Seconds", in: app)
        try importPublicHOTP(in: app)
        verifyImportedHOTP(in: app)
    }

    @MainActor
    func testCandidatePrivacyCurtainAndLockLifecycle() throws {
        continueAfterFailure = false
        let app = try launchInkOTP()
        defer { app.terminate() }
        XCTAssertTrue(element("vault-lock", in: app).waitForExistence(timeout: timeout))
        let fixture = account(app, named: uriAccount)
        let previousAccount = account(app, named: legacyAccount)
        verifyPrivacyCurtainPreservesDraft(in: app, savedAccount: fixture)
        setLockTimeout("Immediately", in: app)
        app.typeKey("n", modifierFlags: .command)
        let openEditor = element("account-setup-input", in: app)
        XCTAssertTrue(openEditor.waitForExistence(timeout: timeout))

        moveToFinder(from: app)
        XCTAssertTrue(element("desktop-privacy-curtain", in: app).waitForExistence(timeout: timeout))
        XCTAssertFalse(fixture.exists, "Account names are hidden while the app is inactive")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: timeout))
        XCTAssertTrue(fixture.waitForExistence(timeout: timeout), "Returning from an automatic lock reopens the vault")
        XCTAssertFalse(openEditor.exists, "The automatic lock closed the editor that was open before leaving")
        XCTAssertTrue(previousAccount.exists)

        click(element("vault-lock", in: app))
        let locked = element("vault-unlock", in: app)
        XCTAssertTrue(locked.waitForExistence(timeout: timeout))
        XCTAssertFalse(fixture.exists)
        moveToFinder(from: app)
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: timeout))
        XCTAssertTrue(locked.waitForExistence(timeout: timeout), "An explicit lock must survive application activation")

        let accountReturned = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: fixture)
        accountReturned.isInverted = true
        wait(for: [accountReturned], timeout: 2)
        click(locked)
        XCTAssertTrue(fixture.waitForExistence(timeout: timeout))
        XCTAssertTrue(previousAccount.exists)
    }

    @MainActor
    func testPublishedQRImageImportAndPersistence() throws {
        continueAfterFailure = false
        let app = try launchInkOTP()
        defer { app.terminate() }
        let unlocked = element("vault-lock", in: app)
        XCTAssertTrue(unlocked.waitForExistence(timeout: timeout))
        setLockTimeout("After 30 Seconds", in: app)

        let initialCount = try unfilteredAccountCount(in: app)
        let single = try stagedFixture("qr-single.png")
        let blank = try stagedFixture("qr-blank.png")
        let multiple = try stagedFixture("qr-multiple.png")
        let save = element("account-save", in: app)
        let qrAccount = account(app, named: "me@example.com")

        @MainActor
        func assertOriginalVault() {
            expectAccountCount(initialCount, in: app)
            XCTAssertTrue(account(app, named: legacyAccount).exists)
            XCTAssertTrue(account(app, named: uriAccount).exists)
            XCTAssertFalse(qrAccount.exists)
            XCTAssertFalse(account(app, named: "alice").exists)
            XCTAssertFalse(save.exists)
        }

        let picker = showQRImagePicker(in: app)
        app.typeKey(XCUIKeyboardKey.escape.rawValue, modifierFlags: [])
        waitUntilAbsent(picker, "Cancel dismisses the native image picker")
        assertOriginalVault()

        chooseQRImage(single, expecting: save, in: app)
        assertQRAccountPreview(in: app)
        click(app.buttons["Cancel"].firstMatch)
        waitUntilAbsent(save, "Cancel dismisses the account preview")
        assertOriginalVault()

        for (image, message) in [
            (blank, "No verification QR code was found in the image."),
            (multiple, "The image contains multiple verification QR codes. Choose an image with one account.")
        ] {
            let notice = staticText(message, in: app)
            chooseQRImage(image, expecting: notice, in: app)
            assertOriginalVault()
            click(app.buttons["Dismiss"].firstMatch)
            waitUntilAbsent(notice, "Dismiss closes the image error")
        }

        chooseQRImage(single, expecting: save, in: app)
        assertQRAccountPreview(in: app)
        click(save)
        waitUntilAbsent(save, "Saving returns to the vault")
        expectAccountCount(initialCount + 1, in: app)
        XCTAssertTrue(qrAccount.waitForExistence(timeout: timeout))

        app.terminate()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: timeout))
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: timeout))
        XCTAssertTrue(unlocked.waitForExistence(timeout: timeout))
        expectAccountCount(initialCount + 1, in: app)
        XCTAssertTrue(account(app, named: legacyAccount).exists)
        XCTAssertTrue(account(app, named: uriAccount).exists)
        XCTAssertFalse(account(app, named: "alice").exists)
        click(qrAccount)

        let issuer = element("account-detail-issuer", in: app)
        let name = element("account-detail-name", in: app)
        XCTAssertTrue(name.waitForExistence(timeout: timeout))
        XCTAssertTrue(issuer.label == "GitHub" || issuer.value as? String == "GitHub")
        XCTAssertTrue(name.label == "me@example.com" || name.value as? String == "me@example.com")
        for parameter in ["TOTP", "SHA1", "6", "30 seconds"] {
            XCTAssertTrue(staticText(parameter, in: app).waitForExistence(timeout: timeout),
                          "The relaunched app retained the imported \(parameter) parameter")
        }
    }

    @MainActor
    private func launchInkOTP() throws -> XCUIApplication {
        let path = try XCTUnwrap(ProcessInfo.processInfo.environment["INKOTP_UI_STAGED_APP"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: path), "The staged app is missing")
        let app = XCUIApplication(url: URL(fileURLWithPath: path, isDirectory: true))
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: timeout), "InkOTP did not reach the desktop foreground")
        return app
    }

    @MainActor
    private func addURIAccount(in app: XCUIApplication) throws {
        app.typeKey("n", modifierFlags: .command)
        let uri = "otpauth://totp/Desk%20CI:\(uriAccount)?secret=\(publicKey)&issuer=Desk%20CI&algorithm=SHA256&digits=8&period=60"
        type(uri, into: element("account-setup-input", in: app))
        click(element("account-setup-preview", in: app))

        let service = element("account-issuer", in: app)
        let name = element("account-name", in: app)
        XCTAssertTrue(service.waitForExistence(timeout: timeout))
        XCTAssertEqual(service.value as? String, uriIssuer)
        XCTAssertEqual(name.value as? String, uriAccount)
        let parameters = element("account-setup-parameters", in: app)
        XCTAssertTrue(parameters.waitForExistence(timeout: timeout))
        let summary = parameters.label + " " + (parameters.value as? String ?? "")
        for token in ["TOTP", "SHA256", "8", "60"] {
            XCTAssertTrue(summary.contains(token), "URI preview missing \(token)")
        }
        click(element("account-save", in: app))
        XCTAssertTrue(account(app, named: uriAccount).waitForExistence(timeout: timeout))
    }

    @MainActor
    private func verifyPrivacyCurtainPreservesDraft(in app: XCUIApplication, savedAccount: XCUIElement) {
        setLockTimeout("After 30 Seconds", in: app)
        app.typeKey("n", modifierFlags: .command)
        let draftName = element("account-name", in: app)
        let draftIssuer = element("account-issuer", in: app)
        type("unsaved-display-name", into: draftName)
        type("Draft CI", into: draftIssuer)
        moveToFinder(from: app)
        XCTAssertTrue(element("desktop-privacy-curtain", in: app).waitForExistence(timeout: timeout))
        XCTAssertFalse(draftName.exists, "The editor is absent from the inactive accessibility tree")
        XCTAssertFalse(draftIssuer.exists, "The unsaved issuer is hidden while the app is inactive")
        XCTAssertFalse(savedAccount.exists, "Saved account names are hidden while the app is inactive")

        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: timeout))
        XCTAssertTrue(draftName.waitForExistence(timeout: timeout))
        XCTAssertEqual(draftName.value as? String, "unsaved-display-name")
        XCTAssertEqual(draftIssuer.value as? String, "Draft CI")
        click(app.buttons["Cancel"].firstMatch)
        XCTAssertTrue(savedAccount.waitForExistence(timeout: timeout))
    }

    @MainActor
    private func verifyHiddenCopy(in app: XCUIApplication) {
        app.typeKey(",", modifierFlags: .command)
        click(element("settings-hide-codes", in: app))
        app.typeKey("w", modifierFlags: .command)
        click(account(app, named: uriAccount))

        let code = element("account-code", in: app)
        let hiddenLabel = "Verification code hidden"
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", hiddenLabel), object: code)
        wait(for: [hidden], timeout: timeout)
        XCTAssertTrue((code.value as? String ?? "").isEmpty)

        let clipboard = NSPasteboard.general
        let before = clipboard.changeCount
        click(element("account-copy", in: app))
        XCTAssertGreaterThan(clipboard.changeCount, before)
        let copied = clipboard.string(forType: .string) ?? ""
        XCTAssertEqual(copied.utf8.count, 8)
        XCTAssertTrue(copied.utf8.allSatisfy { (48...57).contains($0) })
        XCTAssertEqual(code.label, hiddenLabel)
        XCTAssertTrue((code.value as? String ?? "").isEmpty)
    }

    private func stagedFixture(_ filename: String) throws -> URL {
        let appPath = try XCTUnwrap(ProcessInfo.processInfo.environment["INKOTP_UI_STAGED_APP"])
        let file = URL(fileURLWithPath: appPath, isDirectory: true).deletingLastPathComponent()
            .appendingPathComponent(filename)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), "Missing fixture \(filename)")
        return file
    }

    @MainActor
    private func enterNativeFilePath(_ file: URL, in app: XCUIApplication) {
        app.typeKey("g", modifierFlags: [.command, .shift])
        app.typeText(file.resolvingSymlinksInPath().path)
        app.typeKey(XCUIKeyboardKey.return.rawValue, modifierFlags: [])
    }

    @MainActor
    private func showQRImagePicker(in app: XCUIApplication) -> XCUIElement {
        click(element("vault-add-menu", in: app))
        click(app.menuItems["Read QR Image…"].firstMatch)
        let picker = app.buttons["Choose QR Image"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: timeout))
        return picker
    }

    @MainActor
    private func chooseQRImage(_ file: URL, expecting outcome: XCUIElement, in app: XCUIApplication) {
        _ = showQRImagePicker(in: app)
        enterNativeFilePath(file, in: app)
        if !outcome.waitForExistence(timeout: 2) {
            app.typeKey(XCUIKeyboardKey.return.rawValue, modifierFlags: [])
        }
        guard outcome.waitForExistence(timeout: timeout) else {
            XCTFail("Selecting \(file.lastPathComponent) did not reach the expected UI. Accessibility tree:\n\(app.debugDescription)")
            return
        }
    }

    @MainActor
    private func assertQRAccountPreview(in app: XCUIApplication) {
        let service = element("account-issuer", in: app)
        let name = element("account-name", in: app)
        let parameters = element("account-setup-parameters", in: app)
        XCTAssertTrue(parameters.waitForExistence(timeout: timeout))
        XCTAssertEqual(service.value as? String, "GitHub")
        XCTAssertEqual(name.value as? String, "me@example.com")
        let summary = "TOTP · SHA1 · 6 digits · 30 seconds"
        XCTAssertTrue(parameters.label == summary || parameters.value as? String == summary)
    }

    @MainActor
    private func unfilteredAccountCount(in app: XCUIApplication) throws -> Int {
        let pattern = "^[0-9]+ accounts$"
        let count = app.staticTexts.matching(NSPredicate(
            format: "label MATCHES %@ OR value MATCHES %@", pattern, pattern
        )).firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: timeout))
        let labels = [count.label, count.value as? String ?? ""]
        return try XCTUnwrap(labels.lazy.compactMap { value in
            value.hasSuffix(" accounts") ? Int(value.dropLast(" accounts".count)) : nil
        }.first)
    }

    @MainActor
    private func expectAccountCount(_ count: Int, in app: XCUIApplication) {
        XCTAssertTrue(staticText("\(count) accounts", in: app).waitForExistence(timeout: timeout),
                      "The vault should contain \(count) accounts")
    }

    @MainActor
    private func staticText(_ value: String, in app: XCUIApplication) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label == %@ OR value == %@", value, value)).firstMatch
    }

    @MainActor
    private func waitUntilAbsent(_ control: XCUIElement, _ message: String) {
        let absent = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: control)
        XCTAssertEqual(XCTWaiter.wait(for: [absent], timeout: timeout), .completed, message)
    }

    @MainActor
    private func importPublicHOTP(in app: XCUIApplication) throws {
        let file = try stagedFixture("public-hotp.txt")
        app.typeKey("o", modifierFlags: .command)
        let choose = app.buttons["Choose Backup"].firstMatch
        XCTAssertTrue(choose.waitForExistence(timeout: timeout))
        enterNativeFilePath(file, in: app)
        let preview = app.buttons["Preview Backup"].firstMatch
        if !preview.waitForExistence(timeout: 2) {
            // The Go to Folder panel selects the file. Return invokes Open in the real chooser.
            app.typeKey(XCUIKeyboardKey.return.rawValue, modifierFlags: [])
        }
        guard preview.waitForExistence(timeout: timeout) else {
            XCTFail("Choosing the public HOTP file did not reach preview. Accessibility tree:\n\(app.debugDescription)")
            return
        }
        click(preview)
        click(app.buttons["Import Accounts"].firstMatch)
        XCTAssertTrue(account(app, named: hotpAccount).waitForExistence(timeout: timeout))
    }

    @MainActor
    private func verifyImportedHOTP(in app: XCUIApplication) {
        click(account(app, named: hotpAccount))

        let detail = element("account-detail-name", in: app)
        XCTAssertTrue(detail.waitForExistence(timeout: timeout))
        XCTAssertTrue(detail.label == hotpAccount || detail.value as? String == hotpAccount)
        let preserved = app.staticTexts.matching(NSPredicate(
            format: "label CONTAINS %@ OR value CONTAINS %@",
            "preserves HOTP accounts", "preserves HOTP accounts"
        )).firstMatch
        XCTAssertTrue(preserved.waitForExistence(timeout: timeout))
        let counter = app.staticTexts.matching(NSPredicate(format: "label == %@ OR value == %@", "10300", "10300")).firstMatch
        XCTAssertTrue(counter.waitForExistence(timeout: timeout), "Import preserved the HOTP counter")
        XCTAssertFalse(element("account-code", in: app).exists)
        XCTAssertFalse(element("account-copy", in: app).exists)
    }

    @MainActor
    private func setLockTimeout(_ label: String, in app: XCUIApplication) {
        app.typeKey(",", modifierFlags: .command)
        let picker = element("settings-lock-timeout", in: app)
        XCTAssertTrue(picker.waitForExistence(timeout: timeout))
        if picker.value as? String != label {
            click(picker)
            click(app.menuItems[label].firstMatch)
            let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", label), object: picker)
            wait(for: [selected], timeout: timeout)
        }
        app.typeKey("w", modifierFlags: .command)
        XCTAssertTrue(account(app, named: uriAccount).waitForExistence(timeout: timeout))
    }

    @MainActor
    private func moveToFinder(from app: XCUIApplication) {
        let finder = XCUIApplication(bundleIdentifier: "com.apple.finder")
        finder.activate()
        XCTAssertTrue(finder.wait(for: .runningForeground, timeout: timeout))
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: timeout))
    }

    @MainActor
    private func account(_ app: XCUIApplication, named name: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", name, name)).firstMatch
    }

    @MainActor
    private func legacyFields(in app: XCUIApplication, secure: Bool, count: Int,
                              file: StaticString = #filePath, line: UInt = #line) -> XCUIElementQuery {
        // Beta 1 uses SwiftUI form placeholders, with no stable accessibility identifier.
        let sheet = app.sheets.firstMatch
        let scope: XCUIElement = sheet.exists ? sheet : app
        let fields = secure ? scope.secureTextFields : scope.textFields
        guard fields.firstMatch.waitForExistence(timeout: timeout), fields.count == count else {
            XCTFail("Expected \(count) Beta 1 form fields. Accessibility tree:\n\(app.debugDescription)", file: file, line: line)
            return fields
        }
        return fields
    }

    @MainActor
    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    @MainActor
    private func click(_ control: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(control.waitForExistence(timeout: timeout), "Expected control is unavailable", file: file, line: line)
        control.click()
    }

    @MainActor
    private func type(_ text: String, into control: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        click(control, file: file, line: line)
        control.typeText(text)
    }
}
