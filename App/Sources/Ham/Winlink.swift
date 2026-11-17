// Winlink.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Winlink email over a packet connection: the message format and the B2F
// ("FBB compressed v2") exchange as a client of an RMS gateway.
//
// Protocol details follow wl2k-go (MIT, LA5NTA), the open-source Winlink
// library behind Pat: handshake with ;FW / SID / ;PR secure login, FC
// proposals with a checksum, FS answers, then SOH/STX/EOT blocks carrying
// LZHUF-compressed messages. See NOTICE.

import CryptoKit
import Foundation

struct WinlinkAttachment: Codable, Equatable, Sendable, Hashable {
    var name: String
    var data: Data
}

struct WinlinkMessage: Codable, Equatable, Sendable, Identifiable, Hashable {
    var mid: String
    var date: Date
    var from: String
    var to: [String]
    var cc: [String] = []
    var subject: String
    var body: String
    var attachments: [WinlinkAttachment] = []
    var type = "Private"
    var mbo: String

    var id: String { mid }

    static func newMID() -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        return String((0..<12).map { _ in alphabet.randomElement()! })
    }

    static func compose(from: String, to: [String], cc: [String] = [], subject: String, body: String, date: Date = Date()) -> WinlinkMessage {
        WinlinkMessage(mid: newMID(), date: date, from: from.uppercased(), to: to.map(Self.normalizedAddress),
                       cc: cc.map(Self.normalizedAddress), subject: subject, body: body, mbo: from.uppercased())
    }

    /// Callsigns upper-cased; internet addresses as "SMTP:user@host"; "@winlink.org" dropped.
    static func normalizedAddress(_ a: String) -> String {
        let t = a.trimmingCharacters(in: .whitespaces)
        if t.uppercased().hasPrefix("SMTP:") { return "SMTP:" + t.dropFirst(5) }
        if let at = t.firstIndex(of: "@") {
            if t[t.index(after: at)...].lowercased() == "winlink.org" { return String(t[..<at]).uppercased() }
            return "SMTP:" + t
        }
        return t.uppercased()
    }

    /// For display: "SMTP:" removed.
    static func displayAddress(_ a: String) -> String {
        a.uppercased().hasPrefix("SMTP:") ? String(a.dropFirst(5)) : a
    }

    static let dateFormats = ["yyyy/MM/dd HH:mm", "yyyy.MM.dd HH:mm", "yyyy-MM-dd HH:mm", "yyyyMMddHHmmss",
                              "EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm Z"]

    static func formatter(_ f: String) -> DateFormatter {
        let d = DateFormatter()
        d.locale = Locale(identifier: "en_US_POSIX")
        d.timeZone = TimeZone(identifier: "UTC")
        d.dateFormat = f
        return d
    }

    // MARK: Wire format

    func encoded() -> [UInt8] {
        let latin1 = body.data(using: .isoLatin1)
        let bodyBytes = [UInt8](latin1 ?? Data(body.utf8))
        let crlfBody = Self.crlf(bodyBytes)
        var h = "Mid: \(mid)\r\n"
        h += "Body: \(crlfBody.count)\r\n"
        h += "Content-Transfer-Encoding: 8bit\r\n"
        h += "Content-Type: text/plain; charset=\(latin1 != nil ? "ISO-8859-1" : "UTF-8")\r\n"
        h += "Date: \(Self.formatter("yyyy/MM/dd HH:mm").string(from: date))\r\n"
        for f in attachments { h += "File: \(f.data.count) \(Self.qEncode(f.name))\r\n" }
        h += "From: \(from)\r\n"
        h += "Mbo: \(mbo)\r\n"
        h += "Subject: \(Self.qEncode(subject))\r\n"
        for t in to { h += "To: \(t)\r\n" }
        for c in cc { h += "Cc: \(c)\r\n" }
        h += "Type: \(type)\r\n"
        h += "\r\n"
        var out = [UInt8](h.utf8) + crlfBody
        if !attachments.isEmpty { out += [13, 10] }
        for f in attachments { out += [UInt8](f.data) + [13, 10] }
        return out
    }

    static func crlf(_ b: [UInt8]) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(b.count + b.count / 40)
        var prev: UInt8 = 0
        for c in b {
            if c == 10, prev != 13 { out.append(13) }
            out.append(c)
            prev = c
        }
        return out
    }

    enum ParseError: Error { case noHeader, noMID, short }

    static func parse(_ bytes: [UInt8]) throws -> WinlinkMessage {
        var i = 0
        while i < bytes.count, [9, 10, 13, 32].contains(bytes[i]) { i += 1 }
        var headers: [(String, String)] = []
        while true {
            guard let nl = bytes[i...].firstIndex(of: 10) else { throw ParseError.noHeader }
            var line = String(decoding: bytes[i..<nl], as: UTF8.self)
            if line.hasSuffix("\r") { line.removeLast() }
            i = nl + 1
            if line.isEmpty { break }
            if (line.first == " " || line.first == "\t"), !headers.isEmpty {
                headers[headers.count - 1].1 += " " + line.trimmingCharacters(in: .whitespaces)
                continue
            }
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers.append((line[..<colon].trimmingCharacters(in: .whitespaces).lowercased(),
                            line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)))
        }
        func all(_ k: String) -> [String] { headers.filter { $0.0 == k }.map(\.1) }
        func one(_ k: String) -> String? { all(k).first }
        guard let mid = one("mid") else { throw ParseError.noMID }
        let size = Int(one("body") ?? "") ?? max(0, bytes.count - i)
        guard i + size <= bytes.count else { throw ParseError.short }
        let bodyBytes = Data(bytes[i..<(i + size)])
        i += size
        if i + 1 < bytes.count, bytes[i] == 13, bytes[i + 1] == 10 { i += 2 }
        let charset = (one("content-type") ?? "").lowercased()
        let body = charset.contains("utf-8") ? String(decoding: bodyBytes, as: UTF8.self)
            : (String(data: bodyBytes, encoding: .isoLatin1) ?? String(decoding: bodyBytes, as: UTF8.self))
        var files: [WinlinkAttachment] = []
        for f in all("file") {
            let parts = f.split(separator: " ", maxSplits: 1).map(String.init)
            guard parts.count == 2, let n = Int(parts[0]), i + n <= bytes.count else { continue }
            files.append(WinlinkAttachment(name: qDecode(parts[1]), data: Data(bytes[i..<(i + n)])))
            i += n
            if i + 1 < bytes.count, bytes[i] == 13, bytes[i + 1] == 10 { i += 2 }
        }
        var date = Date()
        if let d = one("date") {
            for f in dateFormats { if let p = formatter(f).date(from: d) { date = p; break } }
        }
        return WinlinkMessage(mid: mid, date: date, from: one("from") ?? "", to: all("to"), cc: all("cc"),
                              subject: qDecode(one("subject") ?? ""), body: body.replacingOccurrences(of: "\r\n", with: "\n"),
                              attachments: files, type: one("type") ?? "Private", mbo: one("mbo") ?? "")
    }

    /// RFC 2047 Q-encoding for non-ASCII header text (headers must be ASCII).
    static func qEncode(_ s: String) -> String {
        guard s.unicodeScalars.contains(where: { !$0.isASCII || $0.value < 0x20 }) else { return s }
        var out = "=?utf-8?q?"
        for b in s.utf8 {
            switch b {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "0")...UInt8(ascii: "9"):
                out.append(Character(UnicodeScalar(b)))
            case UInt8(ascii: " "): out += "_"
            default: out += String(format: "=%02X", b)
            }
        }
        return out + "?="
    }

    static func qDecode(_ s: String) -> String {
        guard s.contains("=?") else { return s }
        var result = ""
        var rest = Substring(s)
        while let start = rest.range(of: "=?") {
            result += rest[..<start.lowerBound]
            let after = rest[start.upperBound...]
            let parts = after.split(separator: "?", maxSplits: 3, omittingEmptySubsequences: false)
            guard parts.count >= 4, parts[3].hasPrefix("=") else { result += "=?"; rest = after; continue }
            let charset = parts[0].lowercased(), enc = parts[1].lowercased(), text = parts[2]
            var bytes: [UInt8] = []
            if enc == "b" {
                bytes = [UInt8](Data(base64Encoded: String(text)) ?? Data())
            } else {
                var it = Array(text.utf8)[...]
                while let c = it.popFirst() {
                    if c == UInt8(ascii: "_") { bytes.append(32) }
                    else if c == UInt8(ascii: "="), it.count >= 2, let v = UInt8(String(decoding: it.prefix(2), as: UTF8.self), radix: 16) {
                        bytes.append(v); it = it.dropFirst(2)
                    } else { bytes.append(c) }
                }
            }
            result += charset.contains("8859") ? (String(bytes: bytes, encoding: .isoLatin1) ?? "") : String(decoding: bytes, as: UTF8.self)
            rest = parts[3].dropFirst()
            // Whitespace between two encoded words is dropped.
            if rest.hasPrefix(" =?") { rest = rest.dropFirst() }
        }
        return result + rest
    }
}

