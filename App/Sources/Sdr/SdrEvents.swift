// SdrEvents.swift
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0
//
// The events an SDR box sends over Bluetooth (receive-only): aircraft, APRS frames, weather
// alerts, status. Protocol v1, JSON lines; see the sdrbox project. Parsing is separate from
// the Bluetooth link so it is tested with recorded streams and needs no radio.

import Foundation
import Observation

struct SdrHello: Equatable, Sendable {
    var proto: Int
    var firmware: String
    var model: String
    var dongle: String
    var modes: [String]
    /// True when the box is a simulator: its data is invented and must never be mixed into real logs.
    var simulated: Bool
    /// How many dongles the box has (1 when it does not say).
    var dongles: Int = 1
}

struct SdrAircraft: Equatable, Identifiable, Sendable {
    var id: String { icao }
    var icao: String
    var callsign: String
    var latitude: Double
    var longitude: Double
    var altitudeFeet: Int
    var groundSpeedKnots: Int
    var trackDegrees: Int
    var squawk: String
    var heardAt: Date
}

struct SdrAlert: Equatable, Sendable {
    var source: String
    var mhz: Double
    var same: String
    var text: String
    var receivedAt: Date
}

struct SdrChannel: Equatable, Sendable { var mhz: Double; var snr: Double }

struct SdrShip: Equatable, Identifiable, Sendable {
    var id: Int { mmsi }
    var mmsi: Int
    var name: String
    var latitude: Double
    var longitude: Double
    var speedKnots: Double?
    var courseDegrees: Double?
    var kind: String
    var heardAt: Date
}

struct SdrSonde: Equatable, Identifiable, Sendable {
    var id: String
    var model: String
    var latitude: Double
    var longitude: Double
    var altitudeMetres: Int
    var climbMS: Double
    var mhz: Double
    var heardAt: Date
}

/// A weather-satellite picture the box has ready: fetch it over HTTP (Wi-Fi) from the box.
struct SdrImage: Equatable, Identifiable, Sendable {
    var id: String
    var sat: String
    var width: Int
    var height: Int
    var kb: Int
    var url: String
    var heardAt: Date
}

/// A satellite pass the box plans to record.
struct SdrPass: Equatable, Identifiable, Sendable {
    var id: String { "\(sat)|\(aos.timeIntervalSince1970)" }
    var sat: String
    var aos: Date
    var los: Date
    var elevation: Int
}

/// A station heard on HF (FT8 or WSPR). The position is the middle of its grid square.
/// One JS8 message heard on HF (receive only). Kind: "msg" (directed), "hb" (heartbeat) or "cq".
struct SdrJs8: Equatable, Identifiable, Sendable {
    var id: String { "\(Int(heardAt.timeIntervalSince1970))|\(from)|\(to)|\(text)" }
    var from: String
    var to: String
    var text: String
    var snr: Int
    var mhz: Double
    var kind: String
    var heardAt: Date
}

/// One sweep across a frequency range: dB values from `lowHz` in steps of `stepHz`.
struct SdrSpectrumFrame: Equatable, Sendable {
    var lowHz: Int
    var stepHz: Int
    var db: [Int]
    var highHz: Int { lowHz + stepHz * max(0, db.count - 1) }
    func hz(at index: Int) -> Int { lowHz + stepHz * index }
}

struct SdrSpot: Equatable, Identifiable, Sendable {
    var id: String { "\(mode)|\(call)" }
    var mode: String
    var call: String
    var grid: String
    var snr: Int
    var mhz: Double
    var latitude: Double
    var longitude: Double
    var heardAt: Date
}

