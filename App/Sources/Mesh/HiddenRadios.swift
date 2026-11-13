// HiddenRadios.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Radios the user says are theirs (a second phone, a home repeater, a family
// member's radio). They count as the phone's own radios everywhere: never a
// coverage point, never credited, never on a map layer, never shared. Kept on
// this phone only.

import Foundation
import Observation
import SwiftUI

struct HiddenRadio: Codable, Hashable, Identifiable, Sendable {
    var firmware: MeshFirmware
    var num: UInt32
    var name: String
    /// Start of a MeshCore radio's public key, when known.
    var key: Data?

    /// Matches `MeshNode.listID`.
    var id: String { "\(firmware.rawValue).\(num)" }

    /// The MeshCore key bytes this radio is known by: its key prefix, else its
    /// node number (the first four key bytes, little-endian).
    var keyBytes: Data? {
        guard firmware == .meshcore else { return nil }
        if let key, !key.isEmpty { return key }
        guard num != 0 else { return nil }
        return Data([UInt8(num & 0xFF), UInt8(num >> 8 & 0xFF), UInt8(num >> 16 & 0xFF), UInt8(num >> 24)])
    }

    /// True for this radio by number, or (MeshCore) by a 1-3 byte path hash that
    /// is the start of its key, the same rule as the phone's own MeshCore radio.
    func matches(_ other: UInt32, on network: MeshFirmware) -> Bool {
        guard network == firmware else { return false }
        return MeshService.isOwnRadio(other, on: network, ownNums: [num], ownKeyPrefix: keyBytes)
    }

    /// True for this radio's entry on the community map (`t<num>` or `c<key hex>`).
    func matches(known r: KnownRadio) -> Bool {
        guard r.firmware == firmware else { return false }
        let id = r.id.lowercased()
        if firmware == .meshtastic { return id == "t\(num)" }
        guard let key = keyBytes else { return false }
        let hex = key.prefix(4).map { String(format: "%02x", $0) }.joined()
        return hex.count == 8 && id.hasPrefix("c" + hex)
    }
}

extension HiddenRadio {
    init(_ node: MeshNode) {
        self.init(firmware: node.firmware, num: node.num, name: node.displayName,
                  key: node.firmware == .meshcore ? node.publicKeyPrefix : nil)
    }

    /// From a community-map radio, or nil when its id can't be read.
    init?(_ r: KnownRadio) {
        let id = r.id.lowercased()
        switch r.firmware {
        case .meshtastic:
            guard id.hasPrefix("t"), let n = UInt32(id.dropFirst()), n != 0 else { return nil }
            self.init(firmware: .meshtastic, num: n, name: r.name, key: nil)
        case .meshcore:
            guard id.hasPrefix("c") else { return nil }
            let hex = Array(id.dropFirst().prefix(8))
            guard hex.count == 8 else { return nil }
            var bytes: [UInt8] = []
            for i in stride(from: 0, to: 8, by: 2) {
                guard let b = UInt8(String(hex[i ... i + 1]), radix: 16) else { return nil }
                bytes.append(b)
            }
            let key = Data(bytes)
            self.init(firmware: .meshcore, num: MeshCoreContact.nodeNum(for: key), name: r.name, key: key)
        }
    }
}

/// The hidden list, saved in UserDefaults.
@MainActor @Observable
final class HiddenRadioStore {
    static let shared = HiddenRadioStore()
    static let key = "mesh.hiddenFromMap"

    private(set) var radios: [HiddenRadio]
    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        radios = (defaults.data(forKey: Self.key)).flatMap { try? JSONDecoder().decode([HiddenRadio].self, from: $0) } ?? []
    }

    func hide(_ radio: HiddenRadio) {
        guard radio.num != 0, !radios.contains(where: { $0.id == radio.id }) else { return }
        radios.append(radio)
        save()
    }

    func unhide(_ id: String) {
        radios.removeAll { $0.id == id }
        save()
    }

    func isHidden(_ id: String) -> Bool { radios.contains { $0.id == id } }

    /// True when a node number (or MeshCore path hash) belongs to a hidden radio.
    func hides(_ num: UInt32, on network: MeshFirmware) -> Bool {
        num != 0 && radios.contains { $0.matches(num, on: network) }
    }

    func hides(known r: KnownRadio) -> Bool { radios.contains { $0.matches(known: r) } }

    private func save() {
        defaults.set(try? JSONEncoder().encode(radios), forKey: Self.key)
    }
}

/// "This is mine: hide it from the map", for a tapped radio.
struct HideMineButton: View {
    let radio: HiddenRadio
    var done: () -> Void = {}

    var body: some View {
        Button {
            HiddenRadioStore.shared.hide(radio)
            done()
        } label: {
            Label("This is mine: hide it from the map", systemImage: "eye.slash")
                .frame(maxWidth: .infinity, minHeight: MeshUI.minTap)
        }
        .buttonStyle(.bordered)
        .accessibilityHint("Your own phones and repeaters are never mapped, credited with coverage or shared. Undo it in Coverage settings.")
    }
}
