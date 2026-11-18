// MapShapes.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Lines and areas the user draws on the map: measuring, naming, saving,
// exporting (GeoJSON, GPX) and sending a short summary over the mesh.
// Everything here works offline. The geometry is pure and unit-tested.

import CoreLocation
import Foundation
import Observation

struct MapShape: Codable, Identifiable, Equatable, Sendable {
    var id = UUID()
    var name: String
    /// Index into `ShapeMath.palette`.
    var colorIndex = 0
    var points: [Coordinate]
    /// A closed shape is an area; an open one is a route or a line.
    var closed: Bool
    var created = Date()

    struct Coordinate: Codable, Equatable, Sendable {
        var latitude: Double
        var longitude: Double
    }

    var isArea: Bool { closed && points.count >= 3 }
    var coordinates: [CLLocationCoordinate2D] { points.map { .init(latitude: $0.latitude, longitude: $0.longitude) } }
}

enum ShapeMath {
    /// Name, colour: orange, blue, green, red, purple, yellow.
    static let palette: [(String, UInt32)] = [
        ("Orange", 0xFC9712), ("Blue", 0x4F8DF5), ("Green", 0x46C46E), ("Red", 0xE5484D), ("Purple", 0xAF52DE), ("Yellow", 0xFFD43B),
    ]

    static let earthRadius = 6_371_008.8

    // MARK: Measuring

    /// Metres along the line; a closed shape includes the leg back to the start.
    static func length(_ p: [MapShape.Coordinate], closed: Bool) -> Double {
        guard p.count >= 2 else { return 0 }
        var total = 0.0
        for i in 1..<p.count { total += CoverageShare.distance(p[i - 1].latitude, p[i - 1].longitude, p[i].latitude, p[i].longitude) }
        if closed, p.count >= 3 { total += CoverageShare.distance(p[p.count - 1].latitude, p[p.count - 1].longitude, p[0].latitude, p[0].longitude) }
        return total
    }

    /// Distance of each leg in order (not including a closing leg).
    static func legs(_ p: [MapShape.Coordinate]) -> [Double] {
        guard p.count >= 2 else { return [] }
        return (1..<p.count).map { CoverageShare.distance(p[$0 - 1].latitude, p[$0 - 1].longitude, p[$0].latitude, p[$0].longitude) }
    }

    /// Square metres enclosed by a polygon (spherical, Chamberlain-Duquette). Order and winding don't matter.
    static func area(_ p: [MapShape.Coordinate]) -> Double {
        guard p.count >= 3 else { return 0 }
        var sum = 0.0
        for i in 0..<p.count {
            let a = p[i], b = p[(i + 1) % p.count]
            var dLon = (b.longitude - a.longitude) * .pi / 180
            if dLon > .pi { dLon -= 2 * .pi } else if dLon < -.pi { dLon += 2 * .pi }
            sum += dLon * (2 + sin(a.latitude * .pi / 180) + sin(b.latitude * .pi / 180))
        }
        return abs(sum) * earthRadius * earthRadius / 2
    }

