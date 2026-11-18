import XCTest
@testable import NomadPocket

/// Coordinate parsing and conversion, checked against the Python utm and mgrs libraries.
final class CoordinatesTests: XCTestCase {
    private func reference() throws -> [[String: Any]] {
        let url = try XCTUnwrap(Bundle(for: CoordinatesTests.self).url(forResource: "coord-reference.json", withExtension: nil))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
    }
    private func num(_ d: [String: Any], _ k: String) -> Double { (d[k] as! NSNumber).doubleValue }

    func testUTMAndMGRSMatchTheReferenceLibraries() throws {
        for p in try reference() {
            let lat = num(p, "lat"), lon = num(p, "lon")
            let u = try XCTUnwrap(Coordinates.utm(latitude: lat, longitude: lon), "\(lat),\(lon)")
            XCTAssertEqual(u.zone, (p["zone"] as! NSNumber).intValue, "zone at \(lat),\(lon)")
            XCTAssertEqual(String(u.band), p["letter"] as? String, "band at \(lat),\(lon)")
            XCTAssertEqual(u.easting, num(p, "easting"), accuracy: 0.05, "easting at \(lat),\(lon)")
            XCTAssertEqual(u.northing, num(p, "northing"), accuracy: 0.05, "northing at \(lat),\(lon)")
            XCTAssertEqual(Coordinates.mgrs(latitude: lat, longitude: lon), p["mgrs"] as? String, "MGRS at \(lat),\(lon)")
        }
    }

    func testRoundTripsBackToLatLon() throws {
        for p in try reference() {
            let lat = num(p, "lat"), lon = num(p, "lon")
            let back = try XCTUnwrap(Coordinates.fromMGRS(p["mgrs"] as! String), "\(p["mgrs"]!)")
            XCTAssertEqual(back.latitude, lat, accuracy: 2e-5, "MGRS back to latitude \(p["mgrs"]!)")
            XCTAssertEqual(back.longitude, lon, accuracy: 3e-5, "MGRS back to longitude \(p["mgrs"]!)")
            let u = Coordinates.UTM(zone: (p["zone"] as! NSNumber).intValue, band: Character(p["letter"] as! String), easting: num(p, "easting"), northing: num(p, "northing"))
            let r = Coordinates.latLon(from: u)
            XCTAssertEqual(r.latitude, lat, accuracy: 5e-6, "about half a metre")
            XCTAssertEqual(r.longitude, lon, accuracy: 5e-6, "about half a metre")
        }
    }

    func testLowerPrecisionMGRSNamesASquare() throws {
        let sq = try XCTUnwrap(Coordinates.fromMGRS("13SED"))
        XCTAssertEqual(sq.latitude, 39.7, accuracy: 0.5)
        XCTAssertEqual(sq.longitude, -105.0, accuracy: 0.6)
        let km = try XCTUnwrap(Coordinates.fromMGRS("13S ED 00 98"))
        XCTAssertEqual(km.latitude, 39.739, accuracy: 0.01)
        XCTAssertNil(Coordinates.fromMGRS("13SED123"), "odd digit counts aren't valid")
        XCTAssertNil(Coordinates.fromMGRS("99SED0000"))
        XCTAssertNil(Coordinates.mgrs(latitude: 85, longitude: 0), "no MGRS at the poles")
    }

