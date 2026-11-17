// LZHUF.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// LZHUF compression as Winlink's B2F protocol uses it: LZSS with adaptive
// Huffman coding (Okumura / Yoshizaki, 1988), with Winlink's header of a CRC16
// (XMODEM CRC-CCITT) and the uncompressed length, both little-endian.
//
// Ported from wl2k-go's lzhuf package (MIT, Martin Hebnes Pedersen LA5NTA),
// itself a port of JNOS 2's lzhuf.c ("use, distribute, and modify this program
// freely"). Byte-for-byte compatible with it; see NOTICE.

import Foundation

enum LZHUF {
    static let n = 2048
    static let f = 60
    static let threshold = 2
    static let nChar = 256 - threshold + f
    static let t = nChar * 2 - 1
    static let r = t - 1
    static let maxFreq: UInt = 0x8000
    static let nilNode = n

    enum Failure: Error, Equatable { case truncated, badChecksum, badLength }

    /// Winlink B2 form: CRC16, length, compressed data.
    static func compress(_ data: [UInt8]) -> [UInt8] {
        var w = Encoder()
        for b in data { w.write(b) }
        let body = w.finish()
        let size = UInt32(truncatingIfNeeded: data.count)
        let len: [UInt8] = [UInt8(size & 0xFF), UInt8(size >> 8 & 0xFF), UInt8(size >> 16 & 0xFF), UInt8(size >> 24 & 0xFF)]
        let sum = crc16(len + body)
        return [UInt8(sum & 0xFF), UInt8(sum >> 8)] + len + body
    }

    static func decompress(_ data: [UInt8]) throws -> [UInt8] {
        guard data.count >= 6 else { throw Failure.truncated }
        let crc = UInt16(data[0]) | UInt16(data[1]) << 8
        guard crc16(Array(data[2...])) == crc else { throw Failure.badChecksum }
        let size = Int(UInt32(data[2]) | UInt32(data[3]) << 8 | UInt32(data[4]) << 16 | UInt32(data[5]) << 24)
        guard size < 64 << 20 else { throw Failure.badLength }
        var d = Decoder(bytes: data, start: 6)
        var out: [UInt8] = []
        out.reserveCapacity(size)
        while out.count < size {
            guard try d.next(into: &out, limit: size) else { throw Failure.truncated }
        }
        return out
    }

    // MARK: Shared adaptive Huffman tree

    struct Tree {
        var freq = [UInt](repeating: 0, count: LZHUF.t + 1)
        var prnt = [Int](repeating: 0, count: LZHUF.t + LZHUF.nChar)
        var son = [Int](repeating: 0, count: LZHUF.t)

        init() {
            for i in 0..<nChar {
                freq[i] = 1
                son[i] = i + t
                prnt[i + t] = i
            }
            var i = 0, j = nChar
            while j <= r {
                freq[j] = freq[i] + freq[i + 1]
                son[j] = i
                prnt[i] = j
                prnt[i + 1] = j
                i += 2
                j += 1
            }
            freq[t] = 0xFFFF
            prnt[r] = 0
        }

        mutating func reconst() {
            var j = 0
            for i in 0..<t where son[i] >= t {
                freq[j] = (freq[i] + 1) / 2
                son[j] = son[i]
                j += 1
            }
            var i = 0
            j = nChar
            while j < t {
                var k = i + 1
                freq[j] = freq[i] + freq[k]
                let first = freq[j]
                k = j
                while first < freq[k - 1] { k -= 1 }
                let last = j - k
                if last > 0 {
                    for m in stride(from: k + last - 1, through: k, by: -1) {
                        freq[m + 1] = freq[m]
                        son[m + 1] = son[m]
                    }
                }
                freq[k] = first
                son[k] = i
                i += 2
                j += 1
            }
            for i in 0..<t {
                let k = son[i]
                if k >= t {
                    prnt[k] = i
                } else {
                    prnt[k] = i
                    prnt[k + 1] = i
                }
            }
        }

        mutating func update(_ c0: Int) {
            if freq[r] == maxFreq { reconst() }
            var c = prnt[c0 + t]
            while true {
                freq[c] += 1
                if freq[c] <= freq[c + 1] || freq.count <= c + 2 {
                    c = prnt[c]
                    if c == 0 { break }
                    continue
                }
                var l = c + 1
                let k = freq[c]
                while k > freq[l + 1] { l += 1 }
                freq[c] = freq[l]
                freq[l] = k

                let i = son[c]
                prnt[i] = l
                if i < t { prnt[i + 1] = l }
                let j = son[l]
                son[l] = i
                prnt[j] = c
                if j < t { prnt[j + 1] = c }
                son[c] = j

                c = prnt[l]
                if c == 0 { break }
            }
        }
    }

    // MARK: Decoder

    struct Decoder {
        var tree = Tree()
        var text: [UInt8] = [UInt8](repeating: 0x20, count: LZHUF.n - LZHUF.f) + [UInt8](repeating: 0, count: 2 * LZHUF.f - 1)
        var pos: Int
        let bytes: [UInt8]
        var bitBuf: UInt64 = 0
        var bitCount: UInt = 0
        var r = LZHUF.n - LZHUF.r

