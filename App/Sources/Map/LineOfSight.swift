// LineOfSight.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Is there a clear radio path between two points? Terrain profile from saved elevation
// tiles, Earth curvature (effective radius 4/3), and the first Fresnel zone.
// Terrain only: trees and buildings are not in the data, so "clear" means "possible".

import Foundation

struct RadioBand: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let mhz: Double
    static let all: [RadioBand] = [
        .init(id: "lora915", name: "LoRa mesh 915 MHz", mhz: 915),
        .init(id: "lora868", name: "LoRa mesh 868 MHz", mhz: 868),
        .init(id: "lora433", name: "LoRa 433 MHz", mhz: 433),
        .init(id: "2m", name: "Ham 2 m (146 MHz)", mhz: 146),
        .init(id: "70cm", name: "Ham 70 cm (446 MHz)", mhz: 446),
        .init(id: "wifi", name: "Wi-Fi 2.4 GHz", mhz: 2437),
    ]
}

struct PathPoint: Equatable, Sendable {
    /// Metres from the start.
    var distance: Double
    /// Terrain height above sea level.
    var ground: Double
    /// How far Earth curvature lifts the terrain into the path at this point.
    var bulge: Double
    /// Height of the straight radio line above sea level.
    var line: Double
    /// First Fresnel zone radius.
    var fresnel: Double
    /// Radio line minus (terrain + bulge). Negative = blocked.
    var clearance: Double { line - (ground + bulge) }
}

enum PathVerdict: String, Sendable {
    case clear, marginal, blocked, unknown
    var label: String {
        switch self {
        case .clear: "Clear path"
        case .marginal: "Possible, but tight"
        case .blocked: "Blocked by terrain"
        case .unknown: "No elevation data"
        }
    }
}

struct PathResult: Sendable {
    var distance: Double
    var points: [PathPoint]
    var verdict: PathVerdict
    var worstClearance: Double
    var worstAt: Double
    /// Smallest clearance as a fraction of the first Fresnel zone (1 = zone fully clear).
    var worstFresnelRatio: Double
    var missing: Int
}

enum LineOfSight {
    static let earthRadius = 6_371_008.8
    static let k = 4.0 / 3.0

    /// Wavelength in metres.
    static func wavelength(mhz: Double) -> Double { 299_792_458 / (mhz * 1e6) }

    /// First Fresnel zone radius at d1 from one end and d2 from the other.
    static func fresnelRadius(d1: Double, d2: Double, mhz: Double) -> Double {
        let total = d1 + d2
        return total > 0 ? (wavelength(mhz: mhz) * d1 * d2 / total).squareRoot() : 0
    }

    /// Earth bulge (metres) at d1 / d2 along a path, for effective radius k * R.
    static func bulge(d1: Double, d2: Double) -> Double { d1 * d2 / (2 * k * earthRadius) }

    /// Horizon distance for one antenna height, metres (4/3 Earth).
    static func horizon(height: Double) -> Double { (2 * k * earthRadius * max(0, height)).squareRoot() }

    static func analyze(from a: MapShape.Coordinate, to b: MapShape.Coordinate, heightA: Double, heightB: Double, mhz: Double,
                        elevation: (Double, Double) -> Double?) -> PathResult {
        let total = CoverageShare.distance(a.latitude, a.longitude, b.latitude, b.longitude)
        let steps = max(20, min(400, Int(total / 100)))
        var points: [PathPoint] = []
        var missing = 0
        var groundA: Double?, groundB: Double?
        var samples: [(d: Double, g: Double?)] = []
        for i in 0...steps {
            let f = Double(i) / Double(steps)
            let g = elevation(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
            samples.append((total * f, g))
            if g == nil { missing += 1 }
            if i == 0 { groundA = g }
            if i == steps { groundB = g }
        }
        guard let gA = groundA ?? samples.compactMap(\.g).first, let gB = groundB ?? samples.compactMap(\.g).last else {
            return PathResult(distance: total, points: [], verdict: .unknown, worstClearance: 0, worstAt: 0, worstFresnelRatio: 0, missing: missing)
        }
        let top = (a: gA + heightA, b: gB + heightB)
        for s in samples {
            guard let g = s.g else { continue }
            let f = total > 0 ? s.d / total : 0
            points.append(PathPoint(distance: s.d, ground: g, bulge: bulge(d1: s.d, d2: total - s.d),
                                    line: top.a + (top.b - top.a) * f, fresnel: fresnelRadius(d1: s.d, d2: total - s.d, mhz: mhz)))
        }
        // Judge the interior: the ends are the antennas themselves.
        let inner = points.dropFirst().dropLast()
        guard let worst = inner.min(by: { $0.clearance < $1.clearance }) else {
            return PathResult(distance: total, points: points, verdict: points.isEmpty ? .unknown : .clear, worstClearance: 0, worstAt: 0, worstFresnelRatio: 1, missing: missing)
        }
        let ratio = worst.fresnel > 0 ? worst.clearance / worst.fresnel : 1
        let worstRatio = inner.map { $0.fresnel > 0 ? $0.clearance / $0.fresnel : 1 }.min() ?? 1
        let verdict: PathVerdict = worst.clearance < 0 ? .blocked : worstRatio < 0.6 ? .marginal : .clear
        _ = ratio
        return PathResult(distance: total, points: points, verdict: verdict, worstClearance: worst.clearance, worstAt: worst.distance, worstFresnelRatio: worstRatio, missing: missing)
    }
}
