import XCTest
@testable import NomadPocket

final class NvisTests: XCTestCase {
    private func date(_ s: String) -> Date {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.date(from: s)!
    }

    func testSunElevationAgainstKnownCases() {
        // Equator at the March equinox, solar noon on the prime meridian: the sun is nearly overhead.
        XCTAssertEqual(Nvis.sunElevation(latitude: 0, longitude: 0, at: date("2026-03-20 12:05")), 90, accuracy: 4)
        // Austin around local midnight in June: well below the horizon. Local noon: high.
        XCTAssertLessThan(Nvis.sunElevation(latitude: 30.27, longitude: -97.74, at: date("2026-06-21 06:00")), -20)
        XCTAssertEqual(Nvis.sunElevation(latitude: 30.27, longitude: -97.74, at: date("2026-06-21 18:00")), 83, accuracy: 4)   // 18:00 UTC is local solar noon, sun at 90 - 30.27 + 23.44
        // Midwinter noon at 60 N: very low.
        XCTAssertEqual(Nvis.sunElevation(latitude: 60, longitude: 0, at: date("2026-12-21 12:03")), 6.5, accuracy: 2)
    }

    func testAdviceFollowsDayNightAndStorms() {
        let noon = Nvis.advise(sfi: 150, kp: 1, sunElevation: 60)
        XCTAssertEqual(noon.foF2, 7.3, accuracy: 0.05)
        XCTAssertEqual(noon.best?.name, "60 m")              // FOT 6.2 MHz: 60 m fits, 40 m (7.1) does not
        XCTAssertEqual(noon.alternate?.name, "80 m")
        let night = Nvis.advise(sfi: 120, kp: 1, sunElevation: -30)
        XCTAssertEqual(night.foF2, 3.0, accuracy: 0.05)
        XCTAssertEqual(night.best?.name, "160 m")
        let storm = Nvis.advise(sfi: 150, kp: 6, sunElevation: 60)
        XCTAssertTrue(storm.stormed)
        XCTAssertLessThan(storm.foF2, noon.foF2)
        XCTAssertEqual(storm.best?.name, "80 m")
        let low = Nvis.advise(sfi: 70, kp: 1, sunElevation: 60)   // solar minimum: the daytime window is narrow
        XCTAssertLessThan(low.fot, noon.fot)
        XCTAssertNotNil(low.best)
        XCTAssertTrue(Nvis.advise(sfi: 65, kp: 8, sunElevation: 60).summary.contains("poor"))
    }

    func testAntennaWorkedExamples() {
        let b40 = Nvis.bands.first { $0.name == "40 m" }!
        let a = Nvis.antenna(for: b40)
        XCTAssertEqual(a.dipoleMetres, 142.65 / 7.1, accuracy: 0.001)
        XCTAssertEqual(a.dipoleFeet, 66.0, accuracy: 0.5)
        XCTAssertEqual(a.heightMinMetres, 4.2, accuracy: 0.1)    // a tenth of 42.2 m
        XCTAssertEqual(a.heightMaxMetres, 10.6, accuracy: 0.1)   // a quarter
        let a80 = Nvis.antenna(for: Nvis.bands.first { $0.name == "80 m" }!)
        XCTAssertEqual(a80.dipoleFeet, 130.0, accuracy: 1)
    }

    func testCircleIsTheRightSize() {
        let ring = Nvis.circle(latitude: 30.27, longitude: -97.74, radiusKm: Nvis.rangeKm)
        XCTAssertEqual(ring.count, 73)
        for p in ring where true {
            XCTAssertEqual(CoverageShare.distance(30.27, -97.74, p.latitude, p.longitude), Nvis.rangeKm * 1000, accuracy: 500)
        }
        XCTAssertEqual(Nvis.rangeKm / 1.609344, 300, accuracy: 0.01)
    }
}

@MainActor
final class SolarFluxTests: XCTestCase {
    func testParsesNoaaFlux() {
        XCTAssertEqual(SolarFluxStore.parse(Data(#"[{"flux":121,"time_tag":"2026-11-17T20:00:00"}]"#.utf8)), 121)
        XCTAssertNil(SolarFluxStore.parse(Data("[]".utf8)))
        XCTAssertNil(SolarFluxStore.parse(Data(#"[{"flux":9999}]"#.utf8)))
        XCTAssertNil(SolarFluxStore.parse(Data("junk".utf8)))
    }
}