// MARK: - Secure login

enum WinlinkAuth {
    /// From paclink-unix, via wl2k-go.
    static let salt: [UInt8] = [
        77, 197, 101, 206, 190, 249, 93, 200, 51, 243, 93, 237, 71, 94, 239, 138, 68, 108, 70, 185, 225, 137,
        217, 16, 51, 122, 193, 48, 194, 195, 198, 175, 172, 169, 70, 84, 61, 62, 104, 186, 114, 52, 61, 168,
        66, 129, 192, 208, 187, 249, 232, 193, 41, 113, 41, 45, 240, 16, 29, 228, 208, 228, 61, 20,
    ]

    static func response(challenge: String, password: String) -> String {
        let payload = Array(challenge.utf8) + Array(password.utf8) + salt
        let sum = Array(Insecure.MD5.hash(data: payload))
        var pr = Int32(sum[3] & 0x3F)
        for i in stride(from: 2, through: 0, by: -1) { pr = (pr << 8) | Int32(sum[i]) }
        let s = String(format: "%08d", pr)
        return String(s.suffix(8))
    }
}

// MARK: - Byte channel

/// Bytes from the link, read by the session with await. Closing wakes the reader with the error.
@MainActor
final class HamByteChannel {
    struct Closed: LocalizedError {
        var reason: String
        var errorDescription: String? { reason }
    }

