// ConditionsView.swift
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// The Conditions screen (alerts, forecast, earthquakes, space weather, weather
// radio) and the card that opens it from the top of the Survive tab.

import SwiftUI

struct ConditionsRoute: Hashable {}

struct ConditionsCard: View {
    @State private var store = ConditionsStore.shared
    private let fmt = ConditionsFormat.current

    var body: some View {
        NavigationLink(value: ConditionsRoute()) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label("Conditions", systemImage: "cloud.sun.fill").font(.headline)
                    Spacer()
                    if let t = store.oldestUpdate {
                        Text(ConditionsParsers.age(t, now: Date())).font(.caption).foregroundStyle(.secondary)
                    }
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }
                if !store.hasAnything {
                    Text("Weather alerts, forecast, earthquakes and space weather. Update once, read it with no signal.")
                        .font(.subheadline).foregroundStyle(.secondary)
                } else {
                    if let a = store.activeAlerts.first {
                        Label(a.event + (store.activeAlerts.count > 1 ? " (+\(store.activeAlerts.count - 1))" : ""), systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline.weight(.semibold)).foregroundStyle(a.rank <= 1 ? Theme.danger : MeshUI.warn)
                    } else if store.alerts != nil {
                        Label("No active weather alerts", systemImage: "checkmark.shield").font(.subheadline).foregroundStyle(.secondary)
                    }
                    if let f = store.forecast?.value {
                        let next = ConditionsParsers.upcoming(f, from: Date(), count: 24)
                        if let hi = next.map(\.temperatureC).max(), let lo = next.map(\.temperatureC).min() {
                            let rain = next.reduce(0) { $0 + $1.precipitationMm }
                            Text("Next 24 h: \(fmt.temperature(lo)) to \(fmt.temperature(hi)), rain \(fmt.rain(rain)), gusts \(fmt.speed(next.map(\.gustKmh).max() ?? 0))")
                                .font(.subheadline)
                        }
                    }
                    HStack(spacing: 14) {
                        if let q = store.nearbyQuakes.first(where: { !$0.significant }) {
                            Text("M\(String(format: "%.1f", q.magnitude)) quake \(ConditionsParsers.age(q.time, now: Date()))").font(.footnote).foregroundStyle(.secondary)
                        }
                        if let s = store.space?.value {
                            Text("Kp \(String(format: "%.0f", s.kpNow))").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

struct ConditionsView: View {
    @State private var store = ConditionsStore.shared
    @AppStorage(ConditionsSettings.autoRefreshKey) private var refreshOnOpen = false
    @State private var radius = ConditionsSettings.quakeRadiusKm
    @State private var minMagnitude = ConditionsSettings.quakeMinMagnitude
    private let fmt = ConditionsFormat.current

    var body: some View {
        List {
            updateSection
            alertsSection
            receivedAlertsSection
            forecastSection
            quakesSection
            spaceSection
            radioSection
            Section {
                Text("Sources: USGS (earthquakes), US National Weather Service (alerts), Open-Meteo (forecast, CC BY 4.0), NOAA Space Weather Prediction Center and NOAA Weather Radio. All free, no accounts. Alerts and the forecast are asked for your position rounded to about 7 miles; the rest download whole regions and are filtered on this phone.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Conditions")
        .toolbar {
            Menu {
                Toggle("Update when I open the app", isOn: $refreshOnOpen)
                Picker("Earthquake radius", selection: $radius) {
                    ForEach([100, 250, 500, 1000, 2500], id: \.self) { Text(fmt.radius($0)).tag($0) }
                }
                Picker("Smallest earthquake", selection: $minMagnitude) {
                    ForEach([2.5, 3.0, 4.0, 5.0], id: \.self) { Text("M\(String(format: "%.1f", $0))").tag($0) }
                }
                Button("Delete saved conditions", role: .destructive) { store.deleteAll() }
            } label: { Image(systemName: "ellipsis.circle") }
        }
        .onChange(of: radius) { _, v in ConditionsSettings.quakeRadiusKm = v }
        .onChange(of: minMagnitude) { _, v in ConditionsSettings.quakeMinMagnitude = v }
        .refreshable { await store.refresh(force: true) }
        .task { if LaunchScreen.value != "conditions:demo" { await store.refreshIfStale() } }
    }

    // MARK: Sections

    private var updateSection: some View {
        Section {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(store.updating ? "Updating…" : (store.oldestUpdate.map { "Updated \(ConditionsParsers.age($0, now: Date()))" } ?? "Not updated yet"))
                        .font(.headline)
                    Text("Needs a connection. After that it reads with no signal.").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button { Task { await store.refresh(force: true) } } label: {
                    if store.updating { ProgressView() } else { Text("Update") }
                }
                .buttonStyle(.borderedProminent).tint(Theme.accent).disabled(store.updating)
            }
            if store.needsLocation {
                Label("Allow location to get alerts and a forecast for where you are.", systemImage: "location.slash").font(.footnote)
            }
            ForEach(store.errors.sorted { $0.key.rawValue < $1.key.rawValue }, id: \.key) { k, v in
                Label("\(k.title): \(v)", systemImage: "wifi.exclamationmark").font(.footnote).foregroundStyle(MeshUI.warn)
            }
        }
    }

    /// Alerts picked up straight off the air by an SDR box (they work with no internet).
    @ViewBuilder private var receivedAlertsSection: some View {
        let sdr = SdrState.shared
        if !sdr.alerts.isEmpty {
            Section {
                ForEach(Array(sdr.alerts.prefix(5).enumerated()), id: \.offset) { _, a in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.text).font(.headline)
                        Text("\(a.receivedAt.formatted(date: .abbreviated, time: .shortened)) · \(String(format: "%.3f", a.mhz)) MHz").font(.caption).foregroundStyle(.secondary)
                    }
                }
            } header: { Text(sdr.simulated ? "Weather radio (simulated)" : "Weather radio (received)") } footer: {
                Text("Heard directly by your SDR box. Check the official source when you can.")
            }
        }
    }

    private var alertsSection: some View {
        Section("Weather alerts") {
            if store.alertsUnavailableHere {
                Text("The National Weather Service covers the US and its territories. Alerts aren't available for this location yet.").font(.subheadline).foregroundStyle(.secondary)
            } else if let snap = store.alerts {
                if store.activeAlerts.isEmpty {
                    Label("No active alerts for your area", systemImage: "checkmark.shield").foregroundStyle(MeshUI.ok)
                }
                ForEach(store.activeAlerts) { alertRow($0, expired: false) }
                if !store.expiredAlerts.isEmpty {
                    DisclosureGroup("Ended (\(store.expiredAlerts.count))") {
                        ForEach(store.expiredAlerts) { alertRow($0, expired: true) }
                    }
                }
                Text("As of \(ConditionsParsers.age(snap.fetchedAt, now: Date())). Alerts can change: update when you can.").font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Update to see active watches and warnings.").font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }

    private func alertRow(_ a: WeatherAlert, expired: Bool) -> some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 8) {
                Text(a.areas).font(.caption).foregroundStyle(.secondary)
                Text(a.detail).font(.subheadline)
                if let i = a.instruction { Text(i).font(.subheadline.weight(.semibold)) }
                Text(a.sender).font(.caption).foregroundStyle(.secondary)
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Label(a.event, systemImage: "exclamationmark.triangle.fill")
                    .font(.headline).foregroundStyle(expired ? .secondary : (a.rank <= 1 ? Theme.danger : MeshUI.warn))
                Text(a.expires.map { (expired ? "Ended " : "Until ") + $0.formatted(date: .abbreviated, time: .shortened) } ?? a.headline)
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder private var forecastSection: some View {
        Section("Forecast") {
            if let snap = store.forecast {
                let next = ConditionsParsers.upcoming(snap.value, from: Date(), count: 24)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(Array(next.enumerated()), id: \.offset) { _, h in
                            VStack(spacing: 4) {
                                Text(h.time.formatted(.dateTime.hour())).font(.caption2).foregroundStyle(.secondary)
                                Image(systemName: Self.symbol(h.code)).foregroundStyle(Theme.accent)
                                Text(fmt.temperature(h.temperatureC)).font(.subheadline.weight(.semibold))
                                Text(h.precipitationChance.map { "\($0)%" } ?? "").font(.caption2).foregroundStyle(.blue)
                                Text("\(ConditionsParsers.compass(h.windDirection)) \(Int(fmt.metric ? h.windKmh : h.windKmh * 0.621371))").font(.caption2).foregroundStyle(.secondary)
                            }
                            .frame(minWidth: 48)
                        }
                    }
                }
                ForEach(ConditionsParsers.days(snap.value, calendar: .current), id: \.date) { d in
                    HStack {
                        Text(d.date.formatted(.dateTime.weekday(.wide))).frame(width: 96, alignment: .leading)
                        Image(systemName: Self.symbol(d.code)).foregroundStyle(Theme.accent).frame(width: 28)
                        Text(ConditionsParsers.describe(code: d.code)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        Spacer()
                        VStack(alignment: .trailing, spacing: 1) {
                            Text("\(fmt.temperature(d.highC)) / \(fmt.temperature(d.lowC))").font(.subheadline)
                            Text(d.rainMm >= 0.1 ? "\(fmt.rain(d.rainMm)) · gusts \(fmt.speed(d.maxGustKmh))" : "gusts \(fmt.speed(d.maxGustKmh))")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
                Text("For \(String(format: "%.1f, %.1f", snap.value.latitude, snap.value.longitude)) (rounded), \(ConditionsParsers.age(snap.fetchedAt, now: Date())). Forecast by Open-Meteo.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Update to see wind and rain for the next seven days.").font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }

    private var quakesSection: some View {
        Section("Earthquakes") {
            if let snap = store.quakes {
                let list = store.nearbyQuakes
                if list.isEmpty {
                    Text("None of M\(String(format: "%.1f", minMagnitude)) or more within \(fmt.radius(radius)) in the past week.").font(.subheadline).foregroundStyle(.secondary)
                }
                ForEach(list.prefix(25)) { q in
                    HStack(spacing: 12) {
                        Text(String(format: "%.1f", q.magnitude)).font(.title3.bold().monospacedDigit())
                            .foregroundStyle(q.magnitude >= 5 ? Theme.danger : q.magnitude >= 4 ? MeshUI.warn : .primary).frame(width: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(q.place).font(.subheadline).lineLimit(2)
                            Text(detail(q)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Text("Past 7 days, plus significant quakes anywhere in the past month. \(ConditionsParsers.age(snap.fetchedAt, now: Date())). Shown on the map under Layers.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Update to see recent earthquakes near you.").font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }

    private func detail(_ q: Quake) -> String {
        var parts = [ConditionsParsers.age(q.time, now: Date())]
        if let c = store.center { parts.append(fmt.distance(HamGeo.distanceKm((c.latitude, c.longitude), (q.latitude, q.longitude))) + " away") }
        parts.append("depth \(fmt.distance(q.depthKm))")
        if q.tsunami { parts.append("tsunami message") }
        if q.significant { parts.append("significant") }
        return parts.joined(separator: " · ")
    }

    private var spaceSection: some View {
        Section("Space weather") {
            if let snap = store.space, case let w = snap.value {
                HStack(alignment: .bottom, spacing: 3) {
                    ForEach(Array(w.kp.suffix(24).enumerated()), id: \.offset) { _, p in
                        RoundedRectangle(cornerRadius: 2)
                            .fill(p.kp >= 5 ? Theme.danger : p.kp >= 4 ? MeshUI.warn : Theme.accent)
                            .opacity(p.predicted ? 0.45 : 1)
                            .frame(height: max(4, CGFloat(p.kp) * 7))
                    }
                }
                .frame(height: 68, alignment: .bottom)
                Text("Kp \(String(format: "%.1f", w.kpNow)) now (0 calm, 9 severe). Lighter bars are forecast.").font(.caption).foregroundStyle(.secondary)
                Label(ConditionsParsers.hfNote(w), systemImage: "dot.radiowaves.left.and.right").font(.subheadline)
                Label(ConditionsParsers.gpsNote(w), systemImage: "location.north.line").font(.subheadline)
                if let aurora = ConditionsParsers.auroraNote(w) { Label(aurora, systemImage: "sparkles").font(.subheadline) }
                Text("NOAA scales now: radio R\(w.radio), radiation S\(w.radiation), geomagnetic G\(w.geomagnetic). \(ConditionsParsers.age(snap.fetchedAt, now: Date())).")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Update to see how solar activity affects HF radio and GPS.").font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }

    private var radioSection: some View {
        Section("NOAA Weather Radio") {
            let near = store.nearestRadio(limit: 3)
            if near.isEmpty {
                Text(store.radio == nil ? "Update once to save the list of transmitters." : "Update with location on to find the nearest transmitters.")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(near, id: \.0.callsign) { s, km in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(s.frequency) MHz").font(.headline.monospacedDigit())
                        Text("\(s.name), \(s.state) · \(s.callsign)").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(fmt.distance(km)).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Text("Tune a radio that receives the weather band (most handhelds do) to one of the seven channels, 162.400 to 162.550 MHz. Strong signals reach about 40 miles; use a better antenna or higher ground for more.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    static func symbol(_ code: Int) -> String {
        switch code {
        case 0: "sun.max.fill"
        case 1, 2: "cloud.sun.fill"
        case 3: "cloud.fill"
        case 45, 48: "cloud.fog.fill"
        case 51...57: "cloud.drizzle.fill"
        case 61...67, 80...82: "cloud.rain.fill"
        case 71...77, 85, 86: "cloud.snow.fill"
        case 95...99: "cloud.bolt.rain.fill"
        default: "cloud.fill"
        }
    }
}
