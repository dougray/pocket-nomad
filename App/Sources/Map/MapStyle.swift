import Foundation
import CoreLocation

/// Bounds and zooms read from a PMTiles v3 header (first 127 bytes).
struct PMTilesInfo: Sendable {
    let minZoom: Int, maxZoom: Int
    let southWest: CLLocationCoordinate2D, northEast: CLLocationCoordinate2D
    let center: CLLocationCoordinate2D, centerZoom: Int

    init?(url: URL) {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let d = try? handle.read(upToCount: 127), d.count == 127,
              String(decoding: d.prefix(7), as: UTF8.self) == "PMTiles", d[7] == 3 else { return nil }
        func i32(_ o: Int) -> Double {
            Double(Int32(littleEndian: d.subdata(in: o..<o + 4).withUnsafeBytes { $0.loadUnaligned(as: Int32.self) })) / 10_000_000
        }
        minZoom = Int(d[100]); maxZoom = Int(d[101])
        southWest = .init(latitude: i32(106), longitude: i32(102))
        northEast = .init(latitude: i32(114), longitude: i32(110))
        centerZoom = Int(d[118])
        center = .init(latitude: i32(123), longitude: i32(119))
    }
}

/// Builds a MapLibre style from the bundled Protomaps layers. The bundled world
/// overview (zoom 0–5) is always drawn; each regional .pmtiles the user adds
/// gets its own copy of the layers, drawn from zoom 5.5 up.
enum MapStyle {
    static let assets = Bundle.main.url(forResource: "Map", withExtension: nil)!
    static let worldURL = assets.appending(path: "world.pmtiles")
    private static let handoffZoom = 5.5

    static func write(dark: Bool, regions: [URL]) throws -> URL {
        let flavor = dark ? "dark" : "light"
        let layerData = try Data(contentsOf: assets.appending(path: "layers-\(flavor).json"))
        guard let template = try JSONSerialization.jsonObject(with: layerData) as? [[String: Any]] else {
            throw CocoaError(.fileReadCorruptFile)
        }

        var sources: [String: Any] = [:]
        var layers: [[String: Any]] = []
        let attribution = "<a href=\"https://openstreetmap.org/copyright\">© OpenStreetMap</a> · Protomaps"

        func add(sourceID: String, file: URL, isWorld: Bool) {
            sources[sourceID] = ["type": "vector", "url": "pmtiles://\(file.absoluteString)", "attribution": attribution]
            for var layer in template {
                let type = layer["type"] as? String
                if type == "background" {
                    if isWorld { layers.append(layer) }
                    continue
                }
                layer["id"] = "\(sourceID)-\(layer["id"] as? String ?? "")"
                layer["source"] = sourceID
                if isWorld {
                    // Past the hand-off, the overview's overzoomed labels would sit on top of
                    // the region's real ones. Keep its land and water as a backdrop only.
                    if type == "symbol" || type == "line", !regions.isEmpty { layer["maxzoom"] = handoffZoom + 1 }
                } else {
                    let current = layer["minzoom"] as? Double ?? 0
                    layer["minzoom"] = max(current, handoffZoom)
                }
                layers.append(layer)
            }
        }

        add(sourceID: "world", file: worldURL, isWorld: true)
        for (i, region) in regions.enumerated() { add(sourceID: "region\(i)", file: region, isWorld: false) }

        let style: [String: Any] = [
            "version": 8,
            "name": "Pocket Nomad \(flavor)",
            "glyphs": assets.appending(path: "fonts").absoluteString + "/{fontstack}/{range}.pbf",
            "sprite": assets.appending(path: "sprites/\(flavor)").absoluteString,
            "sources": sources,
            "layers": layers,
        ]
        // One file per combination, so MapLibre never serves a stale cached style.
        let key = regions.map(\.path).joined(separator: "|").utf8.reduce(UInt64(5381)) { ($0 &* 33) ^ UInt64($1) }
        let out = URL.cachesDirectory.appending(path: "style-\(flavor)-\(String(key, radix: 36)).json")
        try JSONSerialization.data(withJSONObject: style).write(to: out, options: .atomic)
        return out
    }
}