    private var buffer: [UInt8] = []
    private var head = 0
    private var waiter: CheckedContinuation<Void, Error>?
    private var closed: Closed?

    func feed(_ bytes: [UInt8]) {
        buffer += bytes
        wake()
    }

    func close(_ reason: String) {
        if closed == nil { closed = Closed(reason: reason) }
        wake()
    }

    private func wake() {
        if let w = waiter {
            waiter = nil
            w.resume()
        }
    }

    private func waitForData() async throws {
        while head >= buffer.count {
            if let closed { throw closed }
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in waiter = c }
        }
    }

    func readByte() async throws -> UInt8 {
        try await waitForData()
        let b = buffer[head]
        head += 1
        if head > 4096 { buffer.removeFirst(head); head = 0 }
        return b
    }

    func peek() async throws -> UInt8 {
        try await waitForData()
        return buffer[head]
    }

    /// One line ended by CR (LF ignored).
    func readLine() async throws -> String {
        var line: [UInt8] = []
        while true {
            let b = try await readByte()
            if b == 13 { return String(decoding: line, as: UTF8.self) }
            if b == 10 { continue }
            line.append(b)
            if line.count > 1024 { return String(decoding: line, as: UTF8.self) }
        }
    }

    func readUntil(_ stop: UInt8) async throws -> [UInt8] {
        var out: [UInt8] = []
        while true {
            let b = try await readByte()
            if b == stop { return out }
            out.append(b)
            if out.count > 1024 { throw Closed(reason: "Malformed block header from the gateway.") }
        }
    }
}

