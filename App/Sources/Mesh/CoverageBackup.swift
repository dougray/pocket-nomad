// CoverageBackup.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// One file that holds everything the coverage scanner has learned: every
// sample and drive-test ping, the private zones, and the scanner and drive-mode
// settings. Deleting the app deletes all of that, so this is what the user
// keeps in a folder on the phone outside the app (On My iPhone) and restores
// from after a reinstall.
//
// The Android app reads and writes the same file, so the top-level keys and
// the sample and ping keys (the same as coverage.json and
// coverage.pings.json) are a shared format: change them only with a new
// `version`. Sample and ping dates are written as Unix milliseconds and
// `createdAt` as ISO 8601 (UTC); reading accepts milliseconds, Unix seconds,
// Apple reference-date seconds or ISO 8601 text for any date. A record that
// can't be read is skipped; the rest of the file still restores.
//
// Restoring merges: points already on the phone are kept, duplicates are
// skipped, and nothing is ever deleted.

import Foundation

struct CoverageBackup: Codable, Sendable {
    /// Bump when the format changes in a way older apps can't read.
    static let currentVersion = 1
    static let fileName = "pocket-nomad-backup.json"

    var version: Int
    /// What wrote it, such as "Pocket Nomad iOS 1.0".
    var app: String
    var createdAt: Date
    var samples: [CoverageSample]
    var pings: [CoveragePing]
    var zones: [CoverageZone]
    var settings: Settings
    var drive: Drive

    struct Settings: Codable, Equatable, Sendable {
        var recording: Bool?
        var sharing: Bool?
        var retentionDays: Int?
    }

    struct Drive: Codable, Equatable, Sendable {
        var pinging: Bool?
        var keepScreenOn: Bool?
        /// The radio each network pings, by network name ("meshtastic", "meshcore").
        var targets: [String: UInt32]?

        init(pinging: Bool? = nil, keepScreenOn: Bool? = nil, targets: [String: UInt32]? = nil) {
            self.pinging = pinging
            self.keepScreenOn = keepScreenOn
            self.targets = targets
        }

        /// Lenient: a target may be null (no radio picked) or not a number.
        init(from decoder: Decoder) throws {
            enum Keys: String, CodingKey { case pinging, keepScreenOn, targets }
            let c = try decoder.container(keyedBy: Keys.self)
            pinging = try? c.decodeIfPresent(Bool.self, forKey: .pinging)
            keepScreenOn = try? c.decodeIfPresent(Bool.self, forKey: .keepScreenOn)
            let raw = (try? c.decodeIfPresent([String: Lossy<UInt32>].self, forKey: .targets)) ?? nil
            targets = raw.map { $0.compactMapValues(\.value) }
        }
    }

    init(version: Int, app: String, createdAt: Date, samples: [CoverageSample], pings: [CoveragePing],
         zones: [CoverageZone], settings: Settings, drive: Drive) {
        self.version = version
        self.app = app
        self.createdAt = createdAt
        self.samples = samples
        self.pings = pings
        self.zones = zones
        self.settings = settings
        self.drive = drive
    }

    private enum CodingKeys: String, CodingKey { case version, app, createdAt, samples, pings, zones, settings, drive }