        init(bytes: [UInt8], start: Int) {
            self.bytes = bytes
            self.pos = start
        }

        mutating func bits(_ n: UInt) throws -> Int {
            while n > bitCount {
                guard pos < bytes.count else { throw Failure.truncated }
                bitBuf = bitBuf << 8 | UInt64(bytes[pos])
                pos += 1
                bitCount += 8
            }
            let v = (bitBuf >> (bitCount - n)) & ((1 << n) - 1)
            bitCount -= n
            return Int(v)
        }

        mutating func decodeChar() throws -> Int {
            var c = tree.son[LZHUF.r]
            while c < LZHUF.t {
                c += try bits(1)
                c = tree.son[c]
            }
            c -= LZHUF.t
            tree.update(c)
            return c
        }

        mutating func decodePosition() throws -> Int {
            var i = try bits(8)
            let c = Int(dCode[i]) << 6
            var j = Int(dLen[i]) - 2
            while j > 0 {
                i = (i << 1) + (try bits(1))
                j -= 1
            }
            return c | (i & 0x3F)
        }

        /// Decodes one symbol into `out`. False at the end of the input.
        mutating func next(into out: inout [UInt8], limit: Int) throws -> Bool {
            let c = try decodeChar()
            if c < 256 {
                out.append(UInt8(c))
                text[r] = UInt8(c)
                r = (r + 1) & (LZHUF.n - 1)
                return true
            }
            let i = (r - (try decodePosition()) - 1) & (LZHUF.n - 1)
            let j = c - 255 + LZHUF.threshold
            for k in 0..<j {
                let b = text[(i + k) & (LZHUF.n - 1)]
                if out.count < limit { out.append(b) }
                text[r] = b
                r = (r + 1) & (LZHUF.n - 1)
            }
            return true
        }
    }

    // MARK: Encoder

    struct Encoder {
        var tree = Tree()
        var text = [UInt8](repeating: 0, count: LZHUF.n + LZHUF.f - 1)
        var dad = [Int](repeating: LZHUF.nilNode, count: LZHUF.n + 1)
        var lson = [Int](repeating: 0, count: LZHUF.n + 1)
        var rson = [Int](repeating: 0, count: LZHUF.n + 257)
        var matchLength = 0
        var matchPosition = 0
        var out: [UInt8] = []
        var putbuf: UInt64 = 0
        var putlen: UInt = 0
        var len = 0
        var r = LZHUF.n - LZHUF.f
        var s = 0
        var lastMatchLength = 0
        var preFilled = false

        init() {
            for i in (LZHUF.n + 1)...(LZHUF.n + 256) { rson[i] = LZHUF.nilNode }
            for i in 0..<r { text[i] = 0x20 }
        }

        mutating func write(_ b: UInt8) {
            if !preFilled {
                text[r + len] = b
                len += 1
                insertNode(r - len)
                lastMatchLength = 1
                preFilled = len == LZHUF.f
                return
            }
            advance(b)
        }

        mutating func finish() -> [UInt8] {
            while len > 0 { advance(nil) }
            encode()
            if putlen > 0 { out.append(UInt8(truncatingIfNeeded: putbuf >> 8)) }
            return out
        }

        mutating func advance(_ c: UInt8?) {
            if let c {
                text[s] = c
                if s < LZHUF.f - 1 { text[s + LZHUF.n] = c }
                len += 1
            }
            insertNode(r)
            lastMatchLength -= 1
            if lastMatchLength == 0 { encode() }
            deleteNode(s)
            s = (s + 1) & (LZHUF.n - 1)
            r = (r + 1) & (LZHUF.n - 1)
            len -= 1
        }

        mutating func encode() {
            guard len > 0 else { return }
            if matchLength > len { matchLength = len }
            if matchLength <= LZHUF.threshold {
                matchLength = 1
                encodeChar(Int(text[r]))
            } else {
                encodeChar(255 - LZHUF.threshold + matchLength)
                encodePosition(matchPosition)
            }
            lastMatchLength = matchLength
        }

        mutating func encodeChar(_ c: Int) {
            var i: UInt64 = 0
            var j = 0
            var k = tree.prnt[c + LZHUF.t]
            repeat {
                i >>= 1
                j += 1
                if k & 1 != 0 { i += 0x8000 }
                k = tree.prnt[k]
            } while k != LZHUF.r
            putCode(j, i)
            tree.update(c)
        }

        mutating func encodePosition(_ c: Int) {
            let i = c >> 6
            putCode(Int(pLen[i]), UInt64(pCode[i]) << 8)
            putCode(6, UInt64(c & 0x3F) << 10)
        }

