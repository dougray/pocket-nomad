// Coverage.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The coverage scanner: each time the radio hears another node, note where the
// phone was. That maps where the mesh reaches, which is how you pick spots for
// repeaters. Everything stays on the phone unless the user turns sharing on,
// and even then only a privacy-filtered summary (hexagons, no node names or
// IDs, no times finer than a month, private zones blanked) is ever written out.

import CoreLocation
import Foundation
import Observation

/// One "heard a node here" moment. Kept only on this phone.
struct CoverageSample: Codable, Equatable, Sendable {
    var date: Date
    var latitude: Double
    var longitude: Double
    /// GPS accuracy in metres.
    var accuracy: Double
    var firmware: MeshFirmware
    var node: UInt32
    var snr: Float?
    var hops: UInt32?
}

/// A place never to share anything from (home, work).
struct CoverageZone: Codable, Identifiable, Equatable, Sendable {
    var id = UUID()
    var name: String
    var latitude: Double
    var longitude: Double
    var radius: Double

    func contains(latitude lat: Double, longitude lon: Double) -> Bool {
        CoverageShare.distance(latitude, longitude, lat, lon) <= radius
    }
}

/// A live "the radio just heard this node" event from MeshService.
struct MeshHeard: Sendable {
    var node: UInt32
    var snr: Float?
    var hops: UInt32?
    var firmware: MeshFirmware
}

@MainActor @Observable
final class CoverageStore {
    static let shared = CoverageStore()

    private(set) var samples: [CoverageSample] = []
    private(set) var zones: [CoverageZone] = []

    /// Off by default. Needs location while the app is open.
    var recording: Bool {
        didSet {
            defaults.set(recording, forKey: Keys.recording)
            updateLocationUse()
        }
    }
    /// Off by default; can only be turned on once at least one private zone exists.
    var sharing: Bool {
        didSet {
            if sharing && zones.isEmpty { sharing = false; return }
            defaults.set(sharing, forKey: Keys.sharing)
        }
    }
    var retentionDays: Int {
        didSet { defaults.set(retentionDays, forKey: Keys.retention); prune() }
    }

    static let retentionChoices = [7, 30, 90, 365]

    private let defaults: UserDefaults
    private let fileURL: URL
    private let location: () -> CLLocation?
    private let now: () -> Date
    private let controlsGPS: Bool
    private var usingLocation = false
    private var saveTask: Task<Void, Never>?

    private enum Keys {
        static let recording = "coverage.recording"
        static let sharing = "coverage.sharing"
        static let retention = "coverage.retentionDays"
        static let zones = "coverage.zones"
    }

    init(defaults: UserDefaults = .standard,
         fileURL: URL = URL.applicationSupportDirectory.appending(path: "coverage.json"),
         location: @escaping () -> CLLocation? = { LocationService.shared.location },
         now: @escaping () -> Date = Date.init,
         controlsGPS: Bool = true) {
        self.defaults = defaults
        self.controlsGPS = controlsGPS
        self.fileURL = fileURL
        self.location = location
        self.now = now
        recording = defaults.bool(forKey: Keys.recording)
        sharing = defaults.bool(forKey: Keys.sharing)
        retentionDays = defaults.object(forKey: Keys.retention) as? Int ?? 30
        zones = (defaults.data(forKey: Keys.zones)).flatMap { try? JSONDecoder().decode([CoverageZone].self, from: $0) } ?? []
        samples = (try? JSONDecoder().decode([CoverageSample].self, from: Data(contentsOf: fileURL))) ?? []
        if zones.isEmpty { sharing = false }
        prune()
    }

    /// Starts GPS when recording is on (call once at launch).
    func start() { updateLocationUse() }

    private func updateLocationUse() {
        guard controlsGPS else { return }
        if recording && !usingLocation { LocationService.shared.begin(); usingLocation = true }
        if !recording && usingLocation { LocationService.shared.end(); usingLocation = false }
    }

    /// Notes where the phone is now. Ignored when recording is off or there's no
    /// fresh, reasonably accurate fix; one sample per node per 20 s unless the
    /// phone has moved more than 25 m.
    func record(_ heard: MeshHeard) {
        guard recording, let fix = location() else { return }
        let t = now()
        guard t.timeIntervalSince(fix.timestamp) < 30, fix.horizontalAccuracy >= 0, fix.horizontalAccuracy <= 100 else { return }
        let lat = fix.coordinate.latitude, lon = fix.coordinate.longitude
        if let last = samples.last(where: { $0.node == heard.node }),
           t.timeIntervalSince(last.date) < 20,
           CoverageShare.distance(last.latitude, last.longitude, lat, lon) < 25 { return }
        samples.append(CoverageSample(date: t, latitude: lat, longitude: lon, accuracy: fix.horizontalAccuracy,
                                      firmware: heard.firmware, node: heard.node, snr: heard.snr, hops: heard.hops))
        scheduleSave()
    }

