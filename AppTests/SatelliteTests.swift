import CoreLocation
import XCTest
@testable import NomadPocket

/// SGP4 and pass prediction, checked against the Python `sgp4` and Skyfield libraries.
final class SatelliteTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: SatelliteTests.self).url(forResource: name, withExtension: nil), name)
        return try Data(contentsOf: url)
    }
    private func reference() throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("sat-reference.json")) as? [String: Any])
    }
    private func vec(_ any: Any?) -> [Double] { (any as? [NSNumber])?.map(\.doubleValue) ?? [] }

    func testTLEParsingChecksAndEpoch() throws {
        let text = String(decoding: try fixture("stations.tle"), as: UTF8.self)
        let all = TLE.parse(text)
        XCTAssertGreaterThanOrEqual(all.count, 2)
        let iss = try XCTUnwrap(all.first { $0.name.hasPrefix("ISS") })
        XCTAssertEqual(iss.noradID, 25544)
        XCTAssertTrue(iss.isValid)
        XCTAssertEqual(iss.epochYear, 2026)
        XCTAssertEqual(iss.epoch.timeIntervalSince1970, 1_794_902_505.8, accuracy: 1, "day 321.33455779 of 2026")
        var bad = iss
        bad.line1 = String(bad.line1.dropLast()) + "0"
        XCTAssertFalse(bad.isValid || bad.line1 == iss.line1, "a wrong checksum is rejected")
        XCTAssertEqual(TLE.parse("junk\nmore junk\n").count, 0)
    }

    func testValladoVerificationSatellite() throws {
        let v = try XCTUnwrap(try reference()["vallado"] as? [String: Any])
        let tle = TLE(name: "00005", line1: v["l1"] as! String, line2: v["l2"] as! String)
        let sgp4 = try XCTUnwrap(SGP4(tle))
        for item in try XCTUnwrap(v["vectors"] as? [[String: Any]]) {
            let t = (item["tsince"] as! NSNumber).doubleValue
            let (r, vel) = try XCTUnwrap(sgp4.propagate(minutes: t))
            let er = vec(item["r"]), ev = vec(item["v"])
            for i in 0..<3 {
                XCTAssertEqual(r[i], er[i], accuracy: 1e-5, "position \(i) at \(t) min")
                XCTAssertEqual(vel[i], ev[i], accuracy: 1e-8, "velocity \(i) at \(t) min")
            }
        }
        XCTAssertEqual(try XCTUnwrap(sgp4.propagate(minutes: 0)).position.x, 7022.46529266, accuracy: 1e-6, "the published value")
    }

    func testISSMatchesTheReferenceImplementation() throws {
        let iss = try XCTUnwrap(try reference()["iss"] as? [String: Any])
        let tle = TLE(name: "ISS", line1: iss["l1"] as! String, line2: iss["l2"] as! String)
        let sgp4 = try XCTUnwrap(SGP4(tle))
        XCTAssertEqual(sgp4.epochJulianDate, (iss["epochJd"] as! NSNumber).doubleValue, accuracy: 1e-6)
        for item in try XCTUnwrap(iss["vectors"] as? [[String: Any]]) {
            let m = (item["minutesAfterEpoch"] as! NSNumber).doubleValue
            let (r, vel) = try XCTUnwrap(sgp4.propagate(minutes: m))
            let er = vec(item["r"]), ev = vec(item["v"])
            for i in 0..<3 {
                XCTAssertEqual(r[i], er[i], accuracy: 2e-3, "ISS position \(i) at +\(m) min (2 m)")
                XCTAssertEqual(vel[i], ev[i], accuracy: 2e-6, "ISS velocity \(i) at +\(m) min (2 mm/s)")
            }
        }
    }

    func testDeepSpaceIsNotSupported() {
        // A geostationary-style set: about one revolution a day.
        let t = TLE(name: "GEO", line1: "1 41589U 16045A   26321.50000000 -.00000100  00000+0  00000+0 0  9990",
                    line2: "2 41589   0.0300  80.0000 0001000 100.0000 260.0000  1.00270000 12340")
        XCTAssertNil(SGP4(t))
    }

    func testLookAnglesMatchSkyfield() throws {
        let ref = try reference()
        let iss = try XCTUnwrap(ref["iss"] as? [String: Any])
        let sgp4 = try XCTUnwrap(SGP4(TLE(name: "ISS", line1: iss["l1"] as! String, line2: iss["l2"] as! String)))
        let o = Observer(latitude: 39.74, longitude: -104.99, altitudeMetres: 1600)
        for s in try XCTUnwrap(ref["samples"] as? [[String: Any]]) {
            let when = Date(timeIntervalSince1970: (s["unix"] as! NSNumber).doubleValue)
            let sky = try XCTUnwrap(SatelliteMath.look(sgp4, at: when, observer: o))
            XCTAssertEqual(sky.elevation, (s["el"] as! NSNumber).doubleValue, accuracy: 0.1, "elevation at \(when)")
            XCTAssertEqual(sky.rangeKm, (s["rangeKm"] as! NSNumber).doubleValue, accuracy: 3, "range at \(when)")
            if sky.elevation > 0 {
                var d = abs(sky.azimuth - (s["az"] as! NSNumber).doubleValue)
                if d > 180 { d = 360 - d }
                XCTAssertLessThan(d, 0.2, "azimuth at \(when)")
            }
        }
    }

    func testPassesMatchSkyfield() throws {
        let ref = try reference()
        let iss = try XCTUnwrap(ref["iss"] as? [String: Any])
        let tle = TLE(name: "ISS", line1: iss["l1"] as! String, line2: iss["l2"] as! String)
        let block = try XCTUnwrap(ref["passes"] as? [String: Any])
        let o = block["observer"] as! [String: Any]
        let observer = Observer(latitude: (o["lat"] as! NSNumber).doubleValue, longitude: (o["lon"] as! NSNumber).doubleValue,
                                altitudeMetres: (o["altM"] as! NSNumber).doubleValue)
        let start = Date(timeIntervalSince1970: (block["start"] as! NSNumber).doubleValue)
        let mine = SatelliteMath.passes(for: tle, observer: observer, from: start, hours: 48, minElevation: 10)
        let theirs = (try XCTUnwrap(block["events"] as? [[String: Any]])).filter { ($0["maxEl"] as! NSNumber).doubleValue >= 10 }
        XCTAssertEqual(mine.count, theirs.count, "same passes above 10 degrees")
        for (m, t) in zip(mine, theirs) {
            XCTAssertEqual(m.start.timeIntervalSince1970, (t["rise"] as! NSNumber).doubleValue, accuracy: 15, "rise")
            XCTAssertEqual(m.peak.timeIntervalSince1970, (t["culm"] as! NSNumber).doubleValue, accuracy: 15, "peak")
            XCTAssertEqual(m.end.timeIntervalSince1970, (t["set"] as! NSNumber).doubleValue, accuracy: 15, "set")
            XCTAssertEqual(m.maxElevation, (t["maxEl"] as! NSNumber).doubleValue, accuracy: 0.3, "peak elevation")
            XCTAssertEqual(m.peakAzimuth, (t["culmAz"] as! NSNumber).doubleValue, accuracy: 2, "peak azimuth")
            XCTAssertEqual(m.startAzimuth, (t["riseAz"] as! NSNumber).doubleValue, accuracy: 2, "rise azimuth")
            XCTAssertTrue(m.duration > 120 && m.duration < 900)
        }
        XCTAssertEqual(mine, mine.sorted { $0.start < $1.start })
    }

    func testPassInProgressAndWords() throws {
        let ref = try reference()
        let iss = try XCTUnwrap(ref["iss"] as? [String: Any])
        let tle = TLE(name: "ISS", line1: iss["l1"] as! String, line2: iss["l2"] as! String)
        let block = try XCTUnwrap(ref["passes"] as? [String: Any])
        let first = try XCTUnwrap((block["events"] as? [[String: Any]])?.first { ($0["maxEl"] as! NSNumber).doubleValue >= 10 })
        let mid = Date(timeIntervalSince1970: (first["culm"] as! NSNumber).doubleValue)
        let observer = Observer(latitude: 39.74, longitude: -104.99, altitudeMetres: 1600)
        let p = try XCTUnwrap(SatelliteMath.passes(for: tle, observer: observer, from: mid, hours: 3).first)
        XCTAssertTrue(p.inProgress)
        XCTAssertEqual(p.start, mid)
        XCTAssertTrue(SatelliteMath.describe(p).hasPrefix("Rises"))
        XCTAssertEqual(SatelliteMath.compass(0), "N"); XCTAssertEqual(SatelliteMath.compass(359), "N")
        XCTAssertEqual(SatelliteMath.compass(135), "SE"); XCTAssertEqual(SatelliteMath.compass(202), "SSW")
    }
}

