import UIKit
import XCTest
@testable import InkOTP

final class CurtainTests: XCTestCase {
    func testForegroundRecordingBlocksContentUntilCaptureStops() {
        let initial = CurtainMode(sceneIsActive: true, captured: false)
        XCTAssertEqual(initial, .clear)
        XCTAssertFalse(initial.coversContent)

        let recording = CurtainMode(sceneIsActive: true, captured: true)
        XCTAssertEqual(recording, .capture)
        XCTAssertTrue(recording.coversContent)
        XCTAssertTrue(recording.showsNotice)

        let stopped = CurtainMode(sceneIsActive: true, captured: false)
        XCTAssertEqual(stopped, initial)
        XCTAssertFalse(stopped.showsNotice)
    }

    func testInactiveSceneKeepsBlankCurtainEvenIfRecording() {
        let inactive = CurtainMode(sceneIsActive: false, captured: false)
        let recordingInBackground = CurtainMode(sceneIsActive: false, captured: true)
        XCTAssertEqual(inactive, .inactive)
        XCTAssertEqual(recordingInBackground, inactive)
        XCTAssertTrue(inactive.coversContent)
        XCTAssertFalse(inactive.showsNotice)

        let activatesWhileRecording = CurtainMode(sceneIsActive: true, captured: true)
        XCTAssertEqual(activatesWhileRecording, .capture)
        XCTAssertTrue(activatesWhileRecording.coversContent)
    }

    @MainActor
    func testOnlyLocalizedNoticeIsAccessibleOnRecordingCurtain() throws {
        let controller = CurtainNoticeController()
        controller.show(.inactive)
        XCTAssertNil(controller.noticeElement)
        XCTAssertTrue(controller.view.accessibilityElementsHidden)

        controller.show(.capture)
        let heading = try XCTUnwrap(controller.noticeElement)
        let labels = try XCTUnwrap(controller.view.accessibilityElements as? [UILabel])
        XCTAssertTrue(controller.view.accessibilityViewIsModal)
        XCTAssertFalse(controller.view.accessibilityElementsHidden)
        XCTAssertTrue(labels.allSatisfy(\.isAccessibilityElement))
        XCTAssertEqual(labels.count, 2)
        XCTAssertTrue(labels.first === heading)
        XCTAssertEqual(heading.text, String(localized: "Screen sharing active"))
        XCTAssertTrue(heading.accessibilityTraits.contains(.header))
        XCTAssertEqual(labels.last?.text, String(localized: "Stop recording or mirroring to access InkOTP."))
    }
}
