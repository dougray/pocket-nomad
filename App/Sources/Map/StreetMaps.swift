// StreetMaps.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// "Streets around me": street-level map data for the area around the user,
// downloaded into MapLibre's offline storage so the map keeps working with no
// signal. Tiles come from OpenFreeMap (free, no key, OpenStreetMap data in the
// OpenMapTiles schema; vector tiles stop at zoom 14 and are overzoomed past it).
//
// Each download is two offline packs: a wide one (highways, main roads, cities
// and towns, zoom 0-10, out to a few hundred km) and a detail one (every street,
// zoom 11-14, closer in). Only the download requests go to OpenFreeMap; the
// position itself isn't sent anywhere.
//
// OpenFreeMap publishes a new planet every week under a versioned tile path,
// and its TileJSON moves to the newest. A pack keeps the tiles of the week it
// was downloaded, so the map draws from a local copy of the style with that
// week's tile path pinned in it. (Old versions stay reachable online:
// OpenFreeMap serves an unknown version as the latest.)

import CoreLocation
import Foundation
import MapLibre
import Network
import Observation

/// What a street pack remembers about itself (stored as the pack's context).
struct StreetPackContext: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Sendable { case wide, detail }

    /// Shared by the wide and detail pack of one download.
    var downloadID: UUID
    var kind: Kind
    var name: String
    var latitude: Double
    var longitude: Double
    var wideRadiusKm: Int
    var detailRadiusKm: Int
    var created: Date
    /// The versioned tile URL template the pack was downloaded with.
    var tiles: String

    func encoded() -> Data { (try? JSONEncoder().encode(self)) ?? Data() }
    static func decode(_ data: Data) -> StreetPackContext? { try? JSONDecoder().decode(Self.self, from: data) }
}

/// The OpenFreeMap endpoints (checked with curl 2026-11-06: all answer 200).
enum OpenFreeMap {
    static let lightStyle = URL(string: "https://tiles.openfreemap.org/styles/liberty")!
    static let darkStyle = URL(string: "https://tiles.openfreemap.org/styles/dark")!
    /// TileJSON for the planet; its `tiles` entry carries this week's version.
    static let tileJSON = URL(string: "https://tiles.openfreemap.org/planet")!
    static let sourceID = "openmaptiles"
    static let attribution = "© OpenStreetMap contributors · OpenFreeMap"
    static let attributionHTML = "<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a> · <a href=\"https://openfreemap.org\">OpenFreeMap</a>"
}

/// Slippy-map tile maths for sizing a download before it starts. Pure, for tests.
enum StreetTiles {
    static let wideZooms = 0 ... 10
    static let detailZooms = 11 ... 14
    static let wideChoices = [100, 200, 400]
    static let detailChoices = [25, 50, 100]

    struct Bounds: Equatable, Sendable {
        var south: Double, west: Double, north: Double, east: Double
    }

    /// A square reaching `radiusKm` north, south, east and west of the centre.
    static func bounds(latitude: Double, longitude: Double, radiusKm: Double) -> Bounds {
        let dLat = radiusKm / 111.32
        let dLon = radiusKm / (111.32 * max(0.01, cos(latitude * .pi / 180)))
        return Bounds(south: max(-85.0511, latitude - dLat), west: max(-180, longitude - dLon),
                      north: min(85.0511, latitude + dLat), east: min(180, longitude + dLon))
    }

    static func x(_ longitude: Double, _ z: Int) -> Int {
        let n = Double(1 << z)
        return min(Int(n) - 1, max(0, Int(floor((longitude + 180) / 360 * n))))
    }

    static func y(_ latitude: Double, _ z: Int) -> Int {
        let n = Double(1 << z)
        let lat = max(-85.0511, min(85.0511, latitude)) * .pi / 180
        return min(Int(n) - 1, max(0, Int(floor((1 - asinh(tan(lat)) / .pi) / 2 * n))))
    }

    /// Tiles covering the bounds at one zoom.
    static func count(_ b: Bounds, zoom z: Int) -> Int {
        (x(b.east, z) - x(b.west, z) + 1) * (y(b.south, z) - y(b.north, z) + 1)
    }