        mutating func putCode(_ l: Int, _ c: UInt64) {
            putbuf |= c >> putlen
            putlen += UInt(l)
            guard putlen >= 8 else { return }
            out.append(UInt8(truncatingIfNeeded: putbuf >> 8))
            putlen -= 8
            if putlen >= 8 {
                out.append(UInt8(truncatingIfNeeded: putbuf))
                putlen -= 8
                putbuf = c << UInt64(UInt(l) - putlen)
            } else {
                putbuf <<= 8
            }
        }

        mutating func insertNode(_ r: Int) {
            var cmp = 1
            var p = LZHUF.n + 1 + Int(text[r])
            rson[r] = LZHUF.nilNode
            lson[r] = LZHUF.nilNode
            matchLength = 0
            while true {
                if cmp >= 0 {
                    if rson[p] != LZHUF.nilNode { p = rson[p] } else { rson[p] = r; dad[r] = p; return }
                } else {
                    if lson[p] != LZHUF.nilNode { p = lson[p] } else { lson[p] = r; dad[r] = p; return }
                }
                var i = 1
                while i < LZHUF.f {
                    cmp = Int(text[r + i]) - Int(text[p + i])
                    if cmp != 0 { break }
                    i += 1
                }
                if i > LZHUF.threshold {
                    if i > matchLength {
                        matchPosition = ((r - p) & (LZHUF.n - 1)) - 1
                        matchLength = i
                        if matchLength >= LZHUF.f { break }
                    }
                    if i == matchLength {
                        let c = ((r - p) & (LZHUF.n - 1)) - 1
                        if c < matchPosition { matchPosition = c }
                    }
                }
            }
            dad[r] = dad[p]
            lson[r] = lson[p]
            rson[r] = rson[p]
            dad[lson[p]] = r
            dad[rson[p]] = r
            if rson[dad[p]] == p { rson[dad[p]] = r } else { lson[dad[p]] = r }
            dad[p] = LZHUF.nilNode
        }

        mutating func deleteNode(_ p: Int) {
            guard dad[p] != LZHUF.nilNode else { return }
            var q: Int
            if rson[p] == LZHUF.nilNode {
                q = lson[p]
            } else if lson[p] == LZHUF.nilNode {
                q = rson[p]
            } else {
                q = lson[p]
                if rson[q] != LZHUF.nilNode {
                    while rson[q] != LZHUF.nilNode { q = rson[q] }
                    rson[dad[q]] = lson[q]
                    dad[lson[q]] = dad[q]
                    lson[q] = lson[p]
                    dad[lson[p]] = q
                }
                rson[q] = rson[p]
                dad[rson[p]] = q
            }
            dad[q] = dad[p]
            if rson[dad[p]] == p { rson[dad[p]] = q } else { lson[dad[p]] = q }
            dad[p] = LZHUF.nilNode
        }
    }

    // MARK: CRC (XMODEM CRC-CCITT, then two zero bytes, as Winlink does)

    static func crc16(_ bytes: [UInt8]) -> UInt16 {
        var sum: UInt16 = 0
        for c in bytes + [0, 0] {
            sum = (sum << 8) ^ crcTable[Int(sum >> 8)] ^ UInt16(c)
        }
        return sum
    }

    static let crcTable: [UInt16] = (0..<256).map { i -> UInt16 in
        var crc = UInt16(i) << 8
        for _ in 0..<8 { crc = crc & 0x8000 != 0 ? (crc << 1) ^ 0x1021 : crc << 1 }
        return crc
    }

    static let pCode: [UInt8] = [
        0x00, 0x20, 0x30, 0x40, 0x50, 0x58, 0x60, 0x68, 0x70, 0x78, 0x80, 0x88, 0x90, 0x94, 0x98, 0x9C,
        0xA0, 0xA4, 0xA8, 0xAC, 0xB0, 0xB4, 0xB8, 0xBC, 0xC0, 0xC2, 0xC4, 0xC6, 0xC8, 0xCA, 0xCC, 0xCE,
        0xD0, 0xD2, 0xD4, 0xD6, 0xD8, 0xDA, 0xDC, 0xDE, 0xE0, 0xE2, 0xE4, 0xE6, 0xE8, 0xEA, 0xEC, 0xEE,
        0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA, 0xFB, 0xFC, 0xFD, 0xFE, 0xFF,
    ]
    static let pLen: [UInt8] = [3] + [UInt8](repeating: 4, count: 3) + [UInt8](repeating: 5, count: 8)
        + [UInt8](repeating: 6, count: 12) + [UInt8](repeating: 7, count: 24) + [UInt8](repeating: 8, count: 16)

    /// Built from pCode/pLen, as lzhuf.c's d_code/d_len tables are.
    static let (dCode, dLen): ([UInt8], [UInt8]) = {
        var code = [UInt8](repeating: 0, count: 256)
        var len = [UInt8](repeating: 0, count: 256)
        for i in 0..<64 {
            let l = Int(pLen[i])
            let start = Int(pCode[i])
            let span = 1 << (8 - l)
            for k in 0..<span {
                code[start + k] = UInt8(i)
                len[start + k] = UInt8(l)
            }
        }
        return (code, len)
    }()
}
