import Foundation

/// Turns audio into a stream of raw tone decisions: for each bit time, which of two tones (mark or space) was stronger.
/// Each tone is mixed down and averaged over one bit time, scaled by its own recent peak (so a receiver that favours one tone
/// still works); a clock that nudges itself on every tone change picks the sampling moment. Shared by `AfskDemodulator`
/// (packet radio, 1200 baud) and `SameDecoder` (NOAA Weather Radio alerts, 520.83 baud).
/// android/ham/.../AfskDemodulator.kt has the Android twin; keep them in step.
final class ToneSlicer: @unchecked Sendable {
    private let bitSamples: Double
    private let window: Int
    private var ring: [[Double]]
    private var sum = [Double](repeating: 0, count: 4)
    private var pos = 0
    private let markRot: (c: Double, s: Double)
    private let spaceRot: (c: Double, s: Double)
    private var mc = 1.0, ms = 0.0, sc = 1.0, ss = 0.0
    private var peakMark = 1e-9, peakSpace = 1e-9
    private var phase = 0.0
    private var sampled = false
    private var lastSign = 0
    private var count: UInt64 = 0
    private let onRawBit: (Int) -> Void

    init(sampleRate: Double, baud: Double, markHz: Double, spaceHz: Double, onRawBit: @escaping (Int) -> Void) {
        bitSamples = sampleRate / baud
        window = max(2, Int(bitSamples.rounded()))
        ring = [[Double]](repeating: [Double](repeating: 0, count: max(2, Int((sampleRate / baud).rounded()))), count: 4)
        markRot = (cos(2 * .pi * markHz / sampleRate), sin(2 * .pi * markHz / sampleRate))
        spaceRot = (cos(2 * .pi * spaceHz / sampleRate), sin(2 * .pi * spaceHz / sampleRate))
        self.onRawBit = onRawBit
    }

    func step(_ x: Double) {
        var t = mc * markRot.c - ms * markRot.s; ms = mc * markRot.s + ms * markRot.c; mc = t
        t = sc * spaceRot.c - ss * spaceRot.s; ss = sc * spaceRot.s + ss * spaceRot.c; sc = t
        count &+= 1
        if count & 0x3FFF == 0 {   // keep the oscillators at unit length and the running sums honest
            let a = (mc * mc + ms * ms).squareRoot(); mc /= a; ms /= a
            let b = (sc * sc + ss * ss).squareRoot(); sc /= b; ss /= b
            for i in 0..<4 { sum[i] = ring[i].reduce(0, +) }
        }
        let cur = [x * mc, x * ms, x * sc, x * ss]
        for i in 0..<4 { sum[i] += cur[i] - ring[i][pos]; ring[i][pos] = cur[i] }
        pos = (pos + 1) % window
        let em = sum[0] * sum[0] + sum[1] * sum[1]
        let es = sum[2] * sum[2] + sum[3] * sum[3]
        peakMark = max(em, peakMark * 0.99995); peakSpace = max(es, peakSpace * 0.99995)
        let d = em / (peakMark + 1e-9) - es / (peakSpace + 1e-9)
        let sign = d > 0 ? 1 : -1
        if sign != lastSign {   // a tone change: pull the bit clock toward a bit boundary
            let err = phase < bitSamples / 2 ? phase : phase - bitSamples
            phase -= err * 0.3
            lastSign = sign
        }
        phase += 1
        if phase >= bitSamples { phase -= bitSamples; sampled = false }
        if !sampled && phase >= bitSamples / 2 { sampled = true; onRawBit(d > 0 ? 1 : 0) }
    }
}

/// Receive-only decoder for 1200 baud packet radio (Bell 202 AFSK: mark 1200 Hz, space 2200 Hz) from audio samples.
///
/// Audio in, AX.25 frames out (without the 2-byte check sequence, the same shape a KISS TNC delivers), so the frames go
/// straight to `HamService.receive`. It never transmits. Written from the public AX.25 and HDLC descriptions.
/// NRZI and HDLC bit-unstuffing rebuild the bytes; a frame is kept only if its CRC-16 checks out.
/// android/ham/.../AfskDemodulator.kt is the Android twin; keep them in step.
final class AfskDemodulator: @unchecked Sendable {   // used from one thread at a time (the audio thread)
    private var slicer: ToneSlicer!
    private var prevRaw = 0
    private var ones = 0
    private var inFrame = false
    private var bits: [UInt8] = []
    private let onFrame: ([UInt8]) -> Void

    init(sampleRate: Double, onFrame: @escaping ([UInt8]) -> Void) {
        bits.reserveCapacity(2700)
        self.onFrame = onFrame
        slicer = ToneSlicer(sampleRate: sampleRate, baud: 1200, markHz: 1200, spaceHz: 2200) { [unowned self] in self.bit($0) }
    }

    /// Samples are floats in -1...1.
    func feed(_ samples: UnsafeBufferPointer<Float>) { for x in samples { slicer.step(Double(x)) } }
    func feed(_ samples: [Float]) { samples.withUnsafeBufferPointer { feed($0) } }
    /// Samples are signed 16-bit PCM.
    func feed(_ samples: [Int16]) { for x in samples { slicer.step(Double(x) / 32768) } }

    private func bit(_ raw: Int) {
        let b = raw == prevRaw ? 1 : 0   // NRZI: no change is a one
        prevRaw = raw
        if b == 1 {
            ones += 1
            if inFrame { bits.append(1) }
            if ones >= 7 { inFrame = false; bits.removeAll(keepingCapacity: true) }   // seven ones in a row is an abort
            return
        }
        switch ones {
        case 6:   // a flag: finish the frame in progress and start a new one
            if inFrame && bits.count >= 7 {
                bits.removeLast(7)   // the flag's six ones and the zero that opened it
                finish()
            }
            inFrame = true; bits.removeAll(keepingCapacity: true)
        case 5: break   // a stuffed zero: drop it
        default: if inFrame { bits.append(0) }
        }
        ones = 0
    }

    private func finish() {
        let n = bits.count
        guard n % 8 == 0, n >= 17 * 8, n <= 330 * 8 else { return }
        var data = [UInt8](repeating: 0, count: n / 8)
        for i in 0..<data.count { var v: UInt8 = 0; for j in 0..<8 { v |= bits[i * 8 + j] << UInt8(j) }; data[i] = v }
        var crc: UInt16 = 0xFFFF
        for byte in data {
            crc ^= UInt16(byte)
            for _ in 0..<8 { crc = crc & 1 != 0 ? (crc >> 1) ^ 0x8408 : crc >> 1 }
        }
        if crc == 0xF0B8 { onFrame(Array(data.dropLast(2))) }   // good CRC: hand over the frame without it
    }
}
