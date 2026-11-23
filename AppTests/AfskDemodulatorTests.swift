import XCTest
@testable import NomadPocket

final class AfskDemodulatorTests: XCTestCase {
    private func resource(_ name: String) throws -> Data {
        let parts = name.split(separator: ".").map(String.init)
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: parts[0], withExtension: parts[1]), name)
        return try Data(contentsOf: url)
    }
    private func pcm(_ name: String) throws -> [Int16] {
        let d = try resource(name)
        return d.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }.map { Int16(littleEndian: $0) }
    }
    private func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
    private func meta() throws -> [[String: Any]] { try XCTUnwrap(JSONSerialization.jsonObject(with: resource("frames.json")) as? [[String: Any]]) }

    func testDecodesTheReferenceAudioFiles() throws {
        for m in try meta() {
            var got: [String] = []
            let d = AfskDemodulator(sampleRate: Double(m["rate"] as! Int)) { got.append(self.hex($0)) }
            d.feed(try pcm(m["file"] as! String))
            XCTAssertEqual(got, m["frames"] as! [String], m["file"] as! String)
        }
    }

    func testFramesParseAsAprs() throws {
        let m = try meta()[0]
        var frames: [[UInt8]] = []
        AfskDemodulator(sampleRate: Double(m["rate"] as! Int)) { frames.append($0) }.feed(try pcm(m["file"] as! String))
        let parsed = frames.compactMap(AX25Frame.decode)
        XCTAssertEqual(parsed.count, 3)
        XCTAssertEqual(parsed[0].source.description, "W5SIM-9")
        XCTAssertNotNil(APRS.decode(parsed[0]))
    }

    func testChunkSizeDoesNotMatter() throws {
        let m = try meta()[1]
        let samples = try pcm(m["file"] as! String)
        var n = 0
        let d = AfskDemodulator(sampleRate: Double(m["rate"] as! Int)) { _ in n += 1 }
        var i = 0
        while i < samples.count { let e = min(samples.count, i + 777); d.feed(Array(samples[i..<e])); i = e }
        XCTAssertEqual(n, 3)
    }

    func testNoiseAndMusicGiveNothing() {
        var g = SystemRandomNumberGenerator()
        var frames = 0
        let noise = (0..<(48000 * 6)).map { _ in Float.random(in: -0.5...0.5, using: &g) }
        AfskDemodulator(sampleRate: 48000) { _ in frames += 1 }.feed(noise)
        let freqs: [Double] = [440, 880, 1200, 1800, 2200, 3000]
        var tones = [Float](repeating: 0, count: 48000 * 3)
        for i in 0..<tones.count {
            var v = 0.0
            for f in freqs { v += 0.2 * sin(2 * Double.pi * f * Double(i) / 48000.0 + f) }
            tones[i] = Float(v)
        }
        AfskDemodulator(sampleRate: 48000) { _ in frames += 1 }.feed(tones)
        XCTAssertEqual(frames, 0)
    }

    func testAWrongSampleRateWithinTwoPercentStillWorks() throws {
        let m = try meta()[0]
        var n = 0
        AfskDemodulator(sampleRate: Double(m["rate"] as! Int) * 0.98) { _ in n += 1 }.feed(try pcm(m["file"] as! String))
        XCTAssertEqual(n, 3)
    }
}
