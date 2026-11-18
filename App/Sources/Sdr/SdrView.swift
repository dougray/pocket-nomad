// SdrView.swift
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

import SwiftUI

/// The SDR side of the Radio tab: connect to an SDR box, pick what to listen to, see what it hears.
struct SdrView: View {
    @State private var client = SdrClient.shared
    @State private var state = SdrState.shared
    @State private var location = LocationService.shared
    @State private var token = SdrToken.load()
    private let metric = ConditionsFormat.current.metric

    private let allModes: [(id: String, label: String, symbol: String)] = [
        ("adsb", "Aircraft", "airplane"), ("aprs", "APRS", "dot.radiowaves.left.and.right"), ("nwr", "Weather radio", "cloud.bolt.rain"),
        ("ais", "Ships (AIS)", "ferry"), ("sonde", "Weather balloons", "balloon"), ("ft8", "HF stations (FT8)", "waveform"),
    ]
    /// Only what this box says it can receive.
    private var modes: [(id: String, label: String, symbol: String)] {
        guard let offered = state.hello?.modes else { return Array(allModes.prefix(3)) }
        return allModes.filter { offered.contains($0.id) }
    }

    var body: some View {
        List {
            connectionSection
            if client.isConnected { listenSection }
            if client.isConnected { aircraftSection; shipsSection; sondesSection; spotsSection; alertsSection }
            helpSection
        }
        .onAppear {
            // Debug shortcut for screenshots and tests: -screen sdr:connect joins a box on this Mac and listens for aircraft.
            if LaunchScreen.parts()?.1 == "connect", !client.isConnected {
                if client.host.isEmpty { client.host = "127.0.0.1" }
                client.connect()
                Task { try? await Task.sleep(for: .seconds(1)); client.start(mode: "adsb") }
            }
        }
    }

