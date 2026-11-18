import XCTest
@testable import NomadPocket

@MainActor
final class FavoritesAndGoToTests: XCTestCase {
    typealias C = MapShape.Coordinate

    private func store() -> (FavoritesStore, URL) {
        let url = FileManager.default.temporaryDirectory.appending(path: "fav-\(UUID().uuidString).json")
        return (FavoritesStore(url: url), url)
    }

    func testFavoritesPersistAndQuickSpotsAreUnique() {
        let (s, url) = store()
        defer { try? FileManager.default.removeItem(at: url) }
        s.add(name: "Mom's house", kind: .home, latitude: 39.7, longitude: -105.0)
        s.add(name: "Truck at trailhead", kind: .car, latitude: 39.8, longitude: -105.2)
        let second = s.add(name: "New place", kind: .home, latitude: 40.0, longitude: -105.5)
        XCTAssertEqual(s.favorites.filter { $0.kind == .home }.map(\.id), [second.id], "setting Home again replaces it")
        s.add(name: "Spring", kind: .water, latitude: 39.9, longitude: -105.3)
        s.add(name: "Alpha lake", latitude: 39.95, longitude: -105.4)
        XCTAssertEqual(s.sorted.map(\.name), ["New place", "Truck at trailhead", "Alpha lake", "Spring"], "quick spots first, then by name")
        XCTAssertEqual(s.quickSpots.count, 2)
        let again = FavoritesStore(url: url)
        XCTAssertEqual(again.favorites.count, 4)
        XCTAssertEqual(again.favorites.first { $0.kind == .car }?.name, "Truck at trailhead")
    }

    func testStarringTheSameSpotTwiceTogglesAndSearchFinds() {
        let (s, url) = store()
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertTrue(s.toggle(name: "KF0XYZ-9", latitude: 39.74, longitude: -104.99))
        XCTAssertTrue(s.isFavorite(latitude: 39.74001, longitude: -104.99001), "a few metres away is the same place")
        XCTAssertFalse(s.isFavorite(latitude: 39.75, longitude: -104.99))
        XCTAssertFalse(s.toggle(name: "again", latitude: 39.74, longitude: -104.99), "second tap removes it")
        XCTAssertTrue(s.favorites.isEmpty)
        s.add(name: "Blue Lake", latitude: 1, longitude: 1, note: "good water")
        s.add(name: "Camp Alpha", kind: .camp, latitude: 2, longitude: 2)
        XCTAssertEqual(s.search("lake").map(\.name), ["Blue Lake"])
        XCTAssertEqual(s.search("water").map(\.name), ["Blue Lake"], "notes are searched too")
        XCTAssertEqual(s.search("camp").map(\.name), ["Camp Alpha"])
        XCTAssertEqual(s.search("").count, 2)
        let f = s.favorites[0]
        s.rename(f.id, to: "Renamed"); s.rename(f.id, to: "  ")
        XCTAssertEqual(s.favorites[0].name, "Renamed", "a blank name is ignored")
        XCTAssertTrue(s.exportGeoJSON().contains("\"Point\""))
    }

    func testGoToMathBearingDistanceAndArrow() {
        let here = C(latitude: 39.74, longitude: -104.99)
        let north = C(latitude: 39.75, longitude: -104.99)
        let (d, b) = GoToMath.leg(from: here, to: north)
        XCTAssertEqual(d, 1112, accuracy: 5)
        XCTAssertEqual(b, 0, accuracy: 0.01)
        XCTAssertEqual(GoToMath.arrowAngle(bearing: 90, heading: 0), 90)
        XCTAssertEqual(GoToMath.arrowAngle(bearing: 350, heading: 10), -20)
        XCTAssertEqual(GoToMath.arrowAngle(bearing: 10, heading: 350), 20)
        XCTAssertEqual(GoToMath.arrowAngle(bearing: 180, heading: 0), 180, accuracy: 1e-9)
        XCTAssertEqual(GoToMath.eta(distance: 1340), 1000, accuracy: 1)
        XCTAssertEqual(GoToMath.etaText(30), "under a minute")
        XCTAssertEqual(GoToMath.etaText(600), "10 min")
        XCTAssertEqual(GoToMath.etaText(3900), "1 h 5 min")
    }

    func testRoutesAdvanceWaypointByWaypoint() {
        let a = C(latitude: 39.7400, longitude: -104.99), b = C(latitude: 39.7500, longitude: -104.99), c = C(latitude: 39.7500, longitude: -104.97)
        var t = GoToTarget(name: "Loop", waypoints: [a, b, c])
        XCTAssertTrue(t.isRoute)
        XCTAssertEqual(GoToMath.advanced(t, position: C(latitude: 39.7390, longitude: -104.99)).index, 0, "far from the first waypoint")
        t = GoToMath.advanced(t, position: C(latitude: 39.74005, longitude: -104.99))
        XCTAssertEqual(t.index, 1, "within 25 m of the first waypoint moves on")
        XCTAssertEqual(GoToMath.remaining(t, position: a), 1112 + 1709, accuracy: 20)
        XCTAssertFalse(GoToMath.arrived(t, position: b))
        t = GoToMath.advanced(t, position: C(latitude: 39.75001, longitude: -104.99))
        XCTAssertEqual(t.index, 2)
        XCTAssertTrue(GoToMath.arrived(t, position: C(latitude: 39.75, longitude: -104.9701)))
        // A single place never advances past itself.
        let one = GoToTarget(name: "Camp", waypoints: [a])
        XCTAssertEqual(GoToMath.advanced(one, position: a).index, 0)
        XCTAssertTrue(GoToMath.arrived(one, position: a))
    }

    func testNavigationStateStartsStepsAndStops() {
        let nav = NavigationState()
        XCTAssertNil(nav.target)
        nav.start(name: "Camp", latitude: 39.75, longitude: -104.99)
        XCTAssertEqual(nav.target?.name, "Camp"); XCTAssertEqual(nav.activations, 1)
        nav.update(position: C(latitude: 39.75, longitude: -104.99))
        XCTAssertTrue(nav.arrived)
        let route = MapShape(name: "Route", points: [C(latitude: 39.7, longitude: -105), C(latitude: 39.8, longitude: -105), C(latitude: 39.9, longitude: -105)], closed: false)
        nav.start(route: route)
        XCTAssertEqual(nav.target?.waypoints.count, 3); XCTAssertFalse(nav.arrived); XCTAssertEqual(nav.activations, 2)
        nav.skip(); nav.skip(); nav.skip()
        XCTAssertEqual(nav.target?.index, 2, "skip stops at the last waypoint")
        nav.stop()
        XCTAssertNil(nav.target)
    }
}
