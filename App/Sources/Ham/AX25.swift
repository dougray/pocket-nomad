// AX25.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// AX.25 v2.0 frames: addresses (callsign + SSID), the digipeater path, and the
// control field. The TNC adds and checks the FCS, so frames here have none.
// Spec: "AX.25 Link Access Protocol for Amateur Packet Radio" v2.2 (TAPR).

import Foundation

/// A callsign with SSID, e.g. KF0ABC-7. `repeated` is the H bit on a digipeater.
struct AX25Address: Hashable, Sendable, CustomStringConvertible {
    var call: String
    var ssid: UInt8 = 0
    var repeated = false

    init(call: String, ssid: UInt8 = 0, repeated: Bool = false) {
        self.call = call.uppercased()
        self.ssid = ssid & 0x0F
        self.repeated = repeated
    }

    /// Parses "CALL", "CALL-12" or "CALL*" (a used digipeater). Nil if not a valid AX.25 address.
    init?(_ text: String) {
        var t = text.trimmingCharacters(in: .whitespaces).uppercased()
        var rep = false
        if t.hasSuffix("*") { rep = true; t.removeLast() }
        let parts = t.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count <= 2, let c = parts.first, (1...6).contains(c.count),
              c.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return nil }
        var ssid: UInt8 = 0
        if parts.count == 2 {
            guard let n = UInt8(parts[1]), n <= 15 else { return nil }
            ssid = n
        }
        self.init(call: String(c), ssid: ssid, repeated: rep)
    }

    var description: String { ssid == 0 ? call : "\(call)-\(ssid)" }
    /// The same station ignoring the H bit.
    var base: AX25Address { AX25Address(call: call, ssid: ssid) }

    func encoded(last: Bool, cBit: Bool) -> [UInt8] {
        var out = [UInt8](repeating: 0x40, count: 7) // spaces shifted left
        for (i, ch) in call.utf8.prefix(6).enumerated() { out[i] = ch << 1 }
        // 0x60: the two reserved bits, set to 1.
        out[6] = 0x60 | (ssid << 1) | (cBit ? 0x80 : 0) | (last ? 0x01 : 0)
        return out
    }

    static func decode(_ b: ArraySlice<UInt8>) -> (AX25Address, cBit: Bool, last: Bool)? {
        guard b.count >= 7 else { return nil }
        let s = b.startIndex
        var call = ""
        for i in 0..<6 {
            let ch = b[s + i] >> 1
            guard ch == 0x20 || (ch >= 0x30 && ch <= 0x39) || (ch >= 0x41 && ch <= 0x5A) else { return nil }
            if ch != 0x20 { call.append(Character(UnicodeScalar(ch))) }
        }
        guard !call.isEmpty else { return nil }
        let x = b[s + 6]
        return (AX25Address(call: call, ssid: (x >> 1) & 0x0F, repeated: x & 0x80 != 0), x & 0x80 != 0, x & 0x01 != 0)
    }
}

enum AX25Control: Equatable, Sendable {
    case i(ns: UInt8, nr: UInt8, poll: Bool)
    case rr(nr: UInt8, pf: Bool)
    case rnr(nr: UInt8, pf: Bool)
    case rej(nr: UInt8, pf: Bool)
    case sabm(poll: Bool)
    case disc(poll: Bool)
    case ua(final: Bool)
    case dm(final: Bool)
    case frmr(final: Bool)
    case ui(poll: Bool)
    case unknown(UInt8)

    var byte: UInt8 {
        func pf(_ b: Bool) -> UInt8 { b ? 0x10 : 0 }
        switch self {
        case let .i(ns, nr, p): return (nr & 7) << 5 | pf(p) | (ns & 7) << 1
        case let .rr(nr, p): return (nr & 7) << 5 | pf(p) | 0x01
        case let .rnr(nr, p): return (nr & 7) << 5 | pf(p) | 0x05
        case let .rej(nr, p): return (nr & 7) << 5 | pf(p) | 0x09
        case let .sabm(p): return 0x2F | pf(p)
        case let .disc(p): return 0x43 | pf(p)
        case let .ua(f): return 0x63 | pf(f)
        case let .dm(f): return 0x0F | pf(f)
        case let .frmr(f): return 0x87 | pf(f)
        case let .ui(p): return 0x03 | pf(p)
        case let .unknown(b): return b
        }
    }

    init(byte c: UInt8) {
        let pf = c & 0x10 != 0
        if c & 0x01 == 0 {
            self = .i(ns: (c >> 1) & 7, nr: c >> 5, poll: pf)
        } else if c & 0x03 == 0x01 {
            switch c & 0x0F {
            case 0x01: self = .rr(nr: c >> 5, pf: pf)
            case 0x05: self = .rnr(nr: c >> 5, pf: pf)
            case 0x09: self = .rej(nr: c >> 5, pf: pf)
            default: self = .unknown(c)
            }
        } else {
            switch c & ~0x10 {
            case 0x2F: self = .sabm(poll: pf)
            case 0x43: self = .disc(poll: pf)
            case 0x63: self = .ua(final: pf)
            case 0x0F: self = .dm(final: pf)
            case 0x87: self = .frmr(final: pf)
            case 0x03: self = .ui(poll: pf)
            default: self = .unknown(c)
            }
        }
    }

