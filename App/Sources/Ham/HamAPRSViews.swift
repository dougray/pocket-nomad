// HamAPRSViews.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

import CoreLocation
import SwiftUI

struct HamMonitorView: View {
    @State private var ham = HamService.shared
    @State private var filter = "All"

    private var rows: [HamMonitorEntry] {
        let all = ham.monitor.reversed()
        switch filter {
        case "APRS": return all.filter { $0.kind != nil }
        case "Mine": return all.filter(\.outgoing)
        default: return Array(all)
        }
    }

    var body: some View {
        List {
            Picker("Show", selection: $filter) {
                ForEach(["All", "APRS", "Mine"], id: \.self) { Text($0) }
            }
            .pickerStyle(.segmented)
            if rows.isEmpty {
                Text(ham.tnc.isConnected ? "Listening. Packets show here as the radio hears them."
                                         : "Connect a TNC to start listening.")
                    .foregroundStyle(.secondary)
            }
            ForEach(rows) { e in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(e.date.formatted(date: .omitted, time: .standard)).font(.caption2).foregroundStyle(.secondary)
                        if e.outgoing { Text("SENT").font(.caption2.bold()).foregroundStyle(Theme.accent) }
                        if let k = e.kind { Text(k.rawValue).font(.caption2).foregroundStyle(.secondary) }
                    }
                    Text(e.line).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                }
            }
        }
        .navigationTitle("Monitor")
        .toolbar { Button("Clear") { ham.clearMonitor() } }
    }
}

struct HamStationsView: View {
    @State private var ham = HamService.shared
    @State private var search = ""

    var body: some View {
        List {
            if ham.stations.isEmpty {
                Text("APRS stations, objects and weather reports appear here as they're heard.").foregroundStyle(.secondary)
            }
            ForEach(filtered) { s in
                NavigationLink(value: HamRoute.station(s.id)) { HamStationRow(station: s, me: ham.myLocation) }
            }
        }
        .searchable(text: $search, prompt: "Callsign")
        .navigationTitle("Stations")
        .toolbar {
            Menu {
                Button("Forget all stations", role: .destructive) { ham.forgetStations() }
            } label: { Image(systemName: "ellipsis.circle") }
        }
        .task { await ham.refreshLocation() }
    }

    private var filtered: [HamStation] {
        search.isEmpty ? ham.stations : ham.stations.filter { $0.id.localizedCaseInsensitiveContains(search) }
    }
}

struct HamStationRow: View {
    let station: HamStation
    let me: CLLocation?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: HamSymbols.systemImage(station.symbol, weather: station.weather != nil))
                .foregroundStyle(station.emergency ? Theme.danger : Theme.accent)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(station.id).font(.headline)
                    if station.emergency { Text("EMERGENCY").font(.caption.bold()).foregroundStyle(Theme.danger) }
                }
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                if let d = distance { Text(d).font(.subheadline) }
                Text(station.lastHeard, style: .relative).font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private var subtitle: String {
        [station.weather, station.micEStatus, station.comment.isEmpty ? nil : station.comment,
         station.status.isEmpty ? nil : station.status].compactMap { $0 }.joined(separator: " · ")
    }

    private var distance: String? {
        guard let me, let lat = station.latitude, let lon = station.longitude else { return nil }
        let km = HamGeo.distanceKm((me.coordinate.latitude, me.coordinate.longitude), (lat, lon))
        let b = HamGeo.bearing((me.coordinate.latitude, me.coordinate.longitude), (lat, lon))
        let dirs = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
        let dir = dirs[Int((b + 22.5) / 45) % 8]
        let miles = km * 0.621371
        return miles < 10 ? String(format: "%.1f mi %@", miles, dir) : String(format: "%.0f mi %@", miles, dir)
    }
}


struct HamStationDetail: View {
    let id: String
    @State private var ham = HamService.shared
    @State private var reply = ""

    var body: some View {
        let s = ham.stations.first { $0.id == id }
        List {
            if let s {
                Section {
                    HamStationRow(station: s, me: ham.myLocation)
                }
                Section("Details") {
                    if let lat = s.latitude, let lon = s.longitude {
                        LabeledContent("Position", value: String(format: "%.5f, %.5f", lat, lon))
                        LabeledContent("Grid", value: HamGeo.maidenhead(lat: lat, lon: lon))
                    }
                    if let sp = s.speedKnots, sp > 0 { LabeledContent("Speed", value: String(format: "%.0f mph", sp * 1.15078)) }
                    if let c = s.courseDegrees { LabeledContent("Course", value: "\(c)°") }
                    if let a = s.altitudeFeet { LabeledContent("Altitude", value: String(format: "%.0f ft", a)) }
                    if let w = s.weather { LabeledContent("Weather", value: w) }
                    if let m = s.micEStatus { LabeledContent("Status", value: m) }
                    if !s.comment.isEmpty { LabeledContent("Comment", value: s.comment) }
                    if !s.status.isEmpty { LabeledContent("Status text", value: s.status) }
                    LabeledContent("Heard via", value: s.path.isEmpty ? "direct" : s.path.joined(separator: ", "))
                    LabeledContent("Packets", value: "\(s.packets)")
                    LabeledContent("First heard", value: s.firstHeard.formatted(date: .abbreviated, time: .shortened))
                }
                if let lat = s.latitude, let lon = s.longitude {
                    Section { PlaceActions(name: s.id, latitude: lat, longitude: lon) }
                }
                if !s.isObject {
                    Section {
                        NavigationLink(value: HamRoute.thread(s.source)) { Label("Message \(s.source)", systemImage: "bubble.left.fill") }
                    }
                }
            } else {
                Text("Not heard yet.")
            }
        }
        .navigationTitle(id)
    }
}

