// ConditionsStore.swift
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// Downloads earthquakes, weather alerts, a forecast, space weather and the
// Weather Radio transmitter list when the user taps Update, keeps the last
// good copy of each on the phone, and never throws one away because a later
// download failed. Every source is free and needs no key.
//
// Privacy: earthquakes, space weather and the transmitter list are whole-region
// downloads filtered on the phone (no location sent). Alerts and the forecast
// need a point, which is rounded to 0.1 degree (about 7 miles) first.

import CoreLocation
import Foundation
import Observation

@MainActor
@Observable
final class ConditionsStore {
    static let shared = ConditionsStore()

    enum Source: String, CaseIterable, Sendable {
        case quakes, alerts, forecast, space, radio
        var title: String {
            switch self {
            case .quakes: "Earthquakes"
            case .alerts: "Weather alerts"
            case .forecast: "Forecast"
            case .space: "Space weather"
            case .radio: "Weather radio"
            }
        }
    }

    private(set) var quakes: Snapshot<[Quake]>?
    private(set) var alerts: Snapshot<[WeatherAlert]>?
    private(set) var forecast: Snapshot<Forecast>?
    private(set) var space: Snapshot<SpaceWeather>?
    private(set) var radio: Snapshot<[WeatherRadioStation]>?
    /// The rounded position the alerts and forecast were asked for, kept for reading offline.
    private(set) var center: CLLocationCoordinate2D?
    private(set) var updating = false
    private(set) var errors: [Source: String] = [:]
    /// The National Weather Service only covers the US and its territories.
    private(set) var alertsUnavailableHere = false
    private(set) var needsLocation = false

    var now: () -> Date = { Date() }

    private let directory: URL
    private let session: URLSession
    private let locate: () async -> CLLocation?
    /// Don't ask a free service again within this long (unless forced).
    private let minInterval: TimeInterval = 60
    /// Transmitters change rarely.
    private let radioMaxAge: TimeInterval = 30 * 86_400
    nonisolated static let userAgent = "PocketNomad/1.0 (https://www.pocketnomad.us)"

    init(directory: URL = ConditionsStore.defaultDirectory, session: URLSession = .shared,
         locate: (() async -> CLLocation?)? = nil) {
        self.directory = directory
        self.session = session
        let provider = MeshLocationProvider()
        self.locate = locate ?? { await provider.currentLocation(timeout: 12) }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var d = directory
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? d.setResourceValues(rv)
        quakes = load("quakes.json")
        alerts = load("alerts.json")
        forecast = load("forecast.json")
        space = load("space.json")
        radio = load("radio.json")
        if let c: [Double] = load("center.json"), c.count == 2 { center = CLLocationCoordinate2D(latitude: c[0], longitude: c[1]) }
    }

