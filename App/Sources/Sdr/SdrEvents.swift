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

/// A station heard on HF (FT8 or WSPR). The position is the middle of its grid square.
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
    case status(mode: String, decoded: Int, dongle: Bool)
    case aircraft(SdrAircraft)
    /// A raw AX.25 frame (no KISS framing): hand it to the Ham APRS decoder.
    case aprs(frame: [UInt8], date: Date)
    case alert(SdrAlert)
    case weatherChannels([SdrChannel])
    case spectrum(lowHz: Int, stepHz: Int, db: [Int])
    /// The box refused us: "auth" (wrong or missing token) or "blocked" (too many wrong tries).
    case error(code: String)
    case ship(SdrShip)
    case sonde(SdrSonde)
    case spot(SdrSpot)
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
                                   modes: j["modes"] as? [String] ?? [], simulated: j["sim"] as? Bool == true))
        case "status":
            return .status(mode: j["mode"] as? String ?? "", decoded: int("decoded") ?? 0, dongle: j["dongle"] as? Bool ?? true)
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
        case "error":
            return .error(code: j["code"] as? String ?? "unknown")
        case "spectrum":
            guard let lo = int("lo"), let step = int("step"), let db = j["db"] as? [NSNumber] else { return nil }
            return .spectrum(lowHz: lo, stepHz: step, db: db.map(\.intValue))
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
    private(set) var aircraft: [String: SdrAircraft] = [:]
    private(set) var alerts: [SdrAlert] = []
    private(set) var channels: [SdrChannel] = []
    private(set) var ships: [Int: SdrShip] = [:]
    private(set) var sondes: [String: SdrSonde] = [:]
    private(set) var spots: [String: SdrSpot] = [:]
    private(set) var decoded = 0
    /// Set once any hello says sim:true; the whole run then counts as invented.
    private(set) var simulated = false
    /// How long an aircraft stays listed after it was last heard.
    static let aircraftLifetime: TimeInterval = 90

    func apply(_ e: SdrEvent) {
        switch e {
        case .hello(let h): hello = h; if h.simulated { simulated = true }
        case .status(_, let d, _): decoded = d
        case .aircraft(let a): aircraft[a.icao] = a
        case .alert(let a): alerts.insert(a, at: 0); alerts = Array(alerts.prefix(50))
        case .weatherChannels(let c): channels = c
        case .ship(let s): ships[s.mmsi] = s
        case .sonde(let s): sondes[s.id] = s
        case .spot(let s): spots[s.id] = s
        case .aprs, .spectrum, .error: break
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

    func reset() { hello = nil; aircraft = [:]; ships = [:]; sondes = [:]; spots = [:]; alerts = []; channels = []; decoded = 0; simulated = false }
}
