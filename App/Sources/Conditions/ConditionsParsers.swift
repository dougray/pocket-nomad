// ConditionsParsers.swift
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// Pure parsing and small calculations for the conditions snapshots, so they
// are tested against saved real responses. No network here.

import Foundation

enum ConditionsParsers {
    struct Failure: Error { var message: String }

    // MARK: Privacy

    /// Services that need a point get it rounded to 0.1 degree (about 11 km / 7 miles),
    /// never the exact fix.
    static func rounded(_ degrees: Double) -> Double { (degrees * 10).rounded() / 10 }

    // MARK: USGS earthquakes

    /// A USGS GeoJSON summary feed.
    static func parseQuakes(_ data: Data, significant: Bool = false) throws -> [Quake] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]] else { throw Failure(message: "Not an earthquake feed.") }
        return features.compactMap { f in
            guard let p = f["properties"] as? [String: Any],
                  let g = (f["geometry"] as? [String: Any])?["coordinates"] as? [Double], g.count >= 2,
                  let mag = (p["mag"] as? NSNumber)?.doubleValue, let ms = (p["time"] as? NSNumber)?.doubleValue else { return nil }
            return Quake(id: f["id"] as? String ?? "\(ms)", magnitude: mag, place: p["place"] as? String ?? "Unknown place",
                         time: Date(timeIntervalSince1970: ms / 1000), latitude: g[1], longitude: g[0],
                         depthKm: g.count > 2 ? g[2] : 0, url: p["url"] as? String,
                         tsunami: (p["tsunami"] as? NSNumber)?.intValue == 1, felt: (p["felt"] as? NSNumber)?.intValue,
                         significant: significant)
        }
    }

    /// Radius and magnitude filter, plus everything significant anywhere. Newest first.
    static func nearby(_ quakes: [Quake], latitude: Double, longitude: Double, radiusKm: Double, minMagnitude: Double) -> [Quake] {
        quakes.filter {
            $0.significant || ($0.magnitude >= minMagnitude
                && HamGeo.distanceKm((latitude, longitude), ($0.latitude, $0.longitude)) <= radiusKm)
        }.sorted { $0.time > $1.time }
    }

    /// Merges the two feeds (a quake can be in both), keeping the significant flag.
    static func merge(_ week: [Quake], _ significant: [Quake]) -> [Quake] {
        var byID = Dictionary(uniqueKeysWithValues: week.map { ($0.id, $0) })
        for q in significant { byID[q.id] = q }
        return Array(byID.values)
    }

    // MARK: National Weather Service alerts

    static func parseAlerts(_ data: Data) throws -> [WeatherAlert] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]] else { throw Failure(message: "Not an alerts feed.") }
        let iso = ISO8601DateFormatter()
        func date(_ s: Any?) -> Date? { (s as? String).flatMap { iso.date(from: $0) } }
        return features.compactMap { f in
            guard let p = f["properties"] as? [String: Any], let event = p["event"] as? String else { return nil }
            let instruction = (p["instruction"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return WeatherAlert(
                id: p["id"] as? String ?? f["id"] as? String ?? UUID().uuidString, event: event,
                severity: p["severity"] as? String ?? "Unknown", urgency: p["urgency"] as? String ?? "Unknown",
                headline: p["headline"] as? String ?? event,
                detail: (p["description"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                instruction: instruction?.isEmpty == false ? instruction : nil,
                sender: p["senderName"] as? String ?? "National Weather Service",
                areas: p["areaDesc"] as? String ?? "",
                effective: date(p["onset"]) ?? date(p["effective"]), expires: date(p["ends"]) ?? date(p["expires"]))
        }.sorted { ($0.rank, $0.expires ?? .distantFuture) < ($1.rank, $1.expires ?? .distantFuture) }
    }

    // MARK: Open-Meteo forecast

    static func parseForecast(_ data: Data) throws -> Forecast {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let hourly = root["hourly"] as? [String: Any], let times = hourly["time"] as? [String],
              let lat = (root["latitude"] as? NSNumber)?.doubleValue, let lon = (root["longitude"] as? NSNumber)?.doubleValue
        else { throw Failure(message: "Not a forecast.") }
        // Times are local to the place; utc_offset_seconds converts them.
        let offset = (root["utc_offset_seconds"] as? NSNumber)?.intValue ?? 0
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(secondsFromGMT: offset)
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        func series(_ k: String) -> [Any] { hourly[k] as? [Any] ?? [] }
        func num(_ k: String, _ i: Int) -> Double? {
            let s = series(k)
            return i < s.count ? (s[i] as? NSNumber)?.doubleValue : nil
        }
        var hours: [ForecastHour] = []
        for (i, t) in times.enumerated() {
            guard let date = f.date(from: t), let temp = num("temperature_2m", i) else { continue }
            hours.append(ForecastHour(time: date, temperatureC: temp, precipitationMm: num("precipitation", i) ?? 0,
                                      precipitationChance: num("precipitation_probability", i).map { Int($0) },
                                      windKmh: num("wind_speed_10m", i) ?? 0, gustKmh: num("wind_gusts_10m", i) ?? 0,
                                      windDirection: Int(num("wind_direction_10m", i) ?? 0), code: Int(num("weather_code", i) ?? 0)))
        }
        guard !hours.isEmpty else { throw Failure(message: "The forecast was empty.") }
        return Forecast(latitude: lat, longitude: lon, hours: hours)
    }

    /// The hours from `now` on (the feed starts at midnight today).
    static func upcoming(_ f: Forecast, from now: Date, count: Int) -> [ForecastHour] {
        Array(f.hours.filter { $0.time >= now.addingTimeInterval(-1800) }.prefix(count))
    }

    struct ForecastDay: Equatable, Sendable {
        var date: Date
        var highC: Double
        var lowC: Double
        var rainMm: Double
        var maxGustKmh: Double
        var maxChance: Int
        var code: Int
    }

    /// One row per calendar day in `calendar`'s time zone. The weather code is the most
    /// significant one during daylight hours (higher codes are rain, snow, storms).
    static func days(_ f: Forecast, calendar: Calendar) -> [ForecastDay] {
        var out: [ForecastDay] = []
        for (day, hours) in Dictionary(grouping: f.hours, by: { calendar.startOfDay(for: $0.time) }).sorted(by: { $0.key < $1.key }) {
            let daylight = hours.filter { (7...19).contains(calendar.component(.hour, from: $0.time)) }
            out.append(ForecastDay(date: day, highC: hours.map(\.temperatureC).max() ?? 0, lowC: hours.map(\.temperatureC).min() ?? 0,
                                   rainMm: hours.reduce(0) { $0 + $1.precipitationMm }, maxGustKmh: hours.map(\.gustKmh).max() ?? 0,
                                   maxChance: hours.compactMap(\.precipitationChance).max() ?? 0,
                                   code: (daylight.isEmpty ? hours : daylight).map(\.code).max() ?? 0))
        }
        return out
    }

    /// WMO weather codes, in words.
    static func describe(code: Int) -> String {
        switch code {
        case 0: "Clear"
        case 1: "Mostly clear"
        case 2: "Partly cloudy"
        case 3: "Overcast"
        case 45, 48: "Fog"
        case 51, 53, 55: "Drizzle"
        case 56, 57: "Freezing drizzle"
        case 61: "Light rain"
        case 63: "Rain"
        case 65: "Heavy rain"
        case 66, 67: "Freezing rain"
        case 71: "Light snow"
        case 73: "Snow"
        case 75: "Heavy snow"
        case 77: "Snow grains"
        case 80, 81: "Rain showers"
        case 82: "Violent rain showers"
        case 85, 86: "Snow showers"
        case 95: "Thunderstorm"
        case 96, 99: "Thunderstorm with hail"
        default: "Unknown"
        }
    }

    static func compass(_ degrees: Int) -> String {
        ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int(((Double(degrees).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) + 22.5) / 45) % 8]
    }

    // MARK: NOAA space weather

    /// The planetary K index list and NOAA's current scales, with the 3-day text forecast for the outlook.
    static func parseSpaceWeather(kp: Data, scales: Data, threeDay: String) throws -> SpaceWeather {
        guard let rows = try JSONSerialization.jsonObject(with: kp) as? [[String: Any]], !rows.isEmpty else {
            throw Failure(message: "Not a Kp list.")
        }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        var points: [KpPoint] = rows.compactMap { r in
            guard let t = (r["time_tag"] as? String).flatMap({ f.date(from: $0) }) else { return nil }
            let kp = (r["Kp"] as? NSNumber)?.doubleValue ?? Double(r["Kp"] as? String ?? "")
            guard let kp else { return nil }
            return KpPoint(time: t, kp: kp, predicted: false)
        }
        guard let last = points.last else { throw Failure(message: "The Kp list was empty.") }
        points += parseKpForecast(threeDay).filter { $0.time > last.time }
        func scale(_ obj: [String: Any]?, _ key: String) -> Int {
            Int(((obj?[key] as? [String: Any])?["Scale"] as? String) ?? "") ?? 0
        }
        let s = (try JSONSerialization.jsonObject(with: scales) as? [String: Any]) ?? [:]
        let now = s["0"] as? [String: Any]
        var outlook = scale(now, "G")
        for day in ["1", "2", "3"] { outlook = max(outlook, scale(s[day] as? [String: Any], "G")) }
        return SpaceWeather(kpNow: last.kp, kpTime: last.time, kp: points.filter { !$0.predicted || $0.time > last.time },
                            radio: scale(now, "R"), radiation: scale(now, "S"), geomagnetic: scale(now, "G"), geomagneticOutlook: outlook)
    }

    /// The text forecast's Kp table: "00-03UT  0.67  4.67 (G1)  2.33" for three consecutive days.
    static func parseKpForecast(_ text: String) -> [KpPoint] {
        let lines = text.components(separatedBy: "\n")
        guard let head = lines.first(where: { $0.contains("NOAA Kp index breakdown") }),
              let range = head.range(of: #"([A-Z][a-z]{2}) (\d{1,2})-([A-Z][a-z]{2}) (\d{1,2}) (\d{4})"#, options: .regularExpression) else { return [] }
        let parts = head[range].split(whereSeparator: { " -".contains($0) }).map(String.init)
        guard parts.count == 5, let startDay = Int(parts[1]), let year = Int(parts[4]) else { return [] }
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        guard let month = months.firstIndex(of: parts[0]) else { return [] }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        guard let day0 = cal.date(from: DateComponents(year: year, month: month + 1, day: startDay)) else { return [] }
        var out: [KpPoint] = []
        for line in lines {
            guard let m = line.range(of: #"^(\d{2})-(\d{2})UT"#, options: .regularExpression) else { continue }
            let hour = Int(line[m].prefix(2)) ?? 0
            let values = line[m.upperBound...].replacingOccurrences(of: #"\([^)]*\)"#, with: "", options: .regularExpression)
                .split(separator: " ").compactMap { Double($0) }
            for (d, v) in values.enumerated() {
                if let t = cal.date(byAdding: .hour, value: d * 24 + hour, to: day0) { out.append(KpPoint(time: t, kp: v, predicted: true)) }
            }
        }
        return out.sorted { $0.time < $1.time }
    }

    /// Plain words for what Kp and the scales mean for radio and GPS.
    static func hfNote(_ w: SpaceWeather) -> String {
        if w.radio >= 3 { return "Strong radio blackout on the sunlit side: HF is unreliable." }
        if w.radio >= 1 { return "Minor radio blackout: some HF fades on the sunlit side." }
        if w.geomagnetic >= 3 { return "Geomagnetic storm: HF is poor at high latitudes." }
        if w.kpNow >= 5 { return "Disturbed: HF is degraded at higher latitudes." }
        return "HF radio conditions are normal."
    }

    static func gpsNote(_ w: SpaceWeather) -> String {
        if w.geomagnetic >= 4 || w.radiation >= 3 { return "GPS may be noticeably less accurate." }
        if w.geomagnetic >= 2 || w.kpNow >= 6 { return "GPS may be slightly less accurate." }
        return "GPS should be normal."
    }

    static func auroraNote(_ w: SpaceWeather) -> String? {
        w.geomagneticOutlook >= 1 ? "Geomagnetic storm (G\(w.geomagneticOutlook)) expected in the next three days." : nil
    }

    // MARK: NOAA Weather Radio

    /// weather.gov's county coverage list: parallel JavaScript arrays, one row per county a transmitter covers.
    static func parseWeatherRadio(_ js: String) -> [WeatherRadioStation] {
        var fields: [String: [Int: String]] = [:]
        let regex = try! NSRegularExpression(pattern: #"^([A-Z]+)\[(\d+)\] = "(.*)";$"#, options: [.anchorsMatchLines])
        let ns = js as NSString
        for m in regex.matches(in: js, range: NSRange(location: 0, length: ns.length)) {
            let key = ns.substring(with: m.range(at: 1))
            guard let i = Int(ns.substring(with: m.range(at: 2))) else { continue }
            fields[key, default: [:]][i] = ns.substring(with: m.range(at: 3))
        }
        var byCall: [String: WeatherRadioStation] = [:]
        for i in (fields["CALLSIGN"] ?? [:]).keys.sorted() {
            func f(_ k: String) -> String? { fields[k]?[i] }
            guard let call = f("CALLSIGN"), !call.isEmpty, let freq = f("FREQ"), let lat = f("LAT").flatMap(Double.init), let lon = f("LON").flatMap(Double.init),
                  lat != 0 || lon != 0 else { continue }
            let same = f("SAME") ?? ""
            if var s = byCall[call] {
                if !same.isEmpty, !s.counties.contains(same) { s.counties.append(same) }
                byCall[call] = s
            } else {
                byCall[call] = WeatherRadioStation(callsign: call, name: f("SITENAME") ?? call, state: f("SITESTATE") ?? f("ST") ?? "",
                                                   frequency: freq, latitude: lat, longitude: lon, powerWatts: Int(f("PWR") ?? "") ?? 0,
                                                   status: f("STATUS") ?? "", counties: same.isEmpty ? [] : [same])
            }
        }
        return byCall.values.sorted { $0.callsign < $1.callsign }
    }

    /// The closest working transmitters, nearest first, with the distance in km.
    static func nearestRadio(_ stations: [WeatherRadioStation], latitude: Double, longitude: Double, limit: Int) -> [(WeatherRadioStation, Double)] {
        stations.filter { $0.status.uppercased() == "NORMAL" }
            .map { ($0, HamGeo.distanceKm((latitude, longitude), ($0.latitude, $0.longitude))) }
            .sorted { $0.1 < $1.1 }.prefix(limit).map { $0 }
    }

    /// The seven NOAA Weather Radio channels.
    static let radioChannels: [(String, String)] = [
        ("162.400", "WX2"), ("162.425", "WX4"), ("162.450", "WX5"), ("162.475", "WX3"),
        ("162.500", "WX6"), ("162.525", "WX7"), ("162.550", "WX1"),
    ]

    /// "12 min ago", "3 h ago", "2 d ago".
    static func age(_ date: Date, now: Date) -> String {
        let s = max(0, now.timeIntervalSince(date))
        if s < 90 { return "just now" }
        if s < 3600 { return "\(Int(s / 60)) min ago" }
        if s < 86_400 { return "\(Int(s / 3600)) h ago" }
        return "\(Int(s / 86_400)) d ago"
    }
}
