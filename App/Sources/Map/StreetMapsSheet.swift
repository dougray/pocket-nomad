// StreetMapsSheet.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Download street maps around where you are (OpenFreeMap tiles kept in
// MapLibre's offline storage), and the list of what is on the phone.

import CoreLocation
import SwiftUI

struct StreetMapsSheet: View {
    /// Used when there is no GPS fix yet.
    let mapCenter: CLLocationCoordinate2D?
    @Environment(\.dismiss) private var dismiss
    @State private var store = StreetMapStore.shared
    @State private var location = LocationService.shared
    @State private var wideKm = 400
    @State private var detailKm = 50
    @State private var name = ""
    @State private var confirmDelete: StreetMap?

    /// Where to centre the download: the live GPS fix, else the middle of the map.
    private var center: CLLocationCoordinate2D? { location.location?.coordinate ?? mapCenter }
    private var usingGPS: Bool { location.location != nil }

    private var estimate: StreetTiles.Estimate? {
        center.map { StreetTiles.estimate(latitude: $0.latitude, longitude: $0.longitude, wideKm: wideKm, detailKm: detailKm) }
    }

    /// "Streets around 39.74, -104.99", unless the user typed a name.
    private var downloadName: String {
        let typed = name.trimmingCharacters(in: .whitespaces)
        if !typed.isEmpty { return typed }
        guard let c = center else { return "Streets" }
        return "Streets around " + String(format: "%.2f, %.2f", locale: Locale(identifier: "en_US_POSIX"), c.latitude, c.longitude)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Main roads and towns", selection: $wideKm) {
                        ForEach(StreetTiles.wideChoices, id: \.self) { Text("Within \($0) km").tag($0) }
                    }
                    Picker("Every street", selection: $detailKm) {
                        ForEach(StreetTiles.detailChoices, id: \.self) { Text("Within \($0) km").tag($0) }
                    }
                    TextField("Name (optional)", text: $name)
                    if let estimate {
                        LabeledContent("Download", value: "about \(Int64(estimate.bytes).byteString)")
                            .foregroundStyle(.secondary)
                    }
                    Button {
                        guard let center else { return }
                        let label = downloadName
                        Task {
                            await store.download(around: center, wideKm: wideKm, detailKm: detailKm, name: label)
                            name = ""
                        }
                    } label: {
                        if store.preparing {
                            HStack { ProgressView(); Text("Starting…") }
                        } else {
                            Label("Download streets around me", systemImage: "arrow.down.circle")
                        }
                    }
                    .disabled(center == nil || store.preparing)
                    if let error = store.lastError {
                        Text(error).font(.footnote).foregroundStyle(.red)
                    }
                } header: { Text("Download") } footer: {
                    Text(center == nil
                         ? "Waiting for GPS. Streets are downloaded around where you are."
                         : (usingGPS ? "Downloads around where you are. " : "No GPS fix yet, so this downloads around the middle of the map: move the map to your area first. ")
                            + "Highways, main roads, cities and towns cover the wider area; every street, path and building the closer one. "
                            + "The size is a rough guide: cities take more, open country less. Use Wi-Fi if you can. "
                            + "Map data from OpenStreetMap via OpenFreeMap, free with no account. Your location isn't sent, only the requests for map tiles.")
                }

                Section {
                    if store.maps.isEmpty {
                        Text("None downloaded yet.").foregroundStyle(.secondary)
                    }
                    ForEach(store.maps) { map in row(map) }
                } header: { Text("Street maps") } footer: {
                    Text("The map uses these automatically, with or without signal. A region map from Library › Get Content takes their place while one is installed. Map data © OpenStreetMap contributors · OpenFreeMap.")
                }
            }
            .onAppear {
                location.begin()
                store.start()
            }
            .onDisappear { location.end() }
            .navigationTitle("Street maps")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() } }
            .confirmationDialog("Delete \(confirmDelete?.name ?? "this map") from this phone?",
                                isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
                                titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    if let map = confirmDelete { store.delete(map) }
                    confirmDelete = nil
                }
            }
        }
    }

    private func row(_ map: StreetMap) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(map.name).font(.body)
                Spacer()
                Text(map.bytes.byteString).foregroundStyle(.secondary)
            }
            Text("\(map.detailRadiusKm) km of streets, \(map.wideRadiusKm) km of main roads · \(map.created.formatted(date: .abbreviated, time: .omitted))")
                .font(.caption).foregroundStyle(.secondary)
            if map.downloading || (!map.complete && map.progress < 1) {
                ProgressView(value: map.progress)
                HStack {
                    Text(map.downloading ? "Downloading… \(Int(map.progress * 100))%" : "Paused at \(Int(map.progress * 100))%")
                        .font(.caption)
                    Spacer()
                    Button("Cancel", role: .destructive) { store.delete(map) }.font(.caption).buttonStyle(.borderless)
                }
            } else if map.outdated {
                HStack {
                    Text("Older map data: download again to use it offline.").font(.caption).foregroundStyle(.orange)
                    Spacer()
                    Button("Update") { Task { await store.redownload(map) } }.font(.caption).buttonStyle(.borderless)
                }
            }
        }
        .swipeActions { Button("Delete", role: .destructive) { confirmDelete = map } }
    }
}
