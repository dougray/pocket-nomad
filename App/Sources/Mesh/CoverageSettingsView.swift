// CoverageSettingsView.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Coverage scanner controls: recording, retention, private zones and the
// opt-in, privacy-filtered export. Private by default.

import SwiftUI
import UIKit

struct CoverageSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var store = CoverageStore.shared
    @State private var location = LocationService.shared
    @State private var confirmDelete = false
    @State private var addingZone = false
    @State private var zoneName = "Home"
    @State private var zoneRadius = 500.0
    @State private var exportFile: ExportFile?
    @State private var drive = DriveMode.shared
    @State private var mesh = MeshService.shared

    var body: some View {
        NavigationStack {
            Form {
                driveSection

                Section {
                    Toggle("Record coverage", isOn: $store.recording)
                    LabeledContent("Recordings", value: store.samples.count.formatted())
                    Picker("Keep for", selection: $store.retentionDays) {
                        ForEach(CoverageStore.retentionChoices, id: \.self) { Text("\($0) days").tag($0) }
                    }
                    Button("Delete all coverage data", role: .destructive) { confirmDelete = true }
                        .disabled(store.samples.isEmpty)
                } header: { Text("Coverage scanner") } footer: {
                    Text("While Pocket Nomad is open (or in drive mode) and connected to a radio, it notes where your phone was each time the radio hears another node, and how strong the signal was. See it on the map under Layers › Coverage. It stays on this phone.")
                }

                Section {
                    ForEach(store.zones) { zone in
                        LabeledContent(zone.name, value: "\(Int(zone.radius)) m")
                    }
                    .onDelete { store.removeZones(at: $0) }
                    Button {
                        zoneName = store.zones.isEmpty ? "Home" : "Private place \(store.zones.count + 1)"
                        addingZone = true
                    } label: { Label("Add a private zone where I am", systemImage: "plus.circle") }
                        .disabled(location.location == nil)
                } header: { Text("Private zones") } footer: {
                    Text(location.location == nil
                         ? "Waiting for GPS. Private zones are added at your current location."
                         : "Nothing in or near these places is ever shared, including whole map hexagons that touch them. Add your home first, then anywhere else you'd rather keep private.")
                }

                Section {
                    Toggle("Share coverage", isOn: $store.sharing)
                        .disabled(store.zones.isEmpty)
                    if store.sharing {
                        let cells = store.sharedCells
                        let hidden = store.samples.filter { s in store.zones.contains { $0.contains(latitude: s.latitude, longitude: s.longitude) } }.count
                        VStack(alignment: .leading, spacing: 6) {
                            Text("What a file would contain").font(.subheadline.weight(.semibold))
                            Label("\(cells.count) hexagons of about 350 m, each with only its best signal and a count", systemImage: "circle.hexagongrid")
                            Label("\(hidden) recordings in private zones left out, plus hexagons that touch them", systemImage: "house.slash")
                            Label("No node names or IDs, no exact positions, dates only to the month", systemImage: "eye.slash")
                        }
                        .font(.footnote)
                        Button {
                            if let url = try? store.exportFile() { exportFile = ExportFile(url: url) }
                        } label: { Label("Export coverage file", systemImage: "square.and.arrow.up") }
                            .disabled(cells.isEmpty)
                    }
                } header: { Text("Sharing (off by default)") } footer: {
                    Text(store.zones.isEmpty
                         ? "Add a private zone first. Sharing never happens on its own: you export a file and choose where it goes, such as the community coverage map."
                         : "Sharing never happens on its own: you export a file and choose where it goes. Preview it on the map under Layers › Coverage › What sharing would publish.")
                }
            }
            .navigationTitle("Coverage map")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() } }
            .onAppear { location.begin() }
            .onDisappear { location.end() }
            .confirmationDialog("Delete all \(store.samples.count) coverage recordings?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete all", role: .destructive) { store.deleteAll() }
            }
            .sheet(isPresented: $addingZone) { zoneEditor }
            .sheet(item: $exportFile) { ActivityView(items: [$0.url]) }
        }
    }

    private var driveSection: some View {
        Section {
            Toggle("Drive mode", isOn: Binding(get: { drive.active }, set: { $0 ? drive.start() : drive.stop() }))
            Toggle("Ping test", isOn: $drive.pinging)
            if drive.pinging {
                Picker("Ping", selection: $drive.target) {
                    if mesh.firmware != .meshcore { Text("Any radio that repeats it").tag(UInt32?.none) }
                    ForEach(mesh.sortedNodes.filter { $0.num != mesh.myNodeNum }) { node in
                        Text(node.displayName).tag(UInt32?.some(node.num))
                    }
                }
            }
            Toggle("Keep screen on", isOn: $drive.keepScreenOn)
            if drive.active {
                VStack(alignment: .leading, spacing: 4) {
                    if let note = drive.pingNote {
                        Text(note).foregroundStyle(.secondary)
                    } else if drive.pinging {
                        Text(drive.waiting ? "Waiting for an answer…" : "\(drive.sent) pings sent")
                    }
                    if drive.sent > 0 {
                        HStack(spacing: 12) {
                            Label("\(drive.reached) answered", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                            Label("\(drive.relayed) repeated", systemImage: "arrow.triangle.2.circlepath").foregroundStyle(.orange)
                            Label("\(drive.unanswered) nothing", systemImage: "xmark.circle").foregroundStyle(.red)
                        }
                        .labelStyle(.titleAndIcon)
                    }
                }
                .font(.footnote)
            }
        } header: { Text("Drive mode") } footer: {
            Text("Keeps recording while the phone is locked or in another app, and turns itself off after 4 hours. The ping test sends a tiny test message every 300 m (at most every 45 s) and marks the spot green if the radio you chose answered, yellow if another radio only repeated it, or red if nothing heard it. \(mesh.firmware == .meshcore ? "MeshCore pings are a short direct message, so pick your own second radio or a friend's. " : "")Uses battery and GPS: keep the phone on a charger.")
        }
    }

    private var zoneEditor: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $zoneName)
                Picker("Radius", selection: $zoneRadius) {
                    ForEach([300.0, 500, 1000, 2000], id: \.self) { Text("\(Int($0)) m").tag($0) }
                }
                Text("Centred on where you are now.").font(.footnote).foregroundStyle(.secondary)
            }
            .navigationTitle("Private zone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { addingZone = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        if let here = location.location?.coordinate {
                            store.addZone(name: zoneName.isEmpty ? "Private place" : zoneName,
                                          latitude: here.latitude, longitude: here.longitude, radius: zoneRadius)
                        }
                        addingZone = false
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private struct ExportFile: Identifiable {
    let url: URL
    var id: URL { url }
}

/// The system share sheet.
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
