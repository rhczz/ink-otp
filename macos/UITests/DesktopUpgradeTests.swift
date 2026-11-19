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
    func testCandidatePreservesAccountAndDesktopBehavior() throws {
        continueAfterFailure = false
        let app = try launchInkOTP()
        defer { app.terminate() }
        // A replacement can need recovery when the login Keychain denies the old identity.
        // The recovery password is entered through InkOTP's normal visible form.
        let previousAccount = account(app, named: legacyAccount)
        if !previousAccount.waitForExistence(timeout: timeout) {
            click(element("vault-recovery-open", in: app))
            type(recoveryPassword, into: element("vault-recovery-password", in: app))
            click(element("vault-recovery-submit", in: app))
        }
        XCTAssertTrue(previousAccount.waitForExistence(timeout: timeout), "The Beta 1 account survived replacement")

        try addURIAccount(in: app)
        let fixture = account(app, named: uriAccount)
        verifyPrivacyCurtainPreservesDraft(in: app, savedAccount: fixture)
        verifyHiddenCopy(in: app)
        try verifyImportedHOTP(in: app)
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

    @MainActor
    private func verifyImportedHOTP(in app: XCUIApplication) throws {
        let name = "counter-only-account"
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("public-hotp.txt")
        let uri = "otpauth://hotp/Public%20Counter:\(name)?secret=\(publicKey)&issuer=Public%20Counter&counter=10300\n"
        try Data(uri.utf8).write(to: file)

        app.typeKey("o", modifierFlags: .command)
        let choose = app.buttons["Choose Backup"].firstMatch
        XCTAssertTrue(choose.waitForExistence(timeout: timeout))
        app.typeKey("g", modifierFlags: [.command, .shift])
        app.typeText(folder.path)
        app.typeKey(XCUIKeyboardKey.return.rawValue, modifierFlags: [])
        let fileRow = app.staticTexts.matching(NSPredicate(
            format: "label == %@ OR value == %@",
            file.lastPathComponent, file.lastPathComponent
        )).firstMatch
        click(fileRow)
        click(choose)
        click(app.buttons["Preview Backup"].firstMatch)
        click(app.buttons["Import Accounts"].firstMatch)
        click(account(app, named: name))

        let detail = element("account-detail-name", in: app)
        XCTAssertTrue(detail.waitForExistence(timeout: timeout))
        XCTAssertTrue(detail.label == name || detail.value as? String == name)
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
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@ OR value == %@", name, name)).firstMatch
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
