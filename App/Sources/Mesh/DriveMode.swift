// DriveMode.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Drive mode: keeps the coverage scanner running with the phone locked or in
// a mount, and (optionally) sends a drive-test ping every few hundred metres
// to map where messages actually get through, not just where others are heard.
// Pings are rate limited so a drive doesn't crowd the mesh.

import CoreLocation
import Foundation
import Observation
import UIKit

@MainActor @Observable
final class DriveMode {
    static let shared = DriveMode()

    /// At most one ping per 45 s, and only after moving 300 m (about one map hexagon).
    static let minInterval: TimeInterval = 45
    static let minDistance: Double = 300
    /// Drive mode turns itself off after this long, in case it is forgotten.
    static let maxDuration: TimeInterval = 4 * 3600

    private(set) var active = false
    private(set) var startedAt: Date?
    private(set) var sent = 0
    private(set) var reached = 0
    private(set) var relayed = 0
    private(set) var unanswered = 0
    private(set) var lastOutcome: MeshPingOutcome?
    private(set) var waiting = false
    /// Why pings aren't going out right now, for the status line.
    private(set) var pingNote: String?

    var pinging: Bool {
        didSet { defaults.set(pinging, forKey: Keys.pinging) }
    }
    /// Radio to ping; nil means "any radio that repeats it" (Meshtastic only).
    var target: UInt32? {
        didSet { defaults.set(target.map(Int.init) ?? 0, forKey: Keys.target) }
    }
    var keepScreenOn: Bool {
        didSet {
            defaults.set(keepScreenOn, forKey: Keys.screen)
            if active { UIApplication.shared.isIdleTimerDisabled = keepScreenOn }
        }
    }

    private let defaults = UserDefaults.standard
    private var loop: Task<Void, Never>?
    private var lastPing: (date: Date, location: CLLocation)?
    private var coverageWasOn = false

    private enum Keys {
        static let pinging = "drive.pinging"
        static let target = "drive.target"
        static let screen = "drive.keepScreenOn"
    }

    private init() {
        pinging = defaults.object(forKey: Keys.pinging) as? Bool ?? true
        let t = defaults.integer(forKey: Keys.target)
        target = t == 0 ? nil : UInt32(truncatingIfNeeded: t)
        keepScreenOn = defaults.object(forKey: Keys.screen) as? Bool ?? true
    }

    func start() {
        guard !active else { return }
        active = true
        startedAt = Date()
        sent = 0; reached = 0; relayed = 0; unanswered = 0
        lastOutcome = nil
        lastPing = nil
        coverageWasOn = CoverageStore.shared.recording
        CoverageStore.shared.recording = true
        LocationService.shared.beginBackground()
        UIApplication.shared.isIdleTimerDisabled = keepScreenOn
        loop = Task { [weak self] in
            while !Task.isCancelled {
                self?.tick()
                try? await Task.sleep(for: .seconds(5))
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
        if !coverageWasOn { CoverageStore.shared.recording = false }
        pingNote = nil
    }

    private func tick() {
        if let startedAt, Date().timeIntervalSince(startedAt) > Self.maxDuration { stop(); return }
        guard pinging else { pingNote = nil; return }
        let mesh = MeshService.shared
        guard case .connected = mesh.connection else { pingNote = "Waiting for the radio"; return }
        if mesh.firmware == .meshcore && target == nil { pingNote = "MeshCore pings need a radio to ping"; return }
        if let target, mesh.nodes[target] == nil { pingNote = "The radio to ping isn't known on this mesh"; return }
        guard let fix = CoverageStore.shared.freshFix() else { pingNote = "Waiting for GPS"; return }
        pingNote = nil
        guard !waiting else { return }
        if let last = lastPing {
            guard Date().timeIntervalSince(last.date) >= Self.minInterval,
                  fix.distance(from: last.location) >= Self.minDistance else { return }
        }
        let firmware = mesh.firmware
        let sentOK = mesh.ping(target) { [weak self] outcome in
            guard let self else { return }
            self.waiting = false
            self.lastOutcome = outcome
            switch outcome {
            case .reached: self.reached += 1
            case .relayed: self.relayed += 1
            case .none: self.unanswered += 1
            }
            CoverageStore.shared.recordPing(outcome, at: fix, firmware: firmware)
        }
        if sentOK {
            waiting = true
            sent += 1
            lastPing = (Date(), fix)
        }
    }
}
