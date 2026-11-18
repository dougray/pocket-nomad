// Directions.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Offline route planning. Roads and paths come from OpenStreetMap (via the public Overpass
// service), are downloaded once for an area, and routed on the phone (A*). No account,
// no key, and nothing about where you are going leaves the phone.

import CoreLocation
import Foundation
import Observation

enum TravelMode: String, CaseIterable, Identifiable, Sendable {
    case foot, bike, car
    var id: Self { self }
    var label: String { switch self { case .foot: "Walk"; case .bike: "Bike"; case .car: "Drive" } }
    var symbol: String { switch self { case .foot: "figure.walk"; case .bike: "bicycle"; case .car: "car.fill" } }

    /// Typical speed in m/s on a road of this class.
    func speed(highway: String) -> Double? {
        switch self {
        case .foot:
            switch highway {
            case "motorway", "motorway_link", "trunk", "trunk_link": return nil
            default: return 1.34
            }
        case .bike:
            switch highway {
            case "motorway", "motorway_link", "trunk", "trunk_link", "steps": return nil
            case "path", "track", "footway", "pedestrian": return 3.0
            default: return 4.5
            }
        case .car:
            switch highway {
            case "motorway": return 29
            case "motorway_link", "trunk": return 22
            case "trunk_link", "primary": return 17
            case "primary_link", "secondary", "secondary_link": return 14
            case "tertiary", "tertiary_link": return 12
            case "residential", "unclassified", "living_street": return 9
            case "service": return 5
            case "track": return 4
            default: return nil // footways, paths, steps, cycleways, pedestrian
            }
        }
    }
}

struct RoadWay: Codable, Equatable, Sendable {
    var id: Int64
    var highway: String
    var name: String
    var oneway: Bool
    var nodes: [Int64]
    var lat: [Double]
    var lon: [Double]
    /// Dirt, gravel or a track (nil in older saved squares).
    var unpaved: Bool? = nil
}

/// What a route should steer clear of.
struct RoutePrefs: Equatable, Hashable, Sendable {
    var avoidHighways = false
    var avoidUnpaved = false
}

struct RouteStep: Equatable, Sendable {
    var text: String
    var meters: Double
    var latitude: Double
    var longitude: Double
}

struct Route: Equatable, Sendable {
    var points: [MapShape.Coordinate]
    var meters: Double
    var seconds: Double
    var steps: [RouteStep]
}

enum Overpass {
    static let endpoint = URL(string: "https://overpass-api.de/api/interpreter")!
    static let skipped = "proposed|construction|abandoned|razed|platform|bus_guideway|raceway|corridor|elevator"

    static func query(south: Double, west: Double, north: Double, east: Double) -> String {
        "[out:json][timeout:120];way[highway][highway!~\"^(\(skipped))$\"][access!~\"^(private|no)$\"][service!~\"^(driveway|parking_aisle|alley|drive-through)$\"](\(south),\(west),\(north),\(east));out geom qt;"
    }

    /// Parse an Overpass JSON reply into roads.
    static func parse(_ data: Data) -> [RoadWay] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let els = root["elements"] as? [[String: Any]] else { return [] }
        return els.compactMap { e in
            guard e["type"] as? String == "way", let id = (e["id"] as? NSNumber)?.int64Value,
                  let tags = e["tags"] as? [String: Any], let hw = tags["highway"] as? String,
                  let nodes = e["nodes"] as? [NSNumber], let geom = e["geometry"] as? [[String: Any]], nodes.count == geom.count, nodes.count >= 2 else { return nil }
            let surface = (tags["surface"] as? String) ?? ""
            let unpaved = hw == "track" || ["unpaved", "dirt", "gravel", "ground", "earth", "sand", "mud", "grass", "fine_gravel", "compacted", "pebblestone", "dirt/sand"].contains(surface)
            let oneway = (tags["oneway"] as? String).map { $0 == "yes" || $0 == "true" || $0 == "1" } ?? (hw == "motorway" || tags["junction"] as? String == "roundabout")
            return RoadWay(id: id, highway: hw, name: (tags["name"] as? String) ?? (tags["ref"] as? String) ?? "",
                           oneway: oneway, nodes: nodes.map(\.int64Value),
                           lat: geom.map { (((($0["lat"] as? Double) ?? 0) * 1e6).rounded()) / 1e6 }, lon: geom.map { (((($0["lon"] as? Double) ?? 0) * 1e6).rounded()) / 1e6 })
        }
    }
}