    private var connectionSection: some View {
        Section {
            HStack {
                Image(systemName: "externaldrive.connected.to.line.below")
                Text(statusText).foregroundStyle(client.link == .connected ? Color.green : .secondary)
            }
            if state.simulated {
                Label("Simulated box: the aircraft and packets are invented.", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote).foregroundStyle(.orange)
            }
            TextField("Box address (for example sdrbox.local)", text: $client.host)
                .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
            Stepper("Port \(String(client.port))", value: $client.port, in: 1...65535)
            SecureField("Pairing token (printed by the installer)", text: $token)
                .textInputAutocapitalization(.characters).autocorrectionDisabled()
                .onChange(of: token) { _, new in client.token = new }
            if client.isConnected || client.link == .connecting {
                Button("Disconnect", role: .destructive) { client.disconnect() }
            } else {
                Button("Connect") { client.connect() }.disabled(client.host.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if let h = state.hello, client.isConnected {
                LabeledContent("Receiver", value: h.dongle.isEmpty ? h.model : "\(h.dongle) on \(h.model)")
                LabeledContent("Heard so far", value: "\(state.decoded)")
            }
        } header: { Text("SDR box") } footer: {
            Text("An SDR box is a small Raspberry Pi with an RTL-SDR USB dongle that listens for aircraft, APRS and weather radio and sends the results here. It only listens; it never transmits.")
        }
    }

    private var statusText: String {
        switch client.link {
        case .idle: "Not connected"
        case .connecting: "Connecting…"
        case .connected: "Connected"
        case .failed(let m): m
        }
    }

    private var listenSection: some View {
        Section {
            Picker("Listen to", selection: Binding(get: { client.mode ?? "" }, set: { if !$0.isEmpty { client.start(mode: $0) } })) {
                Text("Choose…").tag("")
                ForEach(modes, id: \.id) { Label($0.label, systemImage: $0.symbol).tag($0.id) }
            }
            if client.mode != nil { Button("Stop listening", role: .destructive) { client.stop() } }
        } footer: {
            Text("One dongle can listen to one thing at a time. Aircraft: 1090 MHz. APRS: 144.390 MHz in North America. Weather radio: 162.400 to 162.550 MHz (US and Canada).")
        }
    }

    private var aircraftSection: some View {
        let here = location.location.map { ($0.coordinate.latitude, $0.coordinate.longitude) }
        let planes = state.visibleAircraft(now: Date(), near: here)
        return Section("Aircraft (\(planes.count))") {
            if planes.isEmpty { Text(client.mode == "adsb" ? "Listening… aircraft appear within a minute or two." : "Choose Aircraft above.").foregroundStyle(.secondary) }
            ForEach(planes.prefix(40)) { a in
                HStack {
                    Image(systemName: "airplane").rotationEffect(.degrees(Double(a.trackDegrees) - 90))
                    VStack(alignment: .leading) {
                        Text(a.callsign.isEmpty ? a.icao : a.callsign).font(.headline)
                        Text("\(a.altitudeFeet.formatted()) ft · \(a.groundSpeedKnots) kt · heading \(a.trackDegrees)°").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let here { Text(ShapeMath.distanceText(CoverageShare.distance(here.0, here.1, a.latitude, a.longitude), metric: metric)).font(.footnote).foregroundStyle(.secondary) }
                }
            }
        }
    }

    @ViewBuilder private var shipsSection: some View {
        let ships = state.visibleShips(now: Date())
        if !ships.isEmpty || client.mode == "ais" {
            Section("Ships (\(ships.count))") {
                if ships.isEmpty { Text("Listening… ships appear within a few minutes.").foregroundStyle(.secondary) }
                ForEach(ships.prefix(40)) { s in
                    VStack(alignment: .leading) {
                        Text(s.name.isEmpty ? "MMSI \(s.mmsi)" : s.name).font(.headline)
                        Text([s.kind, s.speedKnots.map { String(format: "%.1f kt", $0) }, s.courseDegrees.map { String(format: "heading %.0f°", $0) }].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    @ViewBuilder private var sondesSection: some View {
        let sondes = state.visibleSondes(now: Date())
        if !sondes.isEmpty || client.mode == "sonde" {
            Section("Weather balloons (\(sondes.count))") {
                if sondes.isEmpty { Text("Listening… balloons are launched twice a day around 00:00 and 12:00 UTC.").foregroundStyle(.secondary) }
                ForEach(sondes) { s in
                    VStack(alignment: .leading) {
                        Text("\(s.id) · \(s.model)").font(.headline)
                        Text("\(s.altitudeMetres.formatted()) m · \(s.climbMS >= 0 ? "rising" : "falling") \(String(format: "%.0f", abs(s.climbMS))) m/s · \(String(format: "%.3f", s.mhz)) MHz").font(.caption).foregroundStyle(.secondary)
                        PlaceActions(name: s.id, latitude: s.latitude, longitude: s.longitude)
                    }
                }
            }
        }
    }

    @ViewBuilder private var spotsSection: some View {
        let spots = state.visibleSpots(now: Date())
        if !spots.isEmpty || client.mode == "ft8" || client.mode == "wspr" {
            Section("Heard on HF (\(spots.count))") {
                ForEach(spots.prefix(40)) { s in
                    HStack { Text(s.call).font(.headline); Text(s.grid).foregroundStyle(.secondary); Spacer(); Text("\(s.snr) dB · \(String(format: "%.3f", s.mhz)) MHz").font(.caption) }
                }
            }
        }
    }

    private var alertsSection: some View {
        Section("Weather radio") {
            if !state.channels.isEmpty {
                ForEach(state.channels.sorted { $0.mhz < $1.mhz }, id: \.mhz) { c in
                    HStack { Text(String(format: "%.3f MHz", c.mhz)); Spacer(); Text(String(format: "%.0f dB", c.snr)).foregroundStyle(.secondary) }
                }
            }
            if state.alerts.isEmpty { Text(client.mode == "nwr" ? "Listening for alerts…" : "No alerts heard.").foregroundStyle(.secondary) }
            ForEach(Array(state.alerts.enumerated()), id: \.offset) { _, a in
                VStack(alignment: .leading) {
                    Text(a.text).font(.headline)
                    Text("\(a.receivedAt.formatted(date: .omitted, time: .shortened)) · \(String(format: "%.3f", a.mhz)) MHz").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var helpSection: some View {
        Section("How to set it up") {
            Text("1. Plug an RTL-SDR dongle and its antenna into a Raspberry Pi Zero 2 W (a USB-OTG adapter is needed).").font(.footnote)
            Text("2. Install the sdrbox software on the Pi (see the sdrbox project) and put the Pi on your phone's Wi-Fi or its own hotspot.").font(.footnote)
            Text("3. Type the Pi's address and the pairing token the installer printed, and tap Connect. Aircraft show on the Map too (Layers > Aircraft).").font(.footnote)
            Text("Tips: an antenna by a window or outside hears far more. Your location is never sent to the box.").font(.footnote).foregroundStyle(.secondary)
        }
    }
}
