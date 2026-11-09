// HeardRadios.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// The radio layers on the map, both placed where each radio says it is:
// - "Heard directly": radios your radio heard straight from them, as the last
//   hop of a packet (credited by `HeardResolver` from where you were) or a
//   0-hop advert. This is what a drive maps.
// - "Known on the mesh": every other contact with a position, mostly learned
//   from adverts relayed many hops, so they may be far out of range.
// Repeaters known only by a hash that no nearby contact matches are listed as
// text, never drawn. Your own radios are never on either layer, and neither is
// anything about where you were when you heard them.

import CoreLocation
import SwiftUI

enum HeardRadios {
    /// Nodes to draw: a known position, not one of this phone's own radios,
    /// not blocked. `isOwn` gets the node number and its network.
    static func mapped(_ nodes: [MeshNode], isOwn: (UInt32, MeshFirmware) -> Bool,
                       blocked: Set<UInt32> = []) -> [MeshNode] {
        nodes.filter { n in
            n.hasPosition && !isOwn(n.num, n.firmware) && !blocked.contains(n.num)
        }
    }

    /// Every network's mapped nodes from a MeshService (both layers together).
    @MainActor
    static func mapped(in mesh: MeshService) -> [MeshNode] {
        mesh.links.flatMap { l in
            mapped(Array(l.nodes.values), isOwn: { num, network in
                num == l.radio.myNodeNum || mesh.isOwnRadio(num, on: network)
            }, blocked: mesh.blocked)
        }
    }

    /// The two layers and the unidentified repeaters.
    struct Layers: Equatable {
        var direct: [MeshNode] = []
        var mesh: [MeshNode] = []
        /// Repeater hashes no nearby contact matched, most heard first.
        var unidentified: [(hash: Data, samples: Int)] = []
        /// Samples with no radio to credit (node 0 and the like): coverage only.
        var coverageOnly = 0

        static func == (a: Layers, b: Layers) -> Bool {
            a.direct == b.direct && a.mesh == b.mesh && a.coverageOnly == b.coverageOnly
                && a.unidentified.map(\.hash) == b.unidentified.map(\.hash) && a.unidentified.map(\.samples) == b.unidentified.map(\.samples)
        }
    }

