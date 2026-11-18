// QsoLog.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// A simple log of radio contacts (QSOs), kept on the phone only and exported as ADIF, the
// standard file every logging program reads. Nothing is uploaded anywhere.

import Foundation
import Observation

struct Qso: Codable, Equatable, Identifiable, Sendable {
    var id = UUID()
    var call: String
    /// When the contact started, UTC.
    var when: Date
    var mhz: Double
    var mode: String
    var rstSent = ""
    var rstRcvd = ""
    var grid = ""
    var name = ""
    var comment = ""
    /// Your own callsign at the time (from Station settings).
    var myCall = ""
    var myGrid = ""
    /// Park or summit reference, for award programmes: POTA, SOTA and so on.
    var sig = ""
    var sigInfo = ""
    /// Transmit power in watts (the stat that makes a contact worth bragging about).
    var watts: Double?

    var band: String { QsoBands.band(mhz: mhz) ?? "" }

    /// Great-circle distance between the two grid squares, in kilometres (nil unless both grids are known).
    var distanceKm: Double? {
        guard let a = Coordinates.fromMaidenhead(myGrid), let b = Coordinates.fromMaidenhead(grid) else { return nil }
        return CoverageShare.distance(a.latitude, a.longitude, b.latitude, b.longitude) / 1000
    }

    /// Miles of range for each watt of power: 5 W reaching 84 miles is 16.8.
    var milesPerWatt: Double? {
        guard let d = distanceKm, let w = watts, w > 0 else { return nil }
        return d / 1.609344 / w
    }
}

enum QsoBands {
    static let table: [(band: String, lo: Double, hi: Double)] = [
        ("2190m", 0.1357, 0.1378), ("630m", 0.472, 0.479), ("160m", 1.8, 2.0), ("80m", 3.5, 4.0), ("60m", 5.06, 5.45), ("40m", 7.0, 7.3), ("30m", 10.1, 10.15),
        ("20m", 14.0, 14.35), ("17m", 18.068, 18.168), ("15m", 21.0, 21.45), ("12m", 24.89, 24.99), ("10m", 28.0, 29.7), ("6m", 50, 54), ("4m", 70, 71),
        ("2m", 144, 148), ("1.25m", 222, 225), ("70cm", 420, 450), ("33cm", 902, 928), ("23cm", 1240, 1300), ("13cm", 2300, 2450),
    ]

    static func band(mhz: Double) -> String? { table.first { mhz >= $0.lo && mhz <= $0.hi }?.band }
    static let modes = ["FT8", "FT4", "SSB", "CW", "FM", "AM", "RTTY", "PSK31", "JS8", "WSPR", "JT65", "PACKET", "WINLINK", "SSTV", "OTHER"]
}

enum ADIF {
    private static func field(_ name: String, _ value: String) -> String {
        value.isEmpty ? "" : "<\(name):\(value.utf8.count)>\(value) "
    }

