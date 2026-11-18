import XCTest
@testable import NomadPocket

@MainActor
final class SdrExtraTests: XCTestCase {
    func testExtraStreamParsesIntoState() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "events-extra", withExtension: "jsonl"))
        var reader = SdrLineReader()
        let events = reader.feed(try Data(contentsOf: url)).compactMap(SdrParser.event)
        XCTAssertEqual(reader.errors, 0)
        let s = SdrState()
        events.forEach { s.apply($0) }
        XCTAssertTrue(s.simulated)
        XCTAssertEqual(Set(s.ships.keys), [366999000, 338087471])
        XCTAssertEqual(s.sondes["R1234567"]?.model, "RS41")
        XCTAssertFalse(s.spots.isEmpty)
        let spot = try XCTUnwrap(s.spots.values.first)
        XCTAssertTrue((-90...90).contains(spot.latitude))
        let last = s.ships.values.map(\.heardAt).max()!
        XCTAssertEqual(s.visibleShips(now: last).count, 2)
        XCTAssertEqual(s.visibleShips(now: last.addingTimeInterval(1000)).count, 0)
        XCTAssertEqual(s.visibleSondes(now: last.addingTimeInterval(1000)).count, 1)
    }

    func testRejectsBadRows() {
        XCTAssertNil(SdrParser.event(["t": "ship", "mmsi": 1, "lat": 95, "lon": 0]))
        XCTAssertNil(SdrParser.event(["t": "spot", "call": "W1AW", "grid": "ZZ99"]))
        XCTAssertNotNil(SdrParser.event(["t": "spot", "call": "W1AW", "grid": "FN31", "snr": -5, "mhz": 14.07, "mode": "ft8"]))
    }
}
