// MeasureViews.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// The measure/draw panel that sits over the map, and the list of saved drawings.

import SwiftUI

struct MeasurePanel: View {
    let controller: MapController
    var onSave: () -> Void
    var onList: () -> Void
    private let metric = ConditionsFormat.current.metric

    private var points: [MapShape.Coordinate] { controller.draft.map { .init(latitude: $0.latitude, longitude: $0.longitude) } }
    private var isArea: Bool { controller.draftClosed && points.count >= 3 }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if points.isEmpty {
                Text("Tap the map to add points. Measure a route or close it to measure an area.").font(.subheadline)
            } else {
                HStack(alignment: .firstTextBaseline) {
                    Text(ShapeMath.distanceText(ShapeMath.length(points, closed: isArea), metric: metric)).font(.title2.bold().monospacedDigit())
                    Text(isArea ? "around" : "total").font(.caption).foregroundStyle(.secondary)
                    if isArea {
                        Text(ShapeMath.areaText(ShapeMath.area(points), metric: metric)).font(.title3.bold().monospacedDigit()).padding(.leading, 8)
                    }
                    Spacer()
                }
                if points.count >= 2, let last = points.last, let prev = points.dropLast().last {
                    Text("Last leg \(ShapeMath.distanceText(CoverageShare.distance(prev.latitude, prev.longitude, last.latitude, last.longitude), metric: metric)), heading \(ShapeMath.bearingText(ShapeMath.bearing(prev, last)))")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 10) {
                Button { if !controller.draft.isEmpty { controller.draft.removeLast() } } label: { Label("Undo", systemImage: "arrow.uturn.backward") }
                    .disabled(controller.draft.isEmpty)
                Button { controller.draft = [] ; controller.draftClosed = false } label: { Label("Clear", systemImage: "xmark") }
                    .disabled(controller.draft.isEmpty)
                Toggle(isOn: Binding(get: { controller.draftClosed }, set: { controller.draftClosed = $0 })) { Text("Area") }
                    .toggleStyle(.button).disabled(controller.draft.count < 3)
                Spacer()
                Button("Save", action: onSave).buttonStyle(.borderedProminent).tint(Theme.accent).disabled(controller.draft.count < 2)
                Button { controller.measuring = false } label: { Image(systemName: "checkmark.circle.fill") }.accessibilityLabel("Done")
            }
            .font(.subheadline)
            .buttonStyle(.bordered)
            Button("My drawings…", action: onList).font(.caption)
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}

struct ShapeFile: Transferable {
    var name: String
    var text: String
    var ext: String
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .data) { f in
            let safe = f.name.replacingOccurrences(of: "/", with: "-")
            let url = FileManager.default.temporaryDirectory.appending(path: "\(safe).\(f.ext)")
            try Data(f.text.utf8).write(to: url)
            return SentTransferredFile(url)
        }
    }
}

struct ShapesListSheet: View {
    let controller: MapController
    @State private var store = ShapeStore.shared
    @State private var mesh = MeshService.shared
    @Environment(\.dismiss) private var dismiss
    @State private var renaming: MapShape?
    @State private var newName = ""
    @State private var sendingTo: MapShape?
    @State private var sent: String?
    private let metric = ConditionsFormat.current.metric

    var body: some View {
        NavigationStack {
            List {
                if store.shapes.isEmpty {
                    Text("Nothing drawn yet. Tap the ruler on the map, tap some points and save.").foregroundStyle(.secondary)
                }
                ForEach(store.shapes) { s in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Circle().fill(Color(rgb: ShapeMath.palette[s.colorIndex % ShapeMath.palette.count].1)).frame(width: 12, height: 12)
                            Text(s.name).font(.headline)
                        }
                        Text(summary(s)).font(.subheadline).foregroundStyle(.secondary)
                    }
                    .swipeActions {
                        Button("Delete", role: .destructive) { store.delete(s.id) }
                        Button("Rename") { renaming = s; newName = s.name }.tint(.blue)
                    }
                    .contextMenu {
                        Button { zoom(to: s) } label: { Label("Show on map", systemImage: "scope") }
                        Button { renaming = s; newName = s.name } label: { Label("Rename", systemImage: "pencil") }
                        Menu("Colour") {
                            ForEach(Array(ShapeMath.palette.enumerated()), id: \.offset) { i, c in Button(c.0) { store.recolor(s.id, to: i) } }
                        }
                        ShareLink(item: ShapeFile(name: s.name, text: ShapeMath.geoJSON([s]), ext: "geojson"), preview: SharePreview("\(s.name).geojson")) {
                            Label("Share as GeoJSON", systemImage: "square.and.arrow.up")
                        }
                        ShareLink(item: ShapeFile(name: s.name, text: ShapeMath.gpx([s]), ext: "gpx"), preview: SharePreview("\(s.name).gpx")) {
                            Label("Share as GPX", systemImage: "square.and.arrow.up")
                        }
                        Button { NavigationState.shared.start(route: s); dismiss() } label: { Label(s.isArea ? "Go to its first corner" : "Go along it", systemImage: "location.north.line.fill") }
                        Button { sendingTo = s } label: { Label("Send summary to the mesh", systemImage: "antenna.radiowaves.left.and.right") }
                    }
                }
                if let sent { Text(sent).font(.footnote).foregroundStyle(.secondary) }
            }
            .navigationTitle("My drawings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                if !store.shapes.isEmpty {
                    ToolbarItem(placement: .topBarLeading) {
                        ShareLink(item: ShapeFile(name: "Pocket Nomad drawings", text: ShapeMath.geoJSON(store.shapes), ext: "geojson"),
                                  preview: SharePreview("Pocket Nomad drawings.geojson")) { Image(systemName: "square.and.arrow.up") }
                    }
                }
            }
            .alert("Rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Name", text: $newName)
                Button("Save") { if let r = renaming { store.rename(r.id, to: newName) }; renaming = nil }
                Button("Cancel", role: .cancel) { renaming = nil }
            }
            .confirmationDialog("Send to the public mesh channel?", isPresented: Binding(get: { sendingTo != nil }, set: { if !$0 { sendingTo = nil } }), titleVisibility: .visible) {
                Button("Send") {
                    if let s = sendingTo {
                        let text = ShapeMath.meshSummary(s, metric: metric, maxBytes: MeshConstants.maxTextBytes)
                        sent = mesh.send(text, to: .channel(0)) ? "Sent: \(text)" : "Couldn't send: connect a radio first."
                    }
                    sendingTo = nil
                }
            } message: {
                Text("Everyone on the default channel can read a name, size and centre point.")
            }
        }
    }

    private func summary(_ s: MapShape) -> String {
        s.isArea
            ? "\(ShapeMath.areaText(ShapeMath.area(s.points), metric: metric)) · \(ShapeMath.distanceText(ShapeMath.length(s.points, closed: true), metric: metric)) around · \(s.points.count) points"
            : "\(ShapeMath.distanceText(ShapeMath.length(s.points, closed: false), metric: metric)) · \(s.points.count) points"
    }

    private func zoom(to s: MapShape) {
        guard let view = controller.mapView, s.points.count >= 1 else { return }
        var coords = s.coordinates
        view.setVisibleCoordinates(&coords, count: UInt(coords.count), edgePadding: UIEdgeInsets(top: 120, left: 60, bottom: 220, right: 60), animated: true)
        dismiss()
    }
}

extension Color {
    init(rgb: UInt32) { self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255) }
}
