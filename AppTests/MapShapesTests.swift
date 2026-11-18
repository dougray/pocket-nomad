import XCTest
@testable import NomadPocket

/// Measuring, areas, export and the mesh summary for hand-drawn map shapes.
@MainActor
final class MapShapesTests: XCTestCase {
    typealias C = MapShape.Coordinate

    /// A square `km` kilometres on a side with its south-west corner at (lat, lon).
    private func square(_ km: Double, lat: Double = 39.74, lon: Double = -104.99) -> [C] {
        let dLat = km / 111.19493
        let dLon = km / (111.19493 * cos(lat * .pi / 180))
        return [C(latitude: lat, longitude: lon), C(latitude: lat, longitude: lon + dLon),
                C(latitude: lat + dLat, longitude: lon + dLon), C(latitude: lat + dLat, longitude: lon)]
    }

    func testLengthOfALineAndAClosedShape() {
        let line = [C(latitude: 39.74, longitude: -104.99), C(latitude: 39.74, longitude: -104.97)]
        XCTAssertEqual(ShapeMath.length(line, closed: false), 1710, accuracy: 15, "0.02 degrees of longitude at 39.74 N is about 1.71 km")
        let sq = square(1)
        XCTAssertEqual(ShapeMath.length(sq, closed: false), 3000, accuracy: 15)
        XCTAssertEqual(ShapeMath.length(sq, closed: true), 4000, accuracy: 20)
        XCTAssertEqual(ShapeMath.length([line[0]], closed: false), 0)
        XCTAssertEqual(ShapeMath.legs(sq).count, 3)
    }

    func testAreaOfKnownShapes() {
        XCTAssertEqual(ShapeMath.area(square(1)), 1_000_000, accuracy: 6000, "1 km square is 1 km²")
        XCTAssertEqual(ShapeMath.area(square(0.1)), 10_000, accuracy: 100)
        XCTAssertEqual(ShapeMath.area(square(1).reversed()), ShapeMath.area(square(1)), accuracy: 1e-6, "winding doesn't matter")
        // One degree by one degree at the equator is about 12,364 km².
        let degree = [C(latitude: 0, longitude: 0), C(latitude: 0, longitude: 1), C(latitude: 1, longitude: 1), C(latitude: 1, longitude: 0)]
        XCTAssertEqual(ShapeMath.area(degree) / 1e6, 12_364, accuracy: 40)
        XCTAssertEqual(ShapeMath.area(Array(square(1).prefix(2))), 0)
        // Across the antimeridian the area stays the same.
        XCTAssertEqual(ShapeMath.area(square(1, lat: 10, lon: 179.995)), ShapeMath.area(square(1, lat: 10, lon: 10)), accuracy: 3000)
    }

    func testBearingAndCentre() {
        let a = C(latitude: 39.74, longitude: -104.99)
        XCTAssertEqual(ShapeMath.bearing(a, C(latitude: 39.84, longitude: -104.99)), 0, accuracy: 0.01)
        XCTAssertEqual(ShapeMath.bearing(a, C(latitude: 39.74, longitude: -104.89)), 90, accuracy: 0.5)
        XCTAssertEqual(ShapeMath.bearing(a, C(latitude: 39.64, longitude: -104.99)), 180, accuracy: 0.01)
        XCTAssertEqual(ShapeMath.bearing(a, C(latitude: 39.74, longitude: -105.09)), 270, accuracy: 0.5)
        let c = ShapeMath.centre(square(1))
        XCTAssertEqual(c.latitude, 39.74 + 0.5 / 111.19493, accuracy: 1e-6)
        XCTAssertEqual(ShapeMath.bearingText(225), "225° SW")
        XCTAssertEqual(ShapeMath.bearingText(359.7), "0° N")
    }

    func testWordsFollowTheRegion() {
        XCTAssertEqual(ShapeMath.distanceText(850, metric: true), "850 m")
        XCTAssertEqual(ShapeMath.distanceText(2410, metric: true), "2.41 km")
        XCTAssertEqual(ShapeMath.distanceText(100, metric: false), "328 ft")
        XCTAssertEqual(ShapeMath.distanceText(2410, metric: false), "1.50 mi")
        XCTAssertEqual(ShapeMath.areaText(10_000, metric: true), "1.00 ha")
        XCTAssertEqual(ShapeMath.areaText(5_000_000, metric: true), "5.00 km²")
        XCTAssertEqual(ShapeMath.areaText(4046.8564, metric: false), "1.00 acres")
        XCTAssertEqual(ShapeMath.areaText(5_000_000, metric: false), "1.93 mi²")
    }

    func testExportsAreValid() throws {
        let area = MapShape(name: "Camp & \"east\" <field>", colorIndex: 2, points: square(0.2), closed: true)
        let route = MapShape(name: "Walk out", points: Array(square(1).prefix(3)), closed: false)
        let json = ShapeMath.geoJSON([area, route])
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let features = try XCTUnwrap(root["features"] as? [[String: Any]])
        XCTAssertEqual(features.count, 2)
        let poly = try XCTUnwrap(features[0]["geometry"] as? [String: Any])
        XCTAssertEqual(poly["type"] as? String, "Polygon")
        let ring = try XCTUnwrap((poly["coordinates"] as? [[[Double]]])?.first)
        XCTAssertEqual(ring.count, 5, "a ring repeats its first point")
        XCTAssertEqual(ring.first, ring.last)
        XCTAssertEqual(ring[0][0], -104.99, accuracy: 1e-9, "GeoJSON is longitude first")
        XCTAssertEqual((features[0]["properties"] as? [String: Any])?["color"] as? String, "#46C46E")
        XCTAssertEqual((features[1]["geometry"] as? [String: Any])?["type"] as? String, "LineString")
        let gpx = ShapeMath.gpx([area, route])
        XCTAssertTrue(gpx.contains("Camp &amp; \"east\" &lt;field&gt;"))
        XCTAssertEqual(gpx.components(separatedBy: "<trkpt").count - 1, 5 + 3)
        let parser = XMLParser(data: Data(gpx.utf8))
        XCTAssertTrue(parser.parse(), "GPX is well-formed XML")
    }

    func testMeshSummaryFitsAndCarriesThePlace() {
        let area = MapShape(name: "Camp perimeter", points: square(0.2), closed: true)
        let text = ShapeMath.meshSummary(area, metric: false, maxBytes: 133)
        XCTAssertTrue(text.hasPrefix("Camp perimeter: 9.88 acres"), text)
        XCTAssertTrue(text.contains("centre 39.7"))
        XCTAssertTrue(text.contains("DM79"))
        let long = MapShape(name: String(repeating: "Very long name ", count: 20), points: square(0.2), closed: true)
        XCTAssertLessThanOrEqual(ShapeMath.meshSummary(long, metric: true, maxBytes: 133).utf8.count, 133)
    }

    func testStorePersistsAndEdits() throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "shapes-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = ShapeStore(url: url)
        var s = MapShape(name: "A", points: square(0.5), closed: true)
        s.colorIndex = 1
        store.add(s)
        store.rename(s.id, to: "Renamed")
        store.recolor(s.id, to: 4)
        let again = ShapeStore(url: url)
        XCTAssertEqual(again.shapes.count, 1)
        XCTAssertEqual(again.shapes[0].name, "Renamed")
        XCTAssertEqual(again.shapes[0].colorIndex, 4)
        again.delete(s.id)
        XCTAssertTrue(ShapeStore(url: url).shapes.isEmpty)
    }
}
