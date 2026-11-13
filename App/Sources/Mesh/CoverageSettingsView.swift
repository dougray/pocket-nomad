// CoverageSettingsView.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Coverage scanner controls: recording, retention, private zones, backups
// and the opt-in, privacy-filtered export. Private by default.

import SwiftUI
import UIKit
import UniformTypeIdentifiers

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
    @State private var backup = CoverageBackupService.shared
    @State private var pickingFolder = false
    @State private var restoring = false
    @State private var restoreMessage: String?
    @State private var hidden = HiddenRadioStore.shared

    var body: some View {
        NavigationStack {
            Form {
                if store.isEmpty {
                    Section {
                        Button { restoring = true } label: { Label("Restore from a backup…", systemImage: "arrow.counterclockwise") }
                    }
                }

                driveSection

                Section {
                    Toggle("Record coverage", isOn: $store.recording)
                    Label(HeardRadios.summary(HeardRadios.mapped(in: mesh)), systemImage: "antenna.radiowaves.left.and.right")
                    LabeledContent("Recordings", value: store.samples.count.formatted())
                    if Set(store.samples.map(\.firmware)).count > 1 || mesh.rememberedLinks.count > 1 {
                        ForEach(MeshFirmware.allCases, id: \.self) { net in
                            let c = store.counts(net)
                            LabeledContent { Text("\(c.samples) points, \(c.pings) pings") } label: { MeshNetworkBadge(network: net) }
                        }
                    }
                    Picker("Keep for", selection: $store.retentionDays) {
                        ForEach(CoverageStore.retentionChoices, id: \.self) { Text("\($0) days").tag($0) }
                    }
                    Button("Delete all coverage data", role: .destructive) { confirmDelete = true }
                        .disabled(store.samples.isEmpty)
                } header: { Text("Coverage scanner") } footer: {
                    Text("While Pocket Nomad is open (or in drive mode) and connected to a radio, it notes where your phone was each time the radio hears another node, and how strong the signal was. With a Meshtastic and a MeshCore radio connected, both record at once and each point is tagged with its network. The map shows the radios heard where they say they are (Layers › Radios heard). Your recordings can also be drawn as hexagons along where you drove (Layers › Coverage hexagons › Where I drove), which is off unless you turn it on. It all stays on this phone.")
                }

                hiddenSection

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

                backupSection
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
            .sheet(isPresented: $pickingFolder) {
                FolderPicker { url in
                    Task {
                        await backup.chooseFolder(url)
                        restoreMessage = backup.folderRestoreMessage
                    }
                }
                .ignoresSafeArea()
            }
            .fileImporter(isPresented: $restoring, allowedContentTypes: [.json]) { result in
                switch result {
                case .success(let url):
                    do { restoreMessage = try backup.restore(from: url) } catch {
                        restoreMessage = (error as? LocalizedError)?.errorDescription ?? "Couldn't read that file."
                    }
                case .failure:
                    break
                }
            }
            .alert("Restore", isPresented: Binding(get: { restoreMessage != nil }, set: { if !$0 { restoreMessage = nil } })) {
                Button("OK") { restoreMessage = nil }
            } message: { Text(restoreMessage ?? "") }
        }
    }

    private var backupStatus: String {
        guard let folder = backup.folderName else { return "Off" }
        let last = backup.lastBackup.map { "last backup " + $0.formatted(.relative(presentation: .named)) } ?? "no backup yet"
        return "Backing up to \(folder) · \(last)"
    }

    private var backupSection: some View {
        Section {
            LabeledContent("Automatic backup") {
                if backup.busy { ProgressView() } else { Text(backupStatus).multilineTextAlignment(.trailing) }
            }
            if let error = backup.lastError {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
            Button { pickingFolder = true } label: {
                Label(backup.isOn ? "Change backup folder…" : "Choose backup folder…", systemImage: "folder")
            }
            if backup.isOn {
                Button { Task { await backup.backUpNow() } } label: { Label("Back up now", systemImage: "arrow.clockwise") }
                    .disabled(backup.busy)
                Button("Turn off automatic backup", role: .destructive) { backup.turnOff() }
            }
            Button {
                if let url = try? backup.exportFile() { exportFile = ExportFile(url: url) }
            } label: { Label("Export a copy…", systemImage: "square.and.arrow.up") }
            Button { restoring = true } label: { Label("Restore from backup…", systemImage: "arrow.counterclockwise") }
        } header: { Text("Backup") } footer: {
            Text("Backups stay on this iPhone, in a folder you choose outside the app, so they survive deleting and reinstalling Pocket Nomad. They contain your location history; nothing is uploaded. Make a folder such as On My iPhone › Pocket Nomad Backups in the Files app and choose it here; it is updated after each drive and when you leave the app, and a backup already in it is merged in first. Export a copy only if you want one elsewhere: where it goes is your choice. Restoring adds to what is here and never deletes anything.")
        }
    }

    /// Networks drive mode can ping on: every set-up radio, or the default one.
    private var driveNetworks: [MeshFirmware] {
        let set = mesh.rememberedLinks.map(\.firmware)
        return set.isEmpty ? [mesh.defaultNetwork] : set
    }

    private var driveSection: some View {
        Section {
            Toggle("Drive mode", isOn: Binding(get: { drive.active }, set: { $0 ? drive.start() : drive.stop() }))
            Toggle("Ping test", isOn: $drive.pinging)
            if drive.pinging {
                ForEach(driveNetworks, id: \.self) { net in
                    Picker(driveNetworks.count > 1 ? "Ping on \(net.displayName)" : "Ping",
                           selection: Binding(get: { drive.target(for: net) }, set: { drive.setTarget($0, for: net) })) {
                        Text(net == .meshcore ? "Nearest repeater" : "Any radio that repeats it").tag(UInt32?.none)
                        ForEach(mesh.link(net).nodes.values.filter { $0.num != mesh.link(net).radio.myNodeNum && !mesh.isBlocked($0.num) }
                            .sorted { $0.displayName < $1.displayName }) { node in
                            Text(node.displayName).tag(UInt32?.some(node.num))
                        }
                    }
                }
            }
            Toggle("Keep screen on", isOn: $drive.keepScreenOn)
            if drive.active {
                VStack(alignment: .leading, spacing: 4) {
                    if let note = drive.pingNote {
                        Text(note).foregroundStyle(.secondary)
                    }
                    if drive.pinging && drive.pingNote == nil || drive.sent > 0 {
                        Text(drive.waiting ? "Waiting for an answer…" : "\(drive.sent) pings sent")
                    }
                    ForEach(MeshFirmware.allCases, id: \.self) { net in
                        if let t = drive.tallies[net], t.sent > 0 {
                            HStack(spacing: 12) {
                                if drive.tallies.count > 1 { MeshNetworkBadge(network: net) }
                                Label("\(t.reached) answered", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                                Label("\(t.relayed) repeated", systemImage: "arrow.triangle.2.circlepath").foregroundStyle(.orange)
                                Label("\(t.unanswered) nothing", systemImage: "xmark.circle").foregroundStyle(.red)
                            }
                            .labelStyle(.titleAndIcon)
                        }
                    }
                }
                .font(.footnote)
            }
        } header: { Text("Drive mode") } footer: {
            Text("Keeps recording while the phone is locked or in another app, until you turn it off. The ping test sends a tiny test message every 300 m (at most every 45 s) and marks its map hexagon green if the radio you chose answered, yellow if another radio only repeated it, or red if nothing heard it (the best result in each hexagon wins). \(driveNetworks.count > 1 ? "With two radios, each pings on its own network, taking turns. " : "")\(driveNetworks.contains(.meshcore) ? "On MeshCore the ping is a trace to the repeater heard most recently (or one you pick), which repeaters answer on their own; a person's radio gets a short direct message instead. " : "")Uses battery and GPS: keep the phone on a charger.")
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

/// The system folder picker, for choosing where backups go.
struct FolderPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let url = urls.first { onPick(url) }
        }
    }
}

extension CoverageSettingsView {
    /// The user's own phones and repeaters, kept off the map.
    var hiddenSection: some View {
        Section {
            if hidden.radios.isEmpty {
                Text("None yet. Tap one of your own radios on the map, or long-press it in Nodes, and choose \u{201C}This is mine\u{201D}.")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(hidden.radios) { radio in
                HStack {
                    MeshNetworkBadge(network: radio.firmware)
                    Text(radio.name.isEmpty ? "Radio \(radio.num)" : radio.name).lineLimit(1)
                    Spacer()
                    Button("Show") { hidden.unhide(radio.id) }
                        .frame(minHeight: MeshUI.minTap)
                }
            }
        } header: { Text("Hidden from the map") } footer: {
            Text("Your own phones and repeaters. They are never mapped, never credited with coverage and never shared. The radios connected to this phone are always left out.")
        }
    }
}
