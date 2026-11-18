import XCTest
@testable import NomadPocket

final class SdrEventsTests: XCTestCase {
    private func stream() throws -> [SdrEvent] {
        let url = Bundle(for: Self.self).url(forResource: "events-sim", withExtension: "jsonl")!
        var reader = SdrLineReader()
        // Feed in awkward 7-byte pieces, as a Bluetooth link would.
        let data = try Data(contentsOf: url)
        var objs: [[String: Any]] = []
        var i = 0
        while i < data.count { objs += reader.feed(data.subdata(in: i..<min(i + 7, data.count))); i += 7 }
        XCTAssertEqual(reader.errors, 0)
        return objs.compactMap(SdrParser.event)
    }

    func testRecordedStreamParses() throws {
        let events = try stream()
        XCTAssertEqual(events.count, 66)
        guard case .hello(let h) = events[0] else { return XCTFail("first event is hello") }
        XCTAssertTrue(h.simulated)
        XCTAssertEqual(h.proto, 1)
        XCTAssertTrue(events.contains { if case .alert = $0 { true } else { false } })
    }

    @MainActor func testStateTracksAircraftAndSimulatedFlag() throws {
        let s = SdrState()
        try stream().forEach { s.apply($0) }
        XCTAssertTrue(s.simulated)
        XCTAssertEqual(Set(s.aircraft.keys), ["A1B2C3", "A4D5E6", "AC0F11", "A8B9CA"])
        let last = s.aircraft.values.map(\.heardAt).max()!
        XCTAssertEqual(s.visibleAircraft(now: last).count, 4)
        XCTAssertEqual(s.visibleAircraft(now: last.addingTimeInterval(200)).count, 0)
        XCTAssertEqual(s.alerts.first?.same.hasPrefix("ZCZC-WXR-TOR"), true)
        XCTAssertEqual(s.channels.count, 2)
    }

    func testAPRSFrameFromBoxDecodesWithHamDecoder() throws {
        let frames = try stream().compactMap { e -> [UInt8]? in if case .aprs(let f, _) = e { return f } else { return nil } }
        XCTAssertFalse(frames.isEmpty)
        let ax = try XCTUnwrap(AX25Frame.decode(frames[0]))
        let packet = try XCTUnwrap(APRS.decode(ax))
        XCTAssertEqual(packet.source, "W5SIM-9")
        XCTAssertEqual(try XCTUnwrap(packet.position).latitude, 30.32, accuracy: 0.01)
        XCTAssertTrue(packet.comment.contains("Simulated"))
    }

    func testBadEventsRejected() {
        XCTAssertNil(SdrParser.event(["t": "aircraft", "icao": "A", "lat": 95, "lon": 0]))
        XCTAssertNil(SdrParser.event(["t": "hello", "proto": 2]))
        XCTAssertNil(SdrParser.event(["t": "nonsense"]))
        var r = SdrLineReader()
        XCTAssertEqual(r.feed(Data("garbage\n{\"t\":\"status\",\"seq\":1}\n".utf8)).count, 1)
        XCTAssertEqual(r.errors, 1)
    }
}
