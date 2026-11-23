// Ft8Decoder.swift
// Pocket Nomad - Ham
// SPDX-License-Identifier: MIT
//
// A Swift port of the FT8 decoder in ft8_lib by Karlis Goba (https://github.com/kgoba/ft8_lib), MIT licence,
// Copyright (c) 2018 Karlis Goba. The algorithm, constants and tuning values follow that code; FT8 itself is by Joe Taylor, K1JT,
// Steve Franke, K9AN and Bill Somerville, G4WJS (the WSJT-X authors).
// Receive only. android/ham/.../Ft8Decoder.kt is the Android twin; keep them in step.

import Foundation

/// One decoded FT8 message. `snr` is the same rough estimate ft8_lib prints (half the sync score), not a calibrated SNR.
struct Ft8Message: Equatable, Sendable {
    var text: String
    var snr: Double
    var dtSeconds: Double
    var freqHz: Double
}

/// The sender's callsign and 4-character grid square when a message carries both (CQ calls and first replies).
func ft8Spot(_ text: String) -> (call: String, grid: String)? {
    let call = try! NSRegularExpression(pattern: "^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,3}[A-Z](?:/[A-Z0-9]{1,4})?$")
    let grid = try! NSRegularExpression(pattern: "^[A-R]{2}[0-9]{2}$")
    func m(_ r: NSRegularExpression, _ s: String) -> Bool { r.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil }
    let t = text.uppercased().split(separator: " ").map(String.init)
    if t.count >= 3, t[0] == "CQ", m(call, t[t.count - 2]), m(grid, t[t.count - 1]) { return (t[t.count - 2], t[t.count - 1]) }
    if t.count == 3, m(call, t[1]), m(grid, t[2]), t[2] != "RR73" { return (t[1], t[2]) }
    return nil
}

