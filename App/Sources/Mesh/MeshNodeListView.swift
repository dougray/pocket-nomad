// MeshNodeListView.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Radios the connected node has heard, most recent first.

import SwiftUI

struct MeshNodeListView: View {
    var onMessage: (UInt32) -> Void
    @State private var mesh = MeshService.shared
    @State private var blocking: MeshNode?

    var body: some View {
        let nodes = mesh.sortedNodes
        ScrollView {
            LazyVStack(spacing: 10) {
                if nodes.isEmpty {
                    MeshInfoCard(symbol: "person.3", title: "No other radios yet",
                                 message: mesh.connection.isConnected
                                 ? "Your radio has not heard anyone. Radios announce themselves every few hours, so leave it on, get higher up, or ask a friend to send a message."
                                 : "Connect your radio to see who is around.")
                }
                ForEach(nodes) { node in
                    Button { onMessage(node.num) } label: { MeshNodeRow(node: node) }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("Block This Radio", systemImage: "hand.raised", role: .destructive) { blocking = node }
                        }
                }
                if !mesh.blockedNodes.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Blocked").font(.headline).padding(.top, 12)
                        ForEach(mesh.blockedNodes) { node in
                            HStack {
                                Text(node.displayName).foregroundStyle(.secondary)
                                Spacer()
                                Button("Unblock") { mesh.unblock(node.num) }
                                    .frame(minHeight: MeshUI.minTap)
                            }
                            .padding(.horizontal, 14)
                            .background(Theme.card, in: RoundedRectangle(cornerRadius: MeshUI.corner))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(16)
        }
        .navigationTitle("Nodes")
        .meshBlockConfirmation($blocking)
    }
}

extension View {
    /// Asks before blocking a radio. Blocking deletes its messages on this phone.
    func meshBlockConfirmation(_ node: Binding<MeshNode?>) -> some View {
        confirmationDialog("Block \(node.wrappedValue?.displayName ?? "this radio")?",
                           isPresented: Binding(get: { node.wrappedValue != nil }, set: { if !$0 { node.wrappedValue = nil } }),
                           titleVisibility: .visible) {
            Button("Block", role: .destructive) {
                if let n = node.wrappedValue { MeshService.shared.block(n.num) }
                node.wrappedValue = nil
            }
        } message: {
            Text("Its messages are deleted from this phone and new ones are ignored. The other person isn't told. You can unblock it from the Nodes list.")
        }
    }
}

struct MeshNodeRow: View {
    let node: MeshNode

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            MeshNodeBadge(node: node, size: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(node.displayName).font(.title3.weight(.semibold)).foregroundStyle(.primary)
                Text(node.hexID).font(.footnote.monospaced()).foregroundStyle(.secondary)
                HStack(spacing: 12) {
                    if let heard = node.lastHeard {
                        Label(heard.meshRelative, systemImage: "clock")
                    } else {
                        Label("Not heard", systemImage: "clock")
                    }
                    if let snr = node.snr { Label(String(format: "%.1f dB", snr), systemImage: "waveform") }
                    if let h = node.hopsAway {
                        Label(h == 0 ? "Direct" : "\(h) hop\(h == 1 ? "" : "s")", systemImage: "point.3.connected.trianglepath.dotted")
                    }
                    if let b = node.batteryLevel {
                        Label(b > 100 ? "Plugged in" : "\(b)%", systemImage: b > 100 ? "powerplug.fill" : "battery.50")
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .labelStyle(.titleAndIcon)
                if let lat = node.latitude, let lon = node.longitude {
                    Label("\(MeshSOS.format(lat)), \(MeshSOS.format(lon))", systemImage: "location.fill")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "bubble.left.fill")
                .font(.title2)
                .foregroundStyle(Theme.accent)
                .frame(minWidth: 44, minHeight: 44)
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: MeshUI.minTap, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: MeshUI.corner))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens a direct message")
    }
}
