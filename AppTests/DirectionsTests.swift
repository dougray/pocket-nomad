import XCTest
@testable import NomadPocket

final class DirectionsTests: XCTestCase {
    private func ways() throws -> [RoadWay] {
        let url = Bundle(for: Self.self).url(forResource: "roads-fixture", withExtension: "json")!
        return Overpass.parse(try Data(contentsOf: url))
    }
    private func c(_ i: Int, _ j: Int) -> MapShape.Coordinate { .init(latitude: 30.0 + Double(i) * 0.001, longitude: -97.0 + Double(j) * 0.001) }

    func testParse() throws {
        let w = try ways()
        XCTAssertEqual(w.count, 6)
        XCTAssertTrue(w.first { $0.name == "Oak Ave" }!.oneway)
        XCTAssertFalse(w.first { $0.name == "Main St" }!.oneway)
    }

    func testCarRouteUsesStreets() throws {
        let r = try XCTUnwrap(RoadGraph(ways: ways(), mode: .car).route(from: c(0, 0), to: c(2, 2)))
        XCTAssertEqual(r.meters, 2 * 96.4 + 2 * 111.2, accuracy: 8) // two blocks east, two north
        XCTAssertFalse(r.steps.contains { $0.text.contains("Park Trail") })
        XCTAssertEqual(r.steps.last?.text, "Arrive")
    }

    func testFootUsesTrailAndTwoWayOak() throws {
        let g = RoadGraph(ways: try ways(), mode: .foot)
        let r = try XCTUnwrap(g.route(from: c(2, 2), to: c(2, 0)))
        XCTAssertEqual(r.meters, 2 * 96.4, accuracy: 8)
        XCTAssertTrue(r.steps.first!.text.contains("Park Trail"))
    }

    func testOnewayHonouredForCarsOnly() throws {
        let car = RoadGraph(ways: try ways(), mode: .car).route(from: c(1, 2), to: c(1, 0))!
        let foot = RoadGraph(ways: try ways(), mode: .foot).route(from: c(1, 2), to: c(1, 0))!
        XCTAssertGreaterThan(car.meters, foot.meters + 150)
        XCTAssertEqual(foot.meters, 2 * 96.4, accuracy: 8)
    }

    func testTurnsAndFailures() throws {
        let r = try XCTUnwrap(RoadGraph(ways: try ways(), mode: .car).route(from: c(0, 0), to: c(2, 2)))
        XCTAssertTrue(r.steps[1].text.hasPrefix("Turn"), r.steps.map(\.text).joined(separator: " | "))
        XCTAssertNil(RoadGraph(ways: try ways(), mode: .car).route(from: c(0, 0), to: .init(latitude: 40, longitude: -97)))
        XCTAssertNil(RoadGraph(ways: [], mode: .car).route(from: c(0, 0), to: c(1, 1)))
    }

    @MainActor func testHeapAndCells() {
        var h = MinHeap(); [5, 1, 4, 2, 3].forEach { h.push(Double($0), $0) }
        XCTAssertEqual((0..<5).compactMap { _ in h.pop()?.1 }, [1, 2, 3, 4, 5])
        let cells = RoadStore.cells(centre: .init(latitude: 30.27, longitude: -97.74), radiusKm: 10)
        XCTAssertTrue((9...30).contains(cells.count), "\(cells.count)")
    }
}