/// Decodes one 15 second FT8 slot from 12 kHz audio. Mono floats in -1...1, about 15 seconds long, starting near the slot's start.
/// The decoder keeps a table of callsigns it has seen so hashed callsigns in later messages show as names.
final class Ft8Decoder: @unchecked Sendable {
    static let sampleRate = 12000
    private static let symbolPeriod = 0.16
    private static let slotTime = 15.0
    private static let fMin = 200.0, fMax = 3000.0
    private static let timeOsr = 2, freqOsr = 2
    private static let minScore = 10, maxCandidates = 140, ldpcIterations = 25, maxDecoded = 50
    private static let n = 174, k = 91, m = 83
    private static let costas = [3, 1, 4, 0, 6, 5, 2]
    private static let gray = [0, 1, 3, 2, 5, 6, 4, 7]
    private static let crcPoly = 0x2757, crcWidth = 14
    private static let nTokens: Int64 = 2063592, max22: Int64 = 4194304
    private static let maxGrid4 = 32400
    private static let full = Array(" 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?")
    private static let alnumSpaceSlash = Array(" 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/")
    private static let alnumSpace = Array(" 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    private static let lettersSpace = Array(" ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    private static let alnum = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    private static let numeric = Array("0123456789")

    private let blockSize = Int(Double(sampleRate) * symbolPeriod)
    private let subblockSize: Int
    private let nfft: Int
    private let minBin = Int(fMin * symbolPeriod)
    private let maxBin = Int(fMax * symbolPeriod) + 1
    private let numBins: Int
    private let maxBlocks = Int(slotTime / symbolPeriod)
    private let blockStride: Int
    private let window: [Float]
    private var callsigns: [(hash: Int64, call: String)] = []   // 22-bit hash and callsign, oldest first

    private struct Waterfall { var mag: [UInt8]; var numBlocks: Int }
    private struct Candidate { var score: Int; var timeOffset: Int; var freqOffset: Int; var timeSub: Int; var freqSub: Int }

    init() {
        subblockSize = blockSize / Self.timeOsr
        nfft = blockSize * Self.freqOsr
        numBins = maxBin - minBin
        blockStride = Self.timeOsr * Self.freqOsr * numBins
        let nf = blockSize * Self.freqOsr
        window = (0..<nf).map { i in let x = Float(sin(Double.pi * Double(i) / Double(nf))); return (2 / Float(nf)) * x * x }
    }

    func decode(_ signal: [Float]) -> [Ft8Message] {
        let wf = buildWaterfall(signal)
        let cands = findCandidates(wf)
        var seen = Set<[UInt8]>()
        var out: [Ft8Message] = []
        for c in cands {
            guard let payload = decodeCandidate(wf, c) else { continue }
            if !seen.insert(payload).inserted || seen.count > Self.maxDecoded { continue }
            guard let text = unpack(payload) else { continue }
            let freq = (Double(minBin + c.freqOffset) + Double(c.freqSub) / Double(Self.freqOsr)) / Self.symbolPeriod
            let time = (Double(c.timeOffset) + Double(c.timeSub) / Double(Self.timeOsr)) * Self.symbolPeriod
            out.append(Ft8Message(text: text, snr: Double(c.score) * 0.5, dtSeconds: time, freqHz: freq))
        }
        if callsigns.count > 512 { callsigns.removeFirst(callsigns.count - 512) }
        return out
    }

    // MARK: waterfall

    private func buildWaterfall(_ signal: [Float]) -> Waterfall {
        var mag = [UInt8](repeating: 0, count: maxBlocks * blockStride)
        var last = [Float](repeating: 0, count: nfft)
        var re = [Double](repeating: 0, count: nfft), im = [Double](repeating: 0, count: nfft)
        var block = 0, framePos = 0
        while framePos + blockSize <= signal.count && block < maxBlocks {
            var offset = block * blockStride
            var fp = framePos
            for _ in 0..<Self.timeOsr {
                for i in 0..<(nfft - subblockSize) { last[i] = last[i + subblockSize] }
                for pos in (nfft - subblockSize)..<nfft { last[pos] = signal[fp]; fp += 1 }
                for i in 0..<nfft { re[i] = Double(window[i] * last[i]); im[i] = 0 }
                Ft8FFT.mixedRadix(&re, &im)
                for freqSub in 0..<Self.freqOsr {
                    for bin in minBin..<maxBin {
                        let src = bin * Self.freqOsr + freqSub
                        let mag2 = re[src] * re[src] + im[src] * im[src]
                        let db = 10.0 * log10(1e-12 + mag2)
                        let scaled = Int(2 * db + 240)
                        mag[offset] = UInt8(scaled < 0 ? 0 : (scaled > 255 ? 255 : scaled))
                        offset += 1
                    }
                }
            }
            block += 1
            framePos += blockSize
        }
        return Waterfall(mag: mag, numBlocks: block)
    }

    private func candBase(_ c: Candidate) -> Int {
        var offset = c.timeOffset
        offset = offset * Self.timeOsr + c.timeSub
        offset = offset * Self.freqOsr + c.freqSub
        return offset * numBins + c.freqOffset
    }

    private func syncScore(_ wf: Waterfall, _ c: Candidate) -> Int {
        var score = 0, numAverage = 0
        let base = candBase(c)
        for m in 0..<3 {
            for k in 0..<7 {
                let block = 36 * m + k
                let blockAbs = c.timeOffset + block
                if blockAbs < 0 { continue }
                if blockAbs >= wf.numBlocks { break }
                let p8 = base + block * blockStride
                let sm = Self.costas[k]
                if sm > 0 { score += Int(wf.mag[p8 + sm]) - Int(wf.mag[p8 + sm - 1]); numAverage += 1 }
                if sm < 7 { score += Int(wf.mag[p8 + sm]) - Int(wf.mag[p8 + sm + 1]); numAverage += 1 }
                if k > 0 && blockAbs > 0 { score += Int(wf.mag[p8 + sm]) - Int(wf.mag[p8 + sm - blockStride]); numAverage += 1 }
                if k + 1 < 7 && blockAbs + 1 < wf.numBlocks { score += Int(wf.mag[p8 + sm]) - Int(wf.mag[p8 + sm + blockStride]); numAverage += 1 }
            }
        }
        if numAverage > 0 { score /= numAverage }   // Swift's integer division truncates toward zero, like C
        return score
    }

    private func findCandidates(_ wf: Waterfall) -> [Candidate] {
        var heap: [Candidate] = []
        for timeSub in 0..<Self.timeOsr {
            for freqSub in 0..<Self.freqOsr {
                for timeOffset in -10..<20 {
                    var freqOffset = 0
                    while freqOffset + 7 < numBins {
                        var cand = Candidate(score: 0, timeOffset: timeOffset, freqOffset: freqOffset, timeSub: timeSub, freqSub: freqSub)
                        cand.score = syncScore(wf, cand)
                        freqOffset += 1
                        if cand.score < Self.minScore { continue }
                        if heap.count == Self.maxCandidates && cand.score > heap[0].score {
                            heap[0] = heap[heap.count - 1]; heap.removeLast()
                            heapifyDown(&heap, heap.count)
                        }
                        if heap.count < Self.maxCandidates { heap.append(cand); heapifyUp(&heap, heap.count) }
                    }
                }
            }
        }
        var unsorted = heap.count
        while unsorted > 1 {
            heap.swapAt(unsorted - 1, 0)
            unsorted -= 1
            heapifyDown(&heap, unsorted)
        }
        return heap
    }

    private func heapifyDown(_ heap: inout [Candidate], _ size: Int) {
        var current = 0
        while true {
            let left = 2 * current + 1, right = left + 1
            var smallest = current
            if left < size && heap[left].score < heap[smallest].score { smallest = left }
            if right < size && heap[right].score < heap[smallest].score { smallest = right }
            if smallest == current { break }
            heap.swapAt(smallest, current)
            current = smallest
        }
    }

    private func heapifyUp(_ heap: inout [Candidate], _ size: Int) {
        var current = size - 1
        while current > 0 {
            let parent = (current - 1) / 2
            if !(heap[current].score < heap[parent].score) { break }
            heap.swapAt(parent, current)
            current = parent
        }
    }

    // MARK: soft decisions and LDPC

    private func max4(_ a: Float, _ b: Float, _ c: Float, _ d: Float) -> Float { max(max(a, b), max(c, d)) }

    private func extractLikelihood(_ wf: Waterfall, _ c: Candidate) -> [Float] {
        var log174 = [Float](repeating: 0, count: Self.n)
        let base = candBase(c)
        for k in 0..<58 {
            let symIdx = k + (k < 29 ? 7 : 14)
            let bitIdx = 3 * k
            let block = c.timeOffset + symIdx
            if block < 0 || block >= wf.numBlocks { continue }
            let p = base + symIdx * blockStride
            var s2 = [Float](repeating: 0, count: 8)
            for j in 0..<8 { s2[j] = Float(wf.mag[p + Self.gray[j]]) * 0.5 - 120 }
            log174[bitIdx] = max4(s2[4], s2[5], s2[6], s2[7]) - max4(s2[0], s2[1], s2[2], s2[3])
            log174[bitIdx + 1] = max4(s2[2], s2[3], s2[6], s2[7]) - max4(s2[0], s2[1], s2[4], s2[5])
            log174[bitIdx + 2] = max4(s2[1], s2[3], s2[5], s2[7]) - max4(s2[0], s2[2], s2[4], s2[6])
        }
        return log174
    }

    private func normalize(_ l: inout [Float]) {
        var sum: Float = 0, sum2: Float = 0
        for v in l { sum += v; sum2 += v * v }
        let invN: Float = 1 / Float(Self.n)
        let variance = (sum2 - sum * sum * invN) * invN
        if variance <= 0 { return }
        let f = (24 / variance).squareRoot()
        for i in 0..<l.count { l[i] *= f }
    }

    private func fastTanh(_ x: Float) -> Float {
        if x < -4.97 { return -1 }
        if x > 4.97 { return 1 }
        let x2 = x * x
        let a = x * (945 + x2 * (105 + x2))
        let b = 945 + x2 * (420 + x2 * 15)
        return a / b
    }

    private func fastAtanh(_ x: Float) -> Float {
        let x2 = x * x
        let a = x * (945 + x2 * (-735 + x2 * 64))
        let b = 945 + x2 * (-1050 + x2 * 225)
        return a / b
    }

    private func ldpcCheck(_ cw: [Int]) -> Int {
        var errors = 0
        for m in 0..<Self.m {
            var x = 0
            for i in 0..<Ft8Constants.numRows[m] { x ^= cw[Ft8Constants.nm[m * 7 + i] - 1] }
            if x != 0 { errors += 1 }
        }
        return errors
    }

    /// Belief propagation. Returns the number of unsatisfied parity checks (0 = a valid codeword) and fills `plain`.
    private func bpDecode(_ codeword: [Float], _ maxIters: Int, _ plain: inout [Int]) -> Int {
        var tov = [[Float]](repeating: [0, 0, 0], count: Self.n)
        var toc = [[Float]](repeating: [Float](repeating: 0, count: 7), count: Self.m)
        var minErrors = Self.m
        for _ in 0..<maxIters {
            var plainSum = 0
            for n in 0..<Self.n { plain[n] = (codeword[n] + tov[n][0] + tov[n][1] + tov[n][2]) > 0 ? 1 : 0; plainSum += plain[n] }
            if plainSum == 0 { break }
            let errors = ldpcCheck(plain)
            if errors < minErrors { minErrors = errors; if errors == 0 { break } }
            for m in 0..<Self.m {
                for nIdx in 0..<Ft8Constants.numRows[m] {
                    let n = Ft8Constants.nm[m * 7 + nIdx] - 1
                    var tnm = codeword[n]
                    for mIdx in 0..<3 where Ft8Constants.mn[n * 3 + mIdx] - 1 != m { tnm += tov[n][mIdx] }
                    toc[m][nIdx] = fastTanh(-tnm / 2)
                }
            }
            for n in 0..<Self.n {
                for mIdx in 0..<3 {
                    let m = Ft8Constants.mn[n * 3 + mIdx] - 1
                    var tmn: Float = 1
                    for nIdx in 0..<Ft8Constants.numRows[m] where Ft8Constants.nm[m * 7 + nIdx] - 1 != n { tmn *= toc[m][nIdx] }
                    tov[n][mIdx] = -2 * fastAtanh(tmn)
                }
            }
        }
        return minErrors
    }

    private func crc14(_ msg: [UInt8], _ numBits: Int) -> Int {
        let topBit = 1 << (Self.crcWidth - 1)
        var remainder = 0, idxByte = 0
        for idxBit in 0..<numBits {
            if idxBit % 8 == 0 { remainder ^= Int(msg[idxByte]) << (Self.crcWidth - 8); idxByte += 1 }
            remainder = remainder & topBit != 0 ? (remainder << 1) ^ Self.crcPoly : remainder << 1
        }
        return remainder & ((topBit << 1) - 1)
    }

    /// The 10 payload bytes (77 bits) of a candidate whose LDPC and CRC both check out, or nil.
    private func decodeCandidate(_ wf: Waterfall, _ c: Candidate) -> [UInt8]? {
        var log174 = extractLikelihood(wf, c)
        normalize(&log174)
        var plain = [Int](repeating: 0, count: Self.n)
        if bpDecode(log174, Self.ldpcIterations, &plain) > 0 { return nil }
        var a91 = [UInt8](repeating: 0, count: 12)
        for i in 0..<Self.k where plain[i] != 0 { a91[i / 8] |= UInt8(0x80 >> (i % 8)) }
        let extracted = ((Int(a91[9]) & 0x07) << 11) | (Int(a91[10]) << 3) | (Int(a91[11]) >> 5)
        a91[9] &= 0xF8
        a91[10] = 0
        if extracted != crc14(a91, 96 - 14) { return nil }
        return Array(a91[0..<10])
    }

    // MARK: unpacking the 77-bit message

    private func unpack(_ p: [UInt8]) -> String? {
        let i3 = (Int(p[9]) >> 3) & 0x07
        let n3 = ((Int(p[8]) << 2) & 0x04) | ((Int(p[9]) >> 6) & 0x03)
        switch i3 {
        case 0: return n3 == 0 ? freeText(p) : (n3 == 5 ? telemetry(p) : nil)
        case 1, 2: return standard(p, i3)
        case 4: return nonStandard(p)
        default: return nil
        }
    }

    private func charAt(_ table: [Character], _ i: Int) -> Character { table.indices.contains(i) ? table[i] : " " }

    private func lookup(bits: Int, value: Int64) -> String {
        for (k, call) in callsigns {
            let hit: Bool
            switch bits { case 22: hit = k == value; case 12: hit = (k >> 10) == value; default: hit = (k >> 12) == value }
            if hit { return "<\(call)>" }
        }
        return "<...>"
    }

    private func save(_ callsign: String) {
        var n58: Int64 = 0
        let chars = Array(callsign.prefix(11))
        for ch in chars {
            guard let j = Self.alnumSpaceSlash.firstIndex(of: ch) else { return }
            n58 = 38 &* n58 &+ Int64(j)
        }
        for _ in chars.count..<11 { n58 = n58 &* 38 }
        let prod = UInt64(bitPattern: 47055833459 &* n58)
        let n22 = Int64((prod >> 42) & 0x3FFFFF)
        if let i = callsigns.firstIndex(where: { $0.hash == n22 }) { callsigns[i].call = callsign } else { callsigns.append((n22, callsign)) }
    }

    private func unpack28(_ n28In: Int64, _ ip: Int, _ i3: Int) -> String? {
        var n28 = n28In
        if n28 < Self.nTokens {
            if n28 <= 2 { return n28 == 0 ? "DE" : (n28 == 1 ? "QRZ" : "CQ") }
            if n28 <= 1002 { return "CQ " + String(format: "%03d", Int(n28 - 3)) }
            if n28 <= 532443 {
                var n = n28 - 1003
                var aaaa = [Character](repeating: " ", count: 4)
                for i in stride(from: 3, through: 0, by: -1) { aaaa[i] = charAt(Self.lettersSpace, Int(n % 27)); n /= 27 }
                return "CQ " + String(aaaa).trimmingCharacters(in: .init(charactersIn: " "))
            }
            return nil
        }
        n28 -= Self.nTokens
        if n28 < Self.max22 { return lookup(bits: 22, value: n28) }
        var n = n28 - Self.max22
        var c = [Character](repeating: " ", count: 6)
        c[5] = charAt(Self.lettersSpace, Int(n % 27)); n /= 27
        c[4] = charAt(Self.lettersSpace, Int(n % 27)); n /= 27
        c[3] = charAt(Self.lettersSpace, Int(n % 27)); n /= 27
        c[2] = charAt(Self.numeric, Int(n % 10)); n /= 10
        c[1] = charAt(Self.alnum, Int(n % 36)); n /= 36
        c[0] = charAt(Self.alnumSpace, Int(n % 37))
        let call = String(c)
        let blanks = CharacterSet(charactersIn: " ")
        var result: String
        if call.hasPrefix("3D0") && c[3] != " " { result = "3DA0" + String(call.dropFirst(3)).trimmingCharacters(in: blanks) }
        else if c[0] == "Q" && c[1].isLetter && c[1].isASCII { result = "3X" + String(call.dropFirst(1)).trimmingCharacters(in: blanks) }
        else { result = call.trimmingCharacters(in: blanks) }
        if result.count < 3 { return nil }
        if ip != 0 { if i3 == 1 { result += "/R" } else if i3 == 2 { result += "/P" } else { return nil } }
        save(result)
        return result
    }

    private func unpackGrid(_ igrid4: Int, _ ir: Int) -> String {
        if igrid4 <= Self.maxGrid4 {
            var n = igrid4
            let d3 = n % 10; n /= 10
            let d2 = n % 10; n /= 10
            let d1 = n % 18; n /= 18
            let d0 = n % 18
            let a = UnicodeScalar(UInt8(65 + d0)), b = UnicodeScalar(UInt8(65 + d1))
            return (ir > 0 ? "R " : "") + "\(Character(a))\(Character(b))\(d2)\(d3)"
        }
        let irpt = igrid4 - Self.maxGrid4
        switch irpt {
        case 1: return ""
        case 2: return "RRR"
        case 3: return "RR73"
        case 4: return "73"
        default:
            let v = irpt - 35
            return (ir > 0 ? "R" : "") + (v < 0 ? "-" : "+") + String(format: "%02d", abs(v))
        }
    }

    private func standard(_ p: [UInt8], _ i3: Int) -> String? {
        let b = p.map { Int64($0) }
        let n29a = (b[0] << 21) | (b[1] << 13) | (b[2] << 5) | (b[3] >> 3)
        let n29b = ((b[3] & 0x07) << 26) | (b[4] << 18) | (b[5] << 10) | (b[6] << 2) | (b[7] >> 6)
        let ir = Int((b[7] & 0x20) >> 5)
        let igrid4 = Int(((b[7] & 0x1F) << 10) | (b[8] << 2) | (b[9] >> 6))
        guard let to = unpack28(n29a >> 1, Int(n29a & 1), i3), let de = unpack28(n29b >> 1, Int(n29b & 1), i3) else { return nil }
        return [to, de, unpackGrid(igrid4, ir)].filter { !$0.isEmpty }.joined(separator: " ")
    }

    private func nonStandard(_ p: [UInt8]) -> String? {
        let b = p.map { Int64($0) }
        let n12 = (b[0] << 4) | (b[1] >> 4)
        var n58 = (b[1] & 0x0F) << 54
        n58 |= (b[2] << 46) | (b[3] << 38) | (b[4] << 30) | (b[5] << 22) | (b[6] << 14) | (b[7] << 6) | (b[8] >> 2)
        let iflip = (b[8] >> 1) & 1
        let nrpt = ((b[8] & 1) << 1) | (b[9] >> 7)
        let icq = (b[9] >> 6) & 1
        var chars = [Character](repeating: " ", count: 11)
        var n = n58
        for i in stride(from: 10, through: 0, by: -1) { chars[i] = charAt(Self.alnumSpaceSlash, Int(n % 38)); n /= 38 }
        let decoded = String(chars).trimmingCharacters(in: .init(charactersIn: " "))
        if decoded.count >= 3 { save(decoded) }
        let other = lookup(bits: 12, value: n12)
        let call1 = iflip == 1 ? decoded : other
        let call2 = iflip == 1 ? other : decoded
        if icq == 0 {
            let extra = nrpt == 1 ? "RRR" : (nrpt == 2 ? "RR73" : (nrpt == 3 ? "73" : ""))
            return [call1, call2, extra].filter { !$0.isEmpty }.joined(separator: " ")
        }
        return "CQ \(call2)"
    }

    private func shiftedPayload(_ p: [UInt8]) -> [Int] {
        var out = [Int](repeating: 0, count: 9)
        var carry = 0
        for i in 0..<9 { out[i] = (carry << 7) | (Int(p[i]) >> 1); carry = Int(p[i]) & 1 }
        return out
    }

    private func freeText(_ p: [UInt8]) -> String {
        var b71 = shiftedPayload(p)
        var c = [Character](repeating: " ", count: 13)
        for idx in stride(from: 12, through: 0, by: -1) {
            var rem = 0
            for i in 0..<9 { rem = (rem << 8) | b71[i]; b71[i] = rem / 42; rem %= 42 }
            c[idx] = charAt(Self.full, rem)
        }
        return String(c).trimmingCharacters(in: .whitespaces)
    }

    private func telemetry(_ p: [UInt8]) -> String { shiftedPayload(p).map { String(format: "%02X", $0) }.joined() }
}

/// Mixed-radix FFT for any length whose prime factors are small (FT8 uses 3840 = 2^8 x 3 x 5).
enum Ft8FFT {
    private nonisolated(unsafe) static var tableN = 0
    private nonisolated(unsafe) static var cosT: [Double] = []
    private nonisolated(unsafe) static var sinT: [Double] = []
    private static let lock = NSLock()

