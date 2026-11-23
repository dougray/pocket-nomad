import XCTest
@testable import NomadPocket

@MainActor
final class SdrWeatherTests: XCTestCase {
    func testParsesPassesAndPictures() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "events-weather", withExtension: "jsonl"))
        var reader = SdrLineReader()
        let events = reader.feed(try Data(contentsOf: url)).compactMap(SdrParser.event)
        XCTAssertEqual(events.count, 4)
        let s = SdrState()
        events.forEach { s.apply($0) }
        XCTAssertEqual(s.images.map(\.id), ["1793975900-NOAA_19", "1793975500-NOAA_19"], "newest first")
        XCTAssertEqual(s.images.first?.url, "/img/1793975900-NOAA_19.png")
        XCTAssertEqual(s.passes.first?.elevation, 62)
        XCTAssertNil(SdrParser.event(["t": "image", "id": "x", "url": "/etc/passwd"]), "only /img/ paths are accepted")
        events.forEach { s.apply($0) }
        XCTAssertEqual(s.images.count, 2, "repeats are not duplicated")
    }
}

@MainActor
final class SdrDongleTests: XCTestCase {
    private func ev(_ json: String) -> SdrEvent? {
        SdrParser.event((try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:])
    }
    func testStatusCarriesWhatEachDongleDoes() {
        guard case .hello(let h)? = ev(#"{"t":"hello","seq":0,"proto":1,"fw":"1","model":"pi","dongle":"d","modes":["adsb","ais"],"gps":false,"dongles":2}"#) else { return XCTFail("hello") }
        XCTAssertEqual(h.dongles, 2)
        guard case .status(_, _, _, let a)? = ev(#"{"t":"status","seq":1,"ts":1,"mode":"adsb","gain":"auto","dongle":true,"decoded":0,"active":{"0":"adsb","1":"ais"}}"#) else { return XCTFail("status") }
        XCTAssertEqual(a, [0: "adsb", 1: "ais"])
        guard case .status(_, _, _, let old)? = ev(#"{"t":"status","seq":2,"ts":1,"mode":"aprs","dongle":true,"decoded":0}"#) else { return XCTFail("old") }
        XCTAssertEqual(old, [0: "aprs"], "an older box that only says mode counts as dongle 0")
        guard case .hello(let h1)? = ev(#"{"t":"hello","seq":0,"proto":1,"fw":"1","model":"pi","dongle":"d","modes":[],"gps":false}"#) else { return XCTFail("hello1") }
        XCTAssertEqual(h1.dongles, 1)
    }
}

@MainActor
final class SdrSpectrumJs8Tests: XCTestCase {
    private func load(_ name: String) throws -> [SdrEvent] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "jsonl"))
        var reader = SdrLineReader()
        return reader.feed(try Data(contentsOf: url)).compactMap(SdrParser.event)
    }
    func testSpectrumKeepsLatestAndPeak() throws {
        let events = try load("events-spectrum")
        XCTAssertEqual(events.count, 1)
        let s = SdrState()
        events.forEach { s.apply($0) }
        XCTAssertEqual(s.spectrum?.lowHz, 144_000_000)
        XCTAssertEqual(s.spectrum?.db.count, 50)
        s.apply(.spectrum(lowHz: 144_000_000, stepHz: 80_000, db: Array(repeating: -90, count: 50)))
        XCTAssertEqual(s.peak, zip(events.compactMap { if case .spectrum(_, _, let d) = $0 { d } else { nil } }[0], Array(repeating: -90, count: 50)).map { max($0, $1) }, "peak hold keeps the strongest")
        s.apply(.spectrum(lowHz: 118_000_000, stepHz: 80_000, db: Array(repeating: -70, count: 50)))
        XCTAssertEqual(s.peak, Array(repeating: -70, count: 50), "a new range starts a new peak hold")
        XCTAssertNil(SdrParser.event(["t": "spectrum", "lo": 1, "step": 0, "db": [1, 2]]))
        XCTAssertNil(SdrParser.event(["t": "spectrum", "lo": 1, "step": 1, "db": [1]]))
    }
    func testJs8MessagesNewestFirstNoDuplicates() throws {
        let events = try load("events-js8")
        XCTAssertEqual(events.count, 5)
        let s = SdrState()
        events.forEach { s.apply($0) }
        events.forEach { s.apply($0) }
        XCTAssertEqual(s.js8.count, 5)
        XCTAssertEqual(Set(s.js8.map(\.kind)), ["hb", "cq", "msg"])
        XCTAssertNil(SdrParser.event(["t": "js8", "from": "", "text": "x"]))
    }
}
