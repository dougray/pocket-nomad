import Foundation
import Observation

/// Turns live audio at any common sample rate into 12 kHz mono floats (a low-pass filter, then linear interpolation).
final class Resampler12k: @unchecked Sendable {
    private let inputRate: Double
    private let taps = 63
    private var coef: [Float] = []
    private var hist: [Float]
    private var h = 0
    private var phase = 0.0
    private let step: Double
    private var prev: Float = 0
    private var prevValid = false

    init(inputRate: Double) {
        self.inputRate = inputRate
        step = inputRate / 12000
        hist = [Float](repeating: 0, count: taps)
        // Windowed-sinc low-pass at 5.4 kHz: well under the 6 kHz Nyquist of the output, wide enough for FT8's 200 to 3000 Hz.
        let fc = 5400.0 / inputRate
        var c = [Double](repeating: 0, count: taps)
        var sum = 0.0
        for i in 0..<taps {
            let n = Double(i) - Double(taps - 1) / 2
            let sinc = n == 0 ? 2 * fc : sin(2 * Double.pi * fc * n) / (Double.pi * n)
            let w = 0.54 - 0.46 * cos(2 * Double.pi * Double(i) / Double(taps - 1))
            c[i] = sinc * w; sum += c[i]
        }
        coef = c.map { Float($0 / sum) }
    }

    /// Appends the resampled audio to `out`.
    func feed(_ samples: UnsafeBufferPointer<Float>, into out: inout [Float]) {
        if inputRate == 12000 { out.append(contentsOf: samples); return }
        for x in samples {
            hist[h] = x
            h = (h + 1) % taps
            var acc: Float = 0
            var idx = h
            for t in 0..<taps { acc += coef[t] * hist[idx]; idx = (idx + 1) % taps }
            if !prevValid { prev = acc; prevValid = true; continue }
            phase += 1
            while phase >= step {
                phase -= step
                let frac = Float(min(1, max(0, 1 - phase)))
                out.append(prev + (acc - prev) * frac)
            }
            prev = acc
        }
    }
}

/// Collects live audio and decodes each 15 second FT8 slot when it ends, on UTC boundaries (the phone's clock must be right;
/// a phone on a network is). `feed` is called from the audio thread; decoding is handed to a background queue.
final class Ft8SlotRunner: @unchecked Sendable {
    private let resampler: Resampler12k
    private let decoder = Ft8Decoder()
    private let now: () -> Date
    private let onSlot: ([Ft8Message]) -> Void
    private let queue = DispatchQueue(label: "org.nomadpocket.ft8")
    private let slotSamples = 15 * 12000
    private var buf: [Float] = []
    private var slotStart = -1.0
    /// When true, decoding happens on the caller's thread (for tests).
    var synchronous = false

    init(inputRate: Double, now: @escaping () -> Date = Date.init, onSlot: @escaping ([Ft8Message]) -> Void) {
        resampler = Resampler12k(inputRate: inputRate)
        self.now = now
        self.onSlot = onSlot
        buf.reserveCapacity(15 * 12000 + 12000)
    }

    func feed(_ samples: UnsafeBufferPointer<Float>) {
        let t = now().timeIntervalSince1970
        let slot = (t / 15).rounded(.down) * 15
        if slotStart < 0 { slotStart = slot; buf.removeAll(keepingCapacity: true) }
        if slot != slotStart {
            // a new slot began: decode the one that just ended (if we heard most of it), then start over
            if buf.count > slotSamples * 3 / 4 {
                let audio = Array(buf.prefix(slotSamples))
                if synchronous { onSlot(decoder.decode(audio)) } else { queue.async { [self] in onSlot(decoder.decode(audio)) } }
            }
            slotStart = slot; buf.removeAll(keepingCapacity: true)
        }
        resampler.feed(samples, into: &buf)
        if buf.count > slotSamples + 12000 { buf.removeLast(buf.count - (slotSamples + 12000)) }
    }
}

/// FT8 messages decoded from radio audio, newest first. Kept only in memory for this run of the app.
@MainActor
@Observable
final class Ft8Heard {
    static let shared = Ft8Heard()
    struct Line: Identifiable, Equatable { let id = UUID(); var text: String; var snr: Int; var date: Date }
    private(set) var lines: [Line] = []

    func add(_ messages: [Ft8Message], now: Date = Date()) {
        guard !messages.isEmpty else { return }
        lines = (messages.map { Line(text: $0.text, snr: Int($0.snr), date: now) } + lines).prefix(100).map { $0 }
    }

    func clear() { lines = [] }
}