/// A road graph for one travel mode.
struct RoadGraph: Sendable {
    struct Edge: Sendable { var to: Int; var meters: Double; var seconds: Double; var way: Int }
    private(set) var coords: [MapShape.Coordinate] = []
    private(set) var adjacency: [[Edge]] = []
    private(set) var ways: [RoadWay]
    let mode: TravelMode
    let prefs: RoutePrefs

    init(ways: [RoadWay], mode: TravelMode, prefs: RoutePrefs = RoutePrefs()) {
        self.ways = ways
        self.mode = mode
        self.prefs = prefs
        var index: [Int64: Int] = [:]
        func node(_ id: Int64, _ lat: Double, _ lon: Double) -> Int {
            if let i = index[id] { return i }
            let i = coords.count
            index[id] = i
            coords.append(.init(latitude: lat, longitude: lon))
            adjacency.append([])
            return i
        }
        for (wi, w) in ways.enumerated() {
            guard let speed = mode.speed(highway: w.highway) else { continue }
            if mode == .car, prefs.avoidHighways, ["motorway", "motorway_link", "trunk", "trunk_link"].contains(w.highway) { continue }
            if mode != .foot, prefs.avoidUnpaved, w.unpaved ?? false { continue }
            for k in 1..<w.nodes.count {
                let a = node(w.nodes[k - 1], w.lat[k - 1], w.lon[k - 1]), b = node(w.nodes[k], w.lat[k], w.lon[k])
                let m = CoverageShare.distance(w.lat[k - 1], w.lon[k - 1], w.lat[k], w.lon[k])
                adjacency[a].append(.init(to: b, meters: m, seconds: m / speed, way: wi))
                // One-way streets bind cars and bikes; people on foot can go either way.
                if !w.oneway || mode == .foot { adjacency[b].append(.init(to: a, meters: m, seconds: m / speed, way: wi)) }
            }
        }
    }

    var isEmpty: Bool { coords.isEmpty }

    /// The nearest node that has any road attached, within `maxMeters`.
    func nearest(latitude: Double, longitude: Double, maxMeters: Double = 1500) -> Int? {
        var best: (Int, Double)?
        for (i, c) in coords.enumerated() where !adjacency[i].isEmpty {
            let d = CoverageShare.distance(latitude, longitude, c.latitude, c.longitude)
            if d <= maxMeters, best == nil || d < best!.1 { best = (i, d) }
        }
        return best?.0
    }

    /// Fastest route (A*) between two positions, or nil if either is off the downloaded roads or they are not connected.
    func route(from a: MapShape.Coordinate, to b: MapShape.Coordinate) -> Route? {
        guard let s = nearest(latitude: a.latitude, longitude: a.longitude), let t = nearest(latitude: b.latitude, longitude: b.longitude) else { return nil }
        let maxSpeed = mode == .car ? 29.0 : mode == .bike ? 4.5 : 1.34
        func h(_ i: Int) -> Double { CoverageShare.distance(coords[i].latitude, coords[i].longitude, coords[t].latitude, coords[t].longitude) / maxSpeed }
        var g = [Int: Double](), prev = [Int: (node: Int, edge: Edge)](), done = Set<Int>()
        var open = MinHeap()
        g[s] = 0; open.push(h(s), s)
        while let (_, u) = open.pop() {
            if u == t { break }
            if !done.insert(u).inserted { continue }
            for e in adjacency[u] where !done.contains(e.to) {
                let cost = g[u]! + e.seconds
                if cost < (g[e.to] ?? .infinity) { g[e.to] = cost; prev[e.to] = (u, e); open.push(cost + h(e.to), e.to) }
            }
        }
        guard g[t] != nil else { return nil }
        var edges: [(from: Int, edge: Edge)] = []
        var cur = t
        while cur != s, let p = prev[cur] { edges.append((p.node, p.edge)); cur = p.node }
        edges.reverse()
        var pts = [coords[s]] + edges.map { coords[$0.edge.to] }
        // Start and finish on the exact points you gave, not the nearest corner.
        if s != t { pts.insert(a, at: 0); pts.append(b) } else { pts = [a, b] }
        return Route(points: pts, meters: edges.reduce(0) { $0 + $1.edge.meters }, seconds: edges.reduce(0) { $0 + $1.edge.seconds },
                     steps: Self.steps(edges: edges.map { ($0.from, $0.edge) }, graph: self))
    }

