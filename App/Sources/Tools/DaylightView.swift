import SwiftUI
import CoreLocation

/// Sunrise, sunset and twilight from the NOAA solar equations. No network needed.
enum SolarCalculator {
    struct Day { let sunrise, sunset, civilDawn, civilDusk: Date? }

    static func day(for date: Date, at c: CLLocationCoordinate2D, calendar: Calendar = .current) -> Day {
        Day(sunrise: event(date, c, zenith: 90.833, rising: true, calendar),
            sunset: event(date, c, zenith: 90.833, rising: false, calendar),
            civilDawn: event(date, c, zenith: 96, rising: true, calendar),
            civilDusk: event(date, c, zenith: 96, rising: false, calendar))
    }

    private static func event(_ date: Date, _ c: CLLocationCoordinate2D, zenith: Double, rising: Bool, _ cal: Calendar) -> Date? {
        let rad = Double.pi / 180
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let comps = cal.dateComponents([.year, .month, .day], from: date)
        guard let noonUTC = utc.date(from: DateComponents(year: comps.year, month: comps.month, day: comps.day, hour: 12)) else { return nil }
        // Julian century from J2000.
        let jd = noonUTC.timeIntervalSince1970 / 86400 + 2_440_587.5 - c.longitude / 360
        let t = (jd - 2_451_545) / 36525
        let l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).truncatingRemainder(dividingBy: 360)
        let m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        let e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        let center = sin(m * rad) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m * rad) * (0.019993 - 0.000101 * t) + sin(3 * m * rad) * 0.000289
        let trueLong = l0 + center
        let omega = 125.04 - 1934.136 * t
        let lambda = trueLong - 0.00569 - 0.00478 * sin(omega * rad)
        let eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        let eps = eps0 + 0.00256 * cos(omega * rad)
        let decl = asin(sin(eps * rad) * sin(lambda * rad))
        let y = pow(tan(eps * rad / 2), 2)
        let eqTime = 4 / rad * (y * sin(2 * l0 * rad) - 2 * e * sin(m * rad) + 4 * e * y * sin(m * rad) * cos(2 * l0 * rad)
            - 0.5 * y * y * sin(4 * l0 * rad) - 1.25 * e * e * sin(2 * m * rad))
        let lat = c.latitude * rad
        let cosH = (cos(zenith * rad) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))
        guard cosH >= -1, cosH <= 1 else { return nil }   // polar day or night
        let h = acos(cosH) / rad
        let minutes = 720 - 4 * c.longitude - eqTime + (rising ? -4 * h : 4 * h)
        guard let midnight = utc.date(from: DateComponents(year: comps.year, month: comps.month, day: comps.day)) else { return nil }
        return midnight.addingTimeInterval(minutes * 60)
    }

    /// 0 = new moon, 0.5 = full.
    static func moonPhase(_ date: Date) -> Double {
        let synodic = 29.530588853
        let knownNew = Date(timeIntervalSince1970: 947_182_440) // 2000-01-06 18:14 UTC
        let days = date.timeIntervalSince(knownNew) / 86400
        let p = days.truncatingRemainder(dividingBy: synodic) / synodic
        return p < 0 ? p + 1 : p
    }
}

struct DaylightView: View {
    @State private var location = LocationService.shared

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            List {
                if let c = location.location?.coordinate {
                    let day = SolarCalculator.day(for: context.date, at: c)
                    Section {
                        if let sunset = day.sunset, sunset > context.date, let sunrise = day.sunrise, sunrise < context.date {
                            big("Daylight left", duration(sunset.timeIntervalSince(context.date)))
                        } else if let dawn = day.civilDawn, dawn > context.date {
                            big("First light in", duration(dawn.timeIntervalSince(context.date)))
                        } else {
                            big("After dark", "Plan for night")
                        }
                    }
                    Section("Today") {
                        row("First light", day.civilDawn)
                        row("Sunrise", day.sunrise)
                        row("Sunset", day.sunset)
                        row("Last light", day.civilDusk)
                    }
                    Section("Moon") {
                        let p = SolarCalculator.moonPhase(context.date)
                        Label(moonName(p), systemImage: moonSymbol(p))
                        Text("A bright moon (near full) gives enough light to move at night.").font(.footnote).foregroundStyle(.secondary)
                    }
                } else {
                    Text("Waiting for a GPS fix to work out sunrise and sunset where you are.").foregroundStyle(.secondary)
                }
                Section {
                    Text("Quick check: hold your hand at arm's length with fingers flat under the sun. Each finger between the sun and the horizon is about 15 minutes of daylight.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Daylight")
        .onAppear { location.begin() }
        .onDisappear { location.end() }
    }

    private func big(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading) {
            Text(label).foregroundStyle(.secondary)
            Text(value).font(.system(size: 40, weight: .bold, design: .rounded))
        }
        .padding(.vertical, 6)
    }

    private func row(_ label: String, _ date: Date?) -> some View {
        LabeledContent(label, value: date?.formatted(date: .omitted, time: .shortened) ?? "—")
    }

    private func duration(_ s: TimeInterval) -> String {
        let m = Int(s / 60)
        return m >= 60 ? "\(m / 60) h \(m % 60) min" : "\(m) min"
    }

    private func moonName(_ p: Double) -> String {
        switch p {
        case ..<0.03, 0.97...: "New moon"
        case ..<0.22: "Waxing crescent"
        case ..<0.28: "First quarter"
        case ..<0.47: "Waxing gibbous"
        case ..<0.53: "Full moon"
        case ..<0.72: "Waning gibbous"
        case ..<0.78: "Last quarter"
        default: "Waning crescent"
        }
    }

    private func moonSymbol(_ p: Double) -> String {
        switch p {
        case ..<0.03, 0.97...: "moonphase.new.moon"
        case ..<0.22: "moonphase.waxing.crescent"
        case ..<0.28: "moonphase.first.quarter"
        case ..<0.47: "moonphase.waxing.gibbous"
        case ..<0.53: "moonphase.full.moon"
        case ..<0.72: "moonphase.waning.gibbous"
        case ..<0.78: "moonphase.last.quarter"
        default: "moonphase.waning.crescent"
        }
    }
}