    func deleteAll() {
        samples.removeAll()
        saveNow()
    }

    func addZone(name: String, latitude: Double, longitude: Double, radius: Double) {
        zones.append(CoverageZone(name: name, latitude: latitude, longitude: longitude, radius: radius))
        saveZones()
    }

    func removeZones(at offsets: IndexSet) {
        zones.remove(atOffsets: offsets)
        saveZones()
        if zones.isEmpty { sharing = false }
    }

    /// What sharing would publish, built from the current samples and zones.
    var sharedCells: [CoverageShare.Cell] { CoverageShare.cells(from: samples, zones: zones) }

    /// Writes the privacy-filtered GeoJSON to a temporary file for the share sheet.
    func exportFile() throws -> URL {
        guard sharing else { throw CocoaError(.userCancelled) }
        let url = FileManager.default.temporaryDirectory.appending(path: "pocket-nomad-coverage.geojson")
        try CoverageShare.geoJSON(sharedCells).write(to: url, options: .atomic)
        return url
    }

    #if DEBUG
    /// Screenshots and demos: a drive around a pretend tower in downtown Denver,
    /// with signal fading with distance, and a private zone at the start.
    func loadDemo() {
        let tower = (lat: 39.7480, lon: -104.9970)
        var out: [CoverageSample] = []
        var rng = SystemRandomNumberGenerator()
        let base = now().addingTimeInterval(-3 * 86_400)
        for route in 0 ..< 6 {
            let angle = Double(route) * .pi / 3 + 0.4
            for step in 0 ..< 40 {
                let d = Double(step) * 0.0011
                let lat = tower.lat + d * sin(angle) + Double.random(in: -0.0004 ... 0.0004, using: &rng)
                let lon = tower.lon + d * cos(angle) * 1.3 + Double.random(in: -0.0004 ... 0.0004, using: &rng)
                let km = CoverageShare.distance(tower.lat, tower.lon, lat, lon) / 1000
                let snr = Float(9 - km * 5 + Double.random(in: -2 ... 2, using: &rng))
                if snr < -18 { continue }
                out.append(CoverageSample(date: base.addingTimeInterval(Double(route * 40 + step) * 30), latitude: lat, longitude: lon,
                                          accuracy: 6, firmware: route.isMultiple(of: 2) ? .meshtastic : .meshcore,
                                          node: UInt32(0xA000 + route % 3), snr: snr, hops: 0))
            }
        }
        samples = out
        zones = [CoverageZone(name: "Home (demo)", latitude: 39.7392, longitude: -104.9849, radius: 500)]
        saveZones()
        saveNow()
    }
    #endif

    private func prune() {
        let cutoff = now().addingTimeInterval(-Double(retentionDays) * 86_400)
        let before = samples.count
        samples.removeAll { $0.date < cutoff }
        if samples.count != before { scheduleSave() }
    }

    private func saveZones() {
        defaults.set(try? JSONEncoder().encode(zones), forKey: Keys.zones)
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    func saveNow() {
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(samples).write(to: fileURL, options: [.atomic, .completeFileProtection])
    }
}

/// Turns private samples into the shareable summary. Pure functions, so the
/// privacy rules are easy to test.
enum CoverageShare {
    /// Hexagon size on a global Web Mercator grid: the same everywhere, so files
    /// from different people line up. 400 Mercator metres is about 350 m across
    /// on the ground in Texas.
    static let cellSize = 400.0
    private static let earthRadius = 6_378_137.0

    struct Cell: Equatable, Sendable {
        var q: Int
        var r: Int
        var count: Int
        var bestSNR: Float?
        var firmwares: Set<MeshFirmware>
        var firstMonth: String
        var lastMonth: String

        var center: (latitude: Double, longitude: Double) { CoverageShare.center(q: q, r: r) }
        var corners: [(latitude: Double, longitude: Double)] { CoverageShare.corners(q: q, r: r) }
    }

