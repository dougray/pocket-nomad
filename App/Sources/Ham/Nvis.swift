// Nvis.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// NVIS (Near Vertical Incidence Skywave): short-range HF, roughly 0 to 300 miles, by sending the signal almost
// straight up so the ionosphere bounces it back down around you. It is how people keep contact inside a region
// when towers and internet are down. This is a rule-of-thumb planner: a rough estimate of which band works now,
// the wire lengths for it, and a map ring for the area it should cover. Not a propagation prediction.

import Foundation

struct NvisBand: Identifiable, Equatable, Sendable {
    var id: String { name }
    let name: String
    let mhz: Double
    /// Where JS8Call and FT8 listen on this band (MHz).
    let js8mhz: Double
    let ft8mhz: Double
}

enum Nvis {
    /// The HF bands that work for NVIS, lowest first. 60 m uses its channel near 5.357 MHz.
    static let bands: [NvisBand] = [
        .init(name: "160 m", mhz: 1.9, js8mhz: 1.842, ft8mhz: 1.840),
        .init(name: "80 m", mhz: 3.6, js8mhz: 3.578, ft8mhz: 3.573),
        .init(name: "60 m", mhz: 5.357, js8mhz: 5.357, ft8mhz: 5.357),
        .init(name: "40 m", mhz: 7.1, js8mhz: 7.078, ft8mhz: 7.074),
        .init(name: "30 m", mhz: 10.12, js8mhz: 10.130, ft8mhz: 10.136),
    ]

    /// 300 miles in kilometres: the outer edge of NVIS coverage.
    static let rangeKm = 482.803

    /// Elevation of the sun above the horizon in degrees at a place and time (NOAA's low-precision formulas).
    static func sunElevation(latitude: Double, longitude: Double, at date: Date) -> Double {
        let jd = date.timeIntervalSince1970 / 86400 + 2440587.5
        let n = jd - 2451545.0
        let L = (280.460 + 0.9856474 * n).truncatingRemainder(dividingBy: 360)
        let g = (357.528 + 0.9856003 * n).truncatingRemainder(dividingBy: 360) * .pi / 180
        let lambda = (L + 1.915 * sin(g) + 0.020 * sin(2 * g)) * .pi / 180
        let eps = (23.439 - 0.0000004 * n) * .pi / 180
        let dec = asin(sin(eps) * sin(lambda))
        let ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        let gmst = (18.697374558 + 24.06570982441908 * n).truncatingRemainder(dividingBy: 24)
        let hour = (gmst * 15 + longitude) * .pi / 180 - ra
        let lat = latitude * .pi / 180
        return asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hour)) * 180 / .pi
    }

    struct Advice: Equatable, Sendable {
        var foF2: Double
        var fot: Double
        var luf: Double
        var day: Double          // 0 = night, 1 = full day
        var best: NvisBand?
        var alternate: NvisBand?
        var stormed: Bool
        var summary: String
    }

    /// A rough guess at which band works for NVIS right now.
    /// - sfi: the 10.7 cm solar flux (about 65 to 250). kp: the geomagnetic index (0 to 9). sunElevation in degrees.
    static func advise(sfi: Double, kp: Double, sunElevation: Double) -> Advice {
        let day = max(0, min(1, (sunElevation + 6) / 26))
        let dayF = 4.5 + (sfi - 70) * 0.035
        let nightF = 2.4 + (sfi - 70) * 0.012
        var fo = nightF + day * (dayF - nightF)
        let stormed = kp >= 5
        if kp >= 7 { fo *= 0.6 } else if kp >= 5 { fo *= 0.8 }
        let fot = fo * 0.85                 // the highest frequency that reliably comes back
        let luf = 1.8 + day * 1.7           // below this the daytime layer absorbs a low-power signal
        let usable = bands.filter { $0.mhz <= fot && $0.mhz >= luf }
        let best = usable.last
        let alt = usable.dropLast().last
        let summary: String
        if let best {
            summary = "\(best.name) should work for NVIS now" + (alt.map { ", with \($0.name) as a backup" } ?? "") + "."
        } else if fot < luf {
            summary = "NVIS looks poor right now: the usable window is closed. Try again after dusk or dawn, or try \(day > 0.5 ? "60 m or 80 m" : "160 m or 80 m") anyway."
        } else {
            summary = "No standard band sits in the window right now. Try the nearest one below \(String(format: "%.1f", fot)) MHz."
        }
        return Advice(foF2: fo, fot: fot, luf: luf, day: day, best: best, alternate: alt, stormed: stormed, summary: summary)
    }

    struct Antenna: Equatable, Sendable {
        let band: NvisBand
        let dipoleMetres: Double
        let dipoleFeet: Double
        let heightMinMetres: Double
        let heightMaxMetres: Double
    }

    /// Wire lengths for a half-wave dipole or end-fed half-wave, and the height range that favours NVIS
    /// (a tenth to a quarter of a wavelength above ground). Cut a little long and trim for the lowest SWR.
    static func antenna(for band: NvisBand) -> Antenna {
        let wavelength = 299.792458 / band.mhz
        let metres = 142.65 / band.mhz
        return Antenna(band: band, dipoleMetres: metres, dipoleFeet: metres * 3.28084, heightMinMetres: 0.1 * wavelength, heightMaxMetres: 0.25 * wavelength)
    }

    /// Points on a circle on the Earth's surface (for the map ring).
    static func circle(latitude: Double, longitude: Double, radiusKm: Double, points: Int = 72) -> [(latitude: Double, longitude: Double)] {
        let d = radiusKm / 6371.0088
        let lat1 = latitude * .pi / 180, lon1 = longitude * .pi / 180
        return (0...points).map { i in
            let bearing = 2 * .pi * Double(i) / Double(points)
            let lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(bearing))
            let lon2 = lon1 + atan2(sin(bearing) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
            return (lat2 * 180 / .pi, ((lon2 * 180 / .pi + 540).truncatingRemainder(dividingBy: 360)) - 180)
        }
    }
}
