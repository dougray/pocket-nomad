// HamView.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Root of the Ham section: the TNC, the station's mode (listen only or
// licensed), and links to APRS, Winlink, the packet terminal and the monitor.

import SwiftUI

enum HamRoute: Hashable {
    case monitor, stations, station(String), messages, thread(String), bulletins
    case mail, mailFolder(WinlinkFolder), mailMessage(String, WinlinkFolder), winlink
    case terminal, settings, reference, satellites, log, nvis

    @MainActor @ViewBuilder
    var destination: some View {
        switch self {
        case .monitor: HamMonitorView()
        case .stations: HamStationsView()
        case .log: QsoLogView()
        case .nvis: NvisView()
        case .station(let id): HamStationDetail(id: id)
        case .messages: APRSConversationsView()
        case .thread(let peer): APRSThreadView(peer: peer)
        case .bulletins: APRSBulletinsView()
        case .mail: WinlinkHomeView()
        case .mailFolder(let f): WinlinkFolderView(folder: f)
        case .mailMessage(let mid, let f): WinlinkMessageView(mid: mid, folder: f)
        case .winlink: WinlinkConnectView()
        case .terminal: HamTerminalView()
        case .settings: HamSettingsView()
        case .reference: HamReferenceView()
        case .satellites: SatellitesView()
        }
    }
}

struct HamView: View {
    @State private var ham = HamService.shared
    @State private var path: [HamRoute] = []
    @State private var showTNC = false
    @State private var audio = RadioAudioListener.shared
    @State private var ft8 = Ft8Heard.shared

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section { tncRow } footer: { modeFooter }
                if let notice = ham.notice {
                    Section {
                        Label(notice, systemImage: "info.circle.fill").foregroundStyle(.primary)
                        Button("Dismiss") { ham.notice = nil }
                    }
                }
                Section {
                    Button { audio.toggle() } label: {
                        row("Listen to radio audio", "waveform", detail: audio.running ? "on: \(audio.frames) packets" : nil)
                    }
                    if audio.running { ProgressView(value: Double(min(1, max(0, audio.level)))).tint(.orange) }
                    if let e = audio.error { Text(e).font(.footnote).foregroundStyle(.secondary) }
                    if !ft8.lines.isEmpty {
                        Text("FT8 (\(ft8.lines.count))").font(.headline)
                        ForEach(ft8.lines.prefix(12)) { l in Text("\(l.snr) dB  \(l.text)").font(.footnote.monospaced()) }
                    }
                } header: { Text("Radio audio") } footer: {
                    Text("Plug the radio's speaker or headphone output into the phone's headset input with a cable made for it, and turn the radio's volume up until the bar is about half full. Receive only: nothing is sent and no sound is kept.")
                }
                Section("HF") {
                    NavigationLink(value: HamRoute.nvis) { row("NVIS planner (short-range HF)", "dot.radiowaves.up.forward") }
                }
                Section("Contacts") {
                    NavigationLink(value: HamRoute.log) {
                        row("Contact log", "book.closed.fill", detail: QsoLogStore.shared.log.isEmpty ? nil : "\(QsoLogStore.shared.log.count)")
                    }
                }
                Section("APRS") {
                    NavigationLink(value: HamRoute.messages) {
                        row("Messages", "bubble.left.and.bubble.right.fill", badge: ham.unreadTotal)
                    }
                    NavigationLink(value: HamRoute.stations) {
                        row("Stations heard", "mappin.and.ellipse", detail: ham.stations.isEmpty ? nil : "\(ham.stations.count)")
                    }
                    NavigationLink(value: HamRoute.bulletins) {
                        row("Bulletins", "megaphone.fill", detail: ham.bulletins.isEmpty ? nil : "\(ham.bulletins.count)")
                    }
                    if ham.settings.canTransmit {
                        Button {
                            Task { await ham.beacon() }
                        } label: {
                            row("Send my position", "location.circle.fill",
                                detail: ham.lastBeacon.map { "last " + $0.formatted(date: .omitted, time: .shortened) })
                        }
                        .disabled(!ham.tnc.isConnected)
                    }
                }
                Section {
                    NavigationLink(value: HamRoute.mail) { row("Mail", "envelope.fill", badge: ham.unreadMail,
                                                               detail: ham.outbox.isEmpty ? nil : "\(ham.outbox.count) to send") }
                    NavigationLink(value: HamRoute.winlink) { row("Connect to a gateway", "arrow.up.arrow.down.circle.fill", detail: winlinkStatus) }
                } header: { Text("Winlink email") } footer: {
                    Text("Email over radio through a Winlink RMS packet gateway. No internet needed on your end.")
                }
                Section {
                    NavigationLink(value: HamRoute.terminal) {
                        row("Packet terminal", "terminal.fill", detail: ham.terminalPeer.map { "\($0)" })
                    }
                } header: { Text("BBS and nodes") } footer: {
                    Text("Connect to a packet BBS, node or another station and type to it.")
                }
                Section {
                    NavigationLink(value: HamRoute.monitor) {
                        row("Packet monitor", "waveform", detail: ham.packetsHeard > 0 ? "\(ham.packetsHeard) heard" : nil)
                    }
                    NavigationLink(value: HamRoute.satellites) { row("Satellite passes", "moon.stars.fill") }
                    NavigationLink(value: HamRoute.reference) { row("Frequencies and reference", "book.closed.fill") }
                    NavigationLink(value: HamRoute.settings) { row("Station settings", "gearshape.fill") }
                }
            }
            .navigationTitle("Ham")
            .navigationDestination(for: HamRoute.self) { $0.destination }
            .sheet(isPresented: $showTNC) { HamTNCSheet() }
        }
        .task {
            if LaunchScreen.value == "ham:demo" { ham.loadDemo() }
            if LaunchScreen.value == "ham:satellites" { path.append(.satellites) }
            ham.reconnectLast()
        }
    }

    private var winlinkStatus: String? {
        switch ham.winlink {
        case .idle: nil
        case .connecting: "connecting…"
        case .exchanging: "exchanging…"
        case .done: "done"
        case .failed: "failed"
        }
    }

    private var tncRow: some View {
        HStack(spacing: 14) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.title2)
                .foregroundStyle(ham.tnc.isConnected ? MeshUI.ok : .secondary)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(tncTitle).font(.headline)
                Text(tncDetail).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            Button(ham.tnc.isConnected ? "Disconnect" : "Connect") {
                if ham.tnc.isConnected { ham.disconnect() } else { showTNC = true }
            }
            .buttonStyle(.borderedProminent)
            .tint(ham.tnc.isConnected ? .gray : Theme.accent)
        }
        .padding(.vertical, 4)
    }

    private var tncTitle: LocalizedStringKey {
        switch ham.tnc {
        case .idle: "No TNC connected"
        case .connecting(let n): "Connecting to \(n)…"
        case .connected(let n): LocalizedStringKey(n)
        case .failed: "TNC disconnected"
        }
    }

    private var tncDetail: LocalizedStringKey {
        switch ham.tnc {
        case .idle: "Pair a Bluetooth KISS TNC or radio"
        case .connecting: "Turn on the radio's KISS/TNC mode"
        case .connected: "\(ham.packetsHeard) packets heard"
        case .failed(let why): LocalizedStringKey(why)
        }
    }

    @ViewBuilder private var modeFooter: some View {
        if ham.settings.canTransmit {
            Label("Licensed station \(ham.settings.baseCall): sending is on.", systemImage: "checkmark.seal.fill")
                .foregroundStyle(MeshUI.ok)
        } else {
            Label("Listen only. Add your callsign in Station settings to send.", systemImage: "ear")
        }
    }

    private func row(_ title: String, _ symbol: String, badge: Int = 0, detail: String? = nil) -> some View {
        HStack {
            Label(LocalizedStringKey(title), systemImage: symbol)
            Spacer()
            if let detail { Text(detail).foregroundStyle(.secondary).font(.subheadline) }
            if badge > 0 {
                Text("\(badge)").font(.caption.bold()).padding(.horizontal, 7).padding(.vertical, 2)
                    .background(Theme.accent, in: Capsule()).foregroundStyle(.black)
            }
        }
    }
}

