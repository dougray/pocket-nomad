// MeshComponents.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Small shared views: status card, help cards, big buttons, node badge.

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

enum MeshUI {
    static let minTap: CGFloat = 60
    static let corner: CGFloat = 16
    static let ok = Color.green
    static let warn = Color.yellow
}

/// Large, full-width button style for gloves.
struct MeshBigButtonStyle: ButtonStyle {
    var tint: Color = Theme.accent
    var filled = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.title3.weight(.bold))
            .frame(maxWidth: .infinity, minHeight: MeshUI.minTap)
            .padding(.horizontal, 12)
            .foregroundStyle(filled ? Color.black : tint)
            .background(
                RoundedRectangle(cornerRadius: MeshUI.corner)
                    .fill(filled ? tint : Theme.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: MeshUI.corner)
                    .strokeBorder(tint, lineWidth: filled ? 0 : 2)
            )
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// A card with an icon, title and body text.
struct MeshInfoCard<Actions: View>: View {
    let symbol: String
    let title: String
    let message: String
    var tint: Color = Theme.accent
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label {
                Text(title).font(.headline)
            } icon: {
                Image(systemName: symbol).foregroundStyle(tint)
            }
            Text(message)
                .font(.body)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
            actions()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: MeshUI.corner))
        .overlay(RoundedRectangle(cornerRadius: MeshUI.corner).strokeBorder(tint.opacity(0.6), lineWidth: 1.5))
        .accessibilityElement(children: .combine)
    }
}

extension MeshInfoCard where Actions == EmptyView {
    init(symbol: String, title: String, message: String, tint: Color = Theme.accent) {
        self.init(symbol: symbol, title: title, message: message, tint: tint, actions: { EmptyView() })
    }
}

/// Shows the connection state in words, with colour and an icon.
struct MeshStatusCard: View {
    let state: MeshConnectionState
    let radio: MeshRadioInfo
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(tint)
                    .frame(width: 44)
                    .symbolEffect(.pulse, isActive: isBusy)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.title3.weight(.bold)).foregroundStyle(.primary)
                    Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: MeshUI.corner))
            .overlay(RoundedRectangle(cornerRadius: MeshUI.corner).strokeBorder(tint, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title). \(subtitle)")
        .accessibilityHint("Opens the radio list")
    }

    private var isBusy: Bool {
        switch state {
        case .scanning, .connecting, .syncing, .reconnecting: return true
        default: return false
        }
    }

    private var symbol: String {
        switch state {
        case .connected: return "antenna.radiowaves.left.and.right"
        case .failed: return "exclamationmark.triangle.fill"
        case .idle: return "antenna.radiowaves.left.and.right.slash"
        default: return "dot.radiowaves.left.and.right"
        }
    }

    private var tint: Color {
        switch state {
        case .connected: return MeshUI.ok
        case .failed: return Theme.danger
        case .idle: return .secondary
        default: return MeshUI.warn
        }
    }

    private var title: String {
        switch state {
        case .idle: return "No radio connected"
        case .scanning: return "Looking for radios"
        case .connecting(let n): return "Connecting to \(n)"
        case .syncing(let n): return "Syncing with \(n)"
        case .connected(let n): return "Connected: \(n)"
        case .reconnecting(let n): return "Waiting for \(n)"
        case .failed: return "Connection problem"
        }
    }

    private var subtitle: String {
        switch state {
        case .idle: return "Tap to find your Meshtastic or MeshCore radio."
        case .scanning: return "Keep the radio close and switched on."
        case .connecting: return "If iOS asks for a PIN, read it off the radio's screen."
        case .syncing: return "Downloading the node list and settings."
        case .connected:
            var bits: [String] = []
            if let f = radio.firmware { bits.append(f.displayName) }
            let channel = radio.primaryChannelName.isEmpty ? "Primary channel" : radio.primaryChannelName
            if let r = radio.settingsSummary { bits.append(r) }
            // Meshtastic names its default channel after the preset ("US · LongFast"): say it once.
            if radio.settingsSummary?.hasSuffix("· \(channel)") != true { bits.append(channel) }
            return bits.joined(separator: " · ")
        case .reconnecting: return "Will reconnect by itself when the radio is in range."
        case .failed(let why): return why
        }
    }
}

