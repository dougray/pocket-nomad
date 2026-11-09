// MeshModels.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// App-level models: what the UI shows and what is saved to disk.

import Foundation

/// Whether the phone's Bluetooth can be used right now.
enum MeshBluetoothAvailability: Equatable, Sendable {
    case unknown
    case resetting
    case unsupported
    case unauthorized
    case poweredOff
    case poweredOn
}

/// Which mesh firmware a radio runs. The two networks don't talk to each other,
/// but the app speaks both protocols, and can hold one radio of each at once.
enum MeshFirmware: String, Codable, CaseIterable, Comparable, Sendable {
    case meshtastic
    case meshcore

    var displayName: String {
        switch self {
        case .meshtastic: "Meshtastic"
        case .meshcore: "MeshCore"
        }
    }

    /// Longest text the radio accepts. MeshCore's limit is lower and its channel
    /// messages also carry the sender's name.
    var maxTextBytes: Int {
        switch self {
        case .meshtastic: MeshConstants.maxTextBytes
        case .meshcore: 133
        }
    }

    static func < (a: MeshFirmware, b: MeshFirmware) -> Bool {
        allCases.firstIndex(of: a)! < allCases.firstIndex(of: b)!
    }
}

/// A mesh radio seen while scanning.
struct MeshDiscoveredDevice: Identifiable, Equatable, Sendable {
    let id: UUID
    var name: String
    var rssi: Int
    var lastSeen: Date
    var firmware: MeshFirmware = .meshtastic
}

/// Overall link state shown at the top of the Mesh tab.
enum MeshConnectionState: Equatable, Sendable {
    case idle
    case scanning
    case connecting(name: String)
    /// Connected over BLE, downloading nodes and settings from the radio.
    case syncing(name: String)
    case connected(name: String)
    /// Lost the link; iOS keeps a pending connection open and will reconnect.
    case reconnecting(name: String)
    /// A remembered radio that hasn't come back for a while (left at home, out of
    /// range). iOS still holds the pending connection, so it reconnects by itself
    /// when the radio is back; until then it counts as absent, not as a link to wait for.
    case notNearby(name: String)
    /// The user said this radio isn't with them today: no reconnecting until they
    /// tap Connect or the app next launches. It still reconnects at launch.
    case notWithMeToday(name: String)
    case failed(String)

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }

    /// Waiting for a pending Bluetooth connection to complete.
    var isWaiting: Bool {
        switch self {
        case .connecting, .reconnecting: return true
        default: return false
        }
    }

    var isNotWithMeToday: Bool {
        if case .notWithMeToday = self { return true }
        return false
    }

    /// Remembered but not here: not nearby, or not with me today.
    var isAway: Bool {
        switch self {
        case .notNearby, .notWithMeToday: return true
        default: return false
        }
    }

    var isLinkUp: Bool {
        switch self {
        case .syncing, .connected: return true
        default: return false
        }
    }
}

/// Delivery state of an outgoing text.
enum MeshDeliveryState: Codable, Equatable, Sendable {
    /// Handed to the radio over Bluetooth.
    case sent
    /// Another radio rebroadcast it (or our radio confirmed it went out).
    case heardByMesh
    /// A channel message that went out, but no other radio was heard repeating it
    /// (nobody in range). Not a failure: anyone who can hear you still got it.
    case notRepeated
    /// The destination radio acknowledged it (direct messages only).
    case delivered
    case failed(String)
    /// Received from someone else.
    case received
}

struct MeshMessage: Codable, Identifiable, Equatable, Sendable {
    var id: UUID = UUID()
    /// MeshPacket.id, used to match ACKs and drop duplicates.
    var packetID: UInt32
    var from: UInt32
    var to: UInt32
    var channel: UInt32
    var text: String
    var date: Date
    var isOutgoing: Bool
    var delivery: MeshDeliveryState
    var rxSNR: Float?
    var hopsAway: UInt32?
    /// MeshCore channel messages name their sender in the text instead of by node.
    var senderName: String?
    /// Which network (radio) it came in on or went out on. Files from before
    /// two-radio support have none; MeshService fills it in when loading.
    var network: MeshFirmware?

    var isBroadcast: Bool { to == MeshConstants.broadcastNum }
}

