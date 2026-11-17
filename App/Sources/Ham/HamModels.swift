// HamModels.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

import Foundation

struct HamMonitorEntry: Identifiable, Equatable, Sendable {
    var id = UUID()
    var date: Date
    var line: String
    var outgoing: Bool
    var kind: APRSPacket.Kind?
}

/// A station (or object) heard on APRS.
struct HamStation: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var source: String
    var latitude: Double?
    var longitude: Double?
    var symbol = "/>"
    var comment = ""
    var status = ""
    var lastHeard: Date
    var firstHeard: Date
    var path: [String] = []
    var packets = 0
    var courseDegrees: Int?
    var speedKnots: Double?
    var altitudeFeet: Double?
    var weather: String?
    var micEStatus: String?
    var isObject = false
    var emergency = false
    /// Heard straight from the station rather than through a digipeater.
    var direct = false

    var hasPosition: Bool { latitude != nil && longitude != nil }
}

struct APRSChat: Codable, Identifiable, Equatable, Sendable {
    enum Status: String, Codable, Sendable { case sending, acked, failed, rejected, received, sent }
    var id = UUID()
    var peer: String
    var outgoing: Bool
    var text: String
    var msgID: String?
    var date: Date
    var status: Status
    var tries = 0
    var nextTry: Date?
}

struct APRSBulletin: Codable, Identifiable, Equatable, Sendable {
    var id: String { source + "/" + addressee }
    var source: String
    var addressee: String
    var text: String
    var date: Date
}

enum WinlinkFolder: String, CaseIterable, Identifiable, Sendable {
    case inbox = "Inbox", outbox = "Outbox", sent = "Sent"
    var id: String { rawValue }
}

/// JSON files in Application Support/Ham. Stays on the phone.
@MainActor
final class HamFiles {
    let directory: URL

    init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var d = directory
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? d.setResourceValues(rv)
    }

    static var standard: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appending(path: "Ham")
    }

    func load<T: Decodable>(_ name: String, as: T.Type = T.self) -> T? {
        guard let d = try? Data(contentsOf: directory.appending(path: name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }

    func save<T: Encodable>(_ v: T, _ name: String) {
        guard let d = try? JSONEncoder().encode(v) else { return }
        try? d.write(to: directory.appending(path: name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func removeAll() {
        for f in (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] {
            try? FileManager.default.removeItem(at: f)
        }
    }
}