    func testParsesCommonWaysOfWritingAPlace() {
        func p(_ s: String) -> ParsedCoordinate? { Coordinates.parse(s) }
        let cases: [(String, Double, Double, PositionFormat)] = [
            ("39.7392, -104.9903", 39.7392, -104.9903, .decimal),
            ("39.7392 -104.9903", 39.7392, -104.9903, .decimal),
            ("39.7392N 104.9903W", 39.7392, -104.9903, .decimal),
            ("104.9903W 39.7392N", 39.7392, -104.9903, .decimal),
            ("N 39.7392 W 104.9903", 39.7392, -104.9903, .decimal),
            ("lat 39.7392 lon -104.9903", 39.7392, -104.9903, .decimal),
            ("-33.8688, 151.2093", -33.8688, 151.2093, .decimal),
            ("39°44'21.1\"N 104°59'25.1\"W", 39 + 44.0 / 60 + 21.1 / 3600, -(104 + 59.0 / 60 + 25.1 / 3600), .dms),
            ("39 44 21.1 N 104 59 25.1 W", 39 + 44.0 / 60 + 21.1 / 3600, -(104 + 59.0 / 60 + 25.1 / 3600), .dms),
            ("39°44.352'N 104°59.418'W", 39 + 44.352 / 60, -(104 + 59.418 / 60), .dmm),
            ("13S 500831 4398811", 39.7392, -104.9903, .utm),
            ("13S 500831E 4398811N", 39.7392, -104.9903, .utm),
            ("13SED0083198811", 39.7392, -104.9903, .mgrs),
            ("13S ED 00831 98811", 39.7392, -104.9903, .mgrs),
            ("https://maps.example.com/?q=39.7392,-104.9903&z=15", 39.7392, -104.9903, .decimal),
            ("\u{2212}33.8688, 151.2093", -33.8688, 151.2093, .decimal),
        ]
        for (text, lat, lon, fmt) in cases {
            guard let r = p(text) else { XCTFail("didn't parse \(text)"); continue }
            XCTAssertEqual(r.latitude, lat, accuracy: 1e-4, text)
            XCTAssertEqual(r.longitude, lon, accuracy: 1e-4, text)
            XCTAssertEqual(r.format, fmt, text)
        }
        let grid = p("DM79mr")
        XCTAssertEqual(grid?.format, .maidenhead)
        XCTAssertEqual(grid?.latitude ?? 0, 39.73, accuracy: 0.03)
        XCTAssertEqual(grid?.longitude ?? 0, -104.96, accuracy: 0.06)
    }

    func testRejectsThingsThatAreNotPositions() {
        for bad in ["", "   ", "hello", "91, 0", "0, 181", "39.7392", "39 61 0 N 10 0 0 E", "12 34 56", "N 39.7 N 104.9", "E 39.7, N 104.9"] {
            XCTAssertNil(Coordinates.parse(bad), "should not parse \(bad)")
        }
    }

    func testFormatsEveryWay() {
        XCTAssertEqual(Coordinates.format(latitude: 39.7392, longitude: -104.9903, as: .decimal), "39.73920, -104.99030")
        XCTAssertEqual(Coordinates.format(latitude: 39.7392, longitude: -104.9903, as: .dms), "39°44'21.1\"N 104°59'25.1\"W")
        XCTAssertEqual(Coordinates.format(latitude: 39.7392, longitude: -104.9903, as: .dmm), "39°44.352'N 104°59.418'W")
        XCTAssertEqual(Coordinates.format(latitude: 39.7392, longitude: -104.9903, as: .utm), "13S 500831E 4398811N")
        XCTAssertEqual(Coordinates.format(latitude: 39.7392, longitude: -104.9903, as: .mgrs), "13S ED 00831 98811")
        XCTAssertEqual(Coordinates.format(latitude: 39.7392, longitude: -104.9903, as: .maidenhead), "DM79mr")
        XCTAssertEqual(Coordinates.format(latitude: -33.8688, longitude: 151.2093, as: .dmm), "33°52.128'S 151°12.558'E")
        for fmt in [PositionFormat.decimal, .dms, .dmm, .utm, .mgrs] {
            let text = Coordinates.format(latitude: 39.7392, longitude: -104.9903, as: fmt)
            let r = Coordinates.parse(text)
            XCTAssertEqual(r?.latitude ?? 0, 39.7392, accuracy: 1e-3, "\(fmt) \(text)")
            XCTAssertEqual(r?.longitude ?? 0, -104.9903, accuracy: 1e-3, "\(fmt) \(text)")
        }
    }

    func testMaidenheadRoundTrip() throws {
        for sq in ["DM79", "DM79mr", "JO22", "QF56od", "IO91wm", "FN31pr12"] {
            let c = try XCTUnwrap(Coordinates.fromMaidenhead(sq), sq)
            let again = HamGeo.maidenhead(lat: c.latitude, lon: c.longitude)
            let n = sq.count == 4 ? 4 : 6
            XCTAssertEqual(String(again.prefix(n)).lowercased(), String(sq.prefix(n)).lowercased(), sq)
        }
        XCTAssertNil(Coordinates.fromMaidenhead("ZZ99"))
        XCTAssertNil(Coordinates.fromMaidenhead("DM7"))
    }
}
