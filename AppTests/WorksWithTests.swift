import XCTest
@testable import NomadPocket

final class WorksWithTests: XCTestCase {
    func testShownOnlyToRelatedUsersAndHidesWhenDismissed() {
        XCTAssertFalse(WorksWith.showWarWalker(usesMap: false, usesRadio: false, dismissed: false))
        XCTAssertTrue(WorksWith.showWarWalker(usesMap: true, usesRadio: false, dismissed: false))
        XCTAssertTrue(WorksWith.showWarWalker(usesMap: false, usesRadio: true, dismissed: false))
        XCTAssertFalse(WorksWith.showWarWalker(usesMap: true, usesRadio: true, dismissed: true))
    }
}
