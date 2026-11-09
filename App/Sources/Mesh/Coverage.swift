// Coverage.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The coverage scanner: each time a radio hears another node, note where the
// phone was. With two radios connected (one Meshtastic, one MeshCore) both
// record at once, and every sample and ping carries its network, so the map
// can show or filter each one. That maps where the mesh reaches, which is how you pick spots for
// repeaters. The map only ever draws them binned into hexagons, never as
// points: a trail of dots would map your drive, not the mesh. Everything stays on the phone unless the user turns sharing on,
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

/// What came back from one drive-test ping.
enum MeshPingOutcome: String, Codable, Sendable {
    /// The radio you pinged answered: messages get through both ways.
    case reached
    /// Another radio repeated it, so the mesh heard you, but no answer came back.
    case relayed
    /// Nothing heard it.
    case none

    /// Better outcomes rank higher: reached, then relayed, then none.
    var rank: Int {
        switch self {
        case .reached: 2
        case .relayed: 1
        case .none: 0
        }
    }
}

/// One drive-test ping: where the phone was when it was sent, and what came back.
struct CoveragePing: Codable, Equatable, Sendable {
    var date: Date
    var latitude: Double
    var longitude: Double
    var accuracy: Double
    var firmware: MeshFirmware
    var outcome: MeshPingOutcome
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

extension CoverageZone {
    /// Lenient: a zone from another app may have no id, or one that isn't a UUID.
    init(from decoder: Decoder) throws {
        enum Keys: String, CodingKey { case id, name, latitude, longitude, radius }
        let c = try decoder.container(keyedBy: Keys.self)
        id = (try? c.decodeIfPresent(UUID.self, forKey: .id)) ?? UUID()
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Private place"
        latitude = try c.decode(Double.self, forKey: .latitude)
        longitude = try c.decode(Double.self, forKey: .longitude)
        radius = try c.decodeIfPresent(Double.self, forKey: .radius) ?? 500
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
    private(set) var pings: [CoveragePing] = []
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
    private let pingsURL: URL
    private let location: () -> CLLocation?
    private let nodeName: (UInt32, MeshFirmware) -> String
    private let isOwnRadio: (UInt32, MeshFirmware) -> Bool
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
         nodeName: @escaping (UInt32, MeshFirmware) -> String = { MeshService.shared.link($1).nodes[$0]?.longName ?? "" },
         isOwnRadio: @escaping (UInt32, MeshFirmware) -> Bool = { MeshService.shared.isOwnRadio($0, on: $1) },
         now: @escaping () -> Date = Date.init,
         controlsGPS: Bool = true) {
        self.defaults = defaults
        self.controlsGPS = controlsGPS
        self.fileURL = fileURL
        pingsURL = fileURL.deletingPathExtension().appendingPathExtension("pings.json")
        self.location = location
        self.nodeName = nodeName
        self.isOwnRadio = isOwnRadio
        self.now = now
        recording = defaults.bool(forKey: Keys.recording)
        sharing = defaults.bool(forKey: Keys.sharing)
        retentionDays = defaults.object(forKey: Keys.retention) as? Int ?? 30
        zones = (defaults.data(forKey: Keys.zones)).flatMap { try? JSONDecoder().decode([CoverageZone].self, from: $0) } ?? []
        let saved = Self.readSaved(fileURL, pingsURL)
        samples = saved?.samples ?? []
        pings = saved?.pings ?? []
        savedWasRead = saved != nil
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
    /// fresh, reasonably accurate fix; one sample per node (per network) per
    /// 20 s unless the phone has moved more than 25 m. The phone's own radios
    /// (either network) are never recorded.
    func record(_ heard: MeshHeard) {
        guard recording, !isOwnRadio(heard.node, heard.firmware), !optedOut(heard.node, heard.firmware),
              let fix = freshFix() else { return }
        let t = now()
        let lat = fix.coordinate.latitude, lon = fix.coordinate.longitude
        if let last = samples.last(where: { $0.node == heard.node && $0.firmware == heard.firmware }),
           t.timeIntervalSince(last.date) < 20,
           CoverageShare.distance(last.latitude, last.longitude, lat, lon) < 25 { return }
        samples.append(CoverageSample(date: t, latitude: lat, longitude: lon, accuracy: fix.horizontalAccuracy,
                                      firmware: heard.firmware, node: heard.node, snr: heard.snr, hops: heard.hops))
        scheduleSave()
    }

    /// A GPS fix from the last 30 s that is good to 100 m, or nil.
    func freshFix() -> CLLocation? {
        guard let fix = location(), now().timeIntervalSince(fix.timestamp) < 30,
              fix.horizontalAccuracy >= 0, fix.horizontalAccuracy <= 100 else { return nil }
        return fix
    }

    /// Adds a drive-test result at the place the ping was sent from.
    func recordPing(_ outcome: MeshPingOutcome, at fix: CLLocation, firmware: MeshFirmware) {
        pings.append(CoveragePing(date: now(), latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude,
                                  accuracy: fix.horizontalAccuracy, firmware: firmware, outcome: outcome))
        scheduleSave()
    }

    /// Radios whose owners put a no-mapping sign in their name (a convention
    /// used by community mesh maps) are never recorded or shared.
    /// Checked by scalar, so the emoji counts with or without a variation selector.
    nonisolated static let optOutMarks: Set<UInt32> = [0x26D4, 0x1F6D1, 0x1F6AB] // ⛔ 🛑 🚫

    nonisolated static func optsOut(_ name: String) -> Bool { name.unicodeScalars.contains { optOutMarks.contains($0.value) } }

    func optedOut(_ node: UInt32, _ firmware: MeshFirmware) -> Bool { Self.optsOut(nodeName(node, firmware)) }

    func deleteAll() {
        samples.removeAll()
        pings.removeAll()
        saveNow()
    }

    /// Adds a backup's points and zones to what is here, skipping ones already
    /// present. Nothing here is ever removed. Retention is raised (never
    /// lowered) so the restored points survive the next prune, up to the longest
    /// choice. Returns how many samples, pings and zones were new.
    @discardableResult
    func merge(samples newSamples: [CoverageSample], pings newPings: [CoveragePing], zones newZones: [CoverageZone],
               retentionDays restoredRetention: Int? = nil) -> (samples: Int, pings: Int, zones: Int) {
        // Saved points that couldn't be read yet are merged in first, as saveNow does.
        if !savedWasRead, let saved = Self.readSaved(fileURL, pingsURL) {
            samples = (saved.samples + samples).sorted { $0.date < $1.date }
            pings = (saved.pings + pings).sorted { $0.date < $1.date }
            savedWasRead = true
        }
        let s = CoverageBackup.merge(samples, newSamples)
        let p = CoverageBackup.merge(pings, newPings)
        let z = CoverageBackup.merge(zones, newZones)
        let oldest = (s.merged.first?.date ?? .distantFuture, p.merged.first?.date ?? .distantFuture)
        let ageDays = now().timeIntervalSince(min(oldest.0, oldest.1)) / 86_400
        let needed = Self.retentionChoices.first { Double($0) >= ageDays } ?? Self.retentionChoices.last!
        let wanted = max(retentionDays, restoredRetention ?? 0, needed)
        samples = s.merged
        pings = p.merged
        if z.added > 0 { zones = z.merged; saveZones() }
        // Setting retention prunes, so it goes in after the merge; anything older than the longest choice goes then.
        if wanted != retentionDays { retentionDays = min(wanted, Self.retentionChoices.last!) } else { prune() }
        saveNow()
        return (s.added, p.added, z.added)
    }

    /// Changes whenever the points or zones do, so a backup can tell whether there is anything new.
    var fingerprint: String {
        "\(samples.count)|\(pings.count)|\(zones.count)|\(samples.last?.date.timeIntervalSince1970 ?? 0)|\(pings.last?.date.timeIntervalSince1970 ?? 0)"
    }

    var isEmpty: Bool { samples.isEmpty && pings.isEmpty }

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
    var sharedCells: [CoverageShare.Cell] {
        sharedCells(for: nil)
    }

    /// The same, for one network only (nil: both).
    func sharedCells(for network: MeshFirmware?) -> [CoverageShare.Cell] {
        CoverageShare.cells(from: samples.filter { !optedOut($0.node, $0.firmware) && (network == nil || $0.firmware == network) },
                            pings: pings.filter { network == nil || $0.firmware == network }, zones: zones)
    }

    /// The private map view: every recording binned into hexagons (private
    /// zones included, since it never leaves the phone). Signal only; pings are
    /// in `pingCells(for:)`. Hexagons, never points, so the map shows where the
    /// mesh reaches rather than the route you drove.
    func privateCells(for network: MeshFirmware?) -> [CoverageShare.Cell] {
        CoverageShare.cells(from: samples.filter { !optedOut($0.node, $0.firmware) && (network == nil || $0.firmware == network) },
                            zones: [])
    }

    /// Drive-test pings binned into the same hexagons: the best outcome per
    /// hexagon and network. The raw pings stay on the phone.
    func pingCells(for network: MeshFirmware?) -> [CoverageShare.PingCell] {
        CoverageShare.pingCells(from: pings.filter { network == nil || $0.firmware == network })
    }

    /// How many samples and pings each network has recorded.
    func counts(_ network: MeshFirmware) -> (samples: Int, pings: Int) {
        (samples.count { $0.firmware == network }, pings.count { $0.firmware == network })
    }

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
        // Drive-test pings from both radios, alternating along one route.
        pings = (0 ..< 12).map { i in
            let d = Double(i) * 0.004
            let outcome: MeshPingOutcome = i < 5 ? .reached : (i < 9 ? .relayed : .none)
            return CoveragePing(date: base.addingTimeInterval(Double(i) * 60), latitude: tower.lat + d * 0.6,
                                longitude: tower.lon - d, accuracy: 6,
                                firmware: i.isMultiple(of: 2) ? .meshtastic : .meshcore, outcome: outcome)
        }
        zones = [CoverageZone(name: "Home (demo)", latitude: 39.7392, longitude: -104.9849, radius: 500)]
        saveZones()
        saveNow()
    }
    #endif

