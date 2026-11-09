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
    static let shared = DriveMode(log: .shared)

    /// At most one ping per 45 s per radio, and only after moving 300 m (about one map hexagon).
    static let minInterval: TimeInterval = 45
    static let minDistance: Double = 300
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
    /// Radio to ping on each network; none means "any radio that repeats it" on
    /// Meshtastic and "the repeater heard most recently" on MeshCore.
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
    private let log: DriveLog?
    /// Called after each drive ends (the automatic backup hangs off this).
    @ObservationIgnored var onStop: (() -> Void)?
    /// For the drive log: what was last written about each radio, and when the last heartbeat went in.
    private var loggedConnection: [MeshFirmware: String] = [:]
    private var loggedNotes: [MeshFirmware: String] = [:]
    private var lastHeartbeat: Date?
    private var samplesAtHeartbeat = 0
    private var briefDropsAtHeartbeat: [MeshFirmware: Int] = [:]

    private enum Keys {
        static let pinging = "drive.pinging"
        /// Before two-radio support: one target for the one radio.
        static let target = "drive.target"
        static func target(_ f: MeshFirmware) -> String { "drive.target.\(f.rawValue)" }
        static let screen = "drive.keepScreenOn"
        /// Set while a drive runs, so a drive survives iOS closing the app.
        static let activeSince = "drive.activeSince"
        static let coverageWasOn = "drive.coverageWasOn"
    }

    init(mesh: MeshService = .shared, coverage: CoverageStore = .shared,
         defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init, log: DriveLog? = nil) {
        self.log = log
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
        coverageWasOn = coverage.recording
        begin(at: now())
        log?.note("drive mode on")
    }

    /// Picks a drive back up after iOS closed the app mid-drive (it relaunches
    /// it in the background for Bluetooth or location). Call once at launch.
    func resumeIfNeeded() {
        guard !active, let since = defaults.object(forKey: Keys.activeSince) as? Date else { return }
        coverageWasOn = defaults.bool(forKey: Keys.coverageWasOn)
        let inBackground = UIApplication.shared.applicationState == .background
        begin(at: since)
        log?.note("drive mode resumed after iOS closed the app" + (inBackground ? " (relaunched in the background)" : ""))
    }

    private func begin(at since: Date) {
        active = true
        startedAt = since
        defaults.set(since, forKey: Keys.activeSince)
        defaults.set(coverageWasOn, forKey: Keys.coverageWasOn)
        tallies = [:]
        lastPing = [:]
        waitingOn = []
        loggedConnection = [:]
        loggedNotes = [:]
        lastHeartbeat = nil
        samplesAtHeartbeat = coverage.samples.count
        briefDropsAtHeartbeat = Dictionary(uniqueKeysWithValues: mesh.links.map { ($0.firmware, $0.briefDrops) })
        coverage.recording = true
        log?.watchApp { [weak self] in self?.active == true }
        LocationService.shared.beginBackground()
        UIApplication.shared.isIdleTimerDisabled = keepScreenOn
        loop = Task { [weak self] in
            while !Task.isCancelled {
                self?.tick()
                try? await Task.sleep(for: Self.tickInterval)
            }
        }
    }

    func stop(reason: String = "turned off") {
        guard active else { return }
        log?.note("drive mode off: \(reason) · \(sent) pings, \(reached) reached, \(relayed) relayed, \(unanswered) unanswered")
        active = false
        defaults.removeObject(forKey: Keys.activeSince)
        loop?.cancel()
        loop = nil
        LocationService.shared.endBackground()
        UIApplication.shared.isIdleTimerDisabled = false
        if !coverageWasOn { coverage.recording = false }
        notes = [:]
        onStop?()
    }

    // MARK: Backup

    /// The drive settings and ping targets, for the backup file.
    var backupSettings: CoverageBackup.Drive {
        CoverageBackup.Drive(pinging: pinging, keepScreenOn: keepScreenOn,
                             targets: Dictionary(uniqueKeysWithValues: targets.map { ($0.key.rawValue, $0.value) }))
    }

    /// Takes the settings from a backup. The switches come back only when
    /// `switches` is set (a phone with no coverage data yet); ping targets only
    /// fill networks that have none here, so a restore never undoes a choice made on this phone.
    func restore(_ saved: CoverageBackup.Drive, switches: Bool) {
        if switches, let p = saved.pinging { pinging = p }
        if switches, let k = saved.keepScreenOn { keepScreenOn = k }
        for (name, num) in saved.targets ?? [:] {
            guard let f = MeshFirmware(rawValue: name), targets[f] == nil, num != 0 else { continue }
            setTarget(num, for: f)
        }
    }

    /// One check: sends at most one ping, from the connected radio that pinged
    /// longest ago and is due. The other radio gets its turn on a later tick.
    /// A radio that is "not nearby" or "not with me today" counts as absent:
    /// no pings, no "waiting" note.
    func tick() {
        mesh.updateLinks()
        logState()
        guard pinging else { notes = [:]; return }
        var newNotes: [MeshFirmware: String] = [:]
        defer { notes = newNotes; logNotes(newNotes) }
        let candidates = mesh.links.filter { ($0.isRemembered && !$0.connection.isAway) || $0.connection.isConnected }
        guard !candidates.isEmpty else {
            if mesh.rememberedLinks.isEmpty { newNotes[mesh.defaultNetwork] = "Waiting for the radio" }
            return
        }
        var pinged = false
        let order = candidates.sorted {
            (lastPing[$0.firmware]?.date ?? .distantPast, $0.firmware) < (lastPing[$1.firmware]?.date ?? .distantPast, $1.firmware)
        }
        for l in order {
            let network = l.firmware
            guard l.connection.isConnected else {
                // A brief drop isn't shown: pings just pause until it's back.
                if !l.displayedConnection.isConnected { newNotes[network] = "Waiting for the radio" }
                continue
            }
            let target = targets[network] ?? mesh.autoPingTarget(on: network)
            if network == .meshcore && target == nil { newNotes[network] = "Waiting to hear a repeater"; continue }
            if let target, l.nodes[target] == nil, l.neighbors[target] == nil {
                newNotes[network] = "The radio to ping isn't known on this mesh"; continue
            }
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
                self.log?.note("\(network.displayName) ping: \(outcome)")
            }
            if sentOK {
                log?.note("\(network.displayName) ping sent" + (target.map { " to \(MeshNode.hexID($0))" } ?? ""))
                pinged = true
                waitingOn.insert(network)
                tallies[network, default: Tally()].sent += 1
                lastPing[network] = (now(), fix)
            }
        }
    }

    // MARK: Drive log

    /// Radio links coming and going (as shown: drops under 20 s are only
    /// counted, in the heartbeat), and a heartbeat each minute.
    private func logState() {
        guard let log else { return }
        for l in mesh.links where l.isRemembered || l.displayedConnection.isConnected {
            let state = Self.logState(l.displayedConnection)
            let before = loggedConnection[l.firmware]
            guard before != state else { continue }
            loggedConnection[l.firmware] = state
            var line = "\(l.firmware.displayName) radio \(state)"
            switch l.displayedConnection {
            case .reconnecting, .idle, .failed:
                if let why = l.lastDropReason, before == Self.logState(.connected(name: "")) { line += " (\(why))" }
            case .connected where before?.hasPrefix("not nearby") == true:
                line += " (back in range, no longer not nearby)"
            case .connected where before?.hasPrefix("not with me today") == true:
                line += " (no longer not with me today)"
            default:
                break
            }
            log.note(line)
        }
        let t = now()
        if let last = lastHeartbeat, t.timeIntervalSince(last) < 60 { return }
        lastHeartbeat = t
        let gps: String
        if let fix = coverage.freshFix() {
            gps = "GPS ±\(Int(fix.horizontalAccuracy)) m"
        } else {
            gps = "no fresh GPS fix"
        }
        let points = coverage.samples.count
        var drops = ""
        for l in mesh.links {
            let n = l.briefDrops - (briefDropsAtHeartbeat[l.firmware] ?? 0)
            if n > 0 { drops += " · \(l.firmware.displayName) brief drop ×\(n)" }
            briefDropsAtHeartbeat[l.firmware] = l.briefDrops
        }
        log.note("still running · \(gps) · \(points - samplesAtHeartbeat) new coverage points\(drops) · app \(Self.appState)")
        samplesAtHeartbeat = points
    }

    /// A link state in drive-log words.
    private static func logState(_ state: MeshConnectionState) -> String {
        switch state {
        case .connected: "connected"
        case .syncing: "syncing"
        case .connecting: "connecting"
        case .reconnecting: "dropped, reconnecting"
        case .notNearby: "not nearby · reconnects when it's back"
        case .notWithMeToday: "not with me today · not reconnecting until Connect"
        case .idle: "disconnected"
        case .scanning: "scanning"
        case .failed(let why): "failed: \(why)"
        }
    }

    private func logNotes(_ new: [MeshFirmware: String]) {
        guard let log else { return }
        for f in MeshFirmware.allCases where loggedNotes[f] != new[f] {
            if let note = new[f] { log.note("\(f.displayName) pings waiting: \(note)") }
            loggedNotes[f] = new[f]
        }
    }

    private static var appState: String {
        switch UIApplication.shared.applicationState {
        case .active: "in front"
        case .inactive: "inactive"
        case .background: "in the background"
        @unknown default: "unknown"
        }
    }
}