    /// Lists are read record by record, so one bad sample doesn't lose the rest.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decode(Int.self, forKey: .version)
        app = (try? c.decodeIfPresent(String.self, forKey: .app)) ?? ""
        createdAt = (try? c.decodeIfPresent(Date.self, forKey: .createdAt)) ?? .distantPast
        samples = ((try? c.decodeIfPresent([Lossy<CoverageSample>].self, forKey: .samples)) ?? []).compactMap(\.value)
        pings = ((try? c.decodeIfPresent([Lossy<CoveragePing>].self, forKey: .pings)) ?? []).compactMap(\.value)
        zones = ((try? c.decodeIfPresent([Lossy<CoverageZone>].self, forKey: .zones)) ?? []).compactMap(\.value)
        settings = ((try? c.decodeIfPresent(Settings.self, forKey: .settings)) ?? nil) ?? Settings()
        drive = ((try? c.decodeIfPresent(Drive.self, forKey: .drive)) ?? nil) ?? Drive()
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(version, forKey: .version)
        try c.encode(app, forKey: .app)
        // The one date written as text; record dates go through the encoder's millisecond strategy.
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        iso.timeZone = TimeZone(identifier: "UTC")
        try c.encode(iso.string(from: createdAt), forKey: .createdAt)
        try c.encode(samples, forKey: .samples)
        try c.encode(pings, forKey: .pings)
        try c.encode(zones, forKey: .zones)
        try c.encode(settings, forKey: .settings)
        try c.encode(drive, forKey: .drive)
    }

    /// Something that decodes to nil instead of failing.
    struct Lossy<T: Decodable>: Decodable {
        var value: T?
        init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    }

    /// Nothing worth writing: a backup like this would only hide an older, fuller one.
    var isEmpty: Bool { samples.isEmpty && pings.isEmpty && zones.isEmpty }

    static var appName: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        return "Pocket Nomad iOS \(version)"
    }

    enum ReadError: LocalizedError {
        case notABackup
        case tooNew(Int)

        var errorDescription: String? {
            switch self {
            case .notABackup: "This file isn't a Pocket Nomad backup."
            case .tooNew(let v): "This backup was made by a newer Pocket Nomad (format \(v)). Update the app, then try again."
            }
        }
    }

    // MARK: Reading and writing

    func encoded() throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        e.dateEncodingStrategy = .custom { date, encoder in
            var c = encoder.singleValueContainer()
            try c.encode(Int64((date.timeIntervalSince1970 * 1000).rounded()))
        }
        return try e.encode(self)
    }

    static func decode(_ data: Data) throws -> CoverageBackup {
        // Check the version on its own first, so a newer file gets a clear message
        // instead of a decoding error.
        struct Header: Decodable { var version: Int? }
        guard let header = try? JSONDecoder().decode(Header.self, from: data), let version = header.version else {
            throw ReadError.notABackup
        }
        guard version <= currentVersion else { throw ReadError.tooNew(version) }
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let c = try decoder.singleValueContainer()
            if let n = try? c.decode(Double.self) { return date(fromNumber: n) }
            let text = try c.decode(String.self)
            if let date = isoDate(text) { return date }
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "Unreadable date \(text)")
        }
        do { return try d.decode(CoverageBackup.self, from: data) } catch { throw ReadError.notABackup }
    }

    /// Unix milliseconds (what this app writes), Unix seconds, or Apple
    /// reference-date seconds (what coverage.json holds), told apart by size.
    static func date(fromNumber n: Double) -> Date {
        if abs(n) >= 1e11 { return Date(timeIntervalSince1970: n / 1000) }
        if abs(n) >= 1e9 { return Date(timeIntervalSince1970: n) }
        return Date(timeIntervalSinceReferenceDate: n)
    }

    private static func isoDate(_ text: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: text) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: text)
    }

    // MARK: Merging

    /// What makes two samples the same point: time to the millisecond, place
    /// to about a centimetre, and network (the file's dates are milliseconds).
    struct PointKey: Hashable {
        var ms: Int64
        var lat: Int64
        var lon: Int64
        var firmware: MeshFirmware

        init(_ date: Date, _ latitude: Double, _ longitude: Double, _ firmware: MeshFirmware) {
            ms = Int64((date.timeIntervalSince1970 * 1000).rounded())
            lat = Int64((latitude * 1e7).rounded())
            lon = Int64((longitude * 1e7).rounded())
            self.firmware = firmware
        }
    }

    static func merge(_ existing: [CoverageSample], _ incoming: [CoverageSample]) -> (merged: [CoverageSample], added: Int) {
        mergePoints(existing, incoming) { PointKey($0.date, $0.latitude, $0.longitude, $0.firmware) }
    }

    static func merge(_ existing: [CoveragePing], _ incoming: [CoveragePing]) -> (merged: [CoveragePing], added: Int) {
        mergePoints(existing, incoming) { PointKey($0.date, $0.latitude, $0.longitude, $0.firmware) }
    }

    /// Keeps every existing point, adds incoming ones not already there (or
    /// repeated within the file), and sorts by date as the store expects.
    private static func mergePoints<T: HasDate>(_ existing: [T], _ incoming: [T], key: (T) -> PointKey) -> (merged: [T], added: Int) {
        var seen = Set(existing.map(key))
        var out = existing
        for p in incoming where seen.insert(key(p)).inserted { out.append(p) }
        let added = out.count - existing.count
        if added > 0 { out.sort { $0.date < $1.date } }
        return (out, added)
    }

    /// Zones match by name and centre (to about a metre); a restored zone never replaces one here.
    static func merge(_ existing: [CoverageZone], _ incoming: [CoverageZone]) -> (merged: [CoverageZone], added: Int) {
        func key(_ z: CoverageZone) -> String {
            "\(z.name)|\(Int64((z.latitude * 1e5).rounded()))|\(Int64((z.longitude * 1e5).rounded()))"
        }
        var seen = Set(existing.map(key))
        var out = existing
        for z in incoming where seen.insert(key(z)).inserted {
            var z = z
            if out.contains(where: { $0.id == z.id }) { z.id = UUID() }
            out.append(z)
        }
        return (out, out.count - existing.count)
    }
}

/// Points with a time, so samples and pings merge the same way.
protocol HasDate { var date: Date { get } }
extension CoverageSample: HasDate {}
extension CoveragePing: HasDate {}
