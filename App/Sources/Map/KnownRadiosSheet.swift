// KnownRadiosSheet.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Download (and delete) known radios from the community maps, and the
// details shown when one is tapped on the map.

import CoreLocation
import SwiftUI

struct KnownRadiosSheet: View {
    let center: CLLocationCoordinate2D?
    @Environment(\.dismiss) private var dismiss
    @State private var store = KnownRadioStore.shared
    @State private var radiusKm = 200
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let updated = store.updated {
                        LabeledContent("Meshtastic", value: store.meshtasticCount.formatted())
                        LabeledContent("MeshCore", value: store.meshcoreCount.formatted())
                        LabeledContent("Repeaters and routers", value: store.radios.count(where: { $0.role == .repeater }).formatted())
                        LabeledContent("Downloaded", value: updated.formatted(date: .abbreviated, time: .shortened))
                    } else {
                        Text("None downloaded yet.").foregroundStyle(.secondary)
                    }
                } header: { Text("On this phone") } footer: {
                    Text("Shown on the map under Layers › Known radios. They stay on this phone and work offline. Positions are what each radio last reported, often rounded for privacy, and may be out of date.")
                }

                Section {
                    Picker("Area", selection: $radiusKm) {
                        ForEach(KnownRadioStore.radiusChoices, id: \.self) { Text("Within \($0) km").tag($0) }
                    }
                    Button {
                        guard let center else { return }
                        Task { await store.download(around: center, radiusKm: radiusKm, areaName: nil) }
                    } label: {
                        if store.downloading {
                            HStack { ProgressView(); Text("Downloading…") }
                        } else {
                            Label(store.updated == nil ? "Download known radios" : "Update known radios", systemImage: "arrow.down.circle")
                        }
                    }
                    .disabled(center == nil || store.downloading)
                    if let error = store.lastError {
                        Text(error).font(.footnote).foregroundStyle(.red)
                    }
                } header: { Text("Download") } footer: {
                    Text(center == nil
                         ? "Waiting for GPS. Radios are kept around where you are."
                         : "Gets the public lists from meshmap.net (Meshtastic radios heard by the official MQTT server) and map.meshcore.io (MeshCore radios that uploaded their adverts), about 5 to 20 MB, and keeps only radios near you. Needs internet once. Your location isn't sent. Radios with ⛔, 🛑 or 🚫 in their name are left out.")
                }

                if store.updated != nil {
                    Section {
                        Button("Delete known radios", role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle("Known radios")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() } }
            .confirmationDialog("Delete \(store.radios.count) known radios from this phone?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { store.deleteAll() }
            }
        }
    }
}

struct KnownRadioDetail: View {
    let radio: KnownRadio

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Circle().fill(Color(uiColor: RadioColors.color(radio.firmware))).frame(width: 14, height: 14)
                Text(radio.name).font(.headline).lineLimit(2)
            }
            LabeledContent("System", value: radio.firmware == .meshcore ? "MeshCore" : "Meshtastic")
            LabeledContent("Type", value: radio.roleLabel)
            if let seen = radio.lastSeen {
                LabeledContent("Last reported", value: seen.formatted(.relative(presentation: .named)))
            }
            LabeledContent("Position", value: CoordinateFormat.decimal(CLLocationCoordinate2D(latitude: radio.latitude, longitude: radio.longitude)))
            Text("From \(radio.firmware == .meshcore ? "map.meshcore.io" : "meshmap.net"). Your radio may not hear it.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .font(.subheadline)
        .padding()
    }
}
