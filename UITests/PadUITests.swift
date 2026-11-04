import XCTest

/// iPad only: the split-view layout, its keyboard shortcuts, and a screenshot of each
/// section in portrait and landscape. Screenshots go to the test results, and also to
/// the folder in TEST_RUNNER_PAD_SHOTS_DIR when that is set. Grant location first:
///   xcrun simctl privacy <device> grant location org.nomadpocket.NomadPocket
final class PadUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launch()
        // iPhone (and a narrow iPad window) keep the tab bar; this layout is not there.
        try XCTSkipIf(app.tabBars.firstMatch.waitForExistence(timeout: 10), "iPad split view only")
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    private func launch(_ screen: String) {
        app.terminate()
        app.launchArguments = ["-screen", screen]
        app.launch()
    }

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["PAD_SHOTS_DIR"] {
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }

    func testSidebarAndShortcuts() {
        launch("survive")
        XCTAssertTrue(app.staticTexts["Emergency"].waitForExistence(timeout: 15), "Survive home in the detail column")
        XCTAssertTrue(app.navigationBars["Guides"].exists, "guide list column")

        app.typeKey("2", modifierFlags: .command)
        XCTAssertTrue(app.staticTexts["Ask a question"].waitForExistence(timeout: 5), "Cmd-2 opens Ask")
        app.typeKey("3", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Library"].waitForExistence(timeout: 5), "Cmd-3 opens Library")
        app.typeKey("5", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Mesh"].waitForExistence(timeout: 5), "Cmd-5 opens Mesh")
        app.typeKey("4", modifierFlags: .command)
        XCTAssertTrue(app.buttons["Map layers"].waitForExistence(timeout: 5), "Cmd-4 opens Map")

        // From the map, Cmd-F goes to the guide search.
        app.typeKey("f", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Guides"].waitForExistence(timeout: 5), "Cmd-F opens Survive search")
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertTrue(search.value(forKey: "hasKeyboardFocus") as? Bool ?? false, "Cmd-F focuses search")
        app.typeText("water")
        XCTAssertTrue(app.staticTexts["SURVIVAL GUIDE"].firstMatch.waitForExistence(timeout: 10), "results beside the list")

        // Plain Cmd-S does nothing; Cmd-Shift-S opens the SOS light.
        app.typeKey("s", modifierFlags: .command)
        XCTAssertFalse(app.navigationBars["SOS Light"].waitForExistence(timeout: 2))
        app.typeKey("s", modifierFlags: [.command, .shift])
        XCTAssertTrue(app.navigationBars["SOS Light"].waitForExistence(timeout: 5), "Cmd-Shift-S opens SOS")
        shot("pad-sos")
        app.navigationBars["SOS Light"].buttons["Done"].tap()
        XCTAssertFalse(app.navigationBars["SOS Light"].waitForExistence(timeout: 2))
    }

    func testScreenshots() {
        let screens = [
            ("survive", "guide:first-aid/bleeding.html"),
            ("ask", "ask:How do I stop severe bleeding?"),
            ("library", "book:water"),
            ("map", "map:demo"),
            ("mesh", "mesh:demo"),
        ]
        for (orientation, label) in [(UIDeviceOrientation.landscapeLeft, "landscape"), (.portrait, "portrait")] {
            XCUIDevice.shared.orientation = orientation
            for (name, screen) in screens {
                launch(screen)
                sleep(name == "ask" ? 30 : 10)
                if name == "library" {
                    // Show the article column with results, not just the main page.
                    app.searchFields.firstMatch.tap()
                    app.typeText("water")
                    sleep(4)
                    app.typeKey(XCUIKeyboardKey.escape.rawValue, modifierFlags: [])
                    sleep(1)
                }
                shot("pad-\(name)-\(label)")
            }
        }
    }
}
