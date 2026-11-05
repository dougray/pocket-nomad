// MeshStore.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Saves messages, nodes and radio info as JSON in Application Support/Mesh.
// Files use "until first unlock" protection so a background relaunch (radio
// data arriving while the phone is locked) can still write them.

import Foundation

struct MeshSnapshot: Codable, Equatable, Sendable {
    var messages: [MeshMessage] = []
    /// One radio's nodes and settings: files from before two-radio support
    /// have only these. Newer files also keep the main network here, so an
    /// older app version can still read them.
    var nodes: [MeshNode] = []
    var radio = MeshRadioInfo()
    /// Last read time per conversation key, for unread badges.
    var lastRead: [String: Date] = [:]
    /// Nodes and radio settings per network (one Meshtastic, one MeshCore).
    var networks: [MeshNetworkSnapshot]?
}

/// What is saved for one network.
struct MeshNetworkSnapshot: Codable, Equatable, Sendable {
    var firmware: MeshFirmware
    var nodes: [MeshNode] = []
    var radio = MeshRadioInfo()
}

struct MeshStore: Sendable {
    static let maxMessages = 2000

    let directory: URL

    init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            self.directory = base.appendingPathComponent("Mesh", isDirectory: true)
        }
    }

    private var fileURL: URL { directory.appendingPathComponent("mesh.json") }

    func load() -> MeshSnapshot {
        guard let data = try? Data(contentsOf: fileURL) else { return MeshSnapshot() }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return (try? decoder.decode(MeshSnapshot.self, from: data)) ?? MeshSnapshot()
    }

    func save(_ snapshot: MeshSnapshot) throws {
        var trimmed = snapshot
        trimmed.messages = Self.capped(snapshot.messages)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        let data = try encoder.encode(trimmed)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        #if os(iOS)
        try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: fileURL, options: [.atomic])
        #endif
    }

    /// Keeps the newest `maxMessages`.
    static func capped(_ messages: [MeshMessage], limit: Int = maxMessages) -> [MeshMessage] {
        guard messages.count > limit else { return messages }
        return Array(messages.suffix(limit))
    }
}
