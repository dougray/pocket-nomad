// SatellitesView.swift
// Pocket Nomad - Satellites module
// SPDX-License-Identifier: Apache-2.0
//
// Satellite passes in the Ham tab: when the space station and the amateur radio
// satellites you follow are overhead. Orbit data is downloaded once; the passes
// are worked out on the phone.

import SwiftUI

struct SatellitesView: View {
    @State private var store = SatelliteStore.shared
    @State private var search = ""
    @State private var showAll = false

    var body: some View {
        List {
            updateSection
            if store.observer == nil && !store.satellites.isEmpty {
                Section { Label("Allow location to work out passes for where you are.", systemImage: "location.slash").font(.footnote) }
            }
            if let iss = store.iss { issSection(iss) }
            passesSection
            allSection
            Section {
                Text("Orbit data from CelesTrak, free with no account. Passes are worked out on this phone from your location, which never leaves it. Times assume a clear horizon: hills and buildings shorten passes. Passes below about 10° are left out because they're too low to hear well.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Satellite passes")
        .refreshable { await update() }
        .task { await load() }
    }

    private func load() async {
        await store.updateObserver()
        if store.satellites.isEmpty { await store.refresh() }
        await store.computePasses()
    }

    private func update() async {
        await store.refresh()
        await store.updateObserver()
        await store.computePasses()
    }

    private var updateSection: some View {
        Section {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(store.updating ? "Updating…" : (store.fetchedAt.map { "Orbit data \(ConditionsParsers.age($0, now: Date()))" } ?? "No orbit data yet")).font(.headline)
                    if let days = store.orbitAgeDays, days > 10 {
                        Label("Over \(Int(days)) days old: passes may be off by minutes. Update when you can.", systemImage: "exclamationmark.triangle.fill")
                            .font(.caption).foregroundStyle(MeshUI.warn)
                    } else {
                        Text("Needs a connection once; then it works with no signal.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button { Task { await update() } } label: { if store.updating { ProgressView() } else { Text("Update") } }
                    .buttonStyle(.borderedProminent).tint(Theme.accent).disabled(store.updating)
            }
            if let e = store.error { Label(e, systemImage: "wifi.exclamationmark").font(.footnote).foregroundStyle(MeshUI.warn) }
        }
    }

    private func issSection(_ iss: TLE) -> some View {
        Section("International Space Station") {
            if let next = store.passes.first(where: { $0.noradID == SatelliteStore.issID }) {
                passRow(next)
            } else if store.favorites.contains(SatelliteStore.issID) {
                Text(store.computing ? "Working it out…" : "No passes above 10° in the next 48 hours.").font(.subheadline).foregroundStyle(.secondary)
            } else {
                Button("Follow the space station") { store.toggleFavorite(SatelliteStore.issID); Task { await store.computePasses() } }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Ham radio on the ISS").font(.subheadline.weight(.semibold))
                Text("Packet and APRS digipeater: 145.825 MHz FM, path ARISS, 1200 baud. Voice contacts and school events: 145.800 MHz FM down. Tune a few kHz higher as it rises and lower as it sets (Doppler). Crew activities often switch the radio off.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var passesSection: some View {
        Section("Next passes") {
            if store.favorites.isEmpty { Text("Star satellites below to follow them.").font(.subheadline).foregroundStyle(.secondary) }
            else if store.passes.isEmpty { Text(store.computing ? "Working it out…" : "No passes above 10° in the next 48 hours.").font(.subheadline).foregroundStyle(.secondary) }
            ForEach(store.passes.prefix(20)) { passRow($0) }
        }
    }

    private func passRow(_ p: SatellitePass) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(p.name).font(.headline)
                Spacer()
                Text(p.inProgress || p.start <= Date() ? "overhead now" : "in \(Self.countdown(p.start.timeIntervalSinceNow))")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(p.start <= Date() ? MeshUI.ok : Theme.accent)
            }
            Text("\(p.start.formatted(date: .abbreviated, time: .shortened)) · \(Int(p.duration / 60)) min · peaks \(Int(p.maxElevation.rounded()))°")
                .font(.subheadline)
            Text(SatelliteMath.describe(p)).font(.caption).foregroundStyle(.secondary)
        }
    }

    private var allSection: some View {
        Section {
            DisclosureGroup("All satellites (\(store.satellites.count))", isExpanded: $showAll) {
                TextField("Search", text: $search).textFieldStyle(.roundedBorder)
                ForEach(store.satellites.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }) { s in
                    HStack {
                        Text(s.name).font(.subheadline)
                        Spacer()
                        Button {
                            store.toggleFavorite(s.noradID)
                            Task { await store.computePasses() }
                        } label: { Image(systemName: store.favorites.contains(s.noradID) ? "star.fill" : "star").foregroundStyle(Theme.accent) }
                        .buttonStyle(.plain)
                    }
                }
            }
        } footer: {
            Text("Satellites in very high orbits (a day or more per orbit) aren't included.")
        }
    }

    static func countdown(_ seconds: TimeInterval) -> String {
        let m = Int(max(0, seconds) / 60)
        if m < 60 { return "\(m) min" }
        if m < 1440 { return "\(m / 60) h \(m % 60) min" }
        return "\(m / 1440) d \((m % 1440) / 60) h"
    }
}