    /// Bins samples into hexagons, dropping anything in or near a private zone:
    /// both samples inside a zone and whole cells that touch one, so a cell that
    /// straddles your street doesn't give it away.
    static func cells(from samples: [CoverageSample], zones: [CoverageZone]) -> [Cell] {
        let reach = cellSize // a cell's corners are at most one cell size from its centre
        var bins: [String: Cell] = [:]
        let month = DateFormatter()
        month.locale = Locale(identifier: "en_US_POSIX")
        month.timeZone = TimeZone(identifier: "UTC")
        month.dateFormat = "yyyy-MM"
        for s in samples {
            if zones.contains(where: { $0.contains(latitude: s.latitude, longitude: s.longitude) }) { continue }
            let (q, r) = hex(latitude: s.latitude, longitude: s.longitude)
            let c = center(q: q, r: r)
            if zones.contains(where: { distance($0.latitude, $0.longitude, c.latitude, c.longitude) <= $0.radius + reach }) { continue }
            let m = month.string(from: s.date)
            let key = "\(q),\(r)"
            var cell = bins[key] ?? Cell(q: q, r: r, count: 0, bestSNR: nil, firmwares: [], firstMonth: m, lastMonth: m)
            cell.count += 1
            if let snr = s.snr { cell.bestSNR = max(cell.bestSNR ?? -.infinity, snr) }
            cell.firmwares.insert(s.firmware)
            cell.firstMonth = min(cell.firstMonth, m)
            cell.lastMonth = max(cell.lastMonth, m)
            bins[key] = cell
        }
        return bins.values.sorted { ($0.q, $0.r) < ($1.q, $1.r) }
    }

    /// GeoJSON hexagons with only best signal, count, firmware and month range.
    static func geoJSON(_ cells: [Cell]) throws -> Data {
        let features: [[String: Any]] = cells.map { cell in
            var ring = cell.corners.map { [round6($0.longitude), round6($0.latitude)] }
            ring.append(ring[0])
            var props: [String: Any] = [
                "count": cell.count,
                "firmware": cell.firmwares.map(\.rawValue).sorted(),
                "first_month": cell.firstMonth,
                "last_month": cell.lastMonth,
            ]
            if let snr = cell.bestSNR { props["best_snr"] = (Double(snr) * 10).rounded() / 10 }
            return ["type": "Feature", "geometry": ["type": "Polygon", "coordinates": [ring]], "properties": props]
        }
        let doc: [String: Any] = [
            "type": "FeatureCollection",
            "generator": "Pocket Nomad coverage export",
            "cell_size_mercator_m": cellSize,
            "features": features,
        ]
        return try JSONSerialization.data(withJSONObject: doc, options: [.prettyPrinted, .sortedKeys])
    }

    // MARK: Geometry

    static func distance(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180
        let dp = p2 - p1, dl = (lon2 - lon1) * .pi / 180
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private static func mercator(_ lat: Double, _ lon: Double) -> (x: Double, y: Double) {
        let clamped = max(-85, min(85, lat))
        return (earthRadius * lon * .pi / 180, earthRadius * log(tan(.pi / 4 + clamped * .pi / 360)))
    }

    private static func unmercator(_ x: Double, _ y: Double) -> (latitude: Double, longitude: Double) {
        ((2 * atan(exp(y / earthRadius)) - .pi / 2) * 180 / .pi, x / earthRadius * 180 / .pi)
    }

    /// Pointy-top axial hex coordinates for a point.
    static func hex(latitude: Double, longitude: Double) -> (q: Int, r: Int) {
        let (x, y) = mercator(latitude, longitude)
        let fq = (sqrt(3) / 3 * x - y / 3) / cellSize
        let fr = (2.0 / 3 * y) / cellSize
        // cube rounding
        let fs = -fq - fr
        var q = fq.rounded(), r = fr.rounded()
        let s = fs.rounded()
        let dq = abs(q - fq), dr = abs(r - fr), ds = abs(s - fs)
        if dq > dr && dq > ds { q = -r - s } else if dr > ds { r = -q - s }
        return (Int(q), Int(r))
    }

    static func center(q: Int, r: Int) -> (latitude: Double, longitude: Double) {
        let x = cellSize * sqrt(3) * (Double(q) + Double(r) / 2)
        let y = cellSize * 1.5 * Double(r)
        return unmercator(x, y)
    }

    static func corners(q: Int, r: Int) -> [(latitude: Double, longitude: Double)] {
        let cx = cellSize * sqrt(3) * (Double(q) + Double(r) / 2)
        let cy = cellSize * 1.5 * Double(r)
        return (0 ..< 6).map { i in
            let a = (60 * Double(i) - 30) * .pi / 180
            return unmercator(cx + cellSize * cos(a), cy + cellSize * sin(a))
        }
    }

    private static func round6(_ v: Double) -> Double { (v * 1e6).rounded() / 1e6 }
}
