import XCTest
import SwiftUI
@testable import NomadPocket

@MainActor
final class PadNavigatorTests: XCTestCase {
    private func navigator(_ section: AppTab = .survive, launch: (String, String)? = nil) -> PadNavigator {
        PadNavigator(section: section, launch: launch)
    }

    func testShortcutKeysFollowSidebarOrder() {
        XCTAssertEqual(AppTab.allCases.map(\.shortcutKey), ["1", "2", "3", "4", "5"])
        XCTAssertEqual(AppTab.allCases.map(\.title), ["Survive", "Ask", "Library", "Map", "Mesh"])
    }

    func testSearchGoesToASectionWithASearchField() {
        XCTAssertEqual(AppTab.survive.searchSection, .survive)
        XCTAssertEqual(AppTab.ask.searchSection, .ask)
        XCTAssertEqual(AppTab.library.searchSection, .library)
        XCTAssertEqual(AppTab.map.searchSection, .survive)
        XCTAssertEqual(AppTab.mesh.searchSection, .survive)
    }

    func testRequestSearchSwitchesAndBumps() {
        let nav = navigator(.map)
        nav.requestSearch()
        XCTAssertEqual(nav.section, .survive)
        XCTAssertEqual(nav.searchRequest, 1)
        XCTAssertTrue(nav.takeSearchRequest())
        XCTAssertFalse(nav.takeSearchRequest(), "only one field takes each request")
        nav.select(.library)
        nav.requestSearch()
        XCTAssertEqual(nav.section, .library)
        XCTAssertEqual(nav.searchRequest, 2)
    }

    func testShowReplacesTheDetailColumn() {
        let nav = navigator()
        let a = SourceRef.guide(path: "first-aid/cpr.html")
        let b = SourceRef.guide(path: "first-aid/bleeding.html")
        nav.show(a)
        nav.push(ToolKind.compass)
        XCTAssertEqual(nav.depth(in: .survive), 2)
        nav.show(b)
        XCTAssertEqual(nav.depth(in: .survive), 1)
        XCTAssertEqual(nav.selected(in: .survive), AnyHashable(b))
    }

    func testDetailColumnsAreKeptPerSection() {
        let nav = navigator()
        nav.show(SourceRef.guide(path: "x.html"))
        nav.select(.ask)
        XCTAssertEqual(nav.depth(in: .ask), 0)
        nav.show(SourceRef.book(id: "b", path: "p"))
        nav.select(.survive)
        XCTAssertEqual(nav.selected(in: .survive), AnyHashable(SourceRef.guide(path: "x.html")))
        XCTAssertEqual(nav.selected(in: .ask), AnyHashable(SourceRef.book(id: "b", path: "p")))
    }

    func testPoppingToRootClearsTheSelection() {
        let nav = navigator()
        nav.show(SourceRef.guide(path: "x.html"))
        nav.path(for: .survive).wrappedValue = NavigationPath()
        XCTAssertNil(nav.selected(in: .survive))
    }

    func testMeshRootChatIsNotPushedTwice() {
        let nav = navigator(.mesh)
        nav.show(MeshRoute.chat(.channel(0)))
        XCTAssertEqual(nav.depth(in: .mesh), 0, "the channel chat is already the root")
        nav.show(MeshRoute.nodes)
        XCTAssertEqual(nav.depth(in: .mesh), 1)
    }

    func testBookLinksOpenTheBookInLibrary() {
        let nav = navigator(.library)
        nav.show("book-uuid")
        XCTAssertEqual(nav.book, "book-uuid")
        XCTAssertEqual(nav.depth(in: .library), 0)
        XCTAssertEqual(nav.sidebarSelection, .book("book-uuid"))
        nav.show(SourceRef.book(id: "book-uuid", path: "A/Water"))
        XCTAssertEqual(nav.depth(in: .library), 1)
        // Another book starts with an empty reader stack.
        nav.sidebarSelection = .book("other")
        XCTAssertEqual(nav.book, "other")
        XCTAssertEqual(nav.depth(in: .library), 0)
        // The Library row shows every book again.
        nav.sidebarSelection = .section(.library)
        XCTAssertNil(nav.book)
        XCTAssertEqual(nav.sidebarSelection, .section(.library))
    }

    func testSidebarBookSelectionComesFromAnySection() {
        let nav = navigator(.map)
        nav.sidebarSelection = .book("b")
        XCTAssertEqual(nav.section, .library)
        XCTAssertEqual(nav.book, "b")
        nav.select(.mesh)
        XCTAssertEqual(nav.sidebarSelection, .section(.mesh), "the open book only shows as selected in Library")
    }

    func testLaunchScreenOpensDetail() {
        let guide = navigator(launch: ("guide", "first-aid/bleeding.html"))
        XCTAssertEqual(guide.selected(in: .survive), AnyHashable(SourceRef.guide(path: "first-aid/bleeding.html")))
        let tool = navigator(launch: ("tool", "compass"))
        XCTAssertEqual(tool.selected(in: .survive), AnyHashable(ToolKind.compass))
        XCTAssertTrue(navigator(launch: ("settings", "")).showSettings)
    }
}

final class PadArticleListTests: XCTestCase {
    func testMergeKeepsOrderAndDropsRepeats() {
        typealias Hit = BookHomeView.ZIMEntryHit
        let titles = [Hit(id: "A/Water", title: "Water"), Hit(id: "A/Wells", title: "Wells")]
        let text = [Hit(id: "files/boil.pdf", title: "Boil"), Hit(id: "A/Water", title: "Water")]
        XCTAssertEqual(PadArticleList.merge(titles, text).map(\.id), ["A/Water", "A/Wells", "files/boil.pdf"])
    }
}