    /// Turn-by-turn text: a step wherever the street name changes or the path bends sharply.
    static func steps(edges: [(Int, Edge)], graph g: RoadGraph) -> [RouteStep] {
        guard let first = edges.first else { return [] }
        func bearing(_ a: Int, _ b: Int) -> Double { ShapeMath.bearing(g.coords[a], g.coords[b]) }
        func street(_ e: Edge) -> String { g.ways[e.way].name.isEmpty ? "the path" : g.ways[e.way].name }
        var steps: [RouteStep] = []
        var start = first.0
        var meters = 0.0
        var name = street(first.1)
        steps.append(.init(text: "Head \(ShapeMath.bearingText(bearing(first.0, first.1.to))) on \(name)", meters: 0, latitude: g.coords[start].latitude, longitude: g.coords[start].longitude))
        var runStart = 0
        for i in 0..<edges.count {
            meters += edges[i].1.meters
            guard i + 1 < edges.count else { break }
            let next = edges[i + 1]
            let turn = GoToMath.arrowAngle(bearing: bearing(next.0, next.1.to), heading: bearing(edges[i].0, edges[i].1.to))
            let nextName = street(next.1)
            if nextName != name || abs(turn) >= 45 {
                steps[steps.count - 1].meters = meters
                let dir = abs(turn) < 20 ? "Continue" : abs(turn) < 60 ? (turn > 0 ? "Bear right" : "Bear left") : abs(turn) < 135 ? (turn > 0 ? "Turn right" : "Turn left") : (turn > 0 ? "Sharp right" : "Sharp left")
                steps.append(.init(text: "\(dir) onto \(nextName)", meters: 0, latitude: g.coords[next.0].latitude, longitude: g.coords[next.0].longitude))
                meters = 0; name = nextName; start = next.0; runStart = i + 1
            }
        }
        _ = runStart
        steps[steps.count - 1].meters = meters
        let last = edges[edges.count - 1].1.to
        steps.append(.init(text: "Arrive", meters: 0, latitude: g.coords[last].latitude, longitude: g.coords[last].longitude))
        return steps
    }
}

/// A small binary min-heap of (priority, node).
struct MinHeap: Sendable {
    private var items: [(Double, Int)] = []
    mutating func push(_ p: Double, _ v: Int) {
        items.append((p, v))
        var i = items.count - 1
        while i > 0, items[(i - 1) / 2].0 > items[i].0 { items.swapAt(i, (i - 1) / 2); i = (i - 1) / 2 }
    }
    mutating func pop() -> (Double, Int)? {
        guard !items.isEmpty else { return nil }
        items.swapAt(0, items.count - 1)
        let top = items.removeLast()
        var i = 0
        while true {
            let l = 2 * i + 1, r = l + 1
            var m = i
            if l < items.count, items[l].0 < items[m].0 { m = l }
            if r < items.count, items[r].0 < items[m].0 { m = r }
            if m == i { break }
            items.swapAt(i, m); i = m
        }
        return top
    }
}

/// Roads saved on the phone, in 0.05 degree squares (about 5 km).
@MainActor
@Observable
final class RoadStore {
    static let shared = RoadStore()
    let folder: URL
    private(set) var downloading = false
    private(set) var progress = 0.0
    private(set) var message = ""
    private(set) var cellCount: Int
    private var graphs: [String: RoadGraph] = [:]