    static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appending(path: "Conditions")
    }

    // MARK: What the screens show

    /// Earthquakes inside the user's radius and magnitude, newest first.
    var nearbyQuakes: [Quake] {
        guard let q = quakes?.value else { return [] }
        let c = center ?? CLLocationCoordinate2D(latitude: 39.74, longitude: -104.99)
        return ConditionsParsers.nearby(q, latitude: c.latitude, longitude: c.longitude,
                                        radiusKm: Double(ConditionsSettings.quakeRadiusKm), minMagnitude: ConditionsSettings.quakeMinMagnitude)
    }

    /// Alerts that haven't ended.
    var activeAlerts: [WeatherAlert] { alerts?.value.filter { !$0.isExpired(at: now()) } ?? [] }
    var expiredAlerts: [WeatherAlert] { alerts?.value.filter { $0.isExpired(at: now()) } ?? [] }

    func nearestRadio(limit: Int) -> [(WeatherRadioStation, Double)] {
        guard let stations = radio?.value, let c = center else { return [] }
        return ConditionsParsers.nearestRadio(stations, latitude: c.latitude, longitude: c.longitude, limit: limit)
    }

    var hasAnything: Bool { quakes != nil || alerts != nil || forecast != nil || space != nil }

    /// The oldest of the snapshots, for a single "updated" line.
    var oldestUpdate: Date? {
        [quakes?.fetchedAt, alerts?.fetchedAt, forecast?.fetchedAt, space?.fetchedAt].compactMap { $0 }.min()
    }

    // MARK: Update

    /// Downloads everything that is due. `force` ignores the minimum interval.
    func refresh(force: Bool = false) async {
        guard !updating else { return }
        updating = true
        errors = [:]
        defer { updating = false }
        let t = now()

        if let fix = await locate() {
            center = CLLocationCoordinate2D(latitude: ConditionsParsers.rounded(fix.coordinate.latitude),
                                            longitude: ConditionsParsers.rounded(fix.coordinate.longitude))
            save([center!.latitude, center!.longitude], "center.json")
        }
        needsLocation = center == nil

        func due(_ fetched: Date?, _ maxAge: TimeInterval) -> Bool {
            force || fetched.map { t.timeIntervalSince($0) >= maxAge } ?? true
        }
        let session = session
        let point = center
        let wantQuakes = due(quakes?.fetchedAt, minInterval)
        let wantSpace = due(space?.fetchedAt, minInterval)
        let wantAlerts = point != nil && due(alerts?.fetchedAt, minInterval)
        let wantForecast = point != nil && due(forecast?.fetchedAt, minInterval)
        let wantRadio = due(radio?.fetchedAt, force ? minInterval : radioMaxAge)

        async let q = wantQuakes ? Self.fetchQuakes(session) : nil
        async let s = wantSpace ? Self.fetchSpace(session) : nil
        async let a = wantAlerts ? Self.fetchAlerts(session, point!) : nil
        async let f = wantForecast ? Self.fetchForecast(session, point!) : nil
        async let r = wantRadio ? Self.fetchRadio(session) : nil

        if let result = await q { apply(result, .quakes) { quakes = Snapshot(fetchedAt: t, value: $0); save(quakes, "quakes.json") } }
        if let result = await s { apply(result, .space) { space = Snapshot(fetchedAt: t, value: $0); save(space, "space.json") } }
        if let result = await a {
            switch result {
            case .success(let list):
                alertsUnavailableHere = false
                alerts = Snapshot(fetchedAt: t, value: list)
                save(alerts, "alerts.json")
            case .failure(let e):
                if let h = e as? HTTPFailure, h.status == 404 || h.status == 400 { alertsUnavailableHere = true } else { errors[.alerts] = Self.words(e) }
            }
        }
        if let result = await f { apply(result, .forecast) { forecast = Snapshot(fetchedAt: t, value: $0); save(forecast, "forecast.json") } }
        if let result = await r { apply(result, .radio) { radio = Snapshot(fetchedAt: t, value: $0); save(radio, "radio.json") } }
    }

    /// For "update when I open the app": only when the newest data is over half an hour old.
    func refreshIfStale() async {
        guard ConditionsSettings.refreshOnOpen else { return }
        if let t = oldestUpdate, now().timeIntervalSince(t) < 1800 { return }
        await refresh()
    }

    private func apply<T>(_ result: Result<T, Error>, _ source: Source, _ store: (T) -> Void) {
        switch result {
        case .success(let v): store(v)
        case .failure(let e): errors[source] = Self.words(e)
        }
    }

    func deleteAll() {
        quakes = nil; alerts = nil; forecast = nil; space = nil; radio = nil; center = nil
        errors = [:]
        for f in (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] {
            try? FileManager.default.removeItem(at: f)
        }
    }

    // MARK: Network (off the main actor)

    struct HTTPFailure: Error { var status: Int }

    nonisolated private static func words(_ e: Error) -> String {
        if let h = e as? HTTPFailure { return "The service answered \(h.status). Try again later." }
        if let u = e as? URLError, u.code == .notConnectedToInternet || u.code == .networkConnectionLost || u.code == .timedOut {
            return "No connection. Showing the last update."
        }
        if e is ConditionsParsers.Failure { return "The service sent something unexpected. Showing the last update." }
        return "Couldn't update. Showing the last update."
    }

    nonisolated private static func get(_ url: String, _ session: URLSession, accept: String = "application/json") async throws -> Data {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u, timeoutInterval: 45)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(accept, forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw HTTPFailure(status: status) }
        return data
    }

    nonisolated private static func fetchQuakes(_ s: URLSession) async -> Result<[Quake], Error> {
        do {
            async let week = get("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson", s)
            async let sig = get("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_month.geojson", s)
            let merged = ConditionsParsers.merge(try ConditionsParsers.parseQuakes(try await week),
                                                 try ConditionsParsers.parseQuakes(try await sig, significant: true))
            return .success(merged.sorted { $0.time > $1.time })
        } catch { return .failure(error) }
    }

    nonisolated private static func fetchSpace(_ s: URLSession) async -> Result<SpaceWeather, Error> {
        do {
            async let kp = get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json", s)
            async let scales = get("https://services.swpc.noaa.gov/products/noaa-scales.json", s)
            async let text = get("https://services.swpc.noaa.gov/text/3-day-forecast.txt", s, accept: "text/plain")
            return .success(try ConditionsParsers.parseSpaceWeather(kp: try await kp, scales: try await scales,
                                                                    threeDay: String(decoding: try await text, as: UTF8.self)))
        } catch { return .failure(error) }
    }

    nonisolated private static func fetchAlerts(_ s: URLSession, _ p: CLLocationCoordinate2D) async -> Result<[WeatherAlert], Error> {
        do {
            let data = try await get("https://api.weather.gov/alerts/active?point=\(p.latitude),\(p.longitude)", s, accept: "application/geo+json")
            return .success(try ConditionsParsers.parseAlerts(data))
        } catch { return .failure(error) }
    }

    nonisolated private static func fetchForecast(_ s: URLSession, _ p: CLLocationCoordinate2D) async -> Result<Forecast, Error> {
        do {
            let vars = "temperature_2m,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m,weather_code"
            let data = try await get("https://api.open-meteo.com/v1/forecast?latitude=\(p.latitude)&longitude=\(p.longitude)&hourly=\(vars)&forecast_days=7&timezone=auto", s)
            return .success(try ConditionsParsers.parseForecast(data))
        } catch { return .failure(error) }
    }

    nonisolated private static func fetchRadio(_ s: URLSession) async -> Result<[WeatherRadioStation], Error> {
        do {
            let data = try await get("https://www.weather.gov/source/nwr/JS/CCL.js", s, accept: "*/*")
            let stations = ConditionsParsers.parseWeatherRadio(String(decoding: data, as: UTF8.self))
            guard !stations.isEmpty else { throw ConditionsParsers.Failure(message: "No transmitters") }
            return .success(stations)
        } catch { return .failure(error) }
    }

    // MARK: Disk

    private func load<T: Decodable>(_ name: String) -> T? {
        guard let d = try? Data(contentsOf: directory.appending(path: name)) else { return nil }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .secondsSince1970
        return try? dec.decode(T.self, from: d)
    }

    private func save<T: Encodable>(_ v: T?, _ name: String) {
        guard let v else { return }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .secondsSince1970
        guard let d = try? enc.encode(v) else { return }
        try? d.write(to: directory.appending(path: name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    // MARK: Demo (screenshots and the simulator)

    func loadDemo() {
        let t = now()
        center = CLLocationCoordinate2D(latitude: 39.7, longitude: -105.0)
        quakes = Snapshot(fetchedAt: t.addingTimeInterval(-1500), value: [
            Quake(id: "d1", magnitude: 3.1, place: "5 km N of Golden, Colorado", time: t.addingTimeInterval(-5400), latitude: 39.78, longitude: -105.22, depthKm: 8.2, url: nil, tsunami: false, felt: 12),
            Quake(id: "d2", magnitude: 2.7, place: "22 km SW of Pueblo, Colorado", time: t.addingTimeInterval(-86_400), latitude: 38.1, longitude: -104.8, depthKm: 5, url: nil, tsunami: false, felt: nil),
            Quake(id: "d3", magnitude: 6.6, place: "148 km WSW of Tual, Indonesia", time: t.addingTimeInterval(-9 * 86_400), latitude: -6.1, longitude: 130.5, depthKm: 10, url: nil, tsunami: false, felt: 10, significant: true),
        ])
        alerts = Snapshot(fetchedAt: t.addingTimeInterval(-1500), value: [
            WeatherAlert(id: "a1", event: "Winter Weather Advisory", severity: "Moderate", urgency: "Expected", headline: "Winter Weather Advisory until 5 PM MST",
                         detail: "Snow expected. Total accumulations of 3 to 6 inches. Roads may be slick, especially during the evening commute.",
                         instruction: "Slow down and use caution while driving. Carry extra food, water and a blanket.", sender: "NWS Denver/Boulder CO",
                         areas: "Jefferson; Denver; Arapahoe", effective: t.addingTimeInterval(-7200), expires: t.addingTimeInterval(14_400)),
        ])
        var hours: [ForecastHour] = []
        for i in 0..<48 {
            let wet = i < 8
            let temp: Double = 2 + 4 * sin(Double(i) / 4)
            hours.append(ForecastHour(time: t.addingTimeInterval(Double(i) * 3600), temperatureC: temp, precipitationMm: wet ? 0.4 : 0,
                                      precipitationChance: wet ? 70 : 10, windKmh: 14 + Double(i % 5) * 3, gustKmh: 28,
                                      windDirection: 290, code: wet ? 73 : 2))
        }
        forecast = Snapshot(fetchedAt: t.addingTimeInterval(-1500), value: Forecast(latitude: 39.7, longitude: -105.0, hours: hours))
        let kpValues: [Double] = [1, 2, 3, 3, 2, 4, 5, 3, 2, 2, 1, 2]
        var kpPoints: [KpPoint] = []
        for (i, v) in kpValues.enumerated() {
            kpPoints.append(KpPoint(time: t.addingTimeInterval(Double(i - 4) * 10_800), kp: v, predicted: i >= 4))
        }
        let weather = SpaceWeather(kpNow: 3.0, kpTime: t, kp: kpPoints, radio: 0, radiation: 0, geomagnetic: 1, geomagneticOutlook: 1)
        space = Snapshot(fetchedAt: t.addingTimeInterval(-1500), value: weather)
        radio = Snapshot(fetchedAt: t.addingTimeInterval(-86_400), value: [
            WeatherRadioStation(callsign: "KEC76", name: "Denver", state: "CO", frequency: "162.550", latitude: 39.74, longitude: -104.99, powerWatts: 1000, status: "NORMAL", counties: ["008031"]),
            WeatherRadioStation(callsign: "WNG550", name: "Franktown", state: "CO", frequency: "162.450", latitude: 39.4, longitude: -104.7, powerWatts: 300, status: "NORMAL", counties: ["008035"]),
        ])
    }
}