// MARK: - B2F session (client)

@MainActor
final class B2FSession {
    struct Outcome: Sendable {
        var received: [WinlinkMessage] = []
        var sent: [String] = []
        /// The gateway already had these (answered "-"): counted as sent.
        var alreadyDelivered: [String] = []
        var deferred: [String] = []
    }

    struct ProtocolError: LocalizedError {
        var message: String
        var errorDescription: String? { message }
    }

    struct Proposal {
        var code: Character
        var mid: String
        var size: Int
        var compressedSize: Int
        var data: [UInt8] = []
        var title = ""
        var answer: Character = "="
        var offset = 0
    }

    let mycall: String
    let password: String?
    let locator: String
    let target: String
    let input: HamByteChannel
    let write: ([UInt8]) -> Void
    var log: (String) -> Void = { _ in }
    var progress: (_ label: String, _ done: Int, _ total: Int) -> Void = { _, _, _ in }
    /// Messages we already have (answered "-" so the gateway marks them delivered).
    var knownMIDs: Set<String> = []
    /// Called as each message lands, so nothing is lost if the link drops later.
    var onReceived: (WinlinkMessage) -> Void = { _ in }
    /// Called when the gateway has a message of ours (taken now, or already had it).
    var onSent: (String) -> Void = { _ in }
    static let appName = "PocketNomad"
    static let appVersion = "1.0"

    private var outbound: [WinlinkMessage]
    private var outcome = Outcome()
    private var remoteNoMsgs = false
    /// Sent but not yet confirmed: the gateway's next line confirms it got them intact.
    private var awaitingConfirm: [String] = []
    private var quit = false

    init(mycall: String, password: String?, locator: String, target: String, outbound: [WinlinkMessage],
         input: HamByteChannel, write: @escaping ([UInt8]) -> Void) {
        self.mycall = mycall.uppercased()
        self.password = password
        self.locator = locator
        self.target = target.uppercased()
        self.outbound = outbound
        self.input = input
        self.write = write
    }

    func run() async throws -> Outcome {
        try await handshake()
        var myTurn = true
        while !quit {
            if myTurn { try await sendTurn() } else { try await receiveTurn() }
            myTurn.toggle()
        }
        return outcome
    }

    private func send(_ line: String) {
        log(">" + line)
        write(Array((line + "\r").utf8))
    }

    private func nextLine() async throws -> String {
        let line = try await input.readLine()
        log("<" + line)
        if line.hasPrefix("***") {
            let msg = line.drop(while: { $0 == "*" }).trimmingCharacters(in: .whitespaces)
            if msg.lowercased().contains("secure login failed") {
                throw ProtocolError(message: "Winlink rejected the password (secure login failed). Check it in Ham settings.")
            }
            throw ProtocolError(message: "Gateway: \(msg)")
        }
        return line
    }

    // MARK: Handshake

    private func handshake() async throws {
        var challenge: String?
        var sawSID = false
        while true {
            let line = try await nextLine()
            if line.hasPrefix("["), line.hasSuffix("]") {
                sawSID = true
                let code = line.split(separator: "-").last.map { String($0.dropLast()).uppercased() } ?? ""
                guard code.contains("B2") else { throw ProtocolError(message: "This station does not speak Winlink B2F.") }
            } else if line.hasPrefix(";PQ:") {
                challenge = line.dropFirst(4).trimmingCharacters(in: .whitespaces)
            } else if line.hasSuffix(">") {
                guard sawSID else { throw ProtocolError(message: "No Winlink greeting from \(target). Is it an RMS gateway?") }
                break
            }
        }
        send(";FW: \(mycall)")
        send("[\(Self.appName)-\(Self.appVersion)-B2FHM$]")
        if let challenge {
            guard let password, !password.isEmpty else {
                throw ProtocolError(message: "This gateway asks for your Winlink password. Add it in Ham settings.")
            }
            send(";PR: " + WinlinkAuth.response(challenge: challenge, password: password))
        }
        send("; \(target) DE \(mycall) (\(locator))")
    }