/// Mesh and Ham share one tab on the phone (five tabs is the most a phone shows).
struct RadioTabView: View {
    @AppStorage("radio.section") private var section = "mesh"

    var body: some View {
        VStack(spacing: 0) {
            Picker("Radio", selection: $section) {
                Text("Mesh").tag("mesh")
                Text("Ham").tag("ham")
                Text("SDR").tag("sdr")
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 6)
            if section == "ham" { HamView() } else if section == "sdr" { SdrView() } else { MeshView() }
        }
        .onAppear {
            if LaunchScreen.parts()?.0 == "ham" { section = "ham" }
            if LaunchScreen.parts()?.0 == "sdr" { section = "sdr" }
        }
    }
}

struct HamTNCSheet: View {
    @State private var ham = HamService.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if ham.devices.isEmpty {
                        HStack { ProgressView(); Text("Looking for TNCs…").foregroundStyle(.secondary) }
                    }
                    ForEach(ham.devices) { d in
                        Button {
                            ham.connect(d)
                            dismiss()
                        } label: {
                            HStack {
                                Label(d.name, systemImage: "dot.radiowaves.left.and.right")
                                Spacer()
                                if d.rssi != 0 { Text("\(d.rssi) dBm").foregroundStyle(.secondary).font(.caption) }
                            }
                        }
                    }
                } footer: {
                    Text("Works with Bluetooth LE KISS TNCs: Mobilinkd TNC3/TNC4 and radios with a built-in TNC such as the BTech UV-Pro, Vero VR-N76 and Radioddity GA-5WB (turn on KISS TNC in the radio's menu), plus Nordic-UART and other Bluetooth serial TNCs. Classic-Bluetooth radios like the Kenwood TH-D74/D75 can't connect to an iPhone; Pocket Nomad for Android supports them.")
                }
                Section {
                    Toggle("Show all Bluetooth devices", isOn: $ham.showAllDevices)
                        .onChange(of: ham.showAllDevices) { _, _ in ham.startScan() }
                } footer: {
                    Text("Turn this on if your radio isn't listed: Pocket Nomad then tries any serial service it offers, and the packet monitor lists what it found.")
                }
                if case .poweredOff = ham.availability {
                    Section { Label("Bluetooth is off.", systemImage: "exclamationmark.triangle.fill") }
                }
            }
            .navigationTitle("Connect a TNC")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .onAppear { ham.startScan() }
        .onDisappear { ham.stopScan() }
    }
}