    static func count(_ b: Bounds, zooms: ClosedRange<Int>) -> Int {
        zooms.reduce(0) { $0 + count(b, zoom: $1) }
    }

    /// Typical OpenFreeMap tile size by zoom, from a sample around a city and
    /// its countryside; real areas vary by half either way.
    static func typicalBytes(zoom z: Int) -> Int {
        switch z {
        case ...5: 250_000
        case 6 ... 8: 120_000
        case 9 ... 10: 30_000
        case 11 ... 12: 40_000
        case 13: 25_000
        default: 15_000
        }
    }

    /// Labels (fonts) and icons, downloaded once and shared by every pack.
    static let styleBytes = 12_000_000

    struct Estimate: Equatable, Sendable {
        var wideTiles: Int
        var detailTiles: Int
        var bytes: Int
        var tiles: Int { wideTiles + detailTiles }
    }

    static func estimate(latitude: Double, longitude: Double, wideKm: Int, detailKm: Int) -> Estimate {
        let wide = bounds(latitude: latitude, longitude: longitude, radiusKm: Double(wideKm))
        let detail = bounds(latitude: latitude, longitude: longitude, radiusKm: Double(detailKm))
        var bytes = styleBytes
        for z in wideZooms { bytes += count(wide, zoom: z) * typicalBytes(zoom: z) }
        for z in detailZooms { bytes += count(detail, zoom: z) * typicalBytes(zoom: z) }
        return Estimate(wideTiles: count(wide, zooms: wideZooms), detailTiles: count(detail, zooms: detailZooms), bytes: bytes)
    }
}

/// Which base map the map tab draws.
enum BaseMap: Equatable, Sendable {
    /// The bundled world overview plus any regional .pmtiles files.
    case protomaps
    /// OpenFreeMap straight from the internet (no streets downloaded yet).
    case streetsOnline
    /// OpenFreeMap from the downloaded packs, tile version pinned.
    case streetsOffline

    /// Regional .pmtiles files win (the user put them there); then downloaded
    /// streets; then live streets when online; else the world overview.
    static func choose(hasRegions: Bool, streetsOn: Bool, hasPacks: Bool, online: Bool) -> BaseMap {
        guard streetsOn, !hasRegions else { return .protomaps }
        if hasPacks { return .streetsOffline }
        return online ? .streetsOnline : .protomaps
    }
}

/// One "streets around" download: its wide and detail pack together.
struct StreetMap: Identifiable, Equatable {
    var id: UUID
    var name: String
    var created: Date
    var latitude: Double
    var longitude: Double
    var wideRadiusKm: Int
    var detailRadiusKm: Int
    var bytes: Int64
    var complete: Bool
    var downloading: Bool
    /// 0...1 while downloading.
    var progress: Double
    /// Downloaded with an older weekly tile set than the one the map draws, so
    /// it won't show offline until it is downloaded again.
    var outdated: Bool
}

/// Is there a usable internet path? Only decides whether live street tiles are worth trying.
@MainActor @Observable
final class NetworkStatus {
    static let shared = NetworkStatus()
    private(set) var online = true
    private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { path in
            let up = path.status == .satisfied
            Task { @MainActor in NetworkStatus.shared.online = up }
        }
        monitor.start(queue: DispatchQueue(label: "network-status"))
    }
}

@MainActor @Observable
final class StreetMapStore {
    static let shared = StreetMapStore()

    private(set) var maps: [StreetMap] = []
    private(set) var lastError: String?
    /// Fetching the style and tile version before the packs start.
    private(set) var preparing = false
    /// The tile template the map draws with: the newest download's.
    private(set) var pinnedTiles: String?

    private var packs: [MLNOfflinePack] = []
    private var observers: [NSObjectProtocol] = []
    private var packsObservation: NSKeyValueObservation?
    private let folder: URL
    private let session: URLSession

    init(folder: URL = URL.applicationSupportDirectory.appending(path: "streets"), session: URLSession = .shared) {
        self.folder = folder
        self.session = session
    }

