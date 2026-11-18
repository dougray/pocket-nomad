import XCTest
@testable import NomadPocket

final class LineOfSightTests: XCTestCase {
    // Two points about 10 km apart on one parallel (0.1038 degrees of longitude at 30 N).
    private let a = MapShape.Coordinate(latitude: 30, longitude: -97.0)
    private func east(_ km: Double) -> MapShape.Coordinate { .init(latitude: 30, longitude: -97.0 + km / (111.32 * cos(30 * .pi / 180))) }

    func testWavelengthAndFresnelWorkedExample() {
        XCTAssertEqual(LineOfSight.wavelength(mhz: 915), 0.3277, accuracy: 0.0005)
        // 10 km path, midpoint, 915 MHz: sqrt(0.3277 * 5000 * 5000 / 10000) = 28.6 m
        XCTAssertEqual(LineOfSight.fresnelRadius(d1: 5000, d2: 5000, mhz: 915), 28.62, accuracy: 0.05)
    }

    func testBulgeWorkedExample() {
        XCTAssertEqual(LineOfSight.bulge(d1: 5000, d2: 5000), 1.472, accuracy: 0.005)
        XCTAssertEqual(LineOfSight.horizon(height: 2) * 2 / 1000, 4.12 * 2 * 2.0.squareRoot(), accuracy: 0.1)
    }

    func testFlatGroundHorizon() {
        let flat: (Double, Double) -> Double? = { _, _ in 0 }
        // Two 2 m antennas see each other out to about 11.6 km over flat ground, not 13.
        XCTAssertNotEqual(LineOfSight.analyze(from: a, to: east(11), heightA: 2, heightB: 2, mhz: 915, elevation: flat).verdict, .blocked)
        XCTAssertEqual(LineOfSight.analyze(from: a, to: east(13), heightA: 2, heightB: 2, mhz: 915, elevation: flat).verdict, .blocked)
    }

    func testRidgeBlocksAndHighAntennasClear() {
        let mid = east(5).longitude
        let ridge: (Double, Double) -> Double? = { _, lon in abs(lon - mid) < 0.004 ? 100 : 0 }
        let r = LineOfSight.analyze(from: a, to: east(10), heightA: 10, heightB: 10, mhz: 915, elevation: ridge)
        XCTAssertEqual(r.verdict, .blocked)
        XCTAssertEqual(r.worstAt, 5000, accuracy: 400)
        XCTAssertEqual(LineOfSight.analyze(from: a, to: east(10), heightA: 150, heightB: 150, mhz: 915, elevation: ridge).verdict, .clear)
    }

    func testTightPathAndNoData() {
        // High enough to clear the ground but inside the Fresnel zone.
        let flat: (Double, Double) -> Double? = { _, _ in 0 }
        XCTAssertEqual(LineOfSight.analyze(from: a, to: east(10), heightA: 10, heightB: 10, mhz: 915, elevation: flat).verdict, .marginal)
        XCTAssertEqual(LineOfSight.analyze(from: a, to: east(10), heightA: 10, heightB: 10, mhz: 915, elevation: { _, _ in nil }).verdict, .unknown)
    }
}
