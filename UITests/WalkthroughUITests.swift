import XCTest

/// Taps through every tab and tool, saving a screenshot of each screen to the
/// test results. Grant location first so no permission alert covers the UI:
///   xcrun simctl privacy <device> grant location org.nomadpocket.NomadPocket
final class WalkthroughUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launch()
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func tab(_ name: String) {
        app.tabBars.buttons[name].tap()
    }

    private func back() {
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    func testSurviveTab() {
        XCTAssertTrue(app.staticTexts["Emergency"].waitForExistence(timeout: 10))
        shot("01-survive")
        app.buttons["CPR"].firstMatch.tap()
        sleep(2)
        shot("02-guide-cpr")
        back()
        app.swipeUp()
        shot("03-survive-tools")
        for tool in ["Compass & Position", "SOS Light", "Water Purifier", "Medical ID", "Checklists", "Daylight", "Morse Code"] {
            let button = app.buttons[tool].firstMatch
            // Scroll toward the tile: it can sit under the tab bar or, after an
            // overshoot on a small phone, under the search bar.
            for _ in 0 ..< 4 where !(button.exists && button.isHittable) {
                if button.exists, button.frame.midY < app.frame.midY { app.swipeDown() } else { app.swipeUp() }
            }
            guard button.waitForExistence(timeout: 3) else { XCTFail("missing \(tool); buttons: \(app.buttons.allElementsBoundByIndex.map { $0.label })"); continue }
            button.tap()
            sleep(2)
            shot("04-tool-\(tool)")
            back()
        }
        app.swipeUp()
        let firstAid = app.buttons["First Aid"].firstMatch
        if firstAid.waitForExistence(timeout: 3) {
            firstAid.tap()
            shot("05-category-first-aid")
            back()
        }
    }

    func testSearchAndSettings() {
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("tourniquet")
        sleep(2)
        shot("06-search-tourniquet")
        // iOS 27's search field has no Cancel button; start fresh instead.
        app.terminate()
        app.launch()
        app.buttons["Settings"].tap()
        sleep(1)
        shot("07-settings")
    }

    func testAskTab() {
        tab("Ask")
        shot("08-ask-empty")
        app.buttons["How much bleach purifies a gallon of water?"].tap()
        // Retrieval is instant; an on-device model may take a while.
        _ = app.staticTexts["SOURCES"].waitForExistence(timeout: 90) || app.staticTexts["BEST MATCHES"].waitForExistence(timeout: 5)
        sleep(3)
        shot("09-ask-answer")
    }

    func testLibraryMapMesh() {
        tab("Library")
        sleep(1)
        shot("10-library")
        app.buttons["Get content"].firstMatch.tap()
        sleep(8)
        shot("11-get-content")
        app.buttons["Done"].firstMatch.tap()
        tab("Map")
        sleep(6)
        shot("12-map")
        tab("Mesh")
        sleep(2)
        shot("13-mesh")
    }
}
