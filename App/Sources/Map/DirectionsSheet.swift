// DirectionsSheet.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0

import CoreLocation
import SwiftUI

/// Plan a route to a place on the roads saved on this phone.
struct DirectionsSheet: View {
    let name: String
    let latitude: Double
    let longitude: Double
    @State private var roads = RoadStore.shared
    @State private var compass = NavigationCompass.shared
    @AppStorage("map.travelMode") private var modeRaw = TravelMode.foot.rawValue
    @AppStorage("map.avoidHighways") private var avoidHighways = false
    @AppStorage("map.avoidUnpaved") private var avoidUnpaved = false
    @State private var route: Route?
    @State private var note = ""
    @State private var planning = false
    @State private var radiusKm = 10.0
    @Environment(\.dismiss) private var dismiss
    private let metric = ConditionsFormat.current.metric
    private var mode: TravelMode { TravelMode(rawValue: modeRaw) ?? .foot }

    var body: some View {
        NavigationStack {
            Form {
                Section("To") { Label(name, systemImage: "mappin.circle.fill") }
                Section {
                    Picker("Travel", selection: $modeRaw) {
                        ForEach(TravelMode.allCases) { Label($0.label, systemImage: $0.symbol).tag($0.rawValue) }
                    }.pickerStyle(.segmented)
                    .onChange(of: modeRaw) { _, _ in route = nil; note = "" }
                }
                if mode != .foot {
                    Section {
                        if mode == .car { Toggle("Avoid highways", isOn: $avoidHighways) }
                        Toggle("Avoid dirt and gravel", isOn: $avoidUnpaved)
                    }
                    .onChange(of: avoidHighways) { _, _ in route = nil }
                    .onChange(of: avoidUnpaved) { _, _ in route = nil }
                }
                if !roads.hasRoads { downloadSection }
                else {
                    Section {
                        Button { Task { await plan() } } label: { Label(planning ? "Planning…" : "Plan route from where I am", systemImage: "arrow.triangle.turn.up.right.diamond") }
                            .disabled(planning || compass.location == nil)
                        if compass.location == nil { Text("Waiting for GPS…").font(.footnote).foregroundStyle(.secondary) }
                        if !note.isEmpty { Text(note).font(.footnote).foregroundStyle(.secondary) }
                    }
                }
                if let r = route {
                    Section("Route") {
                        HStack {
                            Text(ShapeMath.distanceText(r.meters, metric: metric)).font(.title3.bold())
                            Spacer()
                            Text(duration(r.seconds)).foregroundStyle(.secondary)
                        }
                        Button { NavigationState.shared.start(name: name, route: r, destination: (latitude, longitude), mode: mode, prefs: RoutePrefs(avoidHighways: avoidHighways && mode == .car, avoidUnpaved: avoidUnpaved)); dismiss() } label: { Label("Start", systemImage: "location.north.line.fill") }
                    }
                    Section("Steps") {
                        ForEach(Array(r.steps.enumerated()), id: \.offset) { _, s in
                            HStack { Text(s.text); Spacer(); if s.meters > 0 { Text(ShapeMath.distanceText(s.meters, metric: metric)).foregroundStyle(.secondary) } }
                        }
                    }
                }
                if roads.hasRoads {
                    Section("Saved roads") {
                        Text("\(roads.cellCount) squares (each about 5 km) on this phone.").font(.footnote)
                        Button("Delete saved roads", role: .destructive) { roads.deleteAll(); route = nil }
                    }
                }
                Section { Text("Roads and paths: OpenStreetMap contributors (ODbL), downloaded through the Overpass service. Routes are estimates: check conditions, closures and private land yourself.").font(.caption).foregroundStyle(.secondary) }
            }
            .navigationTitle("Directions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { NavigationCompass.shared.start() }
        }
    }

    private var downloadSection: some View {
        Section {
            Text("Directions work with no signal once the roads around you are saved. Download them while you have signal.").font(.footnote)
            if let c = compass.location?.coordinate {
                Picker("Radius", selection: $radiusKm) { Text("10 km").tag(10.0); Text("25 km").tag(25.0) }.pickerStyle(.segmented)
                Text("Up to about \(Int(roads.estimateMB(centre: c, radiusKm: radiusKm))) MB in a city, much less in the country. The free public service can be slow, so a big area may take several minutes.").font(.footnote).foregroundStyle(.secondary)
                Button { Task { await roads.download(centre: c, radiusKm: radiusKm) } } label: { Label(roads.downloading ? "Downloading…" : "Download roads around me", systemImage: "arrow.down.circle") }
                    .disabled(roads.downloading)
                if roads.downloading { ProgressView(value: roads.progress) }
                if !roads.message.isEmpty { Text(roads.message).font(.footnote).foregroundStyle(.secondary) }
            } else { Text("Waiting for GPS…").font(.footnote).foregroundStyle(.secondary) }
        }
    }

    private func plan() async {
        guard let here = compass.location?.coordinate else { return }
        planning = true; note = ""
        route = await roads.plan(mode: mode, prefs: RoutePrefs(avoidHighways: avoidHighways && mode == .car, avoidUnpaved: avoidUnpaved), from: .init(latitude: here.latitude, longitude: here.longitude), to: .init(latitude: latitude, longitude: longitude))
        if route == nil { note = "No route found. Either you or the place is outside the saved roads, or they aren't connected for \(mode.label.lowercased())ing." }
        planning = false
    }

    private func duration(_ s: Double) -> String {
        let m = Int((s / 60).rounded())
        return m < 60 ? "\(max(1, m)) min" : "\(m / 60) h \(m % 60) min"
    }
}
