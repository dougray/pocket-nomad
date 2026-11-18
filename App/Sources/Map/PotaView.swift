// PotaView.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0

import CoreLocation
import SwiftUI

/// Parks on the Air near you: who is on the air now, and the parks around you.
struct PotaView: View {
    @State private var store = PotaStore.shared
    @State private var location = LocationService.shared
    @Environment(\.dismiss) private var dismiss
    @State private var logging: Qso?
    private let metric = ConditionsFormat.current.metric

    private var here: CLLocationCoordinate2D? { location.location?.coordinate }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        if let here { Task { await store.refresh(near: here, force: true) } }
                    } label: { Label(store.busy ? "Updating…" : "Update", systemImage: "arrow.clockwise") }
                    .disabled(store.busy || here == nil)
                    if here == nil { Text("Waiting for your position…").font(.footnote).foregroundStyle(.secondary) }
                    if let s = store.snapshot { Text("Updated \(PotaParsers.ageText(s.fetched, now: Date()))").font(.footnote).foregroundStyle(.secondary) }
                    if let e = store.error { Text(e).font(.footnote).foregroundStyle(.orange) }
                } footer: {
                    Text("Parks on the Air (POTA) is an amateur radio award programme: operators go to parks and make contacts. Download once with signal; the last copy stays readable with no signal.")
                }
                let spots = store.activeSpots(near: here)
                Section("On the air now (\(spots.count))") {
                    if spots.isEmpty { Text(store.snapshot == nil ? "Tap Update." : "No fresh spots in the last 45 minutes.").foregroundStyle(.secondary) }
                    ForEach(spots.prefix(60)) { s in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(s.activator).font(.headline)
                                Spacer()
                                Text("\(s.frequencyText) \(s.mode)").font(.subheadline.monospaced())
                            }
                            Text("\(s.parkName) · \(s.reference)").font(.footnote)
                            HStack {
                                if let here { Text(ShapeMath.distanceText(CoverageShare.distance(here.latitude, here.longitude, s.latitude, s.longitude), metric: metric)).font(.caption).foregroundStyle(.secondary) }
                                Text(PotaParsers.ageText(s.spotted, now: Date())).font(.caption).foregroundStyle(.secondary)
                            }
                            PlaceActions(name: s.parkName, latitude: s.latitude, longitude: s.longitude) { dismiss() }
                            Button { logging = QsoLogView.blank(call: s.activator, mhz: s.frequencyKHz / 1000, mode: s.mode.uppercased(), sig: "POTA", sigInfo: s.reference) } label: { Label("Log this contact", systemImage: "square.and.pencil") }
                                .buttonStyle(.bordered).font(.footnote)
                        }
                        .padding(.vertical, 2)
                    }
                }
                let parks = nearestParks
                Section("Parks near you (\(store.snapshot?.parks.count ?? 0))") {
                    ForEach(parks.prefix(40)) { p in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(p.name).font(.subheadline)
                            Text(p.reference).font(.caption).foregroundStyle(.secondary)
                            PlaceActions(name: p.name, latitude: p.latitude, longitude: p.longitude) { dismiss() }
                        }
                    }
                }
                Section { Text("Data: POTA (pota.app), a free public service. Positions come from the park list; activations are spots posted by operators and the Reverse Beacon Network.").font(.caption).foregroundStyle(.secondary) }
            }
            .navigationTitle("Parks on the Air")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { location.begin() }
            .sheet(item: $logging) { QsoEditor(qso: $0) }
        }
    }

    private var nearestParks: [PotaPark] {
        let parks = store.snapshot?.parks ?? []
        guard let here else { return parks }
        return parks.sorted { CoverageShare.distance(here.latitude, here.longitude, $0.latitude, $0.longitude) < CoverageShare.distance(here.latitude, here.longitude, $1.latitude, $1.longitude) }
    }
}