    // MARK: Our turn

    private func sendTurn() async throws {
        if outbound.isEmpty {
            if remoteNoMsgs { send("FQ"); quit = true } else { send("FF") }
            return
        }
        var props: [Proposal] = []
        for m in outbound.prefix(5) {
            let raw = m.encoded()
            let z = LZHUF.compress(raw)
            props.append(Proposal(code: "C", mid: m.mid, size: raw.count, compressedSize: z.count, data: z,
                                  title: String(WinlinkMessage.qEncode(m.subject.isEmpty ? "No title" : m.subject).prefix(80))))
        }
        var checksum = 0
        for p in props {
            let line = "FC EM \(p.mid) \(p.size) \(p.compressedSize) 0"
            send(line)
            for c in line.utf8 { checksum += Int(c) }
            checksum += 13
        }
        send(String(format: "F> %02X", (-checksum) & 0xFF))

        var reply = ""
        while reply.isEmpty {
            let line = try await nextLine()
            if line.hasPrefix("FS ") { reply = String(line.dropFirst(3)) }
            else if line.hasPrefix(";") || line.isEmpty { continue }
            else { throw ProtocolError(message: "Expected an answer to our messages, got: \(line)") }
        }
        try parseAnswers(reply, into: &props)
        for p in props {
            switch p.answer {
            case "+":
                try await writeBlock(p)
                outcome.sent.append(p.mid)
                awaitingConfirm.append(p.mid)
            case "-":
                outcome.alreadyDelivered.append(p.mid)
                onSent(p.mid)
            default:
                outcome.deferred.append(p.mid)
            }
        }
        let offered = Set(props.map(\.mid))
        outbound.removeAll { offered.contains($0.mid) }
        // Deferred messages stay for a later session but aren't offered again now.
    }

    private func parseAnswers(_ s: String, into props: inout [Proposal]) throws {
        var chars = Array(s)[...]
        var i = 0
        while let c = chars.popFirst() {
            guard i < props.count else { throw ProtocolError(message: "The gateway answered more messages than we offered.") }
            switch c {
            case "Y", "y", "+": props[i].answer = "+"
            case "N", "n", "R", "r", "-": props[i].answer = "-"
            case "L", "l", "=", "H", "h": props[i].answer = "="
            case "A", "a", "!":
                let digits = chars.prefix(while: { $0.isNumber })
                chars = chars.dropFirst(digits.count)
                props[i].answer = "+"
                let off = Int(String(digits)) ?? 0
                props[i].offset = off <= 999_999 && off < props[i].compressedSize ? off : 0
            default:
                throw ProtocolError(message: "Unexpected answer '\(c)' from the gateway.")
            }
            i += 1
        }
    }

    private func writeBlock(_ p: Proposal) async throws {
        let title = Array(p.title.utf8)
        let offset = Array(String(p.offset).utf8)
        var out: [UInt8] = [1, UInt8(title.count + offset.count + 2)] + title + [0] + offset + [0]
        var sum = 0
        var i = p.offset
        while i < p.data.count {
            let n = min(125, p.data.count - i)
            out += [2, UInt8(n)]
            for b in p.data[i..<(i + n)] { out.append(b); sum += Int(b) }
            i += n
        }
        out += [4, UInt8((-sum) & 0xFF)]
        log(">[\(p.mid): \(p.data.count - p.offset) bytes]")
        write(out)
        progress("Sending \(p.mid)", p.data.count, p.data.count)
    }

