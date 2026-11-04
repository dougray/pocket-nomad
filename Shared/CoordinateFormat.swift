import CoreLocation

/// Position formats shared by the iPhone and Apple Watch apps (Shared/ is compiled into both).
enum CoordinateFormat {
    static func decimal(_ c: CLLocationCoordinate2D) -> String {
        String(format: "%.5f, %.5f", c.latitude, c.longitude)
    }

    static func dms(_ c: CLLocationCoordinate2D) -> String {
        func part(_ v: Double, _ pos: String, _ neg: String) -> String {
            let a = abs(v)
            let d = Int(a), m = Int((a - Double(d)) * 60), s = (a - Double(d) - Double(m) / 60) * 3600
            return String(format: "%d°%02d′%04.1f″%@", d, m, s, v >= 0 ? pos : neg)
        }
        return "\(part(c.latitude, "N", "S")) \(part(c.longitude, "E", "W"))"
    }

    /// MGRS (the grid search-and-rescue teams use), 1 m precision. Valid 80°S–84°N.
    static func mgrs(_ c: CLLocationCoordinate2D) -> String? {
        let lat = c.latitude, lon = c.longitude
        guard lat >= -80, lat <= 84 else { return nil }
        var zone = Int((lon + 180) / 6) + 1
        if lat >= 56 && lat < 64 && lon >= 3 && lon < 12 { zone = 32 }
        if lat >= 72 && lat < 84 {
            if lon >= 0 && lon < 9 { zone = 31 } else if lon >= 9 && lon < 21 { zone = 33 }
            else if lon >= 21 && lon < 33 { zone = 35 } else if lon >= 33 && lon < 42 { zone = 37 }
        }
        let (e, n) = utm(lat: lat, lon: lon, zone: zone)
        let bands = Array("CDEFGHJKLMNPQRSTUVWXX")
        let band = bands[min(20, max(0, Int((lat + 80) / 8)))]
        let colSets = [Array("ABCDEFGH"), Array("JKLMNPQR"), Array("STUVWXYZ")]
        let rowLetters = Array("ABCDEFGHJKLMNPQRSTUV")
        let set = (zone - 1) % 3
        let col = colSets[set][(Int(e / 100_000) - 1) % 8]
        let rowOffset = zone % 2 == 0 ? 5 : 0
        let row = rowLetters[(Int(n / 100_000) % 20 + rowOffset) % 20]
        let east = Int(e) % 100_000, north = Int(n) % 100_000
        return String(format: "%d%@ %@%@ %05d %05d", zone, String(band), String(col), String(row), east, north)
    }

    private static func utm(lat: Double, lon: Double, zone: Int) -> (Double, Double) {
        let a = 6_378_137.0, f = 1 / 298.257223563, k0 = 0.9996
        let e2 = f * (2 - f), ep2 = e2 / (1 - e2)
        let phi = lat * .pi / 180
        let lon0 = Double((zone - 1) * 6 - 180 + 3) * .pi / 180
        let N = a / sqrt(1 - e2 * sin(phi) * sin(phi))
        let T = tan(phi) * tan(phi), C = ep2 * cos(phi) * cos(phi)
        let A = cos(phi) * (lon * .pi / 180 - lon0)
        let M = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * phi
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * phi)
            + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * phi)
            - (35 * e2 * e2 * e2 / 3072) * sin(6 * phi))
        let easting = k0 * N * (A + (1 - T + C) * pow(A, 3) / 6 + (5 - 18 * T + T * T + 72 * C - 58 * ep2) * pow(A, 5) / 120) + 500_000
        var northing = k0 * (M + N * tan(phi) * (A * A / 2 + (5 - T + 9 * C + 4 * C * C) * pow(A, 4) / 24
            + (61 - 58 * T + T * T + 600 * C - 330 * ep2) * pow(A, 6) / 720))
        if lat < 0 { northing += 10_000_000 }
        return (easting, northing)
    }

    static func cardinal(_ degrees: Double) -> String {
        let names = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
        return names[Int((degrees / 22.5).rounded()) % 16]
    }
}
