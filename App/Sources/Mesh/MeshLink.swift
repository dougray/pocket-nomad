// MeshLink.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// One radio's side of MeshService: which device it is, its link state, the
// handshake bookkeeping and the node list and settings it downloaded.
// MeshService keeps one link per firmware (Meshtastic and MeshCore), so up to
// two radios can be connected at once; the protocol code that drives a link
// lives in MeshService, which also owns the shared chat history.

import Foundation
import Observation

@Observable
@MainActor
final class MeshLink: Identifiable {
    /// The network this slot is for. A radio that turns out to run the other
    /// firmware is moved to the other slot when it is ready.
    nonisolated let firmware: MeshFirmware
    nonisolated var id: MeshFirmware { firmware }

    /// The remembered radio for this network, if any.
    var deviceID: UUID?
    var deviceName: String?
    /// Reconnect at launch (cleared by a user disconnect).
    var autoReconnect = false
    var connection: MeshConnectionState = .idle
    /// Nodes heard on this network. Node numbers are only unique per network.
    var nodes: [UInt32: MeshNode] = [:]
    var radio = MeshRadioInfo()

    init(firmware: MeshFirmware) {
        self.firmware = firmware
    }

    var isRemembered: Bool { deviceID != nil }
    var displayName: String { deviceName ?? "\(firmware.displayName) radio" }
    var myNode: MeshNode? { nodes[radio.myNodeNum] }
    /// The radio's own battery, when it reported one (Meshtastic device metrics).
    var batteryLevel: UInt32? { myNode?.batteryLevel }
    /// Anything worth saving or showing for this network.
    var hasData: Bool { isRemembered || !nodes.isEmpty || radio.myNodeNum != 0 }

    /// Reads, changes and stores a node, tagging it with this network.
    func update(_ num: UInt32, _ change: (inout MeshNode) -> Void) {
        var n = nodes[num] ?? MeshNode(num: num)
        change(&n)
        n.network = firmware
        nodes[num] = n
    }

    // MARK: Meshtastic handshake and read loop

    @ObservationIgnored var configID: UInt32 = 0
    @ObservationIgnored var handshakeDone = false
    @ObservationIgnored var handshakeAttempts = 0
    @ObservationIgnored var isReading = false
    @ObservationIgnored var readAgain = false
    @ObservationIgnored var handshakeTimeout: Task<Void, Never>?

    // MARK: MeshCore: one command in flight at a time, each waiting for its reply

    struct CoreCommand {
        let frame: Data
        /// Response codes that finish this command (errors always do).
        let finishedBy: Set<UInt8>
        var timeout: Duration = .seconds(5)
        var onReply: ((MeshCoreFrame) -> Void)?
        /// Called when no reply came in time.
        var onTimeout: (() -> Void)?
    }
    @ObservationIgnored var coreQueue: [CoreCommand] = []
    @ObservationIgnored var corePending: CoreCommand?
    @ObservationIgnored var coreTimer: Task<Void, Never>?

    // MARK: Radio settings

    /// Progress of a change made on the Radio settings screen.
    var radioApply: RadioApplyState = .idle
    /// Meshtastic: the whole LoRa config as the radio last sent it.
    @ObservationIgnored var lastLoRa: MeshPBLoRaConfig?
    /// Meshtastic: the region and preset the user asked for, until the radio confirms.
    @ObservationIgnored var wantedLoRa: (region: UInt32, preset: UInt32)?
    /// Meshtastic: packet ids of the admin request in flight (0 = none).
    @ObservationIgnored var adminGetID: UInt32 = 0
    @ObservationIgnored var adminSetID: UInt32 = 0
    /// MeshCore: what the radio should report after its restart.
    @ObservationIgnored var wantedCore: MeshCoreRadioParams?
    @ObservationIgnored var wantedTxPower: Int8?
    /// Saved on the radio, waiting for it to restart and report back.
    @ObservationIgnored var awaitingRestart = false
    @ObservationIgnored var applyTimer: Task<Void, Never>?

    // MARK: Drive-test pings

    struct PendingPing {
        var target: UInt32?
        var best: MeshPingOutcome = .none
        var done: (MeshPingOutcome) -> Void
        var timeout: Task<Void, Never>?
    }
    @ObservationIgnored var pendingPings: [UInt32: PendingPing] = [:]

    // MARK: MeshCore: repeaters heard directly (the last hop of flood packets)

    struct Neighbor: Equatable, Sendable {
        var hash: Data
        var lastHeard: Date
        var snr: Float
    }

    /// Keyed by `MeshCoreContact.nodeNum(forHash:)`.
    var neighbors: [UInt32: Neighbor] = [:]

    /// The repeater heard most recently within `window`, for drive-test pings
    /// when no radio was chosen.
    func latestNeighbor(within window: TimeInterval, now: Date = Date()) -> UInt32? {
        neighbors.filter { now.timeIntervalSince($0.value.lastHeard) <= window }
            .max { $0.value.lastHeard < $1.value.lastHeard }?.key
    }
}

/// What one radio did with an SOS.
struct MeshSOSResult: Equatable, Sendable {
    var network: MeshFirmware
    var radioName: String
    /// The text sent, or nil if this radio could not send it.
    var text: String?
    var sent: Bool { text != nil }
}