enum SdrEvent: Equatable, Sendable {
    case hello(SdrHello)
    case status(mode: String, decoded: Int, dongle: Bool, active: [Int: String])
    case aircraft(SdrAircraft)
    /// A raw AX.25 frame (no KISS framing): hand it to the Ham APRS decoder.
    case aprs(frame: [UInt8], date: Date)
    case alert(SdrAlert)
    case weatherChannels([SdrChannel])
    case spectrum(lowHz: Int, stepHz: Int, db: [Int])
    case js8(SdrJs8)
    /// The box refused us: "auth" (wrong or missing token) or "blocked" (too many wrong tries).
    case error(code: String)
    case ship(SdrShip)
    case sonde(SdrSonde)
    case spot(SdrSpot)
    case image(SdrImage)
    case satPass(SdrPass)
}

/// Collects Bluetooth chunks into complete protocol lines.
struct SdrLineReader {
    static let maxLine = 64 * 1024
    private var buffer = Data()
    private var discarding = false
    private(set) var errors = 0

    mutating func feed(_ data: Data) -> [[String: Any]] {
        var out: [[String: Any]] = []
        var data = data
        if discarding {
            guard let i = data.firstIndex(of: 0x0A) else { return out }
            data = data.suffix(from: data.index(after: i)); discarding = false
        }
        buffer.append(data)
        while let i = buffer.firstIndex(of: 0x0A) {
            let line = buffer.prefix(upTo: i)
            buffer = Data(buffer.suffix(from: buffer.index(after: i)))
            if line.count > Self.maxLine { errors += 1; continue }
            if line.allSatisfy({ $0 == 0x20 || $0 == 0x0D }) { continue }
            if let obj = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any] { out.append(obj) } else { errors += 1 }
        }
        if buffer.count > Self.maxLine { buffer = Data(); discarding = true; errors += 1 }
        return out
    }
}