    var hasInfo: Bool {
        switch self {
        case .i, .ui: true
        default: false
        }
    }

    var name: String {
        switch self {
        case let .i(ns, nr, p): "I\(ns)\(nr)\(p ? "P" : "")"
        case let .rr(nr, p): "RR\(nr)\(p ? "P/F" : "")"
        case let .rnr(nr, p): "RNR\(nr)\(p ? "P/F" : "")"
        case let .rej(nr, p): "REJ\(nr)\(p ? "P/F" : "")"
        case let .sabm(p): "SABM\(p ? "P" : "")"
        case let .disc(p): "DISC\(p ? "P" : "")"
        case let .ua(f): "UA\(f ? "F" : "")"
        case let .dm(f): "DM\(f ? "F" : "")"
        case let .frmr(f): "FRMR\(f ? "F" : "")"
        case let .ui(p): "UI\(p ? "P" : "")"
        case let .unknown(b): String(format: "?%02X", b)
        }
    }
}

struct AX25Frame: Equatable, Sendable {
    var destination: AX25Address
    var source: AX25Address
    var path: [AX25Address] = []
    /// Command (dest C=1, source C=0) or response (the reverse). AX.25 v2.
    var isCommand = true
    var control: AX25Control
    var pid: UInt8 = 0xF0
    var info: [UInt8] = []

    static let noLayer3: UInt8 = 0xF0

    func encoded() -> [UInt8] {
        var out = destination.encoded(last: false, cBit: isCommand)
        out += source.encoded(last: path.isEmpty, cBit: !isCommand)
        for (i, d) in path.enumerated() {
            out += d.encoded(last: i == path.count - 1, cBit: d.repeated)
        }
        out.append(control.byte)
        if control.hasInfo {
            out.append(pid)
            out += info
        }
        return out
    }

    static func decode(_ bytes: [UInt8]) -> AX25Frame? {
        guard bytes.count >= 15, let (dest, dC, dLast) = AX25Address.decode(bytes[0..<7]), !dLast,
              let (src, sC, sLast) = AX25Address.decode(bytes[7..<14]) else { return nil }
        var i = 14
        var last = sLast
        var path: [AX25Address] = []
        while !last {
            guard i + 7 <= bytes.count, path.count < 8,
                  let (d, _, l) = AX25Address.decode(bytes[i..<(i + 7)]) else { return nil }
            path.append(d)
            last = l
            i += 7
        }
        guard i < bytes.count else { return nil }
        let control = AX25Control(byte: bytes[i])
        i += 1
        var pid: UInt8 = 0
        var info: [UInt8] = []
        if control.hasInfo {
            guard i < bytes.count else { return nil }
            pid = bytes[i]
            info = Array(bytes[(i + 1)...])
        }
        // C bits 10 = command, 01 = response; 00/11 = AX.25 v1, treat as command.
        let command = dC || !sC
        return AX25Frame(destination: AX25Address(call: dest.call, ssid: dest.ssid),
                         source: AX25Address(call: src.call, ssid: src.ssid),
                         path: path, isCommand: command, control: control, pid: pid, info: info)
    }

    /// TNC2 monitor format: SRC>DEST,DIGI*,DIGI:info
    var monitorLine: String {
        var head = "\(source)>\(destination)"
        for d in path { head += ",\(d)\(d.repeated ? "*" : "")" }
        if case .ui = control {
            return head + ":" + AX25Frame.printable(info)
        }
        var line = head + " <\(control.name)\(isCommand ? " C" : " R")>"
        if control.hasInfo, !info.isEmpty { line += ": " + AX25Frame.printable(info) }
        return line
    }

    static func printable(_ bytes: [UInt8]) -> String {
        String(decoding: bytes.map { b in (b == 0x0D || b == 0x0A) ? 0x20 : (b < 0x20 ? 0x2E : b) }, as: UTF8.self)
    }

    /// The path a reply should take: the digipeaters it came through, reversed, H bits cleared.
    var replyPath: [AX25Address] { path.reversed().map(\.base) }
}

/// Text in a path field, "WIDE1-1,WIDE2-1" or "KF0ABC-1 KF0XYZ".
enum AX25Path {
    static func parse(_ text: String) -> [AX25Address]? {
        let parts = text.split(whereSeparator: { $0 == "," || $0 == " " }).map(String.init)
        var out: [AX25Address] = []
        for p in parts {
            if p.uppercased() == "VIA" || p.uppercased() == "V" { continue }
            guard let a = AX25Address(p) else { return nil }
            out.append(a.base)
        }
        return out.count <= 8 ? out : nil
    }

    static func text(_ path: [AX25Address]) -> String { path.map(\.description).joined(separator: ",") }
}
