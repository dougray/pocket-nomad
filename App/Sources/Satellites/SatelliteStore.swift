// SatelliteStore.swift
// Pocket Nomad - Satellites module
// SPDX-License-Identifier: Apache-2.0
//
// Orbit data (two-line element sets) for the space station and amateur radio
// satellites from CelesTrak, downloaded when the user taps Update and kept on
// the phone. Predicting passes needs no network at all: the sets stay good for
// roughly two weeks. Free, no key, and no location is ever sent.

import CoreLocation
import Foundation
import Observation

@MainActor
@Observable
final class SatelliteStore {
    static let shared = SatelliteStore()

    private(set) var satellites: [TLE] = []
    private(set) var fetchedAt: Date?
    private(set) var updating = false
    private(set) var error: String?
    /// Passes for the favourites, soonest first.
    private(set) var passes: [SatellitePass] = []
    private(set) var observer: Observer?
    private(set) var computing = false

    private let directory: URL
    private let session: URLSession
    private let locate: () async -> CLLocation?
    private let defaults: UserDefaults
    var now: () -> Date = { Date() }

    static let issID = 25544
    static let groups = ["stations", "amateur"]

    init(directory: URL = SatelliteStore.defaultDirectory, session: URLSession = .shared, defaults: UserDefaults = .standard,
         locate: (() async -> CLLocation?)? = nil) {
        self.directory = directory
        self.session = session
        self.defaults = defaults
        let provider = MeshLocationProvider()
        self.locate = locate ?? { await provider.currentLocation(timeout: 10) }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var d = directory
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? d.setResourceValues(rv)
        if let data = try? Data(contentsOf: directory.appending(path: "elements.json")),
           let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            satellites = saved.satellites
            fetchedAt = saved.fetchedAt
        }
        if let o = defaults.array(forKey: "sat.observer") as? [Double], o.count == 3 { observer = Observer(latitude: o[0], longitude: o[1], altitudeMetres: o[2]) }
    }

    static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appending(path: "Satellites")
    }

    private struct Saved: Codable {
        var fetchedAt: Date
        var satellites: [TLE]
    }

    // MARK: Favourites

    var favorites: Set<Int> {
        get { Set((defaults.array(forKey: "sat.favorites") as? [Int]) ?? [Self.issID]) }
        set { defaults.set(Array(newValue).sorted(), forKey: "sat.favorites") }
    }

    func toggleFavorite(_ id: Int) {
        var f = favorites
        if f.contains(id) { f.remove(id) } else { f.insert(id) }
        favorites = f
    }

    var orbitAgeDays: Double? { fetchedAt.map { now().timeIntervalSince($0) / 86_400 } }
    var iss: TLE? { satellites.first { $0.noradID == Self.issID } }

    // MARK: Update

    func refresh() async {
        guard !updating else { return }
        updating = true
        error = nil
        defer { updating = false }
        let session = session
        let results = await withTaskGroup(of: Result<[TLE], Error>.self) { group -> [Result<[TLE], Error>] in
            for g in Self.groups {
                group.addTask { await Self.fetch(g, session) }
            }
            var all: [Result<[TLE], Error>] = []
            for await r in group { all.append(r) }
            return all
        }
        var byID: [Int: TLE] = [:]
        var failed = 0
        for r in results {
            switch r {
            case .success(let list): for t in list where SGP4(t) != nil { byID[t.noradID] = t }
            case .failure: failed += 1
            }
        }
        guard !byID.isEmpty else {
            error = failed == results.count ? "Couldn't reach CelesTrak. Showing the last orbit data." : "CelesTrak sent no usable satellites."
            return
        }
        if failed > 0 { error = "Some orbit data couldn't be updated." }
        satellites = byID.values.sorted { $0.name < $1.name }
        fetchedAt = now()
        if let data = try? JSONEncoder().encode(Saved(fetchedAt: fetchedAt!, satellites: satellites)) {
            try? data.write(to: directory.appending(path: "elements.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    nonisolated private static func fetch(_ group: String, _ session: URLSession) async -> Result<[TLE], Error> {
        do {
            var req = URLRequest(url: URL(string: "https://celestrak.org/NORAD/elements/gp.php?GROUP=\(group)&FORMAT=tle")!, timeoutInterval: 45)
            req.setValue(ConditionsStore.userAgent, forHTTPHeaderField: "User-Agent")
            let (data, response) = try await session.data(for: req)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            let list = TLE.parse(String(decoding: data, as: UTF8.self))
            guard !list.isEmpty else { throw URLError(.cannotParseResponse) }
            return .success(list)
        } catch { return .failure(error) }
    }

    // MARK: Passes

    /// Where the passes are for: a fresh fix if there is one (it never leaves the phone), else the last one used.
    func updateObserver() async {
        if let fix = await locate() {
            let o = Observer(latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude, altitudeMetres: max(0, fix.altitude))
            observer = o
            defaults.set([o.latitude, o.longitude, o.altitudeMetres], forKey: "sat.observer")
        } else if observer == nil, let c = ConditionsStore.shared.center {
            observer = Observer(latitude: c.latitude, longitude: c.longitude)
        }
    }

    /// Recomputes the favourites' passes for the next `hours` (off the main thread).
    func computePasses(hours: Double = 48) async {
        guard let o = observer, !satellites.isEmpty else { passes = []; return }
        computing = true
        defer { computing = false }
        let favs = favorites
        let sats = satellites.filter { favs.contains($0.noradID) }
        let start = now()
        let result = await Task.detached(priority: .userInitiated) {
            sats.flatMap { SatelliteMath.passes(for: $0, observer: o, from: start, hours: hours) }.sorted { $0.start < $1.start }
        }.value
        passes = result
    }

    /// Passes for one satellite (its detail screen).
    func passes(for tle: TLE, hours: Double = 72) async -> [SatellitePass] {
        guard let o = observer else { return [] }
        let start = now()
        return await Task.detached(priority: .userInitiated) { SatelliteMath.passes(for: tle, observer: o, from: start, hours: hours) }.value
    }

    func deleteAll() {
        satellites = []; fetchedAt = nil; passes = []; observer = nil
        defaults.removeObject(forKey: "sat.observer")
        try? FileManager.default.removeItem(at: directory.appending(path: "elements.json"))
    }
}