struct APRSConversationsView: View {
    @State private var ham = HamService.shared
    @State private var newPeer = ""
    @State private var showNew = false

    var body: some View {
        List {
            if ham.conversations.isEmpty {
                Text("APRS messages to and from your callsign appear here. Anyone in range (or through a digipeater or iGate) can reach you.")
                    .foregroundStyle(.secondary)
            }
            ForEach(ham.conversations, id: \.peer) { c in
                NavigationLink(value: HamRoute.thread(c.peer)) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(c.peer).font(.headline)
                            Text(c.last.text).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        if let n = ham.unread[c.peer], n > 0 {
                            Text("\(n)").font(.caption.bold()).padding(6).background(Theme.accent, in: Circle()).foregroundStyle(.black)
                        }
                    }
                }
                .swipeActions { Button("Delete", role: .destructive) { ham.deleteConversation(c.peer) } }
            }
        }
        .navigationTitle("APRS messages")
        .toolbar {
            Button { showNew = true } label: { Image(systemName: "square.and.pencil") }
                .disabled(!ham.settings.canTransmit)
        }
        .alert("New message", isPresented: $showNew) {
            TextField("Callsign, e.g. KF0XYZ-7", text: $newPeer).textInputAutocapitalization(.characters)
            NavigationLink("Write", value: HamRoute.thread(newPeer.uppercased()))
            Button("Cancel", role: .cancel) {}
        }
    }
}

struct APRSThreadView: View {
    let peer: String
    @State private var ham = HamService.shared
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(ham.chats.filter { $0.peer == peer }) { c in
                            HStack {
                                if c.outgoing { Spacer(minLength: 40) }
                                VStack(alignment: c.outgoing ? .trailing : .leading, spacing: 3) {
                                    Text(c.text)
                                        .padding(10)
                                        .background(c.outgoing ? Theme.accent.opacity(0.85) : Theme.card, in: RoundedRectangle(cornerRadius: 14))
                                        .foregroundStyle(c.outgoing ? .black : .primary)
                                    Text(statusText(c)).font(.caption2).foregroundStyle(c.status == .failed ? Theme.danger : .secondary)
                                }
                                if !c.outgoing { Spacer(minLength: 40) }
                            }
                            .id(c.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: ham.chats.count) { _, _ in
                    if let last = ham.chats.last(where: { $0.peer == peer }) { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            if ham.settings.canTransmit {
                HStack {
                    TextField("Message (67 characters per packet)", text: $draft, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(1...4)
                    Button {
                        ham.sendMessage(to: peer, text: draft)
                        draft = ""
                    } label: { Image(systemName: "arrow.up.circle.fill").font(.title) }
                    .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty || !ham.tnc.isConnected)
                }
                .padding()
            } else {
                Text("Listen only: add your callsign in Station settings to reply.")
                    .font(.footnote).foregroundStyle(.secondary).padding()
            }
        }
        .navigationTitle(peer)
        .onAppear { ham.markRead(peer) }
    }

    private func statusText(_ c: APRSChat) -> String {
        let t = c.date.formatted(date: .omitted, time: .shortened)
        switch c.status {
        case .sending: return "\(t) · sending (try \(c.tries) of 5)"
        case .acked: return "\(t) · delivered"
        case .failed: return "\(t) · no acknowledgement"
        case .rejected: return "\(t) · rejected"
        case .received, .sent: return t
        }
    }
}

struct APRSBulletinsView: View {
    @State private var ham = HamService.shared

    var body: some View {
        List {
            if ham.bulletins.isEmpty {
                Text("Bulletins (BLN) and announcements heard on APRS: nets, weather alerts, emergency information.")
                    .foregroundStyle(.secondary)
            }
            ForEach(ham.bulletins) { b in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(b.source).font(.headline)
                        Text(b.addressee).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Text(b.date, style: .relative).font(.caption2).foregroundStyle(.secondary)
                    }
                    Text(b.text)
                }
            }
        }
        .navigationTitle("Bulletins")
    }
}

/// APRS symbols as SF Symbols (the common ones; the rest get a pin).
enum HamSymbols {
    static func systemImage(_ symbol: String, weather: Bool) -> String {
        if weather { return "cloud.sun.fill" }
        let code = symbol.count > 1 ? symbol.last! : "?"
        let primary = symbol.first == "/"
        switch (primary, code) {
        case (true, ">"), (true, "<"), (true, "j"), (true, "k"), (true, "u"), (true, "v"): return "car.fill"
        case (true, "["): return "figure.walk"
        case (true, "b"): return "bicycle"
        case (true, "#"), (false, "#"): return "dot.radiowaves.up.forward"
        case (true, "&"), (false, "&"), (true, "r"): return "antenna.radiowaves.left.and.right"
        case (true, "-"): return "house.fill"
        case (true, "_"), (false, "_"): return "cloud.sun.fill"
        case (true, "+"): return "cross.case.fill"
        case (true, "o"): return "building.columns.fill"
        case (true, "^"), (true, "'"): return "airplane"
        case (true, "s"), (true, "Y"): return "sailboat.fill"
        case (true, ";"): return "tent.fill"
        case (false, "!"): return "exclamationmark.triangle.fill"
        default: return "mappin.circle.fill"
        }
    }
}
