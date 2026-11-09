// HeardCredit.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Which radio a coverage sample was actually heard from. A MeshCore flood
// packet only names the repeater in range of us by a 1-3 byte hash (the last
// hash in its path), and a 1-byte hash matches about one in 256 keys on the
// whole mesh, so a hash is credited to a known contact only when that contact
// has a position and was plausibly in radio range of where the phone was when
// it heard the packet. Anything else stays an "unidentified repeater": named by
// its hash, never drawn as a located radio.
//
// Samples keep what the radio said (a stand-in node number that encodes the
// hash, or the node itself for a straight-from-the-source packet), and credit
// is worked out here from each sample's own position every time it is shown,
// so recordings made under older, looser rules are re-derived without
// rewriting a saved file.

import Foundation

/// Who a sample is credited to.
enum HeardCredit: Equatable, Hashable, Sendable {
    /// No sender known (node 0), or nothing to name: counts for coverage only.
    case coverageOnly
    /// One of this phone's own radios (or a hash that could be one).
    case own
    /// A known radio with a position, heard directly and plausibly in range.
    case radio(UInt32)
    /// A repeater known only by its path hash. Shown as text, never on the map.
    case unidentified(hash: Data)
}

enum HeardResolver {
    /// How far a heard radio can plausibly be from the phone. LoRa from a hilltop
    /// reaches further now and then, but a repeater claiming to be further than
    /// this is far more likely a hash collision or a stale position.
    static let rangeMeters: Double = 40_000

    /// The hash a stand-in node number (`MeshCoreContact.nodeNum(forHash:)`) was
    /// made from: its little-endian bytes without the zero padding, 1 to 3 bytes.
    static func hash(ofStandIn num: UInt32) -> Data {
        var bytes = [UInt8(num & 0xFF), UInt8(num >> 8 & 0xFF), UInt8(num >> 16 & 0xFF)]
        while bytes.count > 1, bytes.last == 0 { bytes.removeLast() }
        return Data(bytes)
    }

    /// A MeshCore node number back to the first key bytes it was made from.
    static func keyBytes(_ num: UInt32) -> Data {
        Data([UInt8(num & 0xFF), UInt8(num >> 8 & 0xFF), UInt8(num >> 16 & 0xFF), UInt8(num >> 24)])
    }

    /// True when `num` is a stand-in for a hash rather than a real node: not a
    /// known node, and the top key byte is zero (a real key's fourth byte is
    /// zero only one time in 256, and those are known contacts anyway).
    static func isStandIn(_ num: UInt32, nodes: [UInt32: MeshNode]) -> Bool {
        num != 0 && num <= 0xFF_FFFF && nodes[num] == nil
    }

    /// The known MeshCore radio a last-hop hash most likely belongs to: its key
    /// starts with the hash, it has a position, and it is within range of the
    /// fix, and it is a repeater or room server (companions and sensors never
    /// pass packets on). The nearest wins. Nil when nothing qualifies.
    static func resolve(hash: Data, latitude: Double, longitude: Double, among nodes: some Sequence<MeshNode>,
                                    excluding isOwn: (UInt32) -> Bool = { _ in false },
                                    range: Double = rangeMeters) -> MeshNode? {
        guard !hash.isEmpty else { return nil }
        let candidates: [(node: MeshNode, distance: Double)] = nodes.compactMap { n in
            guard let key = n.publicKeyPrefix ?? (n.num == 0 ? nil : keyBytes(n.num)),
                  key.starts(with: hash), let lat = n.latitude, let lon = n.longitude, !isOwn(n.num) else { return nil }
            let d = CoverageShare.distance(latitude, longitude, lat, lon)
            return d <= range ? (n, d) : nil
        }
        return candidates.filter { $0.node.meshCoreType == .repeater || $0.node.meshCoreType == .room }.min { ($0.distance, $0.node.num) < ($1.distance, $1.node.num) }?.node
    }

    /// Who one sample is credited to. `nodes` are the sample's network's known
    /// radios by number; `isOwn` is the own-radio guard (it also catches a
    /// stand-in whose hash is the start of our own key).
    static func credit(_ s: CoverageSample, nodes: [UInt32: MeshNode], isOwn: (UInt32) -> Bool,
                                   range: Double = rangeMeters) -> HeardCredit {
        guard s.node != 0 else { return .coverageOnly }
        if isOwn(s.node) { return .own }
        // Hop count unknown (an old advert push, say): can't tell who was in range.
        guard let hops = s.hops else { return .coverageOnly }
        func located(_ n: MeshNode) -> Bool {
            guard let lat = n.latitude, let lon = n.longitude else { return false }
            return CoverageShare.distance(s.latitude, s.longitude, lat, lon) <= range
        }
        switch s.firmware {
        case .meshtastic:
            // Meshtastic names the sender in full, but only a 0-hop packet came
            // straight from it; a relayed one says nothing about who relayed it.
            guard hops == 0, let n = nodes[s.node], located(n) else { return .coverageOnly }
            return .radio(n.num)
        case .meshcore:
            let known = nodes[s.node]
            // A 0-hop packet from a known radio (a direct advert): heard straight from it.
            if hops == 0, let n = known {
                return located(n) ? .radio(n.num) : .unidentified(hash: n.publicKeyPrefix?.prefix(1) ?? keyBytes(n.num).prefix(1))
            }
            // Otherwise all a sample can say is the last hop's hash: the stand-in's
            // own, or, for one credited to a whole node under the old rules, the
            // first byte of that node's key (the hash size wasn't kept; 1 byte is
            // what most meshes use).
            let hash: Data
            if isStandIn(s.node, nodes: nodes) {
                hash = Self.hash(ofStandIn: s.node)
            } else {
                hash = (known?.publicKeyPrefix ?? keyBytes(s.node)).prefix(1)
            }
            if isOwn(MeshCoreContact.nodeNum(forHash: hash)) { return .own }
            if let n = resolve(hash: hash, latitude: s.latitude, longitude: s.longitude, among: nodes.values,
                               excluding: isOwn, range: range) {
                return .radio(n.num)
            }
            return .unidentified(hash: hash)
        }
    }

    /// Every sample's credit added up.
    struct Summary: Equatable, Sendable {
        /// Radios heard directly, with how many samples each.
        var direct: [UInt32: Int] = [:]
        /// Unidentified repeaters by hash, with how many samples each.
        var unidentified: [Data: Int] = [:]
        var coverageOnly = 0
        var own = 0
    }

    static func summarize(_ samples: [CoverageSample], nodes: (MeshFirmware) -> [UInt32: MeshNode],
                                      isOwn: (UInt32, MeshFirmware) -> Bool, range: Double = rangeMeters) -> Summary {
        var out = Summary()
        var byNetwork: [MeshFirmware: [UInt32: MeshNode]] = [:]
        for s in samples {
            let known = byNetwork[s.firmware] ?? nodes(s.firmware)
            byNetwork[s.firmware] = known
            switch credit(s, nodes: known, isOwn: { isOwn($0, s.firmware) }, range: range) {
            case .coverageOnly: out.coverageOnly += 1
            case .own: out.own += 1
            case .radio(let num): out.direct[num, default: 0] += 1
            case .unidentified(let hash): out.unidentified[hash, default: 0] += 1
            }
        }
        return out
    }

    /// "Repeater 3f, not identified".
    static func label(forHash hash: Data) -> String {
        "Repeater \(hash.map { String(format: "%02x", $0) }.joined()), not identified"
    }
}
