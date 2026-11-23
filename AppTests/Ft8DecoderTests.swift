import XCTest
@testable import NomadPocket

/// Compares the port with ft8_lib's own decoder (the .expected files are its output for the same recordings).
final class Ft8DecoderTests: XCTestCase {
    private func resource(_ name: String, _ ext: String) throws -> Data {
        try Data(contentsOf: XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: ext), "\(name).\(ext)"))
    }
    private func shorts(_ name: String) throws -> [Int16] {
        let d = try resource(name, "wav").dropFirst(44)
        return d.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }.map { Int16(littleEndian: $0) }
    }
    private func wav(_ name: String) throws -> [Float] { try shorts(name).map { Float($0) / 32768 } }
    private func expected(_ name: String) throws -> [String] {
        String(decoding: try resource(name, "expected"), as: UTF8.self).split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }
    private func decode(_ name: String) throws -> [String] { Ft8Decoder().decode(try wav(name)).map(\.text) }

    func testGeneratedMessagesDecodeExactly() throws {
        XCTAssertEqual(try decode("gen_cq"), ["CQ K1ABC FN42"])
        XCTAssertEqual(try decode("gen_free"), ["TNX BOB 73 GL"])
    }

    func testRealRecordingsMatchTheReferenceDecoder() throws {
        for name in ["websdr_test4", "websdr_test6"] {
            let want = try expected(name), got = try decode(name)
            XCTAssertFalse(got.isEmpty, name)
            XCTAssertEqual(Set(got), Set(want), "\(name): ref \(want.count), got \(got.count)")
        }
    }

    func testSilenceAndNoiseGiveNothing() {
        XCTAssertTrue(Ft8Decoder().decode([Float](repeating: 0, count: 180000)).isEmpty)
        var g = SystemRandomNumberGenerator()
        XCTAssertTrue(Ft8Decoder().decode((0..<180000).map { _ in Float.random(in: -0.15...0.15, using: &g) }).isEmpty)
    }

    private func upsample(_ x: [Int16], _ rate: Int) -> [Float] {
        let n = x.count * rate / 12000
        return (0..<n).map { i in
            let p = Double(i) * 12000 / Double(rate)
            let i0 = min(Int(p), x.count - 2)
            return Float(Double(x[i0]) + Double(x[i0 + 1] - x[i0]) * (p - Double(i0))) / 32768
        }
    }

    func testLiveAudioAtPhoneRatesDecodesAtTheSlotBoundary() throws {
        for rate in [48000, 44100] {
            let audio = upsample(try shorts("gen_free"), rate)
            var clock = Date(timeIntervalSince1970: 15000 * 1000 + 0.1)
            var got: [[Ft8Message]] = []
            let runner = Ft8SlotRunner(inputRate: Double(rate), now: { clock }) { got.append($0) }
            runner.synchronous = true
            var i = 0
            while i < audio.count {
                let n = min(rate / 10, audio.count - i)
                audio[i..<(i + n)].withUnsafeBufferPointer { runner.feed($0) }
                i += n; clock = clock.addingTimeInterval(0.1)
            }
            clock = clock.addingTimeInterval(3)
            [Float](repeating: 0, count: rate / 10).withUnsafeBufferPointer { runner.feed($0) }
            XCTAssertEqual(got.flatMap { $0 }.map(\.text), ["TNX BOB 73 GL"], "rate \(rate)")
        }
    }

    func testSpots() {
        XCTAssertEqual(ft8Spot("CQ K1ABC FN42")?.call, "K1ABC")
        XCTAssertEqual(ft8Spot("CQ DX R6WA LN32")?.grid, "LN32")
        XCTAssertEqual(ft8Spot("OH3NIV ZS6S KG33")?.call, "ZS6S")
        XCTAssertNil(ft8Spot("K1ABC W9XYZ -11")); XCTAssertNil(ft8Spot("K1ABC W9XYZ RR73")); XCTAssertNil(ft8Spot("TNX BOB 73 GL"))
    }
}