/// A radio known to the mesh.
struct MeshNode: Codable, Identifiable, Equatable, Sendable {
    var num: UInt32
    var userID: String = ""
    var longName: String = ""
    var shortName: String = ""
    var hwModel: UInt32 = 0
    var lastHeard: Date?
    var snr: Float?
    var hopsAway: UInt32?
    var latitude: Double?
    var longitude: Double?
    var altitude: Int32?
    var batteryLevel: UInt32?
    var hasPublicKey = false
    /// MeshCore: first 6 bytes of the contact's public key, used to address direct messages.
    var publicKeyPrefix: Data?
    /// MeshCore: what kind of node advertised (person, repeater, room server, sensor).
    var meshCoreType: MeshCoreContactType?
    /// Which network this node was heard on. Filled in by MeshService.
    var network: MeshFirmware?
    /// Meshtastic: the device role from its user info (0 client, 2 router, 4 repeater...).
    var role: UInt32?

    var id: UInt32 { num }

    /// "!a1b2c3d4", the Meshtastic way of writing a node number.
    var hexID: String { MeshNode.hexID(num) }

    var displayName: String {
        if !longName.isEmpty { return longName }
        if !shortName.isEmpty { return shortName }
        return hexID
    }

    var badge: String {
        if !shortName.isEmpty { return shortName }
        return String(format: "%04x", num & 0xFFFF)
    }

    var hasPosition: Bool { latitude != nil && longitude != nil }

    /// Infrastructure rather than a person's radio: a MeshCore repeater or room
    /// server, or a Meshtastic router or repeater.
    var isRepeater: Bool {
        if let t = meshCoreType { return t == .repeater || t == .room }
        guard let role else { return false }
        return [2, 3, 4, 11].contains(role) // ROUTER, ROUTER_CLIENT, REPEATER, ROUTER_LATE
    }

    /// The network, guessing from MeshCore-only fields for nodes saved before it was stored.
    var firmware: MeshFirmware { network ?? (meshCoreType != nil || publicKeyPrefix != nil ? .meshcore : .meshtastic) }

    static func hexID(_ num: UInt32) -> String { String(format: "!%08x", num) }
}

/// Which chat thread a message belongs to.
enum MeshConversation: Hashable, Codable, Sendable {
    case channel(UInt32)
    case direct(UInt32)

    var key: String {
        switch self {
        case .channel(let i): return "ch\(i)"
        case .direct(let n): return "dm\(n)"
        }
    }

    /// Unread-tracking key. Conversations are per network: channel 0 on
    /// Meshtastic and channel 0 on MeshCore are different threads.
    func key(on network: MeshFirmware) -> String { "\(network.rawValue).\(key)" }
}

/// Settings and identity learned from the connected radio.
struct MeshRadioInfo: Codable, Equatable, Sendable {
    var firmware: MeshFirmware?
    var myNodeNum: UInt32 = 0
    var firmwareVersion: String = ""
    var region: UInt32?
    var regionName: String?
    var hopLimit: UInt32?
    /// Meshtastic LoRa config: modem preset and whether it is used.
    var modemPreset: UInt32?
    var usePreset: Bool?
    /// MeshCore radio settings and TX power from SELF_INFO.
    var meshCoreParams: MeshCoreRadioParams?
    var txPowerDBm: Int8?
    var maxTxPowerDBm: Int8?
    var primaryChannelName: String = ""
    var channelNames: [Int32: String] = [:]
}

/// Builds the emergency text. Kept short to fit the 200-byte limit.
enum MeshSOS {
    static func text(latitude: Double?, longitude: Double?, senderName: String?) -> String {
        var parts = ["SOS - I need help."]
        if let latitude, let longitude {
            parts.append("Location: \(format(latitude)), \(format(longitude))")
        } else {
            parts.append("Location unknown.")
        }
        if let senderName, !senderName.isEmpty {
            parts.append("From \(senderName).")
        }
        parts.append("Please reply.")
        return MeshText.truncated(parts.joined(separator: " "), maxBytes: MeshConstants.maxTextBytes)
    }

    /// Decimal degrees, exactly five places, with a dot separator in any locale.
    static func format(_ degrees: Double) -> String {
        String(format: "%.5f", locale: Locale(identifier: "en_US_POSIX"), degrees)
    }
}

enum MeshText {
    static func byteCount(_ s: String) -> Int { s.utf8.count }

    /// Trims a string to fit `maxBytes` of UTF-8 without splitting a character.
    static func truncated(_ s: String, maxBytes: Int) -> String {
        guard s.utf8.count > maxBytes else { return s }
        var out = ""
        var used = 0
        for ch in s {
            let n = String(ch).utf8.count
            if used + n > maxBytes { break }
            out.append(ch)
            used += n
        }
        return out
    }
}
