// MeshDevicesSheet.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Scan for and connect to nearby Meshtastic and MeshCore radios.

import SwiftUI

struct MeshDevicesSheet: View {
    @State private var mesh = MeshService.shared
    @Environment(\.dismiss) private var dismiss

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
            .onAppear { if !mesh.connection.isLinkUp { mesh.startScan() } }
            .onDisappear { mesh.stopScan() }
            .onChange(of: mesh.connection) { _, new in
                if new.isConnected { dismiss() }
            }
        }
        .presentationDetents([.large])
    }

    @ViewBuilder
    private var currentRadio: some View {
        switch mesh.connection {
        case .connected(let name), .syncing(let name), .connecting(let name), .reconnecting(let name):
            VStack(alignment: .leading, spacing: 12) {
                MeshStatusCard(state: mesh.connection, radio: mesh.radio) {}
                    .allowsHitTesting(false)
                    .accessibilityHint("")
                if !mesh.radio.firmwareVersion.isEmpty, mesh.connection.isConnected {
                    Text("Firmware \(mesh.radio.firmwareVersion)").font(.footnote).foregroundStyle(.secondary)
                }
                Button(role: .destructive) { mesh.disconnect() } label: {
                    Label("Disconnect \(name)", systemImage: "xmark.circle")
                }
                .buttonStyle(MeshBigButtonStyle(tint: Theme.danger, filled: false))
                Button { mesh.forgetDevice(); mesh.startScan() } label: {
                    Label("Forget and pick another", systemImage: "trash")
                }
                .buttonStyle(MeshBigButtonStyle(tint: .secondary, filled: false))
            }
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var pairingCard: some View {
        if !mesh.connection.isConnected {
            MeshInfoCard(symbol: "lock.fill", title: "About the PIN",
                         message: "The first time you connect, iOS asks for a 6-digit PIN. Radios with a screen show it there. Radios without a screen usually use 123456 unless someone changed it.")
        }
    }

    @ViewBuilder
    private var nearby: some View {
        if !mesh.connection.isLinkUp {
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
                ForEach(mesh.discovered) { d in
                    Button { mesh.connect(to: d) } label: { deviceRow(d) }
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
            Text(d.id == mesh.lastDeviceID ? "Reconnect" : "Connect")
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
