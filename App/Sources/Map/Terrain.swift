// Terrain.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Elevation from open "terrarium" tiles (AWS Open Data, no key): hillshade on the map
// and the terrain under a radio path. Tiles are downloaded once for an area and then
// work with no signal.

import CoreGraphics
import CoreLocation
import Foundation
import ImageIO
import Observation

enum TerrainMath {
    /// Fractional slippy-map tile coordinates.
    static func tileXY(latitude: Double, longitude: Double, zoom: Int) -> (x: Double, y: Double) {
        let n = pow(2.0, Double(zoom))
        let lat = max(-85.0511, min(85.0511, latitude))
        let x = (longitude + 180) / 360 * n
        let y = (1 - asinh(tan(lat * .pi / 180)) / .pi) / 2 * n
        return (x, y)
    }

    /// Terrarium: metres = R*256 + G + B/256 - 32768.
    static func terrarium(_ r: UInt8, _ g: UInt8, _ b: UInt8) -> Double {
        Double(r) * 256 + Double(g) + Double(b) / 256 - 32768
    }

    static func bilinear(_ q00: Double, _ q10: Double, _ q01: Double, _ q11: Double, fx: Double, fy: Double) -> Double {
        (q00 * (1 - fx) + q10 * fx) * (1 - fy) + (q01 * (1 - fx) + q11 * fx) * fy
    }

    /// Tiles covering a circle's bounding box at one zoom.
    static func tiles(centre: CLLocationCoordinate2D, radiusKm: Double, zoom: Int) -> [(x: Int, y: Int)] {
        let dLat = radiusKm / 111.32
        let dLon = radiusKm / (111.32 * max(0.05, cos(centre.latitude * .pi / 180)))
        let a = tileXY(latitude: centre.latitude + dLat, longitude: centre.longitude - dLon, zoom: zoom)
        let b = tileXY(latitude: centre.latitude - dLat, longitude: centre.longitude + dLon, zoom: zoom)
        let n = 1 << zoom
        let x0 = max(0, Int(a.x)), x1 = min(n - 1, Int(b.x))
        let y0 = max(0, Int(a.y)), y1 = min(n - 1, Int(b.y))
        guard x0 <= x1, y0 <= y1 else { return [] }
        return (y0...y1).flatMap { y in (x0...x1).map { (x: $0, y: y) } }
    }
}

/// How the map looks: plain streets, or streets over shaded relief.
enum MapLook: String, CaseIterable, Identifiable {
    case streets, topo, relief
    var id: Self { self }
    var label: String { switch self { case .streets: "Streets"; case .topo: "Topo"; case .relief: "Relief" } }
    var symbol: String { switch self { case .streets: "map"; case .topo: "mountain.2"; case .relief: "triangle.lefthalf.filled" } }
    var blurb: String {
        switch self {
        case .streets: "Roads and places."
        case .topo: "Roads with hills shaded underneath."
        case .relief: "Hills, strongly shaded. Best for judging terrain."
        }
    }
    var hillshadeOpacity: Double { switch self { case .streets: 0; case .topo: 0.55; case .relief: 1 } }
}

@MainActor
@Observable
final class TerrainStore {
    static let shared = TerrainStore()
    static let zooms = 6...11
    static let remoteTemplate = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    let folder: URL
    private(set) var downloading = false
    private(set) var progress = 0.0
    private(set) var message = ""
    private(set) var tileCount: Int
    private var cache: [String: [Float]] = [:]

    init(folder: URL = URL.applicationSupportDirectory.appending(path: "terrain")) {
        self.folder = folder
        tileCount = Self.count(in: folder)
    }

    var hasTiles: Bool { tileCount > 0 }

    /// What the map's elevation source reads: the saved tiles, else the live ones.
    var template: String { hasTiles ? folder.absoluteString + "{z}/{x}/{y}.png" : Self.remoteTemplate }

