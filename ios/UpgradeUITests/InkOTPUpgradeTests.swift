import XCTest

final class InkOTPUpgradeTests: XCTestCase {
    @MainActor
    func testReadsExistingAccountWithDeviceKeyAfterUpgrade() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "io.github.rhczz.inkotp")
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        let unlock = app.buttons["vault.unlock"]
        if unlock.waitForExistence(timeout: 3) { unlock.tap() }
        XCTAssertTrue(app.buttons["account.copy"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.buttons["privacy.agree"].exists, app.debugDescription)
        XCTAssertFalse(app.buttons["onboarding.continue"].exists, app.debugDescription)

        let details = app.buttons["Account details"]
        XCTAssertTrue(details.waitForExistence(timeout: 5), app.debugDescription)
        details.tap()
        XCTAssertTrue(
            app.staticTexts.matching(identifier: "Public test account").firstMatch.waitForExistence(timeout: 5),
            app.debugDescription
        )
        app.terminate()
    }
}
