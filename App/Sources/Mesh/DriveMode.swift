// DriveMode.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Drive mode: keeps the coverage scanner running with the phone locked or in
// a mount, and (optionally) sends a drive-test ping every few hundred metres
// to map where messages actually get through, not just where others are heard.
// Pings are rate limited so a drive doesn't crowd the mesh.
//
// With two radios connected each one pings on its own network, with its own
// target, spacing and tally. Their pings are staggered: at most one ping goes
// out per tick, so the two never transmit in the same moment.

import CoreLocation
import Foundation
import Observation
import UIKit

@MainActor @Observable
final class DriveMode {
    static let shared = DriveMode()

    /// At most one ping per 45 s per radio, and only after moving 300 m (about one map hexagon).
    static let minInterval: TimeInterval = 45
    static let minDistance: Double = 300
    /// Drive mode turns itself off after this long, in case it is forgotten.
    static let maxDuration: TimeInterval = 4 * 3600
    /// How often drive mode checks whether a ping is due.
    static let tickInterval: Duration = .seconds(5)

    /// One network's ping results this drive.
    struct Tally: Equatable, Sendable {
        var sent = 0
        var reached = 0
        var relayed = 0
        var unanswered = 0
        var lastOutcome: MeshPingOutcome?
    }

    private(set) var active = false
    private(set) var startedAt: Date?
    private(set) var tallies: [MeshFirmware: Tally] = [:]
    /// Networks whose ping is still waiting for an answer.
    private(set) var waitingOn: Set<MeshFirmware> = []
    /// Why a network's pings aren't going out right now, for the status line.
    private(set) var notes: [MeshFirmware: String] = [:]

    var sent: Int { tallies.values.reduce(0) { $0 + $1.sent } }
    var reached: Int { tallies.values.reduce(0) { $0 + $1.reached } }
    var relayed: Int { tallies.values.reduce(0) { $0 + $1.relayed } }
    var unanswered: Int { tallies.values.reduce(0) { $0 + $1.unanswered } }
    var waiting: Bool { !waitingOn.isEmpty }
    var pingNote: String? {
        let lines = notes.sorted { $0.key < $1.key }.map { notes.count > 1 ? "\($0.key.displayName): \($0.value)" : $0.value }
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }

    var pinging: Bool {
        didSet { defaults.set(pinging, forKey: Keys.pinging) }
    }
    /// Radio to ping on each network; none means "any radio that repeats it"
    /// (Meshtastic only: MeshCore pings need a target).
    private(set) var targets: [MeshFirmware: UInt32] = [:]
    var keepScreenOn: Bool {
        didSet {
            defaults.set(keepScreenOn, forKey: Keys.screen)
            if active { UIApplication.shared.isIdleTimerDisabled = keepScreenOn }
        }
    }

    private let defaults: UserDefaults
    private let mesh: MeshService
    private let coverage: CoverageStore
    private let now: () -> Date
    private var loop: Task<Void, Never>?
    private var lastPing: [MeshFirmware: (date: Date, location: CLLocation)] = [:]
    private var coverageWasOn = false

    private enum Keys {
        static let pinging = "drive.pinging"
        /// Before two-radio support: one target for the one radio.
        static let target = "drive.target"
        static func target(_ f: MeshFirmware) -> String { "drive.target.\(f.rawValue)" }
        static let screen = "drive.keepScreenOn"
    }

    init(mesh: MeshService = .shared, coverage: CoverageStore = .shared,
         defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        self.mesh = mesh
        self.coverage = coverage
        self.defaults = defaults
        self.now = now
        pinging = defaults.object(forKey: Keys.pinging) as? Bool ?? true
        keepScreenOn = defaults.object(forKey: Keys.screen) as? Bool ?? true
        for f in MeshFirmware.allCases {
            let t = defaults.integer(forKey: Keys.target(f))
            if t != 0 { targets[f] = UInt32(truncatingIfNeeded: t) }
        }
        // The old single target belongs to whichever network knows that radio.
        let old = UInt32(truncatingIfNeeded: defaults.integer(forKey: Keys.target))
        if old != 0 {
            let network = MeshFirmware.allCases.first { mesh.link($0).nodes[old] != nil } ?? mesh.defaultNetwork
            if targets[network] == nil { setTarget(old, for: network) }
            defaults.removeObject(forKey: Keys.target)
        }
    }

    func target(for network: MeshFirmware) -> UInt32? { targets[network] }

    func setTarget(_ target: UInt32?, for network: MeshFirmware) {
        targets[network] = target
        defaults.set(target.map(Int.init) ?? 0, forKey: Keys.target(network))
    }

    func start() {
        guard !active else { return }
        active = true
        startedAt = now()
        tallies = [:]
        lastPing = [:]
        waitingOn = []
        coverageWasOn = coverage.recording
        coverage.recording = true
        LocationService.shared.beginBackground()
        UIApplication.shared.isIdleTimerDisabled = keepScreenOn
        loop = Task { [weak self] in
            while !Task.isCancelled {
                self?.tick()
                try? await Task.sleep(for: Self.tickInterval)
            }
        }
    }

    func stop() {
        guard active else { return }
        active = false
        loop?.cancel()
        loop = nil
        LocationService.shared.endBackground()
        UIApplication.shared.isIdleTimerDisabled = false
        if !coverageWasOn { coverage.recording = false }
        notes = [:]
    }

    /// One check: sends at most one ping, from the connected radio that pinged
    /// longest ago and is due. The other radio gets its turn on a later tick.
    func tick() {
        if let startedAt, now().timeIntervalSince(startedAt) > Self.maxDuration { stop(); return }
        guard pinging else { notes = [:]; return }
        var newNotes: [MeshFirmware: String] = [:]
        defer { notes = newNotes }
        let candidates = mesh.links.filter { $0.isRemembered || $0.connection.isConnected }
        guard !candidates.isEmpty else { newNotes[mesh.defaultNetwork] = "Waiting for the radio"; return }
        var pinged = false
        let order = candidates.sorted {
            (lastPing[$0.firmware]?.date ?? .distantPast, $0.firmware) < (lastPing[$1.firmware]?.date ?? .distantPast, $1.firmware)
        }
        for l in order {
            let network = l.firmware
            guard l.connection.isConnected else { newNotes[network] = "Waiting for the radio"; continue }
            let target = targets[network]
            if network == .meshcore && target == nil { newNotes[network] = "MeshCore pings need a radio to ping"; continue }
            if let target, l.nodes[target] == nil { newNotes[network] = "The radio to ping isn't known on this mesh"; continue }
            guard let fix = coverage.freshFix() else { newNotes[network] = "Waiting for GPS"; continue }
            guard !pinged, !waitingOn.contains(network) else { continue }
            if let last = lastPing[network] {
                guard now().timeIntervalSince(last.date) >= Self.minInterval,
                      fix.distance(from: last.location) >= Self.minDistance else { continue }
            }
            let sentOK = mesh.ping(target, on: network) { [weak self] outcome in
                guard let self else { return }
                self.waitingOn.remove(network)
                var t = self.tallies[network] ?? Tally()
                t.lastOutcome = outcome
                switch outcome {
                case .reached: t.reached += 1
                case .relayed: t.relayed += 1
                case .none: t.unanswered += 1
                }
                self.tallies[network] = t
                self.coverage.recordPing(outcome, at: fix, firmware: network)
            }
            if sentOK {
                pinged = true
                waitingOn.insert(network)
                tallies[network, default: Tally()].sent += 1
                lastPing[network] = (now(), fix)
            }
        }
    }
}