    private nonisolated static func count(in folder: URL) -> Int {
        guard let e = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: nil) else { return 0 }
        return e.compactMap { $0 as? URL }.filter { $0.pathExtension == "png" }.count
    }

    func file(z: Int, x: Int, y: Int) -> URL { folder.appending(path: "\(z)/\(x)/\(y).png") }

    func plan(centre: CLLocationCoordinate2D, radiusKm: Double) -> [(z: Int, x: Int, y: Int)] {
        Self.zooms.flatMap { z in TerrainMath.tiles(centre: centre, radiusKm: radiusKm, zoom: z).map { (z: z, x: $0.x, y: $0.y) } }
    }

    /// Download the tiles for a circle around a point (skips ones already saved).
    func download(centre: CLLocationCoordinate2D, radiusKm: Double) async {
        guard !downloading else { return }
        downloading = true; progress = 0; message = "Downloading elevation…"
        let todo = plan(centre: centre, radiusKm: radiusKm)
        var failures = 0
        for (i, t) in todo.enumerated() {
            let dest = file(z: t.z, x: t.x, y: t.y)
            if !FileManager.default.fileExists(atPath: dest.path) {
                let url = URL(string: Self.remoteTemplate.replacingOccurrences(of: "{z}", with: "\(t.z)").replacingOccurrences(of: "{x}", with: "\(t.x)").replacingOccurrences(of: "{y}", with: "\(t.y)"))!
                do {
                    var req = URLRequest(url: url); req.setValue("PocketNomad/1.0 (+https://pocketnomad.us)", forHTTPHeaderField: "User-Agent")
                    let (data, resp) = try await URLSession.shared.data(for: req)
                    guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                    try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try data.write(to: dest, options: .atomic)
                } catch { failures += 1 }
            }
            progress = Double(i + 1) / Double(max(1, todo.count))
        }
        tileCount = Self.count(in: folder)
        message = failures == 0 ? "Saved \(todo.count) tiles." : "Saved with \(failures) tiles missing. Try again with signal."
        downloading = false
    }

    func deleteAll() {
        try? FileManager.default.removeItem(at: folder)
        cache = [:]; tileCount = 0; message = ""
    }

    /// Approximate megabytes for a download (about 110 KB a tile).
    func estimateMB(centre: CLLocationCoordinate2D, radiusKm: Double) -> Double { Double(plan(centre: centre, radiusKm: radiusKm).count) * 0.11 }

    // MARK: Elevation

    private func samples(z: Int, x: Int, y: Int) -> [Float]? {
        let key = "\(z)/\(x)/\(y)"
        if let c = cache[key] { return c }
        guard let src = CGImageSourceCreateWithURL(file(z: z, x: x, y: y) as CFURL, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil), img.width == 256, img.height == 256 else { return nil }
        var rgba = [UInt8](repeating: 0, count: 256 * 256 * 4)
        guard let ctx = CGContext(data: &rgba, width: 256, height: 256, bitsPerComponent: 8, bytesPerRow: 1024,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: 256, height: 256))
        var out = [Float](repeating: 0, count: 256 * 256)
        for i in 0..<(256 * 256) { out[i] = Float(TerrainMath.terrarium(rgba[i * 4], rgba[i * 4 + 1], rgba[i * 4 + 2])) }
        if cache.count > 24 { cache.removeAll() }
        cache[key] = out
        return out
    }

    /// Metres above sea level at a point from the saved tiles, or nil with no data there.
    func elevation(latitude: Double, longitude: Double) -> Double? {
        let z = Self.zooms.upperBound
        let t = TerrainMath.tileXY(latitude: latitude, longitude: longitude, zoom: z)
        func px(_ gx: Int, _ gy: Int) -> Double? {
            let tx = Int((Double(gx) / 256).rounded(.down)), ty = Int((Double(gy) / 256).rounded(.down))
            guard let s = samples(z: z, x: tx, y: ty) else { return nil }
            return Double(s[(gy - ty * 256) * 256 + (gx - tx * 256)])
        }
        let fx = t.x * 256 - 0.5, fy = t.y * 256 - 0.5
        let gx = Int(fx.rounded(.down)), gy = Int(fy.rounded(.down))
        guard let a = px(gx, gy), let b = px(gx + 1, gy), let c = px(gx, gy + 1), let d = px(gx + 1, gy + 1) else { return nil }
        return TerrainMath.bilinear(a, b, c, d, fx: fx - Double(gx), fy: fy - Double(gy))
    }
}
