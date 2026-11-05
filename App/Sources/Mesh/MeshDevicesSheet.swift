// MeshDevicesSheet.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Scan for and connect to nearby Meshtastic and MeshCore radios. One radio of
// each kind can be connected at once; picking a second radio of the same kind
// asks before it replaces the first.

import SwiftUI

struct MeshDevicesSheet: View {
    @State private var mesh = MeshService.shared
    @Environment(\.dismiss) private var dismiss
    @State private var replacing: (device: MeshDiscoveredDevice, current: String)?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    MeshBluetoothHelp(availability: mesh.availability)
                    currentRadio
                    pairingCard
                    if let hint = mesh.pairingHint {
                        MeshInfoCard(symbol: "exclamationmark.lock.fill", title: "Pairing problem", message: hint, tint: Theme.danger)
                    }
                    nearby
                }
                .padding(16)
            }
            .navigationTitle("Radios")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.font(.headline)
                }
            }
            .onAppear { if hasFreeSlot { mesh.startScan() } }
            .onDisappear { mesh.stopScan() }
            .onChange(of: mesh.connectedLinks.count) { old, new in
                if new > old { dismiss() }
            }
            .confirmationDialog("Replace \(replacing?.current ?? "the radio")?",
                                isPresented: Binding(get: { replacing != nil }, set: { if !$0 { replacing = nil } }),
                                titleVisibility: .visible) {
                Button("Replace with \(replacing?.device.name ?? "this radio")", role: .destructive) {
                    if let d = replacing?.device { mesh.connect(to: d) }
                    replacing = nil
                }
                Button("Cancel", role: .cancel) { replacing = nil }
            } message: {
                Text("Pocket Nomad connects one \(replacing?.device.firmware.displayName ?? "") radio at a time (plus one of the other kind). \(replacing?.current ?? "The current one") will be disconnected and forgotten.")
            }
        }
        .presentationDetents([.large])
    }

    /// Fewer than two radios have a working link, so there is room to add one.
    private var hasFreeSlot: Bool { mesh.activeLinks.count < MeshFirmware.allCases.count }

    /// Each set-up radio, with disconnect and forget.
    @ViewBuilder
    private var currentRadio: some View {
        ForEach(mesh.rememberedLinks) { l in
            VStack(alignment: .leading, spacing: 12) {
                MeshRadioCard(link: l)
                if !l.radio.firmwareVersion.isEmpty, l.connection.isConnected {
                    Text("Firmware \(l.radio.firmwareVersion)").font(.footnote).foregroundStyle(.secondary)
                }
                if l.connection != .idle {
                    Button(role: .destructive) { mesh.disconnect(l.firmware) } label: {
                        Label("Disconnect \(l.displayName)", systemImage: "xmark.circle")
                    }
                    .buttonStyle(MeshBigButtonStyle(tint: Theme.danger, filled: false))
                } else {
                    Button { mesh.reconnect(l.firmware) } label: {
                        Label("Reconnect \(l.displayName)", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .buttonStyle(MeshBigButtonStyle(filled: false))
                }
                Button { mesh.forgetDevice(l.firmware); mesh.startScan() } label: {
                    Label("Forget and pick another", systemImage: "trash")
                }
                .buttonStyle(MeshBigButtonStyle(tint: .secondary, filled: false))
            }
        }
        if mesh.rememberedLinks.count == 1 {
            Text("You can also connect a \(MeshFirmware.allCases.first { !mesh.link($0).isRemembered }?.displayName ?? "second") radio at the same time. Both record coverage and show on the map; their messages stay on their own networks.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var pairingCard: some View {
        if mesh.connectedLinks.isEmpty {
            MeshInfoCard(symbol: "lock.fill", title: "About the PIN",
                         message: "The first time you connect, iOS asks for a 6-digit PIN. Radios with a screen show it there. Radios without a screen usually use 123456 unless someone changed it.")
        }
    }

    @ViewBuilder
    private var nearby: some View {
        if hasFreeSlot {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Nearby radios").font(.headline)
                    Spacer()
                    if mesh.isScanning { ProgressView() }
                }
                if mesh.discovered.isEmpty {
                    Text(mesh.availability == .poweredOn
                         ? "Searching. Make sure the radio is on, Bluetooth is enabled on it, and no other phone is connected to it."
                         : "Waiting for Bluetooth.")
                        .foregroundStyle(.secondary)
                }
                ForEach(mesh.discovered.filter { d in !(mesh.link(forDevice: d.id)?.connection.isLinkUp ?? false) }) { d in
                    Button { pick(d) } label: { deviceRow(d) }
                        .buttonStyle(.plain)
                }
                if !mesh.isScanning {
                    Button { mesh.startScan() } label: { Label("Scan again", systemImage: "arrow.clockwise") }
                        .buttonStyle(MeshBigButtonStyle(filled: false))
                }
            }
        }
    }

    private func deviceRow(_ d: MeshDiscoveredDevice) -> some View {
        HStack(spacing: 14) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.title2)
                .foregroundStyle(Theme.accent)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(d.name).font(.title3.weight(.semibold)).foregroundStyle(.primary)
                Text("\(d.firmware.displayName) · " + (d.rssi == 0 ? "Already linked to this phone" : "\(signalWord(d.rssi)) · \(d.rssi) dBm"))
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            signalBars(d.rssi)
            Text(mesh.link(forDevice: d.id) != nil ? "Reconnect" : (mesh.replaced(by: d) != nil ? "Replace" : "Connect"))
                .font(.headline)
                .foregroundStyle(.black)
                .padding(.horizontal, 14).frame(minHeight: 44)
                .background(Capsule().fill(Theme.accent))
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 72)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: MeshUI.corner))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    /// Connects, or asks first when it would replace the radio of the same kind.
    private func pick(_ d: MeshDiscoveredDevice) {
        if let current = mesh.replaced(by: d) {
            replacing = (d, current.displayName)
        } else {
            mesh.connect(to: d)
        }
    }

    private func signalWord(_ rssi: Int) -> String {
        switch rssi {
        case (-60)...: return "Strong"
        case (-75)...: return "Good"
        case (-88)...: return "Weak"
        default: return "Very weak"
        }
    }

    private func signalBars(_ rssi: Int) -> some View {
        let level: Double = rssi == 0 ? 1 : min(1, max(0, Double(rssi + 100) / 45))
        return Image(systemName: "cellularbars", variableValue: level)
            .font(.title3)
            .foregroundStyle(.primary)
            .accessibilityHidden(true)
    }
}