    init(folder: URL = URL.applicationSupportDirectory.appending(path: "roads")) {
        self.folder = folder
        cellCount = (try? FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".json") }.count) ?? 0
    }

    var hasRoads: Bool { cellCount > 0 }

    static func cells(centre: CLLocationCoordinate2D, radiusKm: Double) -> [(lat: Int, lon: Int)] {
        let dLat = radiusKm / 111.32, dLon = radiusKm / (111.32 * max(0.05, cos(centre.latitude * .pi / 180)))
        let la0 = Int(((centre.latitude - dLat) * 20).rounded(.down)), la1 = Int(((centre.latitude + dLat) * 20).rounded(.down))
        let lo0 = Int(((centre.longitude - dLon) * 20).rounded(.down)), lo1 = Int(((centre.longitude + dLon) * 20).rounded(.down))
        return (la0...la1).flatMap { la in (lo0...lo1).map { (lat: la, lon: $0) } }
    }

    private func file(_ c: (lat: Int, lon: Int)) -> URL { folder.appending(path: "\(c.lat)_\(c.lon).json") }

    func download(centre: CLLocationCoordinate2D, radiusKm: Double) async {
        guard !downloading else { return }
        downloading = true; progress = 0; message = "Downloading roads…"
        let todo = Self.cells(centre: centre, radiusKm: radiusKm)
        var failures = 0
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for (i, c) in todo.enumerated() {
            if !FileManager.default.fileExists(atPath: file(c).path) {
                var ok = false
                for attempt in 0..<3 where !ok {
                    do {
                        var req = URLRequest(url: Overpass.endpoint); req.httpMethod = "POST"; req.timeoutInterval = 150
                        req.setValue("PocketNomad/1.0 (+https://pocketnomad.us)", forHTTPHeaderField: "User-Agent")
                        let q = Overpass.query(south: Double(c.lat) / 20, west: Double(c.lon) / 20, north: Double(c.lat + 1) / 20, east: Double(c.lon + 1) / 20)
                        req.httpBody = ("data=" + (q.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")).data(using: .utf8)
                        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
                        let (data, resp) = try await URLSession.shared.data(for: req)
                        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                        let ways = Overpass.parse(data)
                        try JSONEncoder().encode(ways).write(to: file(c), options: .atomic)
                        ok = true
                    } catch { try? await Task.sleep(for: .seconds(Double(5 * (attempt + 1)))) }
                }
                if !ok { failures += 1 }
                try? await Task.sleep(for: .seconds(1)) // be gentle with a free public service
            }
            progress = Double(i + 1) / Double(max(1, todo.count))
        }
        graphs = [:]
        cellCount = (try? FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".json") }.count) ?? 0
        message = failures == 0 ? "Saved \(todo.count) squares." : "\(failures) squares failed (the free service can be busy). Try again later."
        downloading = false
    }

    func deleteAll() { try? FileManager.default.removeItem(at: folder); graphs = [:]; cellCount = 0; message = "" }

    private nonisolated static func loadWays(_ folder: URL) -> [RoadWay] {
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" }) ?? []
        var seen = Set<Int64>()
        return files.flatMap { (try? JSONDecoder().decode([RoadWay].self, from: Data(contentsOf: $0))) ?? [] }.filter { seen.insert($0.id).inserted }
    }

    /// Plan a route off the main thread (the first call per mode builds the graph from the saved squares).
    func plan(mode: TravelMode, prefs: RoutePrefs = RoutePrefs(), from: MapShape.Coordinate, to: MapShape.Coordinate) async -> Route? {
        let key = "\(mode.rawValue)|\(prefs.avoidHighways)|\(prefs.avoidUnpaved)"
        let cached = graphs[key]
        let folder = self.folder
        let (route, built) = await Task.detached(priority: .userInitiated) { () -> (Route?, RoadGraph?) in
            let g = cached ?? RoadGraph(ways: Self.loadWays(folder), mode: mode, prefs: prefs)
            return (g.isEmpty ? nil : g.route(from: from, to: to), cached == nil ? g : nil)
        }.value
        if let built, !built.isEmpty { graphs[key] = built }
        return route
    }

    /// MB estimate: a square is a few MB in a city and far less in the country.
    func estimateMB(centre: CLLocationCoordinate2D, radiusKm: Double) -> Double { Double(Self.cells(centre: centre, radiusKm: radiusKm).count) * 2.5 }
}