    // MARK: Their turn

    private func receiveTurn() async throws {
        var props: [Proposal] = []
        var checksum = 0
        loop: while true {
            let line = try await nextLine()
            for mid in awaitingConfirm { onSent(mid) }
            awaitingConfirm = []
            if line.isEmpty || line.hasPrefix(";") { continue }
            guard line.count >= 2, line.first == "F" else {
                throw ProtocolError(message: "Unexpected line from the gateway: \(line)")
            }
            let cmd = line.prefix(2)
            switch cmd {
            case "FA", "FB", "FC", "FD":
                for c in line.utf8 { checksum += Int(c) }
                checksum += 13
                let parts = line.dropFirst(3).split(separator: " ").map(String.init)
                var p = Proposal(code: line[line.index(after: line.startIndex)], mid: parts.count > 1 ? parts[1] : "",
                                 size: parts.count > 2 ? Int(parts[2]) ?? 0 : 0,
                                 compressedSize: parts.count > 3 ? Int(parts[3]) ?? 0 : 0)
                if p.code != "C" || p.mid.isEmpty { p.answer = "=" }
                else if knownMIDs.contains(p.mid) || props.contains(where: { $0.mid == p.mid }) { p.answer = "-" }
                else { p.answer = "+" }
                props.append(p)
            case "FF":
                remoteNoMsgs = true
                break loop
            case "FQ":
                quit = true
                return
            case "F>":
                let theirs = Int(line.dropFirst(2).trimmingCharacters(in: .whitespaces), radix: 16) ?? -1
                guard theirs == (-checksum) & 0xFF else { throw ProtocolError(message: "Checksum error in the gateway's message list.") }
                if props.isEmpty { remoteNoMsgs = true; return }
                remoteNoMsgs = false
                send("FS " + String(props.map(\.answer)))
                break loop
            default:
                throw ProtocolError(message: "Unknown command from the gateway: \(line)")
            }
        }
        for var p in props where p.answer == "+" {
            try await readBlock(&p)
            let raw = try LZHUF.decompress(p.data)
            let msg = try WinlinkMessage.parse(raw)
            outcome.received.append(msg)
            knownMIDs.insert(p.mid)
            onReceived(msg)
        }
    }

    private func readBlock(_ p: inout Proposal) async throws {
        let first = try await input.readByte()
        if first == UInt8(ascii: "*") {
            let rest = try await input.readLine()
            throw ProtocolError(message: "Gateway: \(rest.trimmingCharacters(in: CharacterSet(charactersIn: "* ")))")
        }
        guard first == 1 else { throw ProtocolError(message: "Bad start of message block from the gateway.") }
        _ = try await input.readByte()
        p.title = WinlinkMessage.qDecode(String(decoding: try await input.readUntil(0), as: UTF8.self))
        _ = try await input.readUntil(0)
        var data: [UInt8] = []
        var sum = 0
        while true {
            let c = try await input.readByte()
            switch c {
            case 2:
                var n = Int(try await input.readByte())
                if n == 0 { n = 256 }
                for _ in 0..<n {
                    let b = try await input.readByte()
                    data.append(b)
                    sum = (sum + Int(b)) & 0xFF
                }
                progress("Receiving \(p.title)", data.count, p.compressedSize)
            case 4:
                let ck = Int(try await input.readByte())
                guard (sum + ck) & 0xFF == 0 else { throw ProtocolError(message: "Checksum error in a received message.") }
                guard data.count == p.compressedSize else { throw ProtocolError(message: "A received message was the wrong length.") }
                p.data = data
                log("<[\(p.mid): \(data.count) bytes]")
                return
            default:
                throw ProtocolError(message: "Unexpected byte in a message block from the gateway.")
            }
        }
    }
}
