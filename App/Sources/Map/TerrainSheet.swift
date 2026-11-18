// TerrainSheet.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0

import CoreLocation
import SwiftUI

/// Download elevation tiles for hills on the map and for radio line of sight.
struct TerrainSheet: View {
    let mapCenter: CLLocationCoordinate2D?
    @State private var store = TerrainStore.shared
    @State private var radiusKm = 50.0
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Elevation data lets the map shade hills (Layers > Map look) and lets Pocket Nomad check a radio path for hills in the way. Download an area once and it works with no signal.")
                        .font(.footnote)
                }
                if let c = mapCenter {
                    Section("Area around the map centre") {
                        Picker("Radius", selection: $radiusKm) {
                            Text("25 km").tag(25.0); Text("50 km").tag(50.0); Text("100 km").tag(100.0)
                        }.pickerStyle(.segmented)
                        Text("About \(Int(store.estimateMB(centre: c, radiusKm: radiusKm).rounded())) MB").font(.footnote).foregroundStyle(.secondary)
                        Button {
                            Task { await store.download(centre: c, radiusKm: radiusKm) }
                        } label: { Label(store.downloading ? "Downloading…" : "Download", systemImage: "arrow.down.circle") }
                        .disabled(store.downloading)
                        if store.downloading { ProgressView(value: store.progress) }
                        if !store.message.isEmpty { Text(store.message).font(.footnote).foregroundStyle(.secondary) }
                    }
                }
                Section("Saved") {
                    Text(store.hasTiles ? "\(store.tileCount) tiles on this phone." : "Nothing saved. Hills load live when you have signal.")
                    if store.hasTiles { Button("Delete saved elevation", role: .destructive) { store.deleteAll() } }
                }
                Section { Text("Elevation: Mapzen terrain tiles from AWS Open Data (SRTM, USGS, and others).").font(.caption).foregroundStyle(.secondary) }
            }
            .navigationTitle("Elevation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
