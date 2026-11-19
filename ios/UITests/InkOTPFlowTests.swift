import XCTest

final class InkOTPFlowTests: XCTestCase {
    @MainActor
    func testConsentSetupAddSearchAndReopenVault() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        let agree = app.buttons["privacy.agree"]
        XCTAssertTrue(agree.waitForExistence(timeout: 12), app.debugDescription)
        agree.tap()
        tap("onboarding.continue", in: app)
        let setupForm = app.scrollViews.containing(.button, identifier: "onboarding.save").firstMatch
        let password = "public test recovery phrase"
        type(password, in: app.secureTextFields["onboarding.recovery"], scrolling: setupForm, app: app)
        type(password, in: app.secureTextFields["onboarding.confirm"], scrolling: setupForm, app: app)
        let acknowledgment = app.switches.matching(identifier: "onboarding.acknowledge").firstMatch
        show(acknowledgment, scrolling: setupForm, app: app)
        acknowledgment.tap()
        let setup = app.buttons["onboarding.save"]
        show(setup, scrolling: setupForm, app: app)
        setup.tap()
        tap("onboarding.authLater", in: app, timeout: 20)
        tap("onboarding.addLater", in: app)

        tap("vault.add", in: app)
        tap("account.manual", in: app)
        let editor = app.scrollViews.containing(.button, identifier: "account.save").firstMatch
        type("Public test account", in: app.textFields["account.name"], scrolling: editor, app: app)
        let secret = app.secureTextFields.matching(identifier: "account.secret").firstMatch
        type("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", in: secret, scrolling: editor, app: app)
        let save = app.buttons["account.save"]
        show(save, scrolling: editor, app: app)
        save.tap()
        let accountRow = app.buttons["account.copy"]
        XCTAssertTrue(accountRow.waitForExistence(timeout: 8), app.debugDescription)

        tap("vault.searchToggle", in: app)
        let search = app.textFields["vault.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5), app.debugDescription)
        search.tap()
        search.typeText("Missing public account")
        XCTAssertTrue(app.staticTexts["No matching accounts"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(accountRow.exists)

        app.terminate()
        app.launch()
        let unlock = app.buttons["vault.unlock"]
        if unlock.waitForExistence(timeout: 3) { unlock.tap() }
        XCTAssertTrue(accountRow.waitForExistence(timeout: 8), app.debugDescription)
        let details = app.buttons["Account details"]
        XCTAssertTrue(details.waitForExistence(timeout: 5), app.debugDescription)
        details.tap()
        XCTAssertTrue(
            app.staticTexts.matching(identifier: "Public test account").firstMatch.waitForExistence(timeout: 5),
            app.debugDescription
        )
    }

    private func tap(
        _ identifier: String, in app: XCUIApplication, timeout: TimeInterval = 8,
        file: StaticString = #filePath, line: UInt = #line
    ) {
        let button = app.buttons.matching(identifier: identifier).firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: timeout), "\(identifier)\n\(app.debugDescription)", file: file, line: line)
        button.tap()
    }

    private func type(_ text: String, in field: XCUIElement, scrolling form: XCUIElement, app: XCUIApplication) {
        show(field, scrolling: form, app: app)
        field.tap()
        field.typeText(text)
    }

    private func show(_ element: XCUIElement, scrolling form: XCUIElement, app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 8), app.debugDescription)
        for _ in 0..<3 where !element.isHittable { form.swipeUp() }
        XCTAssertTrue(element.isHittable, app.debugDescription)
    }
}
