import XCTest
@testable import NomadPocket

final class SameDecoderTests: XCTestCase {
    private func resource(_ name: String) throws -> Data {
        let parts = name.split(separator: ".").map(String.init)
        return try Data(contentsOf: XCTUnwrap(Bundle(for: Self.self).url(forResource: parts[0], withExtension: parts[1]), name))
    }
    private func pcm(_ name: String) throws -> [Int16] {
        try resource(name).withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }.map { Int16(littleEndian: $0) }
    }

    func testDecodesTheReferenceAlerts() throws {
        let meta = try XCTUnwrap(JSONSerialization.jsonObject(with: resource("same.json")) as? [[String: Any]])
        for m in meta {
            var got: [String] = []
            SameDecoder(sampleRate: Double(m["rate"] as! Int)) { got.append($0.header) }.feed(try pcm(m["file"] as! String))
            XCTAssertEqual(got, m["headers"] as! [String], m["file"] as! String)
        }
    }

    func testParsesFields() throws {
        let a = try XCTUnwrap(SameDecoder.parse("ZCZC-WXR-TOR-048453+0030-3201530-KEWX/NWS-"))
        XCTAssertEqual(a.event, "Tornado Warning"); XCTAssertEqual(a.areas, ["048453"]); XCTAssertEqual(a.minutes, 30); XCTAssertEqual(a.station, "KEWX/NWS")
        XCTAssertEqual(a.text, "Tornado Warning for 1 area, valid 30 min")
        XCTAssertEqual(try XCTUnwrap(SameDecoder.parse("ZCZC-WXR-SVR-048021-048055+0045-3201545-KAUS/NWS-")).areas.count, 2)
        XCTAssertNil(SameDecoder.parse("ZCZC-garbage")); XCTAssertNil(SameDecoder.parse("hello"))
        XCTAssertEqual(try XCTUnwrap(SameDecoder.parse("ZCZC-WXR-XYZ-048453+0030-3201530-KEWX/NWS-")).event, "Alert XYZ")
    }

    func testNoiseAndPacketAudioGiveNothing() throws {
        var g = SystemRandomNumberGenerator()
        var n = 0
        SameDecoder(sampleRate: 48000) { _ in n += 1 }.feed((0..<(48000 * 8)).map { _ in Float.random(in: -0.5...0.5, using: &g) })
        let meta = try XCTUnwrap(JSONSerialization.jsonObject(with: resource("frames.json")) as? [[String: Any]])[0]
        SameDecoder(sampleRate: Double(meta["rate"] as! Int)) { _ in n += 1 }.feed(try pcm(meta["file"] as! String))
        XCTAssertEqual(n, 0)
    }
}
