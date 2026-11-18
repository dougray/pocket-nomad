// Coordinates.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Reads and writes positions in the formats people actually use: decimal
// degrees, degrees-minutes-seconds, degrees and decimal minutes, UTM, MGRS (the
// military grid) and Maidenhead (the ham radio grid square). Works with no
// network. UTM/MGRS are checked against the Python `utm` and `mgrs` libraries.

import Foundation

enum PositionFormat: String, CaseIterable, Identifiable, Sendable {
    case decimal, dms, dmm, utm, mgrs, maidenhead
    var id: String { rawValue }
    var label: String {
        switch self {
        case .decimal: "Decimal degrees"
        case .dms: "Degrees, minutes, seconds"
        case .dmm: "Degrees, decimal minutes"
        case .utm: "UTM"
        case .mgrs: "MGRS"
        case .maidenhead: "Maidenhead grid"
        }
    }
}

struct ParsedCoordinate: Equatable, Sendable {
    var latitude: Double
    var longitude: Double
    var format: PositionFormat
}

enum Coordinates {
    // MARK: WGS-84 / UTM

    private static let a = 6_378_137.0
    private static let f = 1 / 298.257223563
    private static let k0 = 0.9996
    private static let e2 = f * (2 - f)
    private static let ep2 = e2 / (1 - e2)
    private static let bands = Array("CDEFGHJKLMNPQRSTUVWX")

    struct UTM: Equatable, Sendable {
        var zone: Int
        var band: Character
        var easting: Double
        var northing: Double
        var isNorth: Bool { band >= "N" }
    }

    /// The UTM zone for a position, including Norway's and Svalbard's exceptions.
    static func zone(latitude: Double, longitude: Double) -> Int {
        var lon = longitude
        if lon >= 180 { lon -= 360 }
        var z = Int(floor((lon + 180) / 6)) + 1
        if latitude >= 56, latitude < 64, lon >= 3, lon < 12 { z = 32 }
        if latitude >= 72, latitude < 84 {
            if lon >= 0, lon < 9 { z = 31 } else if lon >= 9, lon < 21 { z = 33 } else if lon >= 21, lon < 33 { z = 35 } else if lon >= 33, lon < 42 { z = 37 }
        }
        return min(60, max(1, z))
    }

    static func band(latitude: Double) -> Character? {
        guard latitude >= -80, latitude <= 84 else { return nil }
        return bands[min(bands.count - 1, Int(floor((latitude + 80) / 8)))]
    }

    private static func centralMeridian(_ zone: Int) -> Double { Double(zone - 1) * 6 - 180 + 3 }

