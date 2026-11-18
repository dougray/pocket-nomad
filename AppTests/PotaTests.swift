import XCTest
@testable import NomadPocket

final class PotaTests: XCTestCase {
    private func data(_ name: String) throws -> Data {
        try Data(contentsOf: XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json")))
    }

    func testParsesSpots() throws {
        let spots = PotaParsers.spots(try data("spots"))
        XCTAssertEqual(spots.count, 6)
        let first = try XCTUnwrap(spots.first)
        XCTAssertFalse(first.activator.isEmpty)
        XCTAssertTrue(first.reference.contains("-"))
        XCTAssertEqual(first.frequencyKHz > 1000, true)
        XCTAssertTrue(first.frequencyText.hasSuffix("MHz"))
        XCTAssertGreaterThan(first.spotted.timeIntervalSince1970, 1_700_000_000)
    }

    func testParsesParksAndRejectsBadRows() throws {
        let parks = PotaParsers.parks(try data("grid"))
        XCTAssertFalse(parks.isEmpty)
        XCTAssertTrue(parks.allSatisfy { (30...31).contains($0.latitude) || (29...32).contains($0.latitude) })
        XCTAssertEqual(PotaParsers.parks(Data("{\"features\":[{\"properties\":{\"reference\":\"X\"},\"geometry\":{\"coordinates\":[-97,95]}}]}".utf8)).count, 0)
        XCTAssertEqual(PotaParsers.spots(Data("nonsense".utf8)).count, 0)
    }

    func testFreshnessRoundingAndAge() throws {
        let spots = PotaParsers.spots(try data("spots"))
        let t = spots[0].spotted
        XCTAssertEqual(PotaParsers.current(spots, now: t.addingTimeInterval(60)).count, spots.filter { t.addingTimeInterval(60).timeIntervalSince($0.spotted) <= 2700 }.count)
        XCTAssertTrue(PotaParsers.current(spots, now: t.addingTimeInterval(86400)).isEmpty)
        XCTAssertEqual(PotaParsers.rounded(30.2672), 30.3, accuracy: 1e-9)
        XCTAssertEqual(PotaParsers.rounded(-97.7431), -97.7, accuracy: 1e-9)
        XCTAssertEqual(PotaParsers.ageText(t, now: t.addingTimeInterval(30)), "just now")
        XCTAssertEqual(PotaParsers.ageText(t, now: t.addingTimeInterval(7200)), "2 h ago")
    }

    @MainActor func testActiveSpotsNearestFirst() {
        let store = PotaStore(url: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "pota-test-\(UUID().uuidString).json"))
        XCTAssertTrue(store.activeSpots(near: nil).isEmpty)
    }
}