    /// Starts watching MapLibre's offline storage (call when the map first appears).
    func start() {
        guard packsObservation == nil else { return }
        let storage = MLNOfflineStorage.shared
        packsObservation = storage.observe(\.packs, options: [.initial, .new]) { _, _ in
            Task { @MainActor in StreetMapStore.shared.reloadPacks() }
        }
        for name in [Notification.Name.MLNOfflinePackProgressChanged, .MLNOfflinePackError] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { note in
                let message = (note.userInfo?[MLNOfflinePackUserInfoKey.error] as? NSError)?.localizedDescription
                MainActor.assumeIsolated {
                    if let message { StreetMapStore.shared.lastError = "Download problem: \(message)" }
                    StreetMapStore.shared.refresh()
                }
            })
        }
    }

    var hasPacks: Bool { pinnedTiles != nil && styleFile(dark: false) != nil }

    /// Packs already told to carry on this launch.
    private var resumed: Set<ObjectIdentifier> = []

    private func reloadPacks() {
        guard let all = MLNOfflineStorage.shared.packs else { return }
        packs = all.filter { StreetPackContext.decode($0.context) != nil }
        // A pack's state is unknown until its first progress report.
        for p in packs where p.state == .unknown { p.requestProgress() }
        refresh()
    }

    /// Rebuilds the list from the packs' contexts and progress.
    private func refresh() {
        let live = packs.filter { $0.state != .invalid }
        // Downloads cut off when the app last closed carry on, once per launch.
        for p in live where p.state == .inactive && resumed.insert(ObjectIdentifier(p)).inserted { p.resume() }
        let pairs = live.compactMap { p in StreetPackContext.decode(p.context).map { (p, $0) } }
        let newest = pairs.max { $0.1.created < $1.1.created }?.1
        pinnedTiles = newest?.tiles
        maps = Dictionary(grouping: pairs, by: { $0.1.downloadID }).compactMap { id, group in
            guard let c = group.first?.1 else { return nil }
            let expected = group.reduce(UInt64(0)) { $0 + max($1.0.progress.countOfResourcesExpected, 1) }
            let done = group.reduce(UInt64(0)) { $0 + $1.0.progress.countOfResourcesCompleted }
            return StreetMap(id: id, name: c.name, created: c.created, latitude: c.latitude, longitude: c.longitude,
                             wideRadiusKm: c.wideRadiusKm, detailRadiusKm: c.detailRadiusKm,
                             bytes: Int64(group.reduce(UInt64(0)) { $0 + $1.0.progress.countOfBytesCompleted }),
                             complete: group.count == 2 && group.allSatisfy { $0.0.state == .complete },
                             downloading: group.contains { $0.0.state == .active },
                             progress: min(1, Double(done) / Double(expected)),
                             outdated: c.tiles != newest?.tiles)
        }
        .sorted { $0.created > $1.created }
    }

    // MARK: Downloading

    /// Fetches this week's styles and tile version, then starts both packs.
    func download(around center: CLLocationCoordinate2D, wideKm: Int, detailKm: Int, name: String) async {
        guard !preparing else { return }
        preparing = true
        lastError = nil
        defer { preparing = false }
        let tiles: String
        do {
            tiles = try await fetchTileTemplate()
            try await saveStyle(OpenFreeMap.lightStyle, dark: false)
            try? await saveStyle(OpenFreeMap.darkStyle, dark: true)
        } catch {
            lastError = "Couldn't reach OpenFreeMap. Check your connection and try again."
            return
        }
        let id = UUID()
        let base = StreetPackContext(downloadID: id, kind: .wide, name: name, latitude: center.latitude, longitude: center.longitude,
                                     wideRadiusKm: wideKm, detailRadiusKm: detailKm, created: Date(), tiles: tiles)
        var detail = base
        detail.kind = .detail
        addPack(base, radiusKm: wideKm, zooms: StreetTiles.wideZooms)
        addPack(detail, radiusKm: detailKm, zooms: StreetTiles.detailZooms)
    }

    private func addPack(_ context: StreetPackContext, radiusKm: Int, zooms: ClosedRange<Int>) {
        let b = StreetTiles.bounds(latitude: context.latitude, longitude: context.longitude, radiusKm: Double(radiusKm))
        let bounds = MLNCoordinateBounds(sw: .init(latitude: b.south, longitude: b.west), ne: .init(latitude: b.north, longitude: b.east))
        let region = MLNTilePyramidOfflineRegion(styleURL: OpenFreeMap.lightStyle, bounds: bounds,
                                                 fromZoomLevel: Double(zooms.lowerBound), toZoomLevel: Double(zooms.upperBound))
        // Chinese, Japanese and Korean labels are drawn with the phone's own fonts.
        region.includesIdeographicGlyphs = false
        MLNOfflineStorage.shared.addPack(for: region, withContext: context.encoded()) { newPack, error in
            // MapLibre calls back on the main thread.
            nonisolated(unsafe) let pack = newPack
            MainActor.assumeIsolated {
                if let error { StreetMapStore.shared.lastError = "Couldn't start the download: \(error.localizedDescription)" }
                if let pack {
                    StreetMapStore.shared.resumed.insert(ObjectIdentifier(pack))
                    pack.resume()
                }
            }
        }
    }

    /// Downloads an area again with this week's tiles (for an outdated one), then drops the old copy.
    func redownload(_ map: StreetMap) async {
        await download(around: .init(latitude: map.latitude, longitude: map.longitude),
                       wideKm: map.wideRadiusKm, detailKm: map.detailRadiusKm, name: map.name)
        if lastError == nil { delete(map) }
    }

    /// Stops a download, or deletes a finished one. Tiles shared with other areas stay.
    func delete(_ map: StreetMap) {
        for p in packs where StreetPackContext.decode(p.context)?.downloadID == map.id && p.state != .invalid {
            if p.state == .active { p.suspend() }
            MLNOfflineStorage.shared.removePack(p) { _ in
                MainActor.assumeIsolated { StreetMapStore.shared.reloadPacks() }
            }
        }
        maps.removeAll { $0.id == map.id }
    }

    nonisolated private func fetchTileTemplate() async throws -> String {
        struct TileJSON: Decodable { var tiles: [String] }
        let (data, response) = try await session.data(from: OpenFreeMap.tileJSON)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let template = try JSONDecoder().decode(TileJSON.self, from: data).tiles.first,
              template.contains("{z}") else { throw URLError(.badServerResponse) }
        return template
    }

    nonisolated private func saveStyle(_ url: URL, dark: Bool) async throws {
        let (data, response) = try await session.data(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["layers"] != nil else {
            throw URLError(.badServerResponse)
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try data.write(to: folder.appending(path: dark ? "style-dark.json" : "style-light.json"), options: .atomic)
    }

    // MARK: Rendering

    private func styleFile(dark: Bool) -> URL? {
        let url = folder.appending(path: dark ? "style-dark.json" : "style-light.json")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// The style URL for a base map: OpenFreeMap's own online, or a local copy
    /// pinned to the downloaded tiles. Nil for the Protomaps base map.
    func styleURL(for base: BaseMap, dark: Bool) -> URL? {
        switch base {
        case .protomaps:
            return nil
        case .streetsOnline:
            return dark ? OpenFreeMap.darkStyle : OpenFreeMap.lightStyle
        case .streetsOffline:
            guard let tiles = pinnedTiles, let file = styleFile(dark: dark) ?? styleFile(dark: false),
                  let data = try? Data(contentsOf: file),
                  let style = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            let pinned = Self.pin(style, tiles: tiles)
            let key = (tiles + file.lastPathComponent).utf8.reduce(UInt64(5381)) { ($0 &* 33) ^ UInt64($1) }
            let out = URL.cachesDirectory.appending(path: "streets-\(dark ? "dark" : "light")-\(String(key, radix: 36)).json")
            guard let json = try? JSONSerialization.data(withJSONObject: pinned) else { return nil }
            try? json.write(to: out, options: .atomic)
            return out
        }
    }

    /// The style with its OpenMapTiles source pointing straight at one week's tiles
    /// (no TileJSON lookup), so it matches what the packs hold.
    nonisolated static func pin(_ style: [String: Any], tiles: String) -> [String: Any] {
        var style = style
        var sources = style["sources"] as? [String: Any] ?? [:]
        sources[OpenFreeMap.sourceID] = [
            "type": "vector",
            "tiles": [tiles],
            "minzoom": 0,
            "maxzoom": 14,
            "attribution": OpenFreeMap.attributionHTML,
        ] as [String: Any]
        style["sources"] = sources
        return style
    }
}
