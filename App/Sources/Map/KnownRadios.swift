// KnownRadios.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Known mesh radios from the public community maps: meshmap.net (Meshtastic
// nodes heard by the official MQTT server) and map.meshcore.io (MeshCore
// nodes that uploaded their adverts). Downloaded only when the user asks,
// trimmed to the area around them, and kept on the phone for offline use.
// Nothing is sent to either site beyond the download request itself.

import CoreLocation
import Foundation
import Observation

/// One radio from a community map.
struct KnownRadio: Codable, Equatable, Sendable, Identifiable {
    enum Role: String, Codable, Sendable {
        /// Repeaters and routers: the fixed sites that carry the mesh.
        case repeater
        /// MeshCore room servers (bulletin boards).
        case room
        case client
        case sensor
    }

    var id: String
    var firmware: MeshFirmware
    var name: String
    var role: Role
    var latitude: Double
    var longitude: Double
    var lastSeen: Date?

    var roleLabel: String {
        switch role {
        case .repeater: firmware == .meshcore ? "Repeater" : "Router"
        case .room: "Room server"
        case .client: "Client"
        case .sensor: "Sensor"
        }
    }
}

@MainActor @Observable
final class KnownRadioStore {
    static let shared = KnownRadioStore()

    static let meshtasticURL = URL(string: "https://meshmap.net/nodes.json")!
    static let meshcoreURL = URL(string: "https://map.meshcore.io/api/v1/nodes")!
    static let radiusChoices = [50, 100, 200, 400]

    private(set) var radios: [KnownRadio] = []
    private(set) var updated: Date?
    private(set) var areaName: String?
    private(set) var downloading = false
    private(set) var lastError: String?

    private let fileURL: URL
    private let session: URLSession

    private struct Saved: Codable {
        var updated: Date
        var areaName: String?
        var radios: [KnownRadio]
    }

    init(fileURL: URL = URL.applicationSupportDirectory.appending(path: "known-radios.json"),
         session: URLSession = .shared) {
        self.fileURL = fileURL
        self.session = session
        if let saved = try? JSONDecoder().decode(Saved.self, from: Data(contentsOf: fileURL)) {
            radios = saved.radios
            updated = saved.updated
            areaName = saved.areaName
        }
    }

    var meshtasticCount: Int { radios.count(where: { $0.firmware == .meshtastic }) }
    var meshcoreCount: Int { radios.count(where: { $0.firmware == .meshcore }) }

    /// Downloads both maps and keeps radios within `radiusKm` of `center`.
    /// One map failing still keeps the other.
    func download(around center: CLLocationCoordinate2D, radiusKm: Int, areaName: String?) async {
        guard !downloading else { return }
        downloading = true
        lastError = nil
        defer { downloading = false }
        let session = session
        async let tastic = Self.fetch(Self.meshtasticURL, session: session, parse: Self.parseMeshtastic)
        async let core = Self.fetch(Self.meshcoreURL, session: session, parse: Self.parseMeshCore)
        let results = [("meshmap.net", await tastic), ("map.meshcore.io", await core)]
        var all: [KnownRadio] = []
        var failed: [String] = []
        for (name, result) in results {
            switch result {
            case .success(let list): all += list
            case .failure: failed.append(name)
            }
        }
        if failed.count == results.count {
            lastError = "Couldn't reach the community maps. Check your connection and try again."
            return
        }
        if !failed.isEmpty { lastError = "Couldn't reach \(failed.joined(separator: " and ")); kept the rest." }
        radios = Self.nearby(all, center: center, radiusKm: Double(radiusKm))
        updated = Date()
        self.areaName = areaName
        save()
    }

    func deleteAll() {
        radios = []
        updated = nil
        areaName = nil
        try? FileManager.default.removeItem(at: fileURL)
    }

    private func save() {
        guard let updated else { return }
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(Saved(updated: updated, areaName: areaName, radios: radios)).write(to: fileURL, options: .atomic)
    }

    nonisolated private static func fetch(_ url: URL, session: URLSession,
                                          parse: @Sendable (Data) throws -> [KnownRadio]) async -> Result<[KnownRadio], Error> {
        do {
            var request = URLRequest(url: url, timeoutInterval: 60)
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            let (data, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            return .success(try parse(data))
        } catch {
            return .failure(error)
        }
    }

    /// Radios within the radius, minus any whose owner opted out of mapping.
    nonisolated static func nearby(_ radios: [KnownRadio], center: CLLocationCoordinate2D, radiusKm: Double) -> [KnownRadio] {
        radios.filter {
            !CoverageStore.optsOut($0.name)
                && CoverageShare.distance(center.latitude, center.longitude, $0.latitude, $0.longitude) <= radiusKm * 1000
        }
    }

    // MARK: Parsing

    /// meshmap.net: `{ "<node number>": { longName, role, latitude, longitude (degrees x 1e7), seenBy: { topic: unix time } } }`.
    nonisolated static func parseMeshtastic(_ data: Data) throws -> [KnownRadio] {
        struct Node: Decodable {
            var longName: String?
            var shortName: String?
            var role: String?
            var latitude: Int?
            var longitude: Int?
            var seenBy: [String: Double]?
        }
        let nodes = try JSONDecoder().decode([String: Node].self, from: data)
        return nodes.compactMap { key, n in
            guard let lat = n.latitude, let lon = n.longitude, lat != 0 || lon != 0 else { return nil }
            let role: KnownRadio.Role = switch n.role ?? "" {
            case "ROUTER", "ROUTER_LATE", "ROUTER_CLIENT", "REPEATER": .repeater
            case "SENSOR": .sensor
            default: .client
            }
            let name = n.longName?.trimmingCharacters(in: .whitespaces) ?? ""
            return KnownRadio(id: "t" + key, firmware: .meshtastic,
                              name: name.isEmpty ? (n.shortName ?? key) : name, role: role,
                              latitude: Double(lat) / 1e7, longitude: Double(lon) / 1e7,
                              lastSeen: n.seenBy?.values.max().map { Date(timeIntervalSince1970: $0) })
        }
    }

    /// map.meshcore.io: `[ { public_key, type (1 client, 2 repeater, 3 room, 4 sensor), adv_name, adv_lat, adv_lon, last_advert, updated_date } ]`.
    nonisolated static func parseMeshCore(_ data: Data) throws -> [KnownRadio] {
        struct Node: Decodable {
            var public_key: String?
            var type: Int?
            var adv_name: String?
            var adv_lat: Double?
            var adv_lon: Double?
            var last_advert: String?
            var updated_date: String?
        }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return try JSONDecoder().decode([Node].self, from: data).compactMap { n in
            guard let key = n.public_key, let lat = n.adv_lat, let lon = n.adv_lon, lat != 0 || lon != 0,
                  abs(lat) <= 90, abs(lon) <= 180 else { return nil }
            let role: KnownRadio.Role = switch n.type {
            case 2: .repeater
            case 3: .room
            case 4: .sensor
            default: .client
            }
            return KnownRadio(id: "c" + key.prefix(16), firmware: .meshcore, name: n.adv_name ?? String(key.prefix(8)),
                              role: role, latitude: lat, longitude: lon,
                              // The server's update time; a radio's own advert clock is often wrong.
                              lastSeen: (n.updated_date ?? n.last_advert).flatMap { iso.date(from: $0) })
        }
    }
}