    /// Compass bearing in degrees from a to b.
    static func bearing(_ a: MapShape.Coordinate, _ b: MapShape.Coordinate) -> Double {
        let l1 = a.latitude * .pi / 180, l2 = b.latitude * .pi / 180
        let dLon = (b.longitude - a.longitude) * .pi / 180
        let y = sin(dLon) * cos(l2)
        let x = cos(l1) * sin(l2) - sin(l1) * cos(l2) * cos(dLon)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    /// The middle of the points (fine for shapes up to tens of kilometres).
    static func centre(_ p: [MapShape.Coordinate]) -> MapShape.Coordinate {
        guard !p.isEmpty else { return .init(latitude: 0, longitude: 0) }
        return .init(latitude: p.map(\.latitude).reduce(0, +) / Double(p.count), longitude: p.map(\.longitude).reduce(0, +) / Double(p.count))
    }

    // MARK: Words

    /// A short height, in metres or feet.
    static func heightText(_ metres: Double, metric: Bool) -> String {
        metric ? String(format: "%.0f m", metres) : String(format: "%.0f ft", metres * 3.28084)
    }

    static func distanceText(_ metres: Double, metric: Bool) -> String {
        if metric { return metres < 1000 ? "\(Int(metres.rounded())) m" : String(format: "%.2f km", metres / 1000) }
        let feet = metres * 3.28084
        return feet < 528 ? "\(Int(feet.rounded())) ft" : String(format: "%.2f mi", metres / 1609.344)
    }

    static func areaText(_ squareMetres: Double, metric: Bool) -> String {
        if metric { return squareMetres < 1_000_000 ? String(format: "%.2f ha", squareMetres / 10_000) : String(format: "%.2f km²", squareMetres / 1_000_000) }
        let acres = squareMetres / 4046.8564224
        return acres < 640 ? String(format: "%.2f acres", acres) : String(format: "%.2f mi²", squareMetres / 2_589_988.110336)
    }

    static func bearingText(_ degrees: Double) -> String {
        let dirs = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
        return "\(Int(degrees.rounded()) % 360)° \(dirs[Int((degrees + 22.5) / 45) % 8])"
    }

    /// "Camp perimeter: 2.41 acres, 0.62 mi around, centre 39.7400,-104.9900 DM79mr", trimmed to fit a mesh message.
    static func meshSummary(_ s: MapShape, metric: Bool, maxBytes: Int) -> String {
        let c = centre(s.points)
        let place = String(format: "%.4f,%.4f", c.latitude, c.longitude) + " " + HamGeo.maidenhead(lat: c.latitude, lon: c.longitude)
        let size = s.isArea
            ? "\(areaText(area(s.points), metric: metric)), \(distanceText(length(s.points, closed: true), metric: metric)) around"
            : distanceText(length(s.points, closed: false), metric: metric)
        return MeshText.truncated("\(s.name): \(size), centre \(place)", maxBytes: maxBytes)
    }

    // MARK: Export

    static func geoJSON(_ shapes: [MapShape]) -> String {
        let features: [[String: Any]] = shapes.map { s in
            var ring = s.points.map { [$0.longitude, $0.latitude] }
            let geometry: [String: Any]
            if s.isArea {
                ring.append(ring[0])
                geometry = ["type": "Polygon", "coordinates": [ring]]
            } else {
                geometry = ["type": "LineString", "coordinates": ring]
            }
            let props: [String: Any] = ["name": s.name, "color": String(format: "#%06X", palette[s.colorIndex % palette.count].1),
                                        s.isArea ? "area_m2" : "length_m": (s.isArea ? area(s.points) : length(s.points, closed: false)).rounded()]
            return ["type": "Feature", "properties": props, "geometry": geometry]
        }
        let data = (try? JSONSerialization.data(withJSONObject: ["type": "FeatureCollection", "features": features], options: [.sortedKeys, .prettyPrinted])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    static func gpx(_ shapes: [MapShape]) -> String {
        func esc(_ s: String) -> String { s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;") }
        var out = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Pocket Nomad\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
        for s in shapes {
            var pts = s.points
            if s.isArea { pts.append(pts[0]) }
            out += "  <trk><name>\(esc(s.name))</name><trkseg>\n"
            for p in pts { out += String(format: "    <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n", p.latitude, p.longitude) }
            out += "  </trkseg></trk>\n"
        }
        return out + "</gpx>\n"
    }
}

@MainActor
@Observable
final class ShapeStore {
    static let shared = ShapeStore()
    private(set) var shapes: [MapShape] = []
    private let url: URL

    init(url: URL = URL.applicationSupportDirectory.appending(path: "shapes.json")) {
        self.url = url
        shapes = (try? JSONDecoder().decode([MapShape].self, from: Data(contentsOf: url))) ?? []
    }

    func add(_ s: MapShape) { shapes.append(s); save() }
    func rename(_ id: UUID, to name: String) {
        guard let i = shapes.firstIndex(where: { $0.id == id }) else { return }
        shapes[i].name = name
        save()
    }
    func recolor(_ id: UUID, to index: Int) {
        guard let i = shapes.firstIndex(where: { $0.id == id }) else { return }
        shapes[i].colorIndex = index
        save()
    }
    func delete(_ id: UUID) { shapes.removeAll { $0.id == id }; save() }
    func deleteAll() { shapes = []; save() }

    private func save() {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(shapes).write(to: url, options: .atomic)
    }
}
