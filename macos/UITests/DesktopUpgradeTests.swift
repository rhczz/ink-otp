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
        type(recoveryPassword, into: app.secureTextFields["Recovery password (optional)"].firstMatch)
        type(recoveryPassword, into: app.secureTextFields["Confirm password"].firstMatch)
        click(app.buttons["Create Vault"].firstMatch)
        XCTAssertTrue(app.staticTexts["No Accounts"].firstMatch.waitForExistence(timeout: timeout))

        app.typeKey("n", modifierFlags: .command)
        type(legacyIssuer, into: app.textFields["Service (optional)"].firstMatch)
        type(legacyAccount, into: app.textFields["Account"].firstMatch)
        type(publicKey, into: app.secureTextFields["Base32 setup key"].firstMatch)
        click(app.buttons["Save Account"].firstMatch)
        XCTAssertTrue(account(app, named: legacyAccount).waitForExistence(timeout: timeout))
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
        setImmediateLock(in: app)
        let fixture = account(app, named: uriAccount)

        moveToFinder(from: app)
        XCTAssertTrue(element("desktop-privacy-curtain", in: app).waitForExistence(timeout: timeout))
        XCTAssertFalse(fixture.exists, "Account names are hidden while the app is inactive")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: timeout))
        XCTAssertTrue(fixture.waitForExistence(timeout: timeout), "Returning from an automatic lock reopens the vault")
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
    private func setImmediateLock(in app: XCUIApplication) {
        app.typeKey(",", modifierFlags: .command)
        let picker = element("settings-lock-timeout", in: app)
        XCTAssertTrue(picker.waitForExistence(timeout: timeout))
        if picker.value as? String != "Immediately" {
            click(picker)
            click(app.menuItems["Immediately"].firstMatch)
            let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "Immediately"), object: picker)
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
        app.windows["InkOTP"].staticTexts[name].firstMatch
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
