// MeshView.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Root of the Mesh tab.

import SwiftUI

enum MeshRoute: Hashable {
    /// A thread on one network; nil means the default radio's network.
    case chat(MeshConversation, network: MeshFirmware? = nil)
    case nodes
    case info

    /// The screen for this route; `open` follows a link from it (a node to its chat).
    @MainActor @ViewBuilder
    func destination(open: @escaping (MeshRoute) -> Void) -> some View {
        switch self {
        case .chat(let c, let network): MeshChatView(conversation: c, network: network)
        case .nodes: MeshNodeListView { open(.chat(.direct($0.num), network: $0.firmware)) }
        case .info: MeshInfoView()
        }
    }
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
                    radios

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

                    channelTiles
                    nodesTile
                    coverageTile
                    directThreads
                    sosButton

                    DetailLink(value: MeshRoute.info) {
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
                route.destination { path.append($0) }
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
                Text("This message goes to every radio in range on the primary channel\(mesh.activeLinks.count > 1 ? " of both networks (\(mesh.activeLinks.map(\.firmware.displayName).joined(separator: " and ")))" : ""):\n\n\"\(mesh.sosPreview)\"\n\nWith location, your GPS position is added (decimal degrees).")
            }
            .alert("SOS", isPresented: Binding(get: { sosResult != nil }, set: { if !$0 { sosResult = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(sosResult ?? "")
            }
        }
    }

    /// Every set-up radio (up to one Meshtastic and one MeshCore), and a way to add one.
    @ViewBuilder
    private var radios: some View {
        let remembered = mesh.rememberedLinks
        if remembered.isEmpty {
            MeshStatusCard(state: mesh.connection, radio: mesh.radio) { showDevices = true }
        } else {
            VStack(spacing: 10) {
                ForEach(remembered) { l in
                    MeshRadioCard(link: l) { showDevices = true }
                }
                if remembered.count < MeshFirmware.allCases.count {
                    Button { showDevices = true } label: {
                        Label("Add a \(missingNetworks) radio", systemImage: "plus.circle")
                    }
                    .buttonStyle(MeshBigButtonStyle(filled: false))
                }
            }
        }
    }

    private var missingNetworks: String {
        MeshFirmware.allCases.filter { !mesh.link($0).isRemembered }.map(\.displayName).joined(separator: " or ")
    }

    private var connectPrompt: some View {
        VStack(spacing: 10) {
            if !mesh.rememberedLinks.isEmpty {
                Button { mesh.reconnectLast() } label: {
                    Label(mesh.rememberedLinks.count == 1 ? "Reconnect to \(mesh.rememberedLinks[0].displayName)" : "Reconnect both radios",
                          systemImage: "arrow.triangle.2.circlepath")
                }
                .buttonStyle(MeshBigButtonStyle())
            }
            if mesh.rememberedLinks.isEmpty {
                Button { showDevices = true } label: {
                    Label("Find a radio", systemImage: "magnifyingglass")
                }
                .buttonStyle(MeshBigButtonStyle())
            }
        }
    }

    /// One primary channel per network in use; labelled when there are two.
    @ViewBuilder
    private var channelTiles: some View {
        let networks = mesh.networksInUse.isEmpty ? [mesh.defaultNetwork] : mesh.networksInUse
        ForEach(networks, id: \.self) { net in
            let name = mesh.link(net).radio.primaryChannelName
            DetailLink(value: MeshRoute.chat(.channel(0), network: net)) {
                tileRow(symbol: "bubble.left.and.bubble.right.fill",
                        title: name.isEmpty ? "Channel chat" : "Channel: \(name)",
                        subtitle: lastLine(.channel(0), net) ?? "Talk to everyone on the primary channel",
                        badge: mesh.unreadCount(.channel(0), network: net),
                        network: networks.count > 1 ? net : nil)
            }
            .buttonStyle(.plain)
        }
    }

    private var coverageTile: some View {
        Button { showCoverage = true } label: {
            tileRow(symbol: "circle.hexagongrid.fill",
                    title: "Coverage map",
                    subtitle: DriveMode.shared.active ? "Drive mode on · \(DriveMode.shared.sent) pings, \(coverage.samples.count) points"
                        : coverage.recording ? "Recording · \(coverage.samples.count) points, private" : "Map where your radio hears others",
                    badge: 0)
        }
        .buttonStyle(.plain)
    }

    private var nodesTile: some View {
        DetailLink(value: MeshRoute.nodes) {
            tileRow(symbol: "person.3.fill",
                    title: "Nodes (\(mesh.sortedNodes.count))",
                    subtitle: mesh.rememberedLinks.count > 1 ? "Radios heard on both networks" : "Radios your radio has heard",
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
                let labelled = mesh.networksInUse.count > 1
                ForEach(threads, id: \.last.id) { t in
                    DetailLink(value: MeshRoute.chat(.direct(t.node.num), network: t.network)) {
                        HStack(spacing: 12) {
                            MeshNodeBadge(node: t.node, size: 44)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(t.node.displayName).font(.headline)
                                    if labelled { MeshNetworkBadge(network: t.network) }
                                }
                                Text(t.last.text).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                            unreadBadge(mesh.unreadCount(.direct(t.node.num), network: t.network))
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

    private func tileRow(symbol: String, title: String, subtitle: String, badge: Int, network: MeshFirmware? = nil) -> some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 40)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(title).font(.title3.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                    if let network { MeshNetworkBadge(network: network) }
                }
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

    private func lastLine(_ c: MeshConversation, _ network: MeshFirmware) -> String? {
        mesh.messages(in: c, network: network).last.map { $0.text }
    }

    /// Sends on every connected radio and reports each one.
    private func sendSOS(location: Bool) {
        sendingSOS = true
        Task {
            let results = await mesh.sendSOSEverywhere(includeLocation: location)
            sendingSOS = false
            guard let first = results.first(where: \.sent)?.text else {
                sosResult = "Could not send. Check that the radio is connected, then try again."
                return
            }
            var msg: String
            if results.count == 1 {
                msg = "Sent to the mesh:\n\(first)"
            } else {
                msg = results.map { r in "\(r.network.displayName) (\(r.radioName)): \(r.sent ? "sent" : "NOT sent")" }
                    .joined(separator: "\n") + "\n\n\(first)"
            }
            if location && !first.contains("Location:") {
                msg += "\n\nNo GPS fix was available, so it went without coordinates."
            }
            sosResult = msg
        }
    }
}
