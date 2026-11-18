import XCTest
@testable import NomadPocket

@MainActor
final class SdrClientTests: XCTestCase {
    func testSimulatedBoxNeverFeedsHamButRealOneDoes() throws {
        let client = SdrClient()
        SdrState.shared.reset()
        let frames = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "events-sim", withExtension: "jsonl")).path
        var reader = SdrLineReader()
        let events = reader.feed(try Data(contentsOf: URL(fileURLWithPath: frames))).compactMap(SdrParser.event)
        let before = HamService.shared.packetsHeard
        events.forEach { client.handle($0) }
        XCTAssertTrue(SdrState.shared.simulated)
        XCTAssertEqual(HamService.shared.packetsHeard, before, "invented frames must not enter the Ham log")
        // The same frames from a real box (hello without sim) do reach the Ham decoder.
        SdrState.shared.reset()
        let real = events.compactMap { e -> SdrEvent? in if case .hello = e { return nil } else { return e } }
        real.forEach { client.handle($0) }
        XCTAssertGreaterThan(HamService.shared.packetsHeard, before)
        SdrState.shared.reset()
    }

    func testReceivedChunksBuildState() {
        let client = SdrClient()
        SdrState.shared.reset()
        let line = #"{"t":"aircraft","seq":1,"ts":1000,"icao":"abc123","call":"TEST1","lat":30.1,"lon":-97.1,"alt":5000,"gs":200,"trk":90}"# + "\n"
        let d = Data(line.utf8)
        client.received(d.prefix(20)); client.received(d.suffix(from: 20))
        XCTAssertEqual(SdrState.shared.aircraft["ABC123"]?.callsign, "TEST1")
        SdrState.shared.reset()
    }
}
