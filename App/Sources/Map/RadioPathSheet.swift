// RadioPathSheet.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0

import CoreLocation
import SwiftUI

/// Terrain profile and Fresnel clearance between you and a place (a radio, a repeater, a pin).
struct RadioPathSheet: View {
    let name: String
    let latitude: Double
    let longitude: Double
    @State private var terrain = TerrainStore.shared
    @State private var compass = NavigationCompass.shared
    @AppStorage("path.band") private var bandID = "lora915"
    @AppStorage("path.heightA") private var heightA = 2.0
    @AppStorage("path.heightB") private var heightB = 10.0
    @State private var showTerrain = false
    @Environment(\.dismiss) private var dismiss
    private let metric = ConditionsFormat.current.metric

    private var band: RadioBand { RadioBand.all.first { $0.id == bandID } ?? RadioBand.all[0] }

    private var result: PathResult? {
        guard terrain.hasTiles, let here = compass.location?.coordinate else { return nil }
        return LineOfSight.analyze(from: .init(latitude: here.latitude, longitude: here.longitude), to: .init(latitude: latitude, longitude: longitude),
                                   heightA: heightA, heightB: heightB, mhz: band.mhz) { terrain.elevation(latitude: $0, longitude: $1) }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("From where I am to") { Label(name, systemImage: "mappin.circle.fill") }
                Section("Radio") {
                    Picker("Band", selection: $bandID) { ForEach(RadioBand.all) { Text($0.name).tag($0.id) } }
                    Stepper(value: $heightA, in: 0...50, step: 1) { Text("My antenna: \(ShapeMath.heightText(heightA, metric: metric))") }
                    Stepper(value: $heightB, in: 0...100, step: 1) { Text("Their antenna: \(ShapeMath.heightText(heightB, metric: metric))") }
                }
                if !terrain.hasTiles {
                    Section {
                        Text("A path check needs elevation data for the area. Download it once and it works with no signal.").font(.footnote)
                        Button { showTerrain = true } label: { Label("Download elevation…", systemImage: "arrow.down.circle") }
                    }
                } else if compass.location == nil {
                    Section { HStack { ProgressView(); Text("Waiting for GPS…").foregroundStyle(.secondary) } }
                } else if let r = result {
                    Section {
                        HStack {
                            Image(systemName: icon(r.verdict)).foregroundStyle(color(r.verdict))
                            Text(r.verdict.label).font(.headline)
                            Spacer()
                            Text(ShapeMath.distanceText(r.distance, metric: metric)).foregroundStyle(.secondary)
                        }
                        if r.points.count > 1 { PathChart(points: r.points, heightA: heightA, heightB: heightB).frame(height: 170) }
                        if r.verdict != .unknown {
                            Text(detail(r)).font(.footnote)
                        }
                        if r.missing > 0 { Text("Elevation is missing for part of this path (outside the saved area), so the result is a guess.").font(.footnote).foregroundStyle(.orange) }
                    }
                }
                Section { Text("Terrain only. Trees, buildings and weather are not in the data, so Clear means possible, not guaranteed. Elevation: Mapzen terrain tiles (AWS Open Data).").font(.caption).foregroundStyle(.secondary) }
            }
            .navigationTitle("Radio path")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $showTerrain) { TerrainSheet(mapCenter: compass.location?.coordinate) }
            .onAppear { NavigationCompass.shared.start() }
        }
    }

    private func detail(_ r: PathResult) -> String {
        let pct = Int((max(0, min(1, r.worstFresnelRatio)) * 100).rounded())
        switch r.verdict {
        case .blocked: return "The terrain rises \(ShapeMath.heightText(-r.worstClearance, metric: metric)) into the path about \(ShapeMath.distanceText(r.worstAt, metric: metric)) from you. Try a taller antenna or a higher spot."
        case .marginal: return "The line is clear, but only \(pct)% of the first Fresnel zone is open (about 60% is wanted). Expect a weaker link."
        default: return "The first Fresnel zone is \(pct)% clear at its tightest point."
        }
    }

    private func icon(_ v: PathVerdict) -> String { switch v { case .clear: "checkmark.circle.fill"; case .marginal: "exclamationmark.triangle.fill"; case .blocked: "xmark.octagon.fill"; case .unknown: "questionmark.circle" } }
    private func color(_ v: PathVerdict) -> Color { switch v { case .clear: .green; case .marginal: .orange; case .blocked: .red; case .unknown: .secondary } }
}

/// Terrain (with Earth curvature), the radio line and the Fresnel zone's lower edge.
private struct PathChart: View {
    let points: [PathPoint]
    let heightA: Double
    let heightB: Double

    var body: some View {
        Canvas { ctx, size in
            guard let first = points.first, let last = points.last, last.distance > 0 else { return }
            let lows = points.map { $0.ground + $0.bulge }
            let lo = (lows.min() ?? 0) - 5
            let hi = max(points.map(\.line).max() ?? 0, lows.max() ?? 0) + 10
            func x(_ d: Double) -> CGFloat { CGFloat(d / last.distance) * size.width }
            func y(_ h: Double) -> CGFloat { size.height - CGFloat((h - lo) / max(1, hi - lo)) * size.height }
            var land = Path()
            land.move(to: CGPoint(x: x(first.distance), y: size.height))
            for p in points { land.addLine(to: CGPoint(x: x(p.distance), y: y(p.ground + p.bulge))) }
            land.addLine(to: CGPoint(x: x(last.distance), y: size.height)); land.closeSubpath()
            ctx.fill(land, with: .color(.brown.opacity(0.55)))
            var fres = Path()
            for (i, p) in points.enumerated() {
                let pt = CGPoint(x: x(p.distance), y: y(p.line - p.fresnel))
                if i == 0 { fres.move(to: pt) } else { fres.addLine(to: pt) }
            }
            ctx.stroke(fres, with: .color(.orange.opacity(0.8)), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            var line = Path()
            line.move(to: CGPoint(x: x(first.distance), y: y(first.line))); line.addLine(to: CGPoint(x: x(last.distance), y: y(last.line)))
            ctx.stroke(line, with: .color(.blue), lineWidth: 2)
            for p in [first, last] { ctx.fill(Path(ellipseIn: CGRect(x: x(p.distance) - 4, y: y(p.line) - 4, width: 8, height: 8)), with: .color(.blue)) }
        }
        .accessibilityLabel("Terrain profile between the two points")
    }
}