    static func mixedRadix(_ re: inout [Double], _ im: inout [Double]) {
        lock.lock(); defer { lock.unlock() }
        let n = re.count
        if tableN != n {
            cosT = (0..<n).map { cos(-2 * Double.pi * Double($0) / Double(n)) }
            sinT = (0..<n).map { sin(-2 * Double.pi * Double($0) / Double(n)) }
            tableN = n
        }
        var oRe = [Double](repeating: 0, count: n), oIm = [Double](repeating: 0, count: n)
        rec(re, im, 0, 1, n, &oRe, &oIm, 0, n)
        re = oRe; im = oIm
    }

    private static func smallestFactor(_ n: Int) -> Int { var p = 2; while p * p <= n { if n % p == 0 { return p }; p += 1 }; return n }

    private static func rec(_ re: [Double], _ im: [Double], _ start: Int, _ stride: Int, _ n: Int, _ oRe: inout [Double], _ oIm: inout [Double], _ outPos: Int, _ total: Int) {
        if n == 1 { oRe[outPos] = re[start]; oIm[outPos] = im[start]; return }
        let p = smallestFactor(n)
        let m = n / p
        for r in 0..<p { rec(re, im, start + r * stride, stride * p, m, &oRe, &oIm, outPos + r * m, total) }
        var tRe = [Double](repeating: 0, count: p), tIm = [Double](repeating: 0, count: p)
        let stepN = total / n, stepP = total / p
        for k in 0..<m {
            for r in 0..<p {
                let idx = (r * k * stepN) % total
                let c = cosT[idx], s = sinT[idx]
                let xr = oRe[outPos + r * m + k], xi = oIm[outPos + r * m + k]
                tRe[r] = xr * c - xi * s; tIm[r] = xr * s + xi * c
            }
            for q in 0..<p {
                var sr = 0.0, si = 0.0
                for r in 0..<p {
                    let idx = (r * q * stepP) % total
                    let c = cosT[idx], s = sinT[idx]
                    sr += tRe[r] * c - tIm[r] * s; si += tRe[r] * s + tIm[r] * c
                }
                oRe[outPos + q * m + k] = sr; oIm[outPos + q * m + k] = si
            }
        }
    }
}