    /// Splits known radios into heard-directly and known-on-the-mesh.
    /// `nodes` are each network's known radios; `samples` the coverage
    /// recordings, each credited from its own position; `liveDirect` radios
    /// resolved as heard directly while the app was running.
    static func layers(nodes: [MeshFirmware: [UInt32: MeshNode]], samples: [CoverageSample],
                       liveDirect: [MeshFirmware: Set<UInt32>] = [:],
                       isOwn: (UInt32, MeshFirmware) -> Bool, blocked: Set<UInt32> = [],
                       range: Double = HeardResolver.rangeMeters) -> Layers {
        let summary = HeardResolver.summarize(samples, nodes: { nodes[$0] ?? [:] }, isOwn: isOwn, range: range)
        var out = Layers()
        for network in MeshFirmware.allCases {
            let known = nodes[network] ?? [:]
            let all = mapped(Array(known.values), isOwn: isOwn, blocked: blocked)
            let credited = Set(summary.direct.keys)
            let live = liveDirect[network] ?? []
            for n in all.sorted(by: { $0.num < $1.num }) {
                // A 0-hop route (MeshCore) or 0 hops away (Meshtastic) also means heard directly.
                if credited.contains(n.num) || live.contains(n.num) || n.hopsAway == 0 {
                    out.direct.append(n)
                } else {
                    out.mesh.append(n)
                }
            }
        }
        out.unidentified = summary.unidentified.map { ($0.key, $0.value) }
            .sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0.lexicographicallyPrecedes($1.0) }
        out.coverageOnly = summary.coverageOnly
        return out
    }

    @MainActor private static var cache: (key: String, layers: Layers)?

    /// The layers for a MeshService and the coverage recordings, cached until either changes.
    @MainActor
    static func layers(in mesh: MeshService, samples: [CoverageSample]) -> Layers {
        let nodes = Dictionary(uniqueKeysWithValues: mesh.links.map { ($0.firmware, $0.nodes) })
        let live = Dictionary(uniqueKeysWithValues: mesh.links.map { ($0.firmware, Set($0.heardDirect.keys)) })
        let key = "\(samples.count)|\(samples.last?.date.timeIntervalSince1970 ?? 0)|"
            + mesh.links.map { l in
                "\(l.firmware.rawValue):\(l.radio.myNodeNum):\(l.nodes.count):\(l.heardDirect.count):"
                    + "\(l.nodes.values.reduce(0.0) { $0 + ($1.latitude ?? 0) + ($1.longitude ?? 0) + Double($1.hopsAway ?? 99) })"
            }.joined(separator: ",") + "|\(mesh.blocked.sorted())"
        if let cache, cache.key == key { return cache.layers }
        let result = layers(nodes: nodes, samples: samples, liveDirect: live, isOwn: { num, network in
            mesh.links.contains { $0.radio.myNodeNum == num } || mesh.isOwnRadio(num, on: network)
        }, blocked: mesh.blocked)
        cache = (key, result)
        return result
    }

    /// "Also heard 3 repeaters not identified: 3f, a1, 07".
    static func unidentifiedSummary(_ list: [(hash: Data, samples: Int)], limit: Int = 6) -> String? {
        guard !list.isEmpty else { return nil }
        let hex = list.prefix(limit).map { $0.hash.map { String(format: "%02x", $0) }.joined() }
        let more = list.count > limit ? ", …" : ""
        return "Also heard \(list.count) repeater\(list.count == 1 ? "" : "s") not identified: " + hex.joined(separator: ", ") + more
    }

    /// "27 MeshCore repeaters and 3 Meshtastic radios heard".
    static func summary(_ nodes: [MeshNode]) -> String {
        var parts: [String] = []
        for network in MeshFirmware.allCases {
            let mine = nodes.filter { $0.firmware == network }
            let repeaters = mine.count { $0.isRepeater }
            let others = mine.count - repeaters
            if repeaters > 0 { parts.append("\(repeaters) \(network.displayName) repeater\(repeaters == 1 ? "" : "s")") }
            if others > 0 { parts.append("\(others) \(network.displayName) radio\(others == 1 ? "" : "s")") }
        }
        guard let last = parts.popLast() else { return "No radios with a known position heard yet" }
        return (parts.isEmpty ? last : parts.joined(separator: ", ") + " and " + last) + " heard"
    }
}

/// A tapped heard radio: what it is, when and how well it was heard.
struct HeardRadioDetail: View {
    let node: MeshNode
    /// Heard directly (true) or only known from the mesh (false).
    var direct = true

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Circle().fill(Color(uiColor: RadioColors.color(node.firmware))).frame(width: 14, height: 14)
                Text(node.displayName).font(.headline).lineLimit(2)
            }
            LabeledContent("System", value: node.firmware.displayName)
            LabeledContent("Type", value: node.isRepeater ? "Repeater" : "Radio")
            if let heard = node.lastHeard {
                LabeledContent("Last heard", value: heard.formatted(.relative(presentation: .named)))
            }
            if let snr = node.snr { LabeledContent("Signal", value: String(format: "%.1f dB", snr)) }
            if let h = node.hopsAway { LabeledContent("Hops", value: h == 0 ? "Direct" : "\(h)") }
            if let lat = node.latitude, let lon = node.longitude {
                LabeledContent("Position", value: CoordinateFormat.decimal(CLLocationCoordinate2D(latitude: lat, longitude: lon)))
            }
            Text(direct ? "Where the radio says it is. Heard directly by your radio."
                        : "Where the radio says it is. Known from adverts passed along the mesh; not heard directly, so it may be out of range.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .font(.subheadline)
        .padding()
    }
}
