// SatellitePasses.swift
// Pocket Nomad - Satellites module
// SPDX-License-Identifier: Apache-2.0
//
// Where a satellite is in the sky for an observer, and when it next passes.
// Pure maths on top of SGP4; no network. Verified against the Python Skyfield
// library (see SatelliteTests).

import Foundation

struct Observer: Equatable, Sendable {
    var latitude: Double
    var longitude: Double
    var altitudeMetres = 0.0
}

struct SkyPoint: Equatable, Sendable {
    /// Degrees clockwise from north, 0-360.
    var azimuth: Double
    /// Degrees above the horizon (negative = below).
    var elevation: Double
    var rangeKm: Double
}

struct SatellitePass: Equatable, Sendable, Identifiable {
    var noradID: Int
    var name: String
    var start: Date
    var peak: Date
    var end: Date
    var startAzimuth: Double
    var peakAzimuth: Double
    var endAzimuth: Double
    var maxElevation: Double
    /// The satellite was already above the horizon when the search began.
    var inProgress = false

    var id: String { "\(noradID)-\(Int(start.timeIntervalSince1970))" }
    var duration: TimeInterval { end.timeIntervalSince(start) }
}

enum SatelliteMath {
    private static let a = 6378.137 // WGS-84
    private static let f = 1 / 298.257223563
    private static let e2 = f * (2 - f)
    private static let rad = Double.pi / 180

    /// The observer's position in the Earth-fixed frame, km.
    static func ecef(_ o: Observer) -> SIMD3<Double> {
        let lat = o.latitude * rad, lon = o.longitude * rad
        let n = a / (1 - e2 * sin(lat) * sin(lat)).squareRoot()
        let h = o.altitudeMetres / 1000
        return SIMD3((n + h) * cos(lat) * cos(lon), (n + h) * cos(lat) * sin(lon), (n * (1 - e2) + h) * sin(lat))
    }

    /// Azimuth, elevation and range of a TEME position at a moment, for an observer.
    static func look(temePosition r: SIMD3<Double>, at date: Date, observer o: Observer) -> SkyPoint {
        let theta = SGP4.gmst(SGP4.julianDate(date))
        let c = cos(theta), s = sin(theta)
        let ecefSat = SIMD3(c * r.x + s * r.y, -s * r.x + c * r.y, r.z)
        let d = ecefSat - ecef(o)
        let lat = o.latitude * rad, lon = o.longitude * rad
        let sl = sin(lat), cl = cos(lat), so = sin(lon), co = cos(lon)
        let east = -so * d.x + co * d.y
        let north = -sl * co * d.x - sl * so * d.y + cl * d.z
        let up = cl * co * d.x + cl * so * d.y + sl * d.z
        let range = (d.x * d.x + d.y * d.y + d.z * d.z).squareRoot()
        var az = atan2(east, north) / rad
        if az < 0 { az += 360 }
        return SkyPoint(azimuth: az, elevation: asin(up / range) / rad, rangeKm: range)
    }

    static func look(_ sgp4: SGP4, at date: Date, observer: Observer) -> SkyPoint? {
        sgp4.propagate(at: date).map { look(temePosition: $0.position, at: date, observer: observer) }
    }

    /// Passes above `minElevation` between `from` and `from + hours`, soonest first.
    /// Rise and set are where the satellite crosses the horizon (elevation 0).
    static func passes(for tle: TLE, observer o: Observer, from start: Date, hours: Double = 48, minElevation: Double = 10, step: TimeInterval = 20) -> [SatellitePass] {
        guard let sgp4 = SGP4(tle) else { return [] }
        let end = start.addingTimeInterval(hours * 3600)
        func el(_ d: Date) -> Double? { look(sgp4, at: d, observer: o)?.elevation }
        /// The moment elevation crosses zero between `lo` (one side) and `hi` (the other), to a second.
        func cross(_ lo: Date, _ hi: Date) -> Date {
            var a = lo, b = hi
            let aUp = (el(a) ?? -90) > 0
            while b.timeIntervalSince(a) > 0.5 {
                let m = a.addingTimeInterval(b.timeIntervalSince(a) / 2)
                if ((el(m) ?? -90) > 0) == aUp { a = m } else { b = m }
            }
            return b
        }
        func peak(_ a: Date, _ b: Date) -> (Date, SkyPoint)? {
            // Golden-section search for the highest point.
            let g = (5.0.squareRoot() - 1) / 2
            var lo = a, hi = b
            var x1 = hi.addingTimeInterval(-g * hi.timeIntervalSince(lo)), x2 = lo.addingTimeInterval(g * hi.timeIntervalSince(lo))
            var f1 = el(x1) ?? -90, f2 = el(x2) ?? -90
            while hi.timeIntervalSince(lo) > 1 {
                if f1 < f2 {
                    lo = x1; x1 = x2; f1 = f2
                    x2 = lo.addingTimeInterval(g * hi.timeIntervalSince(lo)); f2 = el(x2) ?? -90
                } else {
                    hi = x2; x2 = x1; f2 = f1
                    x1 = hi.addingTimeInterval(-g * hi.timeIntervalSince(lo)); f1 = el(x1) ?? -90
                }
            }
            let t = lo.addingTimeInterval(hi.timeIntervalSince(lo) / 2)
            return look(sgp4, at: t, observer: o).map { (t, $0) }
        }

        var out: [SatellitePass] = []
        var t = start
        var prev = el(t) ?? -90
        var riseAt: Date? = prev > 0 ? start : nil
        var inProgress = prev > 0
        while t < end {
            let next = min(t.addingTimeInterval(step), end)
            let e = el(next) ?? -90
            if prev <= 0, e > 0 {
                riseAt = cross(t, next)
                inProgress = false
            } else if prev > 0, e <= 0, let rise = riseAt {
                let set = cross(t, next)
                if let (pt, sky) = peak(rise, set), sky.elevation >= minElevation,
                   let a = look(sgp4, at: rise, observer: o), let b = look(sgp4, at: set, observer: o) {
                    out.append(SatellitePass(noradID: tle.noradID, name: tle.name, start: rise, peak: pt, end: set, startAzimuth: a.azimuth,
                                             peakAzimuth: sky.azimuth, endAzimuth: b.azimuth, maxElevation: sky.elevation, inProgress: inProgress))
                }
                riseAt = nil
                inProgress = false
            }
            prev = e
            t = next
        }
        return out
    }

    /// "Rises NW, peaks 62° to the S, sets ESE".
    static func describe(_ p: SatellitePass) -> String {
        "Rises \(compass(p.startAzimuth)), peaks \(Int(p.maxElevation.rounded()))° to the \(compass(p.peakAzimuth)), sets \(compass(p.endAzimuth))"
    }

    static func compass(_ az: Double) -> String {
        let names = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
        return names[Int((az.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) / 22.5 + 0.5) % 16]
    }
}