    private static let dateF: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyyMMdd"; return f
    }()
    private static let timeF: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "HHmmss"; return f
    }()

    static func export(_ log: [Qso], program: String = "Pocket Nomad", version: String = "1.0") -> String {
        var out = "Pocket Nomad QSO log\n" + "<ADIF_VER:5>3.1.4 " + field("PROGRAMID", program) + field("PROGRAMVERSION", version) + "<EOH>\n\n"
        for q in log.sorted(by: { $0.when < $1.when }) {
            out += field("CALL", q.call) + field("QSO_DATE", dateF.string(from: q.when)) + field("TIME_ON", timeF.string(from: q.when))
                + field("BAND", q.band) + field("FREQ", q.mhz > 0 ? trimmed(q.mhz) : "") + field("MODE", adifMode(q.mode))
                + field("RST_SENT", q.rstSent) + field("RST_RCVD", q.rstRcvd) + field("GRIDSQUARE", q.grid) + field("NAME", q.name)
                + field("STATION_CALLSIGN", q.myCall) + field("MY_GRIDSQUARE", q.myGrid) + field("COMMENT", q.comment)
                + field("SIG", q.sig) + field("SIG_INFO", q.sigInfo) + field("TX_PWR", q.watts.map(trimmed) ?? "")
                + field("DISTANCE", q.distanceKm.map { String(format: "%.0f", $0) } ?? "") + "<EOR>\n"
        }
        return out
    }

    private static func trimmed(_ v: Double) -> String {
        var s = String(format: "%.6f", v)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    /// ADIF mode names for the ones that differ in the standard.
    private static func adifMode(_ m: String) -> String {
        switch m.uppercased() { case "PACKET": "PKT"; case "WINLINK": "PKT"; case "OTHER": ""; default: m.uppercased() }
    }

    /// Reads QSO records from an ADIF file (the header and unknown fields are skipped).
    static func parse(_ text: String) -> [Qso] {
        let upper = text
        let body: String
        if let r = upper.range(of: "<EOH>", options: .caseInsensitive) { body = String(upper[r.upperBound...]) } else { body = upper }
        var records: [[String: String]] = []
        var cur: [String: String] = [:]
        let scalars = Array(body.utf8)
        var i = 0
        while i < scalars.count {
            guard scalars[i] == UInt8(ascii: "<") else { i += 1; continue }
            guard let close = scalars[i...].firstIndex(of: UInt8(ascii: ">")) else { break }
            let tag = String(decoding: scalars[(i + 1)..<close], as: UTF8.self)
            let parts = tag.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            let name = parts[0].uppercased()
            i = close + 1
            if name == "EOR" { if !cur.isEmpty { records.append(cur) }; cur = [:]; continue }
            guard parts.count >= 2, let len = Int(parts[1]), len >= 0, i + len <= scalars.count else { continue }
            cur[name] = String(decoding: scalars[i..<(i + len)], as: UTF8.self)
            i += len
        }
        return records.compactMap { r in
            guard let call = r["CALL"], !call.isEmpty, let d = r["QSO_DATE"], d.count == 8 else { return nil }
            let t = (r["TIME_ON"] ?? "000000").padding(toLength: 6, withPad: "0", startingAt: 0)
            guard let when = dateTimeF.date(from: d + t.prefix(6)) else { return nil }
            var q = Qso(call: call.uppercased(), when: when, mhz: Double(r["FREQ"] ?? "") ?? 0, mode: (r["MODE"] ?? "").uppercased())
            if q.mhz == 0, let b = r["BAND"], let row = QsoBands.table.first(where: { $0.band.lowercased() == b.lowercased() }) { q.mhz = (row.lo + row.hi) / 2 }
            q.rstSent = r["RST_SENT"] ?? ""; q.rstRcvd = r["RST_RCVD"] ?? ""; q.grid = r["GRIDSQUARE"] ?? ""; q.name = r["NAME"] ?? ""
            q.myCall = r["STATION_CALLSIGN"] ?? r["OPERATOR"] ?? ""; q.myGrid = r["MY_GRIDSQUARE"] ?? ""; q.comment = r["COMMENT"] ?? r["NOTES"] ?? ""
            q.sig = r["SIG"] ?? ""; q.sigInfo = r["SIG_INFO"] ?? ""; q.watts = r["TX_PWR"].flatMap(Double.init)
            return q
        }
    }

    private static let dateTimeF: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyyMMddHHmmss"; return f
    }()
}

@MainActor
@Observable
final class QsoLogStore {
    static let shared = QsoLogStore()
    private(set) var log: [Qso] = []
    private let url: URL

    init(url: URL = URL.applicationSupportDirectory.appending(path: "qso-log.json")) {
        self.url = url
        if let d = try? Data(contentsOf: url) {
            let dec = JSONDecoder(); dec.dateDecodingStrategy = .secondsSince1970
            log = (try? dec.decode([Qso].self, from: d)) ?? []
        }
    }

    var newestFirst: [Qso] { log.sorted { $0.when > $1.when } }

    func add(_ q: Qso) { log.append(q); save() }
    func update(_ q: Qso) { if let i = log.firstIndex(where: { $0.id == q.id }) { log[i] = q; save() } }
    func remove(_ id: UUID) { log.removeAll { $0.id == id }; save() }

    /// Adds imported contacts, skipping ones already logged (same call, minute, and frequency band).
    @discardableResult
    func merge(_ incoming: [Qso]) -> Int {
        var added = 0
        for q in incoming where !log.contains(where: { $0.call == q.call && abs($0.when.timeIntervalSince(q.when)) < 60 && $0.band == q.band }) {
            log.append(q); added += 1
        }
        if added > 0 { save() }
        return added
    }

    func search(_ text: String) -> [Qso] {
        let q = text.trimmingCharacters(in: .whitespaces).uppercased()
        guard !q.isEmpty else { return newestFirst }
        return newestFirst.filter { $0.call.contains(q) || $0.name.uppercased().contains(q) || $0.sigInfo.uppercased().contains(q) || $0.grid.uppercased().contains(q) }
    }

    func exportADIF() -> String { ADIF.export(log) }

    private func save() {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .secondsSince1970
        try? enc.encode(log).write(to: url, options: .atomic)
    }
}
