import XCTest

/// Runs against the actual production app; fixture installation is handled by verification/ios/ui.sh.
final class LocalWorkflowTests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.kingscan.app")

    override func setUpWithError() throws {
        continueAfterFailure = false
        app.launch()
        XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 15), app.debugDescription)
    }

    private func screenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func menu(_ title: String) {
        app.buttons["更多"].tap()
        app.buttons[title].tap()
    }

    private func detailAction(_ title: String) {
        let button = app.buttons[title]
        for _ in 0..<14 {
            if button.exists && button.isHittable { button.tap(); return }
            let frame = app.frame
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.88, dy: 0.92))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.92))
            XCTAssertGreaterThan(frame.height, 0)
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTFail("Missing document action: \(title)\n\(app.debugDescription)")
    }

    func testQrGenerationAndNativeShareSheet() throws {
        menu("二维码与条码")
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        field.tap()
        field.typeText("KingOfScan OFFLINE 2026 https://example.org/local")
        app.buttons["生成二维码"].tap()
        app.swipeUp()
        XCTAssertTrue(app.images["生成的二维码"].waitForExistence(timeout: 15), app.debugDescription)
        screenshot("ios-qr-generated")
        app.buttons["分享二维码"].tap()
        XCTAssertTrue(app.buttons["Close"].waitForExistence(timeout: 15) || app.buttons["关闭"].exists || app.otherElements["ActivityListView"].exists, app.debugDescription)
        screenshot("ios-native-share")
    }

    func testRetouchAnnotationsPagesAndPrintPanel() throws {
        let document = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "UI验收文档")).firstMatch
        XCTAssertTrue(document.waitForExistence(timeout: 10), app.debugDescription)
        document.tap()
        detailAction("擦除与修复")
        XCTAssertTrue(app.buttons["去彩笔"].waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["去彩笔"].tap()
        let reset = app.buttons["重置修复"]
        XCTAssertTrue(reset.waitForExistence(timeout: 10))
        reset.tap()
        app.buttons["撤销"].tap()
        screenshot("ios-retouch-undo")
        app.buttons["保存"].tap()
        XCTAssertTrue(app.buttons["重命名"].waitForExistence(timeout: 15))
        detailAction("批注签名")
        XCTAssertTrue(app.buttons["保存"].waitForExistence(timeout: 10), app.debugDescription)
        screenshot("ios-annotation")
        app.buttons["返回"].tap()
        detailAction("页面管理")
        screenshot("ios-page-management")
        app.buttons["返回"].tap()
        detailAction("系统打印")
        XCTAssertTrue(app.buttons["Cancel"].waitForExistence(timeout: 20) || app.buttons["取消"].exists, app.debugDescription)
        screenshot("ios-system-print")
    }
}
