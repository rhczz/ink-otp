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
        // SwiftUI's system dialog reports both a container and its child as the same button.
        let skip = app.buttons.matching(identifier: "onboarding.skipConfirm").firstMatch
        XCTAssertTrue(skip.waitForExistence(timeout: 8))
        skip.tap()
        tap("onboarding.addLater", in: app)

        tap("vault.add", in: app)
        tap("account.manual", in: app)
        let account = app.textFields["account.name"]
        XCTAssertTrue(account.waitForExistence(timeout: 8))
        account.tap()
        account.typeText("Public test account")
        let secret = app.secureTextFields.matching(identifier: "account.secret").firstMatch
        XCTAssertTrue(secret.waitForExistence(timeout: 5))
        secret.tap()
        secret.typeText("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        let save = app.buttons["account.save"]
        let editor = app.scrollViews.firstMatch
        for _ in 0..<3 where !save.isHittable { editor.swipeUp() }
        XCTAssertTrue(save.isHittable)
        save.tap()
        let accountRow = app.buttons["account.copy"]
        XCTAssertTrue(accountRow.waitForExistence(timeout: 8))

        tap("vault.searchToggle", in: app)
        let search = app.textFields["vault.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("Missing public account")
        XCTAssertTrue(app.staticTexts["No matching accounts"].waitForExistence(timeout: 5))
        XCTAssertFalse(accountRow.exists)

        app.terminate()
        app.launch()
        let unlock = app.buttons["vault.unlock"]
        if unlock.waitForExistence(timeout: 3) { unlock.tap() }
        XCTAssertTrue(accountRow.waitForExistence(timeout: 8))
        let details = app.buttons["Account details"]
        XCTAssertTrue(details.waitForExistence(timeout: 5))
        details.tap()
        XCTAssertTrue(app.staticTexts.matching(identifier: "Public test account").firstMatch.waitForExistence(timeout: 5))
    }

    private func tap(_ identifier: String, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let button = app.buttons[identifier]
        XCTAssertTrue(button.waitForExistence(timeout: 8), identifier, file: file, line: line)
        button.tap()
    }
}