    private func prune() {
        let cutoff = now().addingTimeInterval(-Double(retentionDays) * 86_400)
        let before = samples.count
        samples.removeAll { $0.date < cutoff }
        let pingsBefore = pings.count
        pings.removeAll { $0.date < cutoff }
        if samples.count != before || pings.count != pingsBefore { scheduleSave() }
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
        // Saved points that couldn't be read at launch are merged in first, never written over.
        if !savedWasRead {
            guard let saved = Self.readSaved(fileURL, pingsURL) else { return }
            samples = (saved.samples + samples).sorted { $0.date < $1.date }
            pings = (saved.pings + pings).sorted { $0.date < $1.date }
            savedWasRead = true
        }
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Writable while the phone is locked, which is how a drive usually goes.
        try? JSONEncoder().encode(samples).write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        try? JSONEncoder().encode(pings).write(to: pingsURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// False while saved files exist that couldn't be read (iOS relaunched the app
    /// in the background before the phone was first unlocked).
    private var savedWasRead = true

    /// The saved samples and pings, empty when there are none; nil when a file
    /// exists but can't be read yet. A file that reads but doesn't decode counts as empty.
    private static func readSaved(_ samplesURL: URL, _ pingsURL: URL) -> (samples: [CoverageSample], pings: [CoveragePing])? {
        func read<T: Decodable>(_ url: URL) -> [T]? {
            guard FileManager.default.fileExists(atPath: url.path) else { return [] }
            guard let data = try? Data(contentsOf: url) else { return nil }
            return (try? JSONDecoder().decode([T].self, from: data)) ?? []
        }
        guard let s: [CoverageSample] = read(samplesURL), let p: [CoveragePing] = read(pingsURL) else { return nil }
        return (s, p)
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
        var pingsReached = 0
        var pingsRelayed = 0
        var pingsNone = 0
        var firstMonth: String
        var lastMonth: String

        var center: (latitude: Double, longitude: Double) { CoverageShare.center(q: q, r: r) }
        var corners: [(latitude: Double, longitude: Double)] { CoverageShare.corners(q: q, r: r) }
    }

    /// One network's drive-test result for one hexagon: the best that came back
    /// from any ping sent inside it.
    struct PingCell: Equatable, Sendable {
        var q: Int
        var r: Int
        var firmware: MeshFirmware
        var best: MeshPingOutcome
        var count: Int

        var center: (latitude: Double, longitude: Double) { CoverageShare.center(q: q, r: r) }
        /// The hexagon's corners, shrunk toward its centre by `scale` (1: the whole hexagon).
        func corners(scale: Double = 1) -> [(latitude: Double, longitude: Double)] { CoverageShare.corners(q: q, r: r, scale: scale) }
    }

    /// Bins pings into hexagons per network, keeping the best outcome
    /// (reached beats relayed beats none). Pings in or near a private zone are
    /// dropped by the same rule as `cells`. No per-ping position survives.
    static func pingCells(from pings: [CoveragePing], zones: [CoverageZone] = []) -> [PingCell] {
        var bins: [String: PingCell] = [:]
        for p in pings {
            guard let (q, r) = hexOutsideZones(latitude: p.latitude, longitude: p.longitude, zones: zones) else { continue }
            let key = "\(q),\(r),\(p.firmware.rawValue)"
            var cell = bins[key] ?? PingCell(q: q, r: r, firmware: p.firmware, best: p.outcome, count: 0)
            cell.count += 1
            if p.outcome.rank > cell.best.rank { cell.best = p.outcome }
            bins[key] = cell
        }
        return bins.values.sorted { ($0.q, $0.r, $0.firmware) < ($1.q, $1.r, $1.firmware) }
    }

    /// The hexagon a point falls in, or nil when the point is in a private zone
    /// or its hexagon reaches into one.
    private static func hexOutsideZones(latitude lat: Double, longitude lon: Double, zones: [CoverageZone]) -> (q: Int, r: Int)? {
        if zones.contains(where: { $0.contains(latitude: lat, longitude: lon) }) { return nil }
        let (q, r) = hex(latitude: lat, longitude: lon)
        let c = center(q: q, r: r)
        // A cell's corners are at most one cell size from its centre.
        if zones.contains(where: { distance($0.latitude, $0.longitude, c.latitude, c.longitude) <= $0.radius + cellSize }) { return nil }
        return (q, r)
    }

    /// Bins samples into hexagons, dropping anything in or near a private zone:
    /// both samples inside a zone and whole cells that touch one, so a cell that
    /// straddles your street doesn't give it away.
    static func cells(from samples: [CoverageSample], pings: [CoveragePing] = [], zones: [CoverageZone]) -> [Cell] {
        var bins: [String: Cell] = [:]
        let month = DateFormatter()
        month.locale = Locale(identifier: "en_US_POSIX")
        month.timeZone = TimeZone(identifier: "UTC")
        month.dateFormat = "yyyy-MM"
        // Adds one point to its hexagon, unless it is in or near a private zone.
        func add(_ lat: Double, _ lon: Double, _ date: Date, _ firmware: MeshFirmware, _ update: (inout Cell) -> Void) {
            guard let (q, r) = hexOutsideZones(latitude: lat, longitude: lon, zones: zones) else { return }
            let m = month.string(from: date)
            let key = "\(q),\(r)"
            var cell = bins[key] ?? Cell(q: q, r: r, count: 0, bestSNR: nil, firmwares: [], firstMonth: m, lastMonth: m)
            update(&cell)
            cell.firmwares.insert(firmware)
            cell.firstMonth = min(cell.firstMonth, m)
            cell.lastMonth = max(cell.lastMonth, m)
            bins[key] = cell
        }
        for s in samples {
            add(s.latitude, s.longitude, s.date, s.firmware) { cell in
                cell.count += 1
                if let snr = s.snr { cell.bestSNR = max(cell.bestSNR ?? -.infinity, snr) }
            }
        }
        for p in pings {
            add(p.latitude, p.longitude, p.date, p.firmware) { cell in
                switch p.outcome {
                case .reached: cell.pingsReached += 1
                case .relayed: cell.pingsRelayed += 1
                case .none: cell.pingsNone += 1
                }
            }
        }
        return bins.values.sorted { ($0.q, $0.r) < ($1.q, $1.r) }
    }

    /// GeoJSON hexagons with only best signal, count, drive-test ping counts,
    /// firmware and month range.
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
            if cell.pingsReached + cell.pingsRelayed + cell.pingsNone > 0 {
                props["pings_reached"] = cell.pingsReached
                props["pings_relayed"] = cell.pingsRelayed
                props["pings_none"] = cell.pingsNone
            }
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

    static func corners(q: Int, r: Int, scale: Double = 1) -> [(latitude: Double, longitude: Double)] {
        let cx = cellSize * sqrt(3) * (Double(q) + Double(r) / 2)
        let cy = cellSize * 1.5 * Double(r)
        let size = cellSize * scale
        return (0 ..< 6).map { i in
            let a = (60 * Double(i) - 30) * .pi / 180
            return unmercator(cx + size * cos(a), cy + size * sin(a))
        }
    }

    private static func round6(_ v: Double) -> Double { (v * 1e6).rounded() / 1e6 }
}
