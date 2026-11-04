// MeshView.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Root of the Mesh tab.

import SwiftUI

enum MeshRoute: Hashable {
    case chat(MeshConversation)
    case nodes
    case info
}

struct MeshView: View {
    @State private var mesh = MeshService.shared
    @State private var showDevices = false
    @State private var showSOSConfirm = false
    @State private var sendingSOS = false
    @State private var sosResult: String?
    @State private var path: [MeshRoute] = []
    @State private var showCoverage = false
    @State private var coverage = CoverageStore.shared

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(spacing: 16) {
                    MeshStatusCard(state: mesh.connection, radio: mesh.radio) { showDevices = true }

                    MeshBluetoothHelp(availability: mesh.availability)

                    if let hint = mesh.pairingHint {
                        MeshInfoCard(symbol: "lock.shield", title: "Pairing", message: hint, tint: MeshUI.warn)
                    }

                    if let notice = mesh.notice {
                        MeshInfoCard(symbol: "info.circle.fill", title: "From the radio", message: notice, tint: MeshUI.warn) {
                            Button("Dismiss") { mesh.notice = nil }
                                .buttonStyle(MeshBigButtonStyle(tint: MeshUI.warn, filled: false))
                        }
                    }

                    if case .idle = mesh.connection {
                        connectPrompt
                    }

                    channelTile
                    nodesTile
                    coverageTile
                    directThreads
                    sosButton

                    NavigationLink(value: MeshRoute.info) {
                        Label("What is a mesh radio?", systemImage: "questionmark.circle")
                    }
                    .buttonStyle(MeshBigButtonStyle(filled: false))
                }
                .padding(16)
            }
            .navigationTitle("Mesh")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showDevices = true } label: {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.title2)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel("Radios")
                }
            }
            .navigationDestination(for: MeshRoute.self) { route in
                switch route {
                case .chat(let c): MeshChatView(conversation: c)
                case .nodes: MeshNodeListView { path.append(.chat(.direct($0))) }
                case .info: MeshInfoView()
                }
            }
            .sheet(isPresented: $showDevices) { MeshDevicesSheet() }
            .sheet(isPresented: $showCoverage) { CoverageSettingsView() }
            .onAppear { if LaunchScreen.parts()?.0 == "coverage" { showCoverage = true } }
            .confirmationDialog("Send SOS to everyone on the mesh?", isPresented: $showSOSConfirm,
                                titleVisibility: .visible) {
                Button("Send SOS with my location", role: .destructive) { sendSOS(location: true) }
                Button("Send SOS without location", role: .destructive) { sendSOS(location: false) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This message goes to every radio in range on the primary channel:\n\n\"\(mesh.sosPreview)\"\n\nWith location, your GPS position is added (decimal degrees).")
            }
            .alert("SOS", isPresented: Binding(get: { sosResult != nil }, set: { if !$0 { sosResult = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(sosResult ?? "")
            }
        }
    }

    private var connectPrompt: some View {
        VStack(spacing: 10) {
            if let name = mesh.lastDeviceName, mesh.lastDeviceID != nil {
                Button { mesh.reconnectLast() } label: {
                    Label("Reconnect to \(name)", systemImage: "arrow.triangle.2.circlepath")
                }
                .buttonStyle(MeshBigButtonStyle())
            }
            Button { showDevices = true } label: {
                Label("Find a radio", systemImage: "magnifyingglass")
            }
            .buttonStyle(MeshBigButtonStyle(filled: mesh.lastDeviceID == nil))
        }
    }

    private var channelTile: some View {
        NavigationLink(value: MeshRoute.chat(.channel(0))) {
            tileRow(symbol: "bubble.left.and.bubble.right.fill",
                    title: mesh.radio.primaryChannelName.isEmpty ? "Channel chat" : "Channel: \(mesh.radio.primaryChannelName)",
                    subtitle: lastLine(.channel(0)) ?? "Talk to everyone on the primary channel",
                    badge: mesh.unreadCount(.channel(0)))
        }
        .buttonStyle(.plain)
    }

    private var coverageTile: some View {
        Button { showCoverage = true } label: {
            tileRow(symbol: "circle.hexagongrid.fill",
                    title: "Coverage map",
                    subtitle: coverage.recording ? "Recording · \(coverage.samples.count) points, private" : "Map where your radio hears others",
                    badge: 0)
        }
        .buttonStyle(.plain)
    }

    private var nodesTile: some View {
        NavigationLink(value: MeshRoute.nodes) {
            tileRow(symbol: "person.3.fill",
                    title: "Nodes (\(mesh.sortedNodes.count))",
                    subtitle: "Radios your radio has heard",
                    badge: 0)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var directThreads: some View {
        let threads = mesh.directThreads
        if !threads.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Direct messages").font(.headline).foregroundStyle(.secondary)
                ForEach(threads, id: \.node.num) { t in
                    NavigationLink(value: MeshRoute.chat(.direct(t.node.num))) {
                        HStack(spacing: 12) {
                            MeshNodeBadge(node: t.node, size: 44)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t.node.displayName).font(.headline)
                                Text(t.last.text).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                            unreadBadge(mesh.unreadCount(.direct(t.node.num)))
                        }
                        .padding(12)
                        .frame(minHeight: MeshUI.minTap)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: MeshUI.corner))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var sosButton: some View {
        Button {
            showSOSConfirm = true
        } label: {
            HStack(spacing: 12) {
                if sendingSOS { ProgressView().tint(.white) } else { Image(systemName: "sos.circle.fill") }
                Text(sendingSOS ? "Getting location..." : "SOS")
            }
            .font(.title.weight(.heavy))
            .frame(maxWidth: .infinity, minHeight: 76)
            .foregroundStyle(.white)
            .background(Theme.danger, in: RoundedRectangle(cornerRadius: MeshUI.corner))
        }
        .buttonStyle(.plain)
        .disabled(!mesh.canSend || sendingSOS)
        .opacity(mesh.canSend ? 1 : 0.4)
        .accessibilityHint(mesh.canSend ? "Asks before sending an emergency message to everyone" : "Connect a radio first")
    }

    private func tileRow(symbol: String, title: String, subtitle: String, badge: Int) -> some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.title3.weight(.semibold)).foregroundStyle(.primary)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            unreadBadge(badge)
            Image(systemName: "chevron.right").foregroundStyle(.secondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: MeshUI.corner))
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private func unreadBadge(_ n: Int) -> some View {
        if n > 0 {
            Text("\(n)")
                .font(.headline.weight(.bold))
                .foregroundStyle(.black)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Capsule().fill(Theme.accent))
                .accessibilityLabel("\(n) unread")
        }
    }

    private func lastLine(_ c: MeshConversation) -> String? {
        mesh.messages(in: c).last.map { $0.text }
    }

    private func sendSOS(location: Bool) {
        sendingSOS = true
        Task {
            let sent = await mesh.sendSOS(includeLocation: location)
            sendingSOS = false
            if let sent {
                var msg = "Sent to the mesh:\n\(sent)"
                if location && !sent.contains("Location:") {
                    msg += "\n\nNo GPS fix was available, so it went without coordinates."
                }
                sosResult = msg
            } else {
                sosResult = "Could not send. Check that the radio is connected, then try again."
            }
        }
    }
}