/// Short-name badge for a node.
struct MeshNodeBadge: View {
    let node: MeshNode
    var size: CGFloat = 48

    var body: some View {
        Text(node.badge)
            .font(.system(size: size * 0.32, weight: .heavy, design: .rounded))
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .foregroundStyle(.black)
            .frame(width: size, height: size)
            .background(Circle().fill(color))
            .accessibilityHidden(true)
    }

    /// A stable colour per node so people are easy to tell apart.
    private var color: Color {
        let hue = Double(node.num % 360) / 360
        return Color(hue: hue, saturation: 0.55, brightness: 0.95)
    }
}

/// What to do when Bluetooth cannot be used.
struct MeshBluetoothHelp: View {
    let availability: MeshBluetoothAvailability

    var body: some View {
        switch availability {
        case .poweredOff:
            MeshInfoCard(symbol: "bolt.horizontal.circle", title: "Bluetooth is off",
                         message: "Swipe down from the top-right corner to open Control Center and tap the Bluetooth icon. Or open Settings, tap Bluetooth, and turn it on.",
                         tint: MeshUI.warn)
        case .unauthorized:
            MeshInfoCard(symbol: "hand.raised.fill", title: "Bluetooth permission is off",
                         message: "Pocket Nomad needs Bluetooth to talk to your radio. Tap the button below, then turn on Bluetooth for this app.",
                         tint: Theme.danger) {
                #if canImport(UIKit)
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .buttonStyle(MeshBigButtonStyle(tint: Theme.danger))
                #endif
            }
        case .unsupported:
            MeshInfoCard(symbol: "xmark.octagon.fill", title: "Bluetooth LE not available",
                         message: "This device cannot talk to mesh radios. Mesh messaging needs an iPhone or iPad with Bluetooth.",
                         tint: Theme.danger)
        case .resetting:
            MeshInfoCard(symbol: "arrow.clockwise", title: "Bluetooth is restarting",
                         message: "Wait a few seconds. If this does not clear, turn Bluetooth off and on again.",
                         tint: MeshUI.warn)
        case .unknown, .poweredOn:
            EmptyView()
        }
    }
}

extension Date {
    var meshRelative: String {
        if abs(timeIntervalSinceNow) < 60 { return "just now" }
        return formatted(.relative(presentation: .named))
    }
}

/// Small coloured tag naming a network, in the same colours as the map
/// (Meshtastic green, MeshCore blue).
struct MeshNetworkBadge: View {
    let network: MeshFirmware

    var body: some View {
        Text(network.displayName)
            .font(.caption.weight(.bold))
            .foregroundStyle(.black)
            // Never wraps ("Mesh-tastic") in a narrow column; the name beside it truncates instead.
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Capsule().fill(Color(uiColor: RadioColors.color(network))))
            .accessibilityLabel("\(network.displayName) network")
    }
}

/// One set-up radio on the Mesh tab: network, state, battery, and what you
/// can do with it.
struct MeshRadioCard: View {
    let link: MeshLink
    var onTap: () -> Void = {}
    @State private var confirmForget = false
    @State private var showSettings = false
    @State private var known = KnownRadioStore.shared

    private var mesh: MeshService { MeshService.shared }

    /// "Most radios near you use 910.525 MHz" when this MeshCore radio is on other settings.
    private var hint: String? {
        guard link.firmware == .meshcore, link.connection.isConnected else { return nil }
        return MeshCoreRadioHint.text(current: link.radio.meshCoreParams,
                                      nearby: MeshCoreNearbyConsensus.compute(known.radios))
    }

