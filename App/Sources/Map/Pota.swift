// Pota.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Parks on the Air (POTA): the parks near you and who is on the air from them right now.
// Free, keyless public data from pota.app. Like Conditions, it is a snapshot: tap Update
// with signal, the last copy is kept and shown with its age. Parks are asked for a box
// around your position rounded to 0.1 degree, never your exact location.

import CoreLocation
import Foundation
import Observation

struct PotaPark: Codable, Equatable, Identifiable, Sendable {
    var id: String { reference }
    var reference: String
    var name: String
    var latitude: Double
    var longitude: Double
}

struct PotaSpot: Codable, Equatable, Identifiable, Sendable {
    var id: Int
    var activator: String
    var reference: String
    var parkName: String
    var frequencyKHz: Double
    var mode: String
    var latitude: Double
    var longitude: Double
    var spotted: Date
    var comments: String
    var region: String

    var frequencyText: String { frequencyKHz >= 1000 ? String(format: "%.3f MHz", frequencyKHz / 1000) : String(format: "%.0f kHz", frequencyKHz) }
}

struct PotaSnapshot: Codable, Equatable, Sendable {
    var fetched: Date
    /// The rounded centre the parks were asked for.
    var centreLat: Double
    var centreLon: Double
    var parks: [PotaPark]
    var spots: [PotaSpot]
}

enum PotaParsers {
    static let isoNoZone: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f
    }()

    /// Activator spots (the API gives UTC times with no zone).
    static func spots(_ data: Data) -> [PotaSpot] {
        guard let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
        return arr.compactMap { s in
            guard let id = (s["spotId"] as? NSNumber)?.intValue, let act = s["activator"] as? String, let ref = s["reference"] as? String,
                  let lat = (s["latitude"] as? NSNumber)?.doubleValue, let lon = (s["longitude"] as? NSNumber)?.doubleValue,
                  (-90...90).contains(lat), (-180...180).contains(lon),
                  let f = (s["frequency"] as? String).flatMap(Double.init) ?? (s["frequency"] as? NSNumber)?.doubleValue else { return nil }
            let when = (s["spotTime"] as? String).flatMap { isoNoZone.date(from: String($0.prefix(19))) } ?? Date()
            return PotaSpot(id: id, activator: act, reference: ref, parkName: (s["name"] as? String) ?? (s["parkName"] as? String) ?? ref,
                            frequencyKHz: f, mode: (s["mode"] as? String) ?? "", latitude: lat, longitude: lon, spotted: when,
                            comments: (s["comments"] as? String) ?? "", region: (s["locationDesc"] as? String) ?? "")
        }
    }

    /// Parks in a box (GeoJSON points).
    static func parks(_ data: Data) -> [PotaPark] {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let feats = root["features"] as? [[String: Any]] else { return [] }
        return feats.compactMap { f in
            guard let p = f["properties"] as? [String: Any], let ref = p["reference"] as? String,
                  let g = f["geometry"] as? [String: Any], let c = g["coordinates"] as? [NSNumber], c.count >= 2 else { return nil }
            let lon = c[0].doubleValue, lat = c[1].doubleValue
            guard (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
            return PotaPark(reference: ref, name: (p["name"] as? String) ?? ref, latitude: lat, longitude: lon)
        }
    }

    /// Spots only count while they are fresh.
    static func current(_ spots: [PotaSpot], now: Date, maxAge: TimeInterval = 45 * 60) -> [PotaSpot] {
        spots.filter { now.timeIntervalSince($0.spotted) <= maxAge }
    }

    /// 0.1 degree, so the box asked for never pins down where you are.
    static func rounded(_ v: Double) -> Double { (v * 10).rounded() / 10 }

    static func ageText(_ date: Date, now: Date) -> String {
        let s = Int(now.timeIntervalSince(date))
        if s < 90 { return "just now" }
        if s < 3600 { return "\(s / 60) min ago" }
        if s < 86400 { return "\(s / 3600) h ago" }
        return "\(s / 86400) d ago"
    }
}

@MainActor
@Observable
final class PotaStore {
    static let shared = PotaStore()
    private(set) var snapshot: PotaSnapshot?
    private(set) var busy = false
    private(set) var error: String?
    private let url: URL
    private var lastTry = Date.distantPast
    static let minInterval: TimeInterval = 120

    init(url: URL = URL.applicationSupportDirectory.appending(path: "pota.json")) {
        self.url = url
        if let d = try? Data(contentsOf: url) {
            let dec = JSONDecoder(); dec.dateDecodingStrategy = .secondsSince1970
            snapshot = try? dec.decode(PotaSnapshot.self, from: d)
        }
    }

    func refresh(near here: CLLocationCoordinate2D, force: Bool = false) async {
        guard !busy, force || Date().timeIntervalSince(lastTry) > Self.minInterval else { return }
        busy = true; error = nil; lastTry = Date()
        defer { busy = false }
        let lat = PotaParsers.rounded(here.latitude), lon = PotaParsers.rounded(here.longitude)
        func get(_ path: String) async throws -> Data {
            var req = URLRequest(url: URL(string: "https://api.pota.app" + path)!)
            req.setValue("PocketNomad/1.0 (+https://pocketnomad.us)", forHTTPHeaderField: "User-Agent")
            req.timeoutInterval = 30
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            return data
        }
        do {
            let spotsData = try await get("/spot/activator")
            let box = String(format: "%.1f/%.1f/%.1f/%.1f", lat - 0.5, lon - 0.75, lat + 0.5, lon + 0.75)
            let parksData = try await get("/park/grids/\(box)/0")
            let snap = PotaSnapshot(fetched: Date(), centreLat: lat, centreLon: lon, parks: PotaParsers.parks(parksData), spots: PotaParsers.spots(spotsData))
            snapshot = snap
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let enc = JSONEncoder(); enc.dateEncodingStrategy = .secondsSince1970
            try? enc.encode(snap).write(to: url, options: .atomic)
        } catch {
            self.error = "Couldn't reach the POTA service. The last copy is kept."
        }
    }

    /// Spots still fresh, nearest first.
    func activeSpots(near here: CLLocationCoordinate2D?, now: Date = Date()) -> [PotaSpot] {
        let live = PotaParsers.current(snapshot?.spots ?? [], now: now)
        guard let here else { return live.sorted { $0.spotted > $1.spotted } }
        return live.sorted { CoverageShare.distance(here.latitude, here.longitude, $0.latitude, $0.longitude) < CoverageShare.distance(here.latitude, here.longitude, $1.latitude, $1.longitude) }
    }
}
