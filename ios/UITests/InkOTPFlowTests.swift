import XCTest

final class InkOTPFlowTests: XCTestCase {
    @MainActor
    func testConsentSetupAddSearchAndReopenVault() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        let agree = app.buttons["privacy.agree"]
        XCTAssertTrue(agree.waitForExistence(timeout: 12))
        agree.tap()
        tap("onboarding.continue", in: app)
        tap("onboarding.skip", in: app)
        let skip = app.buttons["onboarding.skipConfirm"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        else { tap("Skip for now", in: app) }
        let addLater = app.buttons["onboarding.addLater"]
        if addLater.waitForExistence(timeout: 3) { addLater.tap() }

        tap("vault.add", in: app)
        tap("account.manual", in: app)
        let account = app.textFields["account.name"]
        XCTAssertTrue(account.waitForExistence(timeout: 8))
        account.tap()
        account.typeText("Public test account")
        let secret = app.descendants(matching: .any)["account.secret"].firstMatch
        XCTAssertTrue(secret.exists)
        secret.tap()
        secret.typeText("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        let save = app.buttons["account.save"]
        if !save.isHittable { app.swipeUp() }
        save.tap()
        XCTAssertTrue(app.staticTexts["Public test account"].waitForExistence(timeout: 8))

        tap("vault.searchToggle", in: app)
        let search = app.textFields["vault.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("Missing public account")
        XCTAssertFalse(app.staticTexts["Public test account"].exists)

        app.terminate()
        app.launch()
        let unlock = app.buttons["vault.unlock"]
        if unlock.waitForExistence(timeout: 3) { unlock.tap() }
        XCTAssertTrue(app.staticTexts["Public test account"].waitForExistence(timeout: 8))
    }

    private func tap(_ identifier: String, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let button = app.buttons[identifier]
        XCTAssertTrue(button.waitForExistence(timeout: 8), identifier, file: file, line: line)
        button.tap()
    }
}
