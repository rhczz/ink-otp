import Foundation
import XCTest

final class DesktopDiagnosticsTests: XCTestCase {
    private let timeout: TimeInterval = 12

    @MainActor
    func testCandidateDiagnosticsAndOfflinePrivacy() throws {
        continueAfterFailure = false
        let path = try XCTUnwrap(ProcessInfo.processInfo.environment["INKOTP_UI_STAGED_APP"])
        let app = XCUIApplication(url: URL(fileURLWithPath: path, isDirectory: true))
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: timeout))

        XCTAssertTrue(account("beta-one-account", in: app).waitForExistence(timeout: timeout),
                      "The staged candidate reopened the prepared Beta 1 vault")
        XCTAssertTrue(account("advanced-account", in: app).waitForExistence(timeout: timeout),
                      "The preceding candidate test saved a second TOTP account")
        checkDiagnostics(in: app)
        checkAboutPrivacy(in: app)
        checkCameraPrivacyWithoutPermission(in: app)
    }

    @MainActor
    private func checkDiagnostics(in app: XCUIApplication) {
        openVaultMenu("Diagnostics…", in: app)
        let recalculate = element("diagnostics-recalculate", in: app)
        let recalculated = element("diagnostics-recalculated", in: app)
        let sample = element("diagnostics-sample-resources", in: app)
        let resourceResult = element("diagnostics-resource-result", in: app)
        XCTAssertTrue(recalculate.waitForExistence(timeout: timeout))
        XCTAssertFalse(recalculated.exists)
        XCTAssertFalse(resourceResult.exists)

        click(sample)
        XCTAssertTrue(resourceResult.waitForExistence(timeout: timeout))
        XCTAssertFalse(recalculated.exists, "Sampling resources does not start a code refresh")
        let originalSample = text(of: resourceResult)
        XCTAssertFalse(originalSample.isEmpty)

        click(recalculate)
        XCTAssertTrue(recalculated.waitForExistence(timeout: timeout))
        XCTAssertTrue(text(of: recalculated).contains("Recalculated 2 TOTP codes"),
                      "The recalculation uses both seeded TOTP entries")
        XCTAssertTrue(resourceResult.exists, "Refreshing codes preserves the resource result")
        XCTAssertEqual(text(of: resourceResult), originalSample, "Refreshing codes does not replace the resource sample")
        click(element("info-done", in: app))
        waitUntilAbsent(recalculate, "Closing Diagnostics removes its controls")
    }

    @MainActor
    private func checkAboutPrivacy(in app: XCUIApplication) {
        openVaultMenu("About and Licenses…", in: app)
        let privacy = element("about-privacy", in: app)
        click(privacy)
        assertBundledPolicy(in: app)
        waitUntilAbsent(privacy, "About is replaced while the policy is open")
        click(element("info-done", in: app))
        XCTAssertTrue(privacy.waitForExistence(timeout: timeout), "Closing the policy returns to About")
        waitUntilAbsent(element("privacy-document", in: app), "Returning to About closes the policy")
        click(element("info-done", in: app))
        waitUntilAbsent(privacy, "Closing About returns to the vault")
    }

    @MainActor
    private func checkCameraPrivacyWithoutPermission(in app: XCUIApplication) {
        click(element("vault-add-menu", in: app))
        click(app.menuItems["Scan with Camera…"].firstMatch)
        let rationale = element("camera-rationale", in: app)
        let allowCamera = element("camera-request-permission", in: app)
        XCTAssertTrue(rationale.waitForExistence(timeout: timeout))
        let requestAvailable = allowCamera.exists
        if requestAvailable { XCTAssertTrue(allowCamera.isEnabled) }

        click(element("camera-privacy", in: app))
        assertBundledPolicy(in: app)
        waitUntilAbsent(allowCamera, "The privacy reader has no camera authorization action")
        waitUntilAbsent(rationale, "The camera rationale is replaced while the policy is open")
        click(element("info-done", in: app))
        XCTAssertTrue(rationale.waitForExistence(timeout: timeout), "Closing the policy returns to the camera rationale")
        waitUntilAbsent(element("privacy-document", in: app), "Returning to the camera closes the policy")
        XCTAssertEqual(allowCamera.exists, requestAvailable, "Reading the policy does not change camera permission")
        if requestAvailable { XCTAssertTrue(allowCamera.isEnabled) }

        // Exercise only the rationale and its navigation. The actual macOS
        // camera permission button is never activated in this hosted test.
        click(app.buttons["Cancel"].firstMatch)
        waitUntilAbsent(rationale, "Closing the camera rationale returns to the vault")
    }

    @MainActor
    private func assertBundledPolicy(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let policy = element("privacy-document", in: app)
        XCTAssertTrue(policy.waitForExistence(timeout: timeout), file: file, line: line)
        let chapter = app.staticTexts.matching(NSPredicate(
            format: "label CONTAINS %@ OR value CONTAINS %@", "What InkOTP processes", "What InkOTP processes"
        )).firstMatch
        XCTAssertTrue(chapter.waitForExistence(timeout: timeout), "The bundled policy contains its first section", file: file, line: line)
        XCTAssertFalse(element("privacy-accept", in: app).exists, "Reading does not change consent", file: file, line: line)
    }

    @MainActor
    private func openVaultMenu(_ item: String, in app: XCUIApplication) {
        click(app.menuBars.menuBarItems["Vault"].firstMatch)
        click(app.menuItems[item].firstMatch)
    }

    @MainActor
    private func account(_ name: String, in app: XCUIApplication) -> XCUIElement {
        app.staticTexts
            .matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", name, name)).firstMatch
    }

    @MainActor
    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    @MainActor
    private func text(of element: XCUIElement) -> String {
        [element.label, element.value as? String ?? ""].joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    @MainActor
    private func waitUntilAbsent(_ element: XCUIElement, _ message: String,
                                 file: StaticString = #filePath, line: UInt = #line) {
        let absent = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [absent], timeout: timeout), .completed, message, file: file, line: line)
    }

    @MainActor
    private func click(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "Expected desktop control is unavailable", file: file, line: line)
        element.click()
    }
}