    private var regionUnset: Bool {
        link.firmware == .meshtastic && link.connection.isConnected && link.radio.region == MeshtasticRegion.unset
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            cardRow
            if let hint {
                hintButton("\(hint) — Radio settings")
            } else if regionUnset {
                hintButton("No region set, so this radio can't transmit — Radio settings")
            }
        }
        .frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: MeshUI.corner))
        .overlay(RoundedRectangle(cornerRadius: MeshUI.corner).strokeBorder(tint, lineWidth: 2))
        .sheet(isPresented: $showSettings) { RadioSettingsView(link: link) }
        .confirmationDialog("Forget \(link.displayName)?", isPresented: $confirmForget, titleVisibility: .visible) {
            Button("Forget", role: .destructive) { mesh.forgetDevice(link.firmware) }
        } message: {
            Text("It disconnects and won't reconnect. Its messages stay on this phone.")
        }
    }

    private func hintButton(_ text: String) -> some View {
        Button { showSettings = true } label: {
            Label(text, systemImage: "exclamationmark.circle")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(MeshUI.warn)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
        .accessibilityHint("Opens radio settings")
    }

    private var cardRow: some View {
        HStack(spacing: 12) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    Image(systemName: symbol)
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(tint)
                        .frame(width: 36)
                        .symbolEffect(.pulse, isActive: isBusy)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Text(link.displayName).font(.title3.weight(.bold)).foregroundStyle(.primary).lineLimit(1)
                            MeshNetworkBadge(network: link.firmware)
                        }
                        Text(stateLine).font(.subheadline).foregroundStyle(.secondary)
                            .multilineTextAlignment(.leading)
                        if let b = link.batteryLevel, link.connection.isConnected {
                            Label(b > 100 ? "Plugged in" : "Battery \(b)%", systemImage: b > 100 ? "powerplug.fill" : "battery.50")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(link.displayName), \(link.firmware.displayName). \(stateLine)")
            Menu {
                if link.connection == .idle || isFailed {
                    Button("Reconnect", systemImage: "arrow.triangle.2.circlepath") { mesh.reconnect(link.firmware) }
                } else {
                    Button("Disconnect", systemImage: "xmark.circle") { mesh.disconnect(link.firmware) }
                }
                if link.connection.isConnected {
                    Button("Radio Settings…", systemImage: "slider.horizontal.3") { showSettings = true }
                }
                Button("Forget This Radio", systemImage: "trash", role: .destructive) { confirmForget = true }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title2)
                    .frame(minWidth: MeshUI.minTap, minHeight: MeshUI.minTap)
            }
            .accessibilityLabel("Actions for \(link.displayName)")
        }
        .padding(.leading, 16)
        .frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
    }

    private var isFailed: Bool {
        if case .failed = link.connection { return true }
        return false
    }

    private var isBusy: Bool {
        switch link.connection {
        case .scanning, .connecting, .syncing, .reconnecting: return true
        default: return false
        }
    }

    private var symbol: String {
        switch link.connection {
        case .connected: return "antenna.radiowaves.left.and.right"
        case .failed: return "exclamationmark.triangle.fill"
        case .idle: return "antenna.radiowaves.left.and.right.slash"
        default: return "dot.radiowaves.left.and.right"
        }
    }

    private var tint: Color {
        switch link.connection {
        case .connected: return MeshUI.ok
        case .failed: return Theme.danger
        case .idle: return .secondary
        default: return MeshUI.warn
        }
    }

    private var stateLine: String {
        switch link.connection {
        case .idle: return "Not connected"
        case .scanning: return "Looking for radios"
        case .connecting: return "Connecting. If iOS asks for a PIN, read it off the radio's screen."
        case .syncing: return "Syncing: downloading nodes and settings"
        case .connected:
            var bits = ["Connected"]
            let channel = link.radio.primaryChannelName.isEmpty ? "Primary channel" : link.radio.primaryChannelName
            if let r = link.radio.settingsSummary { bits.append(r) }
            // Meshtastic names its default channel after the preset ("US · LongFast"): say it once.
            if link.radio.settingsSummary?.hasSuffix("· \(channel)") != true { bits.append(channel) }
            return bits.joined(separator: " · ")
        case .reconnecting: return "Waiting. Reconnects by itself when the radio is in range."
        case .failed(let why): return why
        }
    }
}
