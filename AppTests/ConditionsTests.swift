import XCTest
@testable import NomadPocket

/// The conditions parsers, run on real saved responses from each free source.
final class ConditionsTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: ConditionsTests.self).url(forResource: name, withExtension: nil), name)
        return try Data(contentsOf: url)
    }

    private func utc(_ s: String) -> Date { ISO8601DateFormatter().date(from: s)! }

    // MARK: Earthquakes

    func testQuakesParseAndFilterByDistance() throws {
        let quakes = try ConditionsParsers.parseQuakes(fixture("usgs.json"))
        XCTAssertEqual(quakes.count, 15)
        XCTAssertEqual(quakes[0].id, "aka2026wuapjf")
        XCTAssertEqual(quakes[0].magnitude, 2.8)
        XCTAssertEqual(quakes[0].place, "29 km E of Chenega, Alaska")
        // Near Denver, only the Golden quake is within 50 km.
        let near = ConditionsParsers.nearby(quakes, latitude: 39.74, longitude: -104.99, radiusKm: 50, minMagnitude: 2.5)
        XCTAssertEqual(near.map(\.id), ["test-co"])
        XCTAssertEqual(near[0].depthKm, 8.2, accuracy: 0.01)
        XCTAssertTrue(ConditionsParsers.nearby(quakes, latitude: 39.74, longitude: -104.99, radiusKm: 50, minMagnitude: 3.5).isEmpty)
    }

    func testSignificantQuakesAreKeptAnywhere() throws {
        let week = try ConditionsParsers.parseQuakes(fixture("usgs.json"))
        let sig = try ConditionsParsers.parseQuakes(fixture("usgs-sig.json"), significant: true)
        XCTAssertEqual(sig.count, 7)
        XCTAssertEqual(sig[0].id, "us7000tymu")
        XCTAssertEqual(sig[0].magnitude, 6.6)
        XCTAssertFalse(sig[0].tsunami)
        let merged = ConditionsParsers.merge(week, sig)
        XCTAssertEqual(merged.count, 22)
        let near = ConditionsParsers.nearby(merged, latitude: 39.78, longitude: -105.22, radiusKm: 10, minMagnitude: 2.5)
        XCTAssertEqual(near.count, 8, "the Golden quake plus every significant one")
        XCTAssertEqual(near, near.sorted { $0.time > $1.time }, "newest first")
        XCTAssertThrowsError(try ConditionsParsers.parseQuakes(Data("[]".utf8)))
    }

    // MARK: Alerts

    func testAlertsParseSortAndExpire() throws {
        XCTAssertTrue(try ConditionsParsers.parseAlerts(fixture("nws-empty.json")).isEmpty)
        let alerts = try ConditionsParsers.parseAlerts(fixture("nws-one.json"))
        XCTAssertEqual(alerts.count, 1)
        let a = alerts[0]
        XCTAssertEqual(a.event, "Dense Fog Advisory")
        XCTAssertEqual(a.severity, "Moderate")
        XCTAssertEqual(a.rank, 2)
        XCTAssertEqual(a.sender, "NWS San Angelo TX")
        XCTAssertEqual(a.areas, "Coke; Tom Green")
        XCTAssertEqual(a.expires, utc("2026-11-17T15:00:00Z"))
        XCTAssertNotNil(a.instruction)
        XCTAssertFalse(a.isExpired(at: utc("2026-11-17T14:59:00Z")))
        XCTAssertTrue(a.isExpired(at: utc("2026-11-17T15:01:00Z")))
    }

    func testAlertsSortMostSevereFirst() {
        let json = #"{"features":[{"properties":{"id":"a","event":"Frost Advisory","severity":"Minor"}},{"properties":{"id":"b","event":"Tornado Warning","severity":"Extreme"}},{"properties":{"id":"c","event":"Wind Advisory","severity":"Moderate"}}]}"#
        let alerts = try! ConditionsParsers.parseAlerts(Data(json.utf8))
        XCTAssertEqual(alerts.map(\.event), ["Tornado Warning", "Wind Advisory", "Frost Advisory"])
    }

    // MARK: Forecast

    func testForecastParsesLocalTimes() throws {
        let f = try ConditionsParsers.parseForecast(fixture("openmeteo.json"))
        XCTAssertEqual(f.hours.count, 168)
        XCTAssertEqual(f.latitude, 39.690926, accuracy: 1e-6)
        // Midnight local at UTC-7 is 07:00 UTC.
        XCTAssertEqual(f.hours[0].time, utc("2026-11-17T07:00:00Z"))
        XCTAssertEqual(f.hours[0].temperatureC, 3.6, accuracy: 0.01)
        XCTAssertEqual(f.hours[0].code, 0)
        let next = ConditionsParsers.upcoming(f, from: utc("2026-11-17T10:10:00Z"), count: 5)
        XCTAssertEqual(next.count, 5)
        XCTAssertEqual(next[0].time, utc("2026-11-17T10:00:00Z"))
        XCTAssertEqual(ConditionsParsers.describe(code: 0), "Clear")
        XCTAssertEqual(ConditionsParsers.describe(code: 95), "Thunderstorm")
        XCTAssertEqual(ConditionsParsers.compass(0), "N")
        XCTAssertEqual(ConditionsParsers.compass(225), "SW")
        XCTAssertEqual(ConditionsParsers.compass(350), "N")
        XCTAssertThrowsError(try ConditionsParsers.parseForecast(Data(#"{"hourly":{"time":[]}}"#.utf8)))
    }

    func testPositionIsRoundedBeforeItIsSent() {
        XCTAssertEqual(ConditionsParsers.rounded(39.7499), 39.7)
        XCTAssertEqual(ConditionsParsers.rounded(-105.04), -105.0)
        XCTAssertEqual(ConditionsParsers.rounded(-104.96), -105.0)
    }

    // MARK: Space weather

    func testSpaceWeatherFromRealFeeds() throws {
        let kp = try fixture("kp.json"), scales = try fixture("scales.json")
        let text = String(decoding: try fixture("3day.txt"), as: UTF8.self)
        let w = try ConditionsParsers.parseSpaceWeather(kp: kp, scales: scales, threeDay: text)
        XCTAssertEqual(w.kpNow, 1.0)
        XCTAssertEqual(w.kpTime, utc("2026-11-17T09:00:00Z"))
        XCTAssertEqual(w.geomagnetic, 0)
        XCTAssertEqual(w.radio, 0)
        XCTAssertEqual(w.geomagneticOutlook, 1)
        XCTAssertEqual(w.kp.count, 37, "17 observed + 20 forecast after them")
        let predicted = w.kp.filter(\.predicted)
        XCTAssertEqual(predicted.count, 20)
        XCTAssertEqual(predicted[0].time, utc("2026-11-17T12:00:00Z"))
        XCTAssertEqual(predicted[0].kp, 3.67, accuracy: 0.001)
        XCTAssertEqual(predicted.map(\.kp).max(), 4.67)
        XCTAssertEqual(ConditionsParsers.hfNote(w), "HF radio conditions are normal.")
        XCTAssertEqual(ConditionsParsers.gpsNote(w), "GPS should be normal.")
        XCTAssertEqual(ConditionsParsers.auroraNote(w), "Geomagnetic storm (G1) expected in the next three days.")
    }

    func testKpForecastRollsOverTheMonth() {
        let text = """
        NOAA Kp index breakdown Nov 30-Dec 02 2026

                     Nov 30       Dec 01       Dec 02
        00-03UT       1.00         2.00 (G0)    3.00
        21-00UT       4.00         5.00 (G1)    6.00
        """
        let kp = ConditionsParsers.parseKpForecast(text)
        XCTAssertEqual(kp.count, 6)
        XCTAssertEqual(kp[0].time, utc("2026-11-30T00:00:00Z"))
        XCTAssertEqual(kp[1].time, utc("2026-11-30T21:00:00Z"))
        XCTAssertEqual(kp[2].time, utc("2026-12-01T00:00:00Z"))
        XCTAssertEqual(kp[2].kp, 2.0)
        XCTAssertEqual(kp[5].time, utc("2026-12-02T21:00:00Z"))
        XCTAssertTrue(ConditionsParsers.parseKpForecast("nothing useful").isEmpty)
    }

    func testRadioAndGPSWordsFollowTheScales() {
        func w(r: Int = 0, s: Int = 0, g: Int = 0, kp: Double = 1) -> SpaceWeather {
            SpaceWeather(kpNow: kp, kpTime: Date(), kp: [], radio: r, radiation: s, geomagnetic: g, geomagneticOutlook: g)
        }
        XCTAssertTrue(ConditionsParsers.hfNote(w(r: 3)).contains("unreliable"))
        XCTAssertTrue(ConditionsParsers.hfNote(w(r: 1)).contains("Minor"))
        XCTAssertTrue(ConditionsParsers.hfNote(w(g: 3)).contains("storm"))
        XCTAssertTrue(ConditionsParsers.gpsNote(w(g: 4)).contains("noticeably"))
        XCTAssertTrue(ConditionsParsers.gpsNote(w(kp: 6)).contains("slightly"))
    }

    // MARK: Weather radio

    func testWeatherRadioStationsMergeCounties() throws {
        let js = String(decoding: try fixture("nwr.js"), as: UTF8.self)
        let stations = ConditionsParsers.parseWeatherRadio(js)
        XCTAssertEqual(stations.count, 10, "one per callsign; rows with no callsign or position are skipped")
        let denver = try XCTUnwrap(stations.first { $0.callsign == "KEC76" })
        XCTAssertEqual(denver.frequency, "162.550")
        XCTAssertEqual(denver.name, "Denver")
        XCTAssertEqual(denver.counties.count, 7)
        let nearest = ConditionsParsers.nearestRadio(stations, latitude: 39.74, longitude: -104.99, limit: 3)
        XCTAssertEqual(nearest.map(\.0.callsign), ["KEC76", "WNG550", "WXM51"])
        XCTAssertEqual(nearest[0].1, 7.0, accuracy: 0.5)
        XCTAssertEqual(ConditionsParsers.radioChannels.count, 7)
        XCTAssertEqual(ConditionsParsers.radioChannels.first?.0, "162.400")
    }

    func testAgeWords() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertEqual(ConditionsParsers.age(now.addingTimeInterval(-30), now: now), "just now")
        XCTAssertEqual(ConditionsParsers.age(now.addingTimeInterval(-600), now: now), "10 min ago")
        XCTAssertEqual(ConditionsParsers.age(now.addingTimeInterval(-7200), now: now), "2 h ago")
        XCTAssertEqual(ConditionsParsers.age(now.addingTimeInterval(-3 * 86_400), now: now), "3 d ago")
    }
}

final class ConditionsDayTests: XCTestCase {
    func testDaysGroupInThePlacesTimeZone() throws {
        let url = try XCTUnwrap(Bundle(for: ConditionsTests.self).url(forResource: "openmeteo.json", withExtension: nil))
        let f = try ConditionsParsers.parseForecast(Data(contentsOf: url))
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: -25_200)!
        let days = ConditionsParsers.days(f, calendar: cal)
        XCTAssertEqual(days.count, 7)
        let first = f.hours.prefix(24)
        XCTAssertEqual(days[0].highC, first.map(\.temperatureC).max()!, accuracy: 1e-9)
        XCTAssertEqual(days[0].lowC, first.map(\.temperatureC).min()!, accuracy: 1e-9)
        XCTAssertEqual(days[0].rainMm, first.reduce(0) { $0 + $1.precipitationMm }, accuracy: 1e-9)
        XCTAssertTrue(days.allSatisfy { $0.lowC <= $0.highC })
        XCTAssertEqual(days, days.sorted { $0.date < $1.date })
    }

    func testUnitsFollowTheRegion() {
        let us = ConditionsFormat(metric: false), m = ConditionsFormat(metric: true)
        XCTAssertEqual(us.temperature(0), "32°F")
        XCTAssertEqual(m.temperature(21.6), "22°C")
        XCTAssertEqual(us.speed(100), "62 mph")
        XCTAssertEqual(m.speed(100), "100 km/h")
        XCTAssertEqual(us.rain(25.4), "1.00 in")
        XCTAssertEqual(m.rain(0), "0 mm")
        XCTAssertEqual(us.distance(32.1869), "20 mi")
        XCTAssertEqual(us.distance(8.0467), "5.0 mi")
        XCTAssertEqual(m.distance(4.25), "4.2 km")
        XCTAssertEqual(us.radius(500), "311 mi")
    }
}
