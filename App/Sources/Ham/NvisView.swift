// NvisView.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

import CoreLocation
import SwiftUI

/// The 10.7 cm solar flux (a number between about 65 and 250 that says how active the sun is), from NOAA.
@MainActor
@Observable
final class SolarFluxStore {
    static let shared = SolarFluxStore()
    private(set) var flux: Double?
    private(set) var fetched: Date?
    private(set) var busy = false
    private(set) var error: String?

    init() {
        let d = UserDefaults.standard
        if d.object(forKey: "nvis.flux") != nil { flux = d.double(forKey: "nvis.flux"); fetched = d.object(forKey: "nvis.fluxDate") as? Date }
    }

    static func parse(_ data: Data) -> Double? {
        guard let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]], let f = (arr.first?["flux"] as? NSNumber)?.doubleValue, (50...500).contains(f) else { return nil }
        return f
    }

    func refresh() async {
        guard !busy else { return }
        busy = true; error = nil; defer { busy = false }
        do {
            var req = URLRequest(url: URL(string: "https://services.swpc.noaa.gov/products/summary/10cm-flux.json")!)
            req.setValue("PocketNomad/1.0 (+https://pocketnomad.us)", forHTTPHeaderField: "User-Agent"); req.timeoutInterval = 20
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode == 200, let f = Self.parse(data) else { throw URLError(.badServerResponse) }
            flux = f; fetched = Date()
            UserDefaults.standard.set(f, forKey: "nvis.flux"); UserDefaults.standard.set(fetched, forKey: "nvis.fluxDate")
        } catch { self.error = "Couldn't reach NOAA. The last value is kept." }
    }
}

/// A rule-of-thumb planner for short-range HF (NVIS): which band to try now, the wire for it, and a map ring.
struct NvisView: View {
    @State private var flux = SolarFluxStore.shared
    @State private var location = LocationService.shared
    @AppStorage("map.showNvis") private var showRing = false
    @AppStorage("nvis.manualSFI") private var manualSFI = 0.0
    @Environment(\.dismiss) private var dismiss
    private let metric = ConditionsFormat.current.metric

    private var here: CLLocationCoordinate2D? { location.location?.coordinate }
    private var kp: Double { ConditionsStore.shared.space?.value.kpNow ?? 2 }
    private var sfi: Double { manualSFI > 0 ? manualSFI : (flux.flux ?? 100) }
    private var elevation: Double { Nvis.sunElevation(latitude: here?.latitude ?? 30, longitude: here?.longitude ?? -97, at: Date()) }
    private var advice: Nvis.Advice { Nvis.advise(sfi: sfi, kp: kp, sunElevation: elevation) }

    var body: some View {
        List {
            Section {
                Text("NVIS (near vertical incidence skywave) is HF radio aimed almost straight up, so the signal comes back down around you, roughly 0 to 300 miles. It keeps a region in touch when towers and internet are down, even with 5 watts and a wire.")
                    .font(.footnote)
            }
            Section("Right now") {
                LabeledContent("Sun", value: elevation > 6 ? "Daytime" : (elevation < -6 ? "Night" : "Dawn or dusk"))
                LabeledContent("Solar flux", value: flux.flux == nil && manualSFI == 0 ? "100 (assumed)" : String(format: "%.0f", sfi))
                if let d = flux.fetched, manualSFI == 0 { Text("Updated \(PotaParsers.ageText(d, now: Date()))").font(.footnote).foregroundStyle(.secondary) }
                LabeledContent("Geomagnetic Kp", value: ConditionsStore.shared.space == nil ? "2 (assumed)" : String(format: "%.1f", kp))
                Button { Task { await flux.refresh() } } label: { Label(flux.busy ? "Updating…" : "Update solar flux", systemImage: "arrow.clockwise") }.disabled(flux.busy)
                if let e = flux.error { Text(e).font(.footnote).foregroundStyle(.orange) }
            }
            Section("Try this band") {
                Text(advice.summary).font(.headline)
                if advice.stormed { Label("A geomagnetic storm lowers the usable frequencies. Expect weaker or no NVIS.", systemImage: "exclamationmark.triangle.fill").font(.footnote).foregroundStyle(.orange) }
                ForEach([advice.best, advice.alternate].compactMap { $0 }) { b in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(b.name).font(.title3.bold())
                        Text("JS8Call \(String(format: "%.3f", b.js8mhz)) MHz · FT8 \(String(format: "%.3f", b.ft8mhz)) MHz (USB)").font(.footnote.monospaced())
                    }
                }
                Text("Estimated critical frequency \(String(format: "%.1f", advice.foF2)) MHz. Best band: highest one below \(String(format: "%.1f", advice.fot)) MHz and above \(String(format: "%.1f", advice.luf)) MHz.").font(.caption).foregroundStyle(.secondary)
                Stepper(manualSFI > 0 ? "Use flux \(Int(manualSFI)) (typed)" : "Type a flux instead", value: $manualSFI, in: 0...300, step: 5)
            }
            Section {
                Toggle("Show the 300-mile ring on the map", isOn: $showRing)
            } footer: { Text("Draws a ring \(metric ? "483 km" : "300 miles") around your position on the Map. NVIS should cover everything inside it, given a good band and a low antenna.") }
            Section("Wire for each band") {
                ForEach(Nvis.bands) { b in
                    let a = Nvis.antenna(for: b)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(b.name).font(.headline)
                        Text(metric ? String(format: "Dipole %.1f m total, hung %.0f to %.0f m up", a.dipoleMetres, a.heightMinMetres, a.heightMaxMetres)
                                    : String(format: "Dipole %.0f ft total, hung %.0f to %.0f ft up", a.dipoleFeet, a.heightMinMetres * 3.28084, a.heightMaxMetres * 3.28084))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Text("A half-wave dipole or an end-fed half-wave wire (the same length). Lay it horizontal and low: a tenth to a quarter of a wavelength above ground sends the signal up instead of out. Cut a little long and trim for the best match.").font(.footnote)
            }
            Section { Text("This is a rule of thumb, not a propagation forecast: it guesses from the time of day, the solar flux and the Kp index. Real conditions vary. If a band is quiet, try the neighbour, and always listen first.").font(.caption).foregroundStyle(.secondary) }
        }
        .navigationTitle("NVIS planner")
        .onAppear { location.begin() }
    }
}