    /// nil outside 80°S to 84°N, where UTM doesn't apply.
    static func utm(latitude: Double, longitude: Double) -> UTM? {
        guard let band = band(latitude: latitude) else { return nil }
        let z = zone(latitude: latitude, longitude: longitude)
        let phi = latitude * .pi / 180
        var dl = longitude - centralMeridian(z)
        if dl > 180 { dl -= 360 } else if dl < -180 { dl += 360 }
        let lam = dl * .pi / 180
        let s = sin(phi), c = cos(phi), t = tan(phi)
        let n = a / (1 - e2 * s * s).squareRoot()
        let T = t * t, C = ep2 * c * c, A = lam * c
        let m = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * pow(e2, 3) / 256) * phi
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * pow(e2, 3) / 1024) * sin(2 * phi)
            + (15 * e2 * e2 / 256 + 45 * pow(e2, 3) / 1024) * sin(4 * phi)
            - (35 * pow(e2, 3) / 3072) * sin(6 * phi))
        let x = k0 * n * (A + (1 - T + C) * pow(A, 3) / 6 + (5 - 18 * T + T * T + 72 * C - 58 * ep2) * pow(A, 5) / 120)
        var y = k0 * (m + n * t * (A * A / 2 + (5 - T + 9 * C + 4 * C * C) * pow(A, 4) / 24
            + (61 - 58 * T + T * T + 600 * C - 330 * ep2) * pow(A, 6) / 720))
        if latitude < 0 { y += 10_000_000 }
        return UTM(zone: z, band: band, easting: x + 500_000, northing: y)
    }

    static func latLon(from u: UTM) -> (latitude: Double, longitude: Double) {
        let x = u.easting - 500_000
        let y = u.isNorth ? u.northing : u.northing - 10_000_000
        let m = y / k0
        let mu = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * pow(e2, 3) / 256))
        let e1 = (1 - (1 - e2).squareRoot()) / (1 + (1 - e2).squareRoot())
        let phi1 = mu + (3 * e1 / 2 - 27 * pow(e1, 3) / 32) * sin(2 * mu) + (21 * e1 * e1 / 16 - 55 * pow(e1, 4) / 32) * sin(4 * mu)
            + (151 * pow(e1, 3) / 96) * sin(6 * mu) + (1097 * pow(e1, 4) / 512) * sin(8 * mu)
        let s = sin(phi1), c = cos(phi1), t = tan(phi1)
        let C1 = ep2 * c * c, T1 = t * t
        let n1 = a / (1 - e2 * s * s).squareRoot()
        let r1 = a * (1 - e2) / pow(1 - e2 * s * s, 1.5)
        let d = x / (n1 * k0)
        let phi = phi1 - (n1 * t / r1) * (d * d / 2 - (5 + 3 * T1 + 10 * C1 - 4 * C1 * C1 - 9 * ep2) * pow(d, 4) / 24
            + (61 + 90 * T1 + 298 * C1 + 45 * T1 * T1 - 252 * ep2 - 3 * C1 * C1) * pow(d, 6) / 720)
        let lam = (d - (1 + 2 * T1 + C1) * pow(d, 3) / 6 + (5 - 2 * C1 + 28 * T1 - 3 * C1 * C1 + 8 * ep2 + 24 * T1 * T1) * pow(d, 5) / 120) / c
        return (phi * 180 / .pi, centralMeridian(u.zone) + lam * 180 / .pi)
    }

    // MARK: MGRS

    private static let columnSets = ["ABCDEFGH", "JKLMNPQR", "STUVWXYZ"].map(Array.init)
    private static let rowLetters = Array("ABCDEFGHJKLMNPQRSTUV")

    /// MGRS with `digits` per axis (5 = 1 metre), truncated like the standard says.
    static func mgrs(latitude: Double, longitude: Double, digits: Int = 5) -> String? {
        guard let u = utm(latitude: latitude, longitude: longitude) else { return nil }
        let col = columnSets[(u.zone - 1) % 3][Int(floor(u.easting / 100_000)) - 1]
        let rowIndex = (Int(floor(u.northing / 100_000)) + (u.zone % 2 == 0 ? 5 : 0)) % 20
        let ee = Int(floor(u.easting.truncatingRemainder(dividingBy: 100_000)))
        let nn = Int(floor(u.northing.truncatingRemainder(dividingBy: 100_000)))
        let d = max(0, min(5, digits))
        func pad(_ v: Int) -> String { d == 0 ? "" : String(String(format: "%05d", v).prefix(d)) }
        return String(format: "%02d", u.zone) + "\(u.band)\(col)\(rowLetters[rowIndex])\(pad(ee))\(pad(nn))"
    }

    /// The centre of the MGRS square named (any precision from 100 km to 1 m).
    static func fromMGRS(_ text: String) -> (latitude: Double, longitude: Double)? {
        let s = text.uppercased().filter { !$0.isWhitespace }
        guard let m = s.range(of: #"^(\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\d*)$"#, options: .regularExpression) else { return nil }
        let chars = Array(s[m])
        var i = 0
        var zs = ""
        while i < chars.count, chars[i].isNumber { zs.append(chars[i]); i += 1 }
        guard let zone = Int(zs), (1...60).contains(zone), i + 3 <= chars.count else { return nil }
        let band = chars[i], colL = chars[i + 1], rowL = chars[i + 2]
        let digits = String(chars[(i + 3)...])
        guard digits.count % 2 == 0, digits.count <= 10, bands.contains(band),
              let colIdx = columnSets[(zone - 1) % 3].firstIndex(of: colL), let rowIdx = rowLetters.firstIndex(of: rowL) else { return nil }
        let half = digits.count / 2
        let size = pow(10.0, Double(5 - half))
        let e = Double(colIdx + 1) * 100_000 + (Double(digits.prefix(half)) ?? 0) * size + (half == 0 ? 50_000 : size / 2)
        var nPart = Double(((rowIdx - (zone % 2 == 0 ? 5 : 0)) % 20 + 20) % 20) * 100_000
            + (Double(digits.suffix(half)) ?? 0) * size + (half == 0 ? 50_000 : size / 2)
        // Northings repeat every 2,000 km: pick the cycle that matches the latitude band.
        guard let bandLat = bands.firstIndex(of: band).map({ Double($0) * 8 - 80 }) else { return nil }
        let probe = utm(latitude: bandLat + (bandLat >= 72 ? 0 : 0), longitude: centralMeridian(zone))
        var minN = probe?.northing ?? 0
        if bandLat < 0 { minN = (probe?.northing ?? 0) - 100_000 } else { minN -= 100_000 }
        while nPart < minN { nPart += 2_000_000 }
        return latLon(from: UTM(zone: zone, band: band, easting: e, northing: nPart))
    }

    // MARK: Maidenhead

    /// The centre of a 4, 6 or 8 character grid square.
    static func fromMaidenhead(_ text: String) -> (latitude: Double, longitude: Double)? {
        let s = text.trimmingCharacters(in: .whitespaces)
        guard s.range(of: #"^[A-Ra-r]{2}\d{2}([A-Xa-x]{2}(\d{2})?)?$"#, options: .regularExpression) != nil else { return nil }
        let c = Array(s.uppercased())
        func letter(_ ch: Character) -> Double { Double(ch.asciiValue! - 65) }
        var lon = letter(c[0]) * 20 - 180 + Double(c[2].wholeNumberValue!) * 2
        var lat = letter(c[1]) * 10 - 90 + Double(c[3].wholeNumberValue!)
        var lonStep = 2.0, latStep = 1.0
        if c.count >= 6 {
            lonStep = 2.0 / 24; latStep = 1.0 / 24
            lon += letter(c[4]) * lonStep; lat += letter(c[5]) * latStep
        }
        if c.count == 8 {
            lonStep /= 10; latStep /= 10
            lon += Double(c[6].wholeNumberValue!) * lonStep; lat += Double(c[7].wholeNumberValue!) * latStep
        }
        return (lat + latStep / 2, lon + lonStep / 2)
    }

    // MARK: Parse

    /// Reads whatever a person types or pastes. nil if it isn't a position.
    static func parse(_ raw: String) -> ParsedCoordinate? {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        // A map link: take the first "lat,lon" pair in it.
        if text.lowercased().contains("http"), let r = text.range(of: #"(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)"#, options: .regularExpression) {
            return parsePair(String(text[r]), format: .decimal)
        }
        text = text.replacingOccurrences(of: "\u{2212}", with: "-")
        if let r = fromMGRS(text), text.range(of: #"^\s*\d{1,2}\s*[C-HJ-NP-Xc-hj-np-x]\s*[A-HJ-NP-Za-hj-np-z]{2}\s*[\d\s]*$"#, options: .regularExpression) != nil,
           validPosition(r.latitude, r.longitude) {
            return ParsedCoordinate(latitude: r.latitude, longitude: r.longitude, format: .mgrs)
        }
        if let u = parseUTM(text) { return u }
        if let r = fromMaidenhead(text) { return ParsedCoordinate(latitude: r.latitude, longitude: r.longitude, format: .maidenhead) }
        return parsePair(text, format: nil)
    }

    private static func validPosition(_ lat: Double, _ lon: Double) -> Bool { abs(lat) <= 90 && abs(lon) <= 180 && lat.isFinite && lon.isFinite }

    private static func parseUTM(_ text: String) -> ParsedCoordinate? {
        let pattern = #"^\s*(\d{1,2})\s*([C-HJ-NP-Xc-hj-np-x])\s+(\d{5,7}(?:\.\d+)?)\s*m?\s*E?\s*[, ]\s*(\d{6,8}(?:\.\d+)?)\s*m?\s*N?\s*$"#
        guard let re = try? NSRegularExpression(pattern: pattern), let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
        func g(_ i: Int) -> String { (text as NSString).substring(with: m.range(at: i)) }
        guard let zone = Int(g(1)), (1...60).contains(zone), let e = Double(g(3)), let n = Double(g(4)) else { return nil }
        let band = Character(g(2).uppercased())
        guard bands.contains(band) else { return nil }
        let r = latLon(from: UTM(zone: zone, band: band, easting: e, northing: n))
        return validPosition(r.latitude, r.longitude) ? ParsedCoordinate(latitude: r.latitude, longitude: r.longitude, format: .utm) : nil
    }

    /// Splits "a b" into two angle expressions and reads each.
    private static func parsePair(_ raw: String, format forced: PositionFormat?) -> ParsedCoordinate? {
        var text = raw
        for word in ["latitude", "longitude", "lat", "lon", "lng", "long"] {
            text = text.replacingOccurrences(of: word, with: " ", options: [.caseInsensitive])
        }
        text = text.replacingOccurrences(of: "=", with: " ").replacingOccurrences(of: ":", with: " ")
        let parts: [String]
        if let comma = text.firstIndex(where: { $0 == "," || $0 == ";" }) {
            parts = [String(text[..<comma]), String(text[text.index(after: comma)...])]
        } else if let split = splitAfterFirstHemisphere(text) {
            parts = split
        } else {
            let numbers = text.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            guard numbers.count >= 2, numbers.count % 2 == 0 else { return nil }
            let half = numbers.count / 2
            parts = [numbers[..<half].joined(separator: " "), numbers[half...].joined(separator: " ")]
        }
        guard parts.count == 2, let a = angle(parts[0]), let b = angle(parts[1]) else { return nil }
        // Hemisphere letters decide which is which; otherwise latitude first.
        var lat = a, lon = b
        if a.axis == .lon || b.axis == .lat { lat = b; lon = a }
        guard lat.axis != .lon, lon.axis != .lat else { return nil }
        guard abs(lat.value) <= 90, abs(lon.value) <= 180 else { return nil }
        let fmt: PositionFormat = forced ?? (lat.parts >= 3 ? .dms : lat.parts == 2 ? .dmm : .decimal)
        return ParsedCoordinate(latitude: lat.value, longitude: lon.value, format: fmt)
    }

    private static func splitAfterFirstHemisphere(_ s: String) -> [String]? {
        let chars = Array(s)
        guard let idx = chars.firstIndex(where: { "NSEWnsew".contains($0) }) else { return nil }
        // "N 39 44 W 104 59": the letter leads. "39 44 N 104 59 W": it trails.
        let before = String(chars[..<idx]).trimmingCharacters(in: .whitespaces)
        if before.isEmpty {
            // Leading letters: split at the second hemisphere letter.
            guard let second = chars[(idx + 1)...].firstIndex(where: { "NSEWnsew".contains($0) }) else { return nil }
            return [String(chars[..<second]), String(chars[second...])]
        }
        return [String(chars[...idx]), String(chars[(idx + 1)...])]
    }

    private struct Angle { var value: Double; var axis: Axis?; var parts: Int }
    private enum Axis { case lat, lon }

    private static func angle(_ s: String) -> Angle? {
        var hemisphere: Character?
        var body = ""
        for ch in s {
            if "NSEWnsew".contains(ch) { hemisphere = Character(ch.uppercased()) } else { body.append(ch) }
        }
        // Degree, minute and second marks become spaces.
        let cleaned = body.map { "°º'′’\"″”,".contains($0) ? " " : $0 }
        let nums = String(cleaned).split(separator: " ").map(String.init)
        guard (1...3).contains(nums.count) else { return nil }
        var negative = false
        var values: [Double] = []
        for (i, n) in nums.enumerated() {
            guard let v = Double(n) else { return nil }
            if i == 0, n.hasPrefix("-") { negative = true }
            values.append(abs(v))
        }
        if values.count > 1, values[1] >= 60 { return nil }
        if values.count > 2, values[2] >= 60 { return nil }
        var total = values[0]
        if values.count > 1 { total += values[1] / 60 }
        if values.count > 2 { total += values[2] / 3600 }
        if hemisphere == "S" || hemisphere == "W" { negative = true }
        let axis: Axis? = hemisphere == "N" || hemisphere == "S" ? .lat : hemisphere == "E" || hemisphere == "W" ? .lon : nil
        return Angle(value: negative ? -total : total, axis: axis, parts: values.count)
    }

    // MARK: Format

    static func format(latitude: Double, longitude: Double, as f: PositionFormat) -> String {
        switch f {
        case .decimal:
            return String(format: "%.5f, %.5f", latitude, longitude)
        case .dms:
            func dms(_ v: Double, _ pos: String, _ neg: String) -> String {
                let a = abs(v)
                var d = Int(a)
                var m = Int((a - Double(d)) * 60)
                var s = ((a - Double(d)) * 60 - Double(m)) * 60
                if s >= 59.95 { s = 0; m += 1 }
                if m >= 60 { m = 0; d += 1 }
                return String(format: "%d°%02d'%04.1f\"%@", d, m, s, v < 0 ? neg : pos)
            }
            return dms(latitude, "N", "S") + " " + dms(longitude, "E", "W")
        case .dmm:
            func dmm(_ v: Double, _ pos: String, _ neg: String) -> String {
                let a = abs(v)
                var d = Int(a)
                var m = (a - Double(d)) * 60
                if m >= 59.9995 { m = 0; d += 1 }
                return String(format: "%d°%06.3f'%@", d, m, v < 0 ? neg : pos)
            }
            return dmm(latitude, "N", "S") + " " + dmm(longitude, "E", "W")
        case .utm:
            guard let u = utm(latitude: latitude, longitude: longitude) else { return "Outside UTM (poles)" }
            return "\(u.zone)\(u.band) \(Int(u.easting))E \(Int(u.northing))N"
        case .mgrs:
            guard let m = mgrs(latitude: latitude, longitude: longitude) else { return "Outside MGRS (poles)" }
            let chars = Array(m)
            let head = chars.prefix(while: { $0.isNumber })
            let rest = chars.dropFirst(head.count)
            return "\(String(head))\(rest.first!) \(String(rest.dropFirst().prefix(2))) \(String(rest.dropFirst(3).prefix(5))) \(String(rest.dropFirst(8)))"
        case .maidenhead:
            return HamGeo.maidenhead(lat: latitude, lon: longitude)
        }
    }
}

extension ParsedCoordinate: Identifiable {
    var id: String { "\(latitude),\(longitude)" }
}