enum SdrParser {
    static func event(_ j: [String: Any]) -> SdrEvent? {
        guard let t = j["t"] as? String else { return nil }
        func int(_ k: String) -> Int? { (j[k] as? NSNumber)?.intValue }
        func dbl(_ k: String) -> Double? { (j[k] as? NSNumber)?.doubleValue }
        let date = Date(timeIntervalSince1970: Double(int("ts") ?? 0) / 1000)
        switch t {
        case "hello":
            guard let proto = int("proto"), proto == 1 else { return nil }
            return .hello(SdrHello(proto: proto, firmware: j["fw"] as? String ?? "", model: j["model"] as? String ?? "", dongle: j["dongle"] as? String ?? "",
                                   modes: j["modes"] as? [String] ?? [], simulated: j["sim"] as? Bool == true, dongles: max(1, min(8, int("dongles") ?? 1))))
        case "status":
            var active: [Int: String] = [:]
            if let a = j["active"] as? [String: String] { for (k, v) in a { if let i = Int(k), (0..<8).contains(i) { active[i] = v } } }
            else if let m = j["mode"] as? String, !m.isEmpty, m != "idle" { active[0] = m }
            return .status(mode: j["mode"] as? String ?? "", decoded: int("decoded") ?? 0, dongle: j["dongle"] as? Bool ?? true, active: active)
        case "aircraft":
            guard let icao = j["icao"] as? String, let lat = dbl("lat"), let lon = dbl("lon"), (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
            return .aircraft(SdrAircraft(icao: icao.uppercased(), callsign: (j["call"] as? String ?? "").trimmingCharacters(in: .whitespaces), latitude: lat, longitude: lon,
                                         altitudeFeet: int("alt") ?? 0, groundSpeedKnots: int("gs") ?? 0, trackDegrees: int("trk") ?? 0, squawk: j["squawk"] as? String ?? "", heardAt: date))
        case "aprs":
            guard let b64 = j["kiss"] as? String, let d = Data(base64Encoded: b64), d.count >= 16 else { return nil }
            return .aprs(frame: [UInt8](d), date: date)
        case "alert":
            guard let same = j["same"] as? String else { return nil }
            return .alert(SdrAlert(source: j["src"] as? String ?? "", mhz: dbl("freq") ?? 0, same: same, text: j["text"] as? String ?? "", receivedAt: date))
        case "nwr":
            let ch = (j["channels"] as? [[String: Any]] ?? []).compactMap { c -> SdrChannel? in
                guard let m = (c["mhz"] as? NSNumber)?.doubleValue, let s = (c["snr"] as? NSNumber)?.doubleValue else { return nil }
                return SdrChannel(mhz: m, snr: s)
            }
            return .weatherChannels(ch)
        case "ship":
            guard let mmsi = int("mmsi"), let lat = dbl("lat"), let lon = dbl("lon"), (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
            return .ship(SdrShip(mmsi: mmsi, name: j["name"] as? String ?? "", latitude: lat, longitude: lon, speedKnots: dbl("sog"), courseDegrees: dbl("cog"),
                                 kind: j["kind"] as? String ?? "", heardAt: date))
        case "sonde":
            guard let id = j["id"] as? String, let lat = dbl("lat"), let lon = dbl("lon"), (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
            return .sonde(SdrSonde(id: id, model: j["model"] as? String ?? "", latitude: lat, longitude: lon, altitudeMetres: int("alt") ?? 0, climbMS: dbl("vv") ?? 0,
                                   mhz: dbl("mhz") ?? 0, heardAt: date))
        case "spot":
            guard let call = j["call"] as? String, let grid = j["grid"] as? String, let c = Coordinates.fromMaidenhead(grid) else { return nil }
            return .spot(SdrSpot(mode: j["mode"] as? String ?? "", call: call, grid: grid, snr: int("snr") ?? 0, mhz: dbl("mhz") ?? 0, latitude: c.latitude, longitude: c.longitude, heardAt: date))
        case "image":
            guard let id = j["id"] as? String, let url = j["url"] as? String, url.hasPrefix("/img/") else { return nil }
            return .image(SdrImage(id: id, sat: j["sat"] as? String ?? "", width: int("w") ?? 0, height: int("h") ?? 0, kb: int("kb") ?? 0, url: url, heardAt: date))
        case "pass":
            guard let sat = j["sat"] as? String, let a = int("aos"), let l = int("los") else { return nil }
            return .satPass(SdrPass(sat: sat, aos: Date(timeIntervalSince1970: Double(a) / 1000), los: Date(timeIntervalSince1970: Double(l) / 1000), elevation: int("elev") ?? 0))
        case "error":
            return .error(code: j["code"] as? String ?? "unknown")
        case "spectrum":
            guard let lo = int("lo"), let step = int("step"), lo > 0, step > 0, let db = j["db"] as? [NSNumber], (2...1000).contains(db.count) else { return nil }
            return .spectrum(lowHz: lo, stepHz: step, db: db.map { max(-200, min(50, $0.intValue)) })
        case "js8":
            guard let from = j["from"] as? String, !from.isEmpty, from.count <= 16 else { return nil }
            let kind = ["msg", "hb", "cq"].contains(j["kind"] as? String ?? "") ? (j["kind"] as? String ?? "msg") : "msg"
            return .js8(SdrJs8(from: from, to: String((j["to"] as? String ?? "").prefix(16)), text: String((j["text"] as? String ?? "").prefix(160)), snr: int("snr") ?? 0,
                               mhz: dbl("mhz") ?? 0, kind: kind, heardAt: date))
        default:
            return nil
        }
    }
}

/// What the app currently knows from the box.
@MainActor
@Observable
final class SdrState {
    static let shared = SdrState()
    private(set) var hello: SdrHello?
    /// What each dongle is listening to (dongle number to mode); the box reports it in every status.
    private(set) var active: [Int: String] = [:]
    /// The latest spectrum sweep and the highest value seen in each bin since the range last changed (peak hold).
    private(set) var spectrum: SdrSpectrumFrame?
    private(set) var peak: [Int] = []
    /// JS8 messages heard, newest first.
    private(set) var js8: [SdrJs8] = []
    private(set) var aircraft: [String: SdrAircraft] = [:]
    private(set) var alerts: [SdrAlert] = []
    private(set) var channels: [SdrChannel] = []
    private(set) var ships: [Int: SdrShip] = [:]
    private(set) var sondes: [String: SdrSonde] = [:]
    private(set) var spots: [String: SdrSpot] = [:]
    private(set) var images: [SdrImage] = []
    private(set) var passes: [SdrPass] = []
    private(set) var decoded = 0
    /// Set once any hello says sim:true; the whole run then counts as invented.
    private(set) var simulated = false
    /// How long an aircraft stays listed after it was last heard.
    static let aircraftLifetime: TimeInterval = 90

    func apply(_ e: SdrEvent) {
        switch e {
        case .hello(let h): hello = h; if h.simulated { simulated = true }
        case .status(_, let d, _, let a): decoded = d; active = a
        case .aircraft(let a): aircraft[a.icao] = a
        case .alert(let a): alerts.insert(a, at: 0); alerts = Array(alerts.prefix(50))
        case .weatherChannels(let c): channels = c
        case .ship(let s): ships[s.mmsi] = s
        case .sonde(let s): sondes[s.id] = s
        case .spot(let s): spots[s.id] = s
        case .image(let i): if !images.contains(where: { $0.id == i.id }) { images.insert(i, at: 0); images = Array(images.prefix(40)) }
        case .satPass(let p): passes.removeAll { $0.id == p.id }; passes.append(p); passes.sort { $0.aos < $1.aos }; passes = Array(passes.suffix(6))
        case .spectrum(let lo, let step, let db):
            let f = SdrSpectrumFrame(lowHz: lo, stepHz: step, db: db)
            if let old = spectrum, old.lowHz == lo, old.stepHz == step, peak.count == db.count { peak = zip(peak, db).map { max($0, $1) } } else { peak = db }
            spectrum = f
        case .js8(let m): if !js8.contains(where: { $0.id == m.id }) { js8.insert(m, at: 0); js8 = Array(js8.prefix(200)) }
        case .aprs, .error: break
        }
    }

    /// Aircraft heard within the lifetime, nearest first when a position is given.
    func visibleAircraft(now: Date, near: (Double, Double)? = nil) -> [SdrAircraft] {
        let live = aircraft.values.filter { now.timeIntervalSince($0.heardAt) <= Self.aircraftLifetime }
        guard let near else { return live.sorted { $0.icao < $1.icao } }
        return live.sorted {
            CoverageShare.distance(near.0, near.1, $0.latitude, $0.longitude) < CoverageShare.distance(near.0, near.1, $1.latitude, $1.longitude)
        }
    }

    static let shipLifetime: TimeInterval = 600
    static let sondeLifetime: TimeInterval = 1800
    static let spotLifetime: TimeInterval = 1800

    func visibleShips(now: Date) -> [SdrShip] { ships.values.filter { now.timeIntervalSince($0.heardAt) <= Self.shipLifetime }.sorted { $0.mmsi < $1.mmsi } }
    func visibleSondes(now: Date) -> [SdrSonde] { sondes.values.filter { now.timeIntervalSince($0.heardAt) <= Self.sondeLifetime }.sorted { $0.id < $1.id } }
    func visibleSpots(now: Date) -> [SdrSpot] { spots.values.filter { now.timeIntervalSince($0.heardAt) <= Self.spotLifetime }.sorted { $0.heardAt > $1.heardAt } }

    func setActive(_ dongle: Int, _ mode: String?) { active[dongle] = mode }
    func resetPeak() { peak = spectrum?.db ?? [] }
    func reset() { hello = nil; active = [:]; spectrum = nil; peak = []; js8 = []; aircraft = [:]; ships = [:]; sondes = [:]; spots = [:]; images = []; passes = []; alerts = []; channels = []; decoded = 0; simulated = false }
}