@MainActor
final class SatelliteStoreTests: XCTestCase {
    func testFavouritesDefaultToTheStationAndPassesComeFromSavedOrbits() async throws {
        let suite = "sat-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let dir = FileManager.default.temporaryDirectory.appending(path: suite)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = try XCTUnwrap(Bundle(for: SatelliteTests.self).url(forResource: "stations.tle", withExtension: nil))
        let tles = TLE.parse(String(decoding: try Data(contentsOf: url), as: UTF8.self))
        let start = Date(timeIntervalSince1970: 1_794_953_000) // just before a Denver pass in the reference data
        struct Saved: Codable { var fetchedAt: Date; var satellites: [TLE] }
        try JSONEncoder().encode(Saved(fetchedAt: start.addingTimeInterval(-3600), satellites: tles)).write(to: dir.appending(path: "elements.json"))

        let store = SatelliteStore(directory: dir, defaults: defaults, locate: { CLLocation(latitude: 39.74, longitude: -104.99) })
        store.now = { start }
        XCTAssertEqual(store.favorites, [SatelliteStore.issID])
        XCTAssertEqual(store.satellites.count, tles.count)
        XCTAssertEqual(store.orbitAgeDays ?? 0, 1.0 / 24, accuracy: 1e-6)
        await store.updateObserver()
        XCTAssertEqual(store.observer?.latitude ?? 0, 39.74, accuracy: 1e-9)
        await store.computePasses(hours: 6)
        XCTAssertFalse(store.passes.isEmpty)
        XCTAssertTrue(store.passes.allSatisfy { $0.noradID == SatelliteStore.issID })
        XCTAssertEqual(store.passes[0].start.timeIntervalSince1970, 1_794_953_065.7, accuracy: 20)
        // Following another satellite adds its passes; unfollowing removes them.
        let other = try XCTUnwrap(tles.first { $0.noradID != SatelliteStore.issID && SGP4($0) != nil })
        store.toggleFavorite(other.noradID)
        XCTAssertEqual(SatelliteStore(directory: dir, defaults: defaults).favorites, [SatelliteStore.issID, other.noradID])
        store.toggleFavorite(other.noradID)
        XCTAssertEqual(store.favorites, [SatelliteStore.issID])
        store.deleteAll()
        XCTAssertTrue(store.satellites.isEmpty)
        XCTAssertNil(store.observer)
    }

    func testCountdownWords() {
        XCTAssertEqual(SatellitesView.countdown(300), "5 min")
        XCTAssertEqual(SatellitesView.countdown(3 * 3600 + 120), "3 h 2 min")
        XCTAssertEqual(SatellitesView.countdown(2 * 86_400 + 5 * 3600), "2 d 5 h")
        XCTAssertEqual(SatellitesView.countdown(-5), "0 min")
    }
}
