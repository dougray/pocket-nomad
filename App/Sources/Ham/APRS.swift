// APRS.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Decodes and builds APRS packets (the info field of AX.25 UI frames).
// Reference: APRS Protocol Reference 1.0.1 (APRS101) and the 1.1/1.2 addenda.
// Covers what a handheld actually hears: plain, timestamped and compressed
// positions, Mic-E (most radios beacon this), messages and acks, bulletins,
// status, objects, items, weather, and third-party (gated) packets.

import Foundation

struct APRSPosition: Equatable, Sendable {
    var latitude: Double
    var longitude: Double
    var symbolTable: Character = "/"
    var symbolCode: Character = ">"
    /// Digits blanked for privacy (0 = exact).
    var ambiguity = 0
}

struct APRSWeather: Equatable, Sendable {
    var windDirection: Int?
    var windSpeedMph: Int?
    var gustMph: Int?
    var temperatureF: Int?
    var rainLastHourIn: Double?
    var rain24hIn: Double?
    var humidity: Int?
    var pressureMbar: Double?

    var summary: String {
        var parts: [String] = []
        if let t = temperatureF { parts.append("\(t)°F") }
        if let h = humidity { parts.append("\(h)% humidity") }
        if let s = windSpeedMph {
            var w = "wind \(s) mph"
            if let d = windDirection { w += " from \(d)°" }
            if let g = gustMph, g > 0 { w += ", gusts \(g)" }
            parts.append(w)
        }
        if let p = pressureMbar { parts.append(String(format: "%.1f mbar", p)) }
        if let r = rainLastHourIn, r > 0 { parts.append(String(format: "rain %.2f in/h", r)) }
        return parts.joined(separator: ", ")
    }
}

struct APRSPacket: Equatable, Sendable {
    enum Kind: String, Sendable {
        case position, micE, message, ack, reject, bulletin, status, object, item, weather, telemetry, query, other
    }

    var source: String
    var destination: String
    var path: [String] = []
    var kind: Kind = .other
    var position: APRSPosition?
    var courseDegrees: Int?
    var speedKnots: Double?
    var altitudeFeet: Double?
    var comment = ""
    /// Messages, acks and bulletins.
    var addressee: String?
    var text: String?
    var messageID: String?
    /// Objects and items.
    var objectName: String?
    var objectAlive = true
    var weather: APRSWeather?
    /// Mic-E message ("En Route", "Emergency", ...).
    var micEStatus: String?
    /// True when the station can receive APRS messages (= or @ positions, Mic-E).
    var messaging = false
    var raw: String

    /// The station this packet is about: an object's name, otherwise the sender.
    var subject: String { objectName ?? source }
    var isEmergency: Bool { micEStatus == "Emergency" || comment.uppercased().contains("EMERGENCY") }
}

enum APRS {
    /// Our tocall, from the experimental APZ block.
    static let tocall = "APZNMD"
    static let defaultPath = "WIDE1-1,WIDE2-1"

    // MARK: - Decode

    static func decode(_ f: AX25Frame) -> APRSPacket? {
        guard case .ui = f.control, f.pid == 0xF0, !f.info.isEmpty else { return nil }
        let info = String(decoding: f.info, as: UTF8.self)
        return decode(source: f.source.description, destination: f.destination.description,
                      path: f.path.map { $0.description + ($0.repeated ? "*" : "") }, info: info, rawInfo: f.info)
    }

    /// TNC2 text, "SRC>DEST,PATH:info".
    static func decode(tnc2 line: String) -> APRSPacket? {
        guard let colon = line.firstIndex(of: ":"), let gt = line.firstIndex(of: ">"), gt < colon else { return nil }
        let src = String(line[..<gt])
        let hdr = line[line.index(after: gt)..<colon].split(separator: ",").map(String.init)
        guard let dest = hdr.first else { return nil }
        let info = String(line[line.index(after: colon)...])
        return decode(source: src, destination: dest, path: Array(hdr.dropFirst()), info: info,
                      rawInfo: Array(info.utf8))
    }

    static func decode(source: String, destination: String, path: [String], info: String, rawInfo: [UInt8]) -> APRSPacket? {
        guard let type = rawInfo.first else { return nil }
        var p = APRSPacket(source: source, destination: destination, path: path, raw: info)
        let body = String(decoding: rawInfo.dropFirst(), as: UTF8.self)
        switch type {
        case UInt8(ascii: "!"), UInt8(ascii: "="):
            p.messaging = type == UInt8(ascii: "=")
            if !parsePositionBody(body, into: &p) { return fallback(p, body) }
        case UInt8(ascii: "/"), UInt8(ascii: "@"):
            p.messaging = type == UInt8(ascii: "@")
            guard body.count > 7 else { return fallback(p, body) }
            if !parsePositionBody(String(body.dropFirst(7)), into: &p) { return fallback(p, body) }
        case UInt8(ascii: "`"), UInt8(ascii: "'"), 0x1C, 0x1D:
            if !parseMicE(destination: destination, info: rawInfo, into: &p) { return fallback(p, body) }
        case UInt8(ascii: ":"):
            parseMessage(body, into: &p)
        case UInt8(ascii: ">"):
            p.kind = .status
            p.comment = body.trimmingCharacters(in: .whitespaces)
        case UInt8(ascii: ";"):
            if !parseObject(body, into: &p) { return fallback(p, body) }
        case UInt8(ascii: ")"):
            if !parseItem(body, into: &p) { return fallback(p, body) }
        case UInt8(ascii: "_"):
            p.kind = .weather
            p.weather = parseWeather(String(body.dropFirst(8)), windFromCourse: nil)
        case UInt8(ascii: "T"):
            p.kind = .telemetry
            p.comment = body
        case UInt8(ascii: "?"):
            p.kind = .query
            p.comment = body
        case UInt8(ascii: "}"):
            // Third party: the original packet, gated from the internet or another port.
            if var inner = decode(tnc2: body) {
                inner.raw = info
                return inner
            }
            return fallback(p, body)
        default:
            // Some trackers put the position after a free-text prefix ("!" within 40 chars).
            if let bang = info.firstIndex(of: "!"), info.distance(from: info.startIndex, to: bang) < 40,
               parsePositionBody(String(info[info.index(after: bang)...]), into: &p) {
                break
            }
            return fallback(p, info)
        }
        return p
    }

    private static func fallback(_ p: APRSPacket, _ body: String) -> APRSPacket {
        var p = p
        p.kind = .other
        p.position = nil
        p.comment = body
        return p
    }

    /// Position (plain or compressed) followed by an optional extension and comment.
    @discardableResult
    static func parsePositionBody(_ body: String, into p: inout APRSPacket) -> Bool {
        let b = Array(body.utf8)
        guard let first = b.first else { return false }
        p.kind = .position
        var rest: [UInt8]
        if (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(first) || first == UInt8(ascii: " ") {
            guard b.count >= 19,
                  let (lat, amb) = parseLat(Array(b[0..<8])),
                  let lon = parseLon(Array(b[9..<18])) else { return false }
            p.position = APRSPosition(latitude: lat, longitude: lon, symbolTable: Character(UnicodeScalar(b[8])),
                                      symbolCode: Character(UnicodeScalar(b[18])), ambiguity: amb)
            rest = Array(b[19...])
            // Course/speed extension "ccc/sss".
            if rest.count >= 7, rest[3] == UInt8(ascii: "/"),
               let c = Int(String(decoding: rest[0..<3], as: UTF8.self)),
               let s = Int(String(decoding: rest[4..<7], as: UTF8.self)) {
                if p.position?.symbolCode != "_" {
                    if c > 0 { p.courseDegrees = c == 360 ? 0 : c }
                    p.speedKnots = Double(s)
                }
                if p.position?.symbolCode == "_" {
                    p.weather = parseWeather(String(decoding: rest[7...], as: UTF8.self), windFromCourse: (c, s))
                    p.kind = .weather
                    rest = []
                } else {
                    rest = Array(rest[7...])
                }
            } else if p.position?.symbolCode == "_" {
                p.weather = parseWeather(String(decoding: rest, as: UTF8.self), windFromCourse: nil)
                p.kind = .weather
                rest = []
            }
        } else {
            guard b.count >= 13 else { return false }
            let table = b[0]
            guard table == UInt8(ascii: "/") || table == UInt8(ascii: "\\") ||
                  (UInt8(ascii: "A")...UInt8(ascii: "Z")).contains(table) ||
                  (UInt8(ascii: "a")...UInt8(ascii: "j")).contains(table),
                  let y = base91(Array(b[1..<5])), let x = base91(Array(b[5..<9])) else { return false }
            let lat = 90.0 - Double(y) / 380926.0
            let lon = -180.0 + Double(x) / 190463.0
            guard abs(lat) <= 90, abs(lon) <= 180 else { return false }
            var t = Character(UnicodeScalar(table))
            if (UInt8(ascii: "a")...UInt8(ascii: "j")).contains(table) {
                t = Character(UnicodeScalar(table - UInt8(ascii: "a") + UInt8(ascii: "0")))
            }
            p.position = APRSPosition(latitude: lat, longitude: lon, symbolTable: t, symbolCode: Character(UnicodeScalar(b[9])))
            let c = b[10], s = b[11], typ = b[12]
            if c != UInt8(ascii: " ") {
                let tbits = (Int(typ) - 33) & 0x18
                if tbits == 0x10 {
                    let cs = (Int(c) - 33) * 91 + (Int(s) - 33)
                    p.altitudeFeet = pow(1.002, Double(cs))
                } else if (UInt8(ascii: "!")...UInt8(ascii: "z")).contains(c) {
                    let course = (Int(c) - 33) * 4
                    p.courseDegrees = course == 360 ? 0 : course
                    p.speedKnots = pow(1.08, Double(Int(s) - 33)) - 1
                }
            }
            rest = Array(b[13...])
            if p.position?.symbolCode == "_" {
                p.weather = parseWeather(String(decoding: rest, as: UTF8.self), windFromCourse: nil)
                p.kind = .weather
                rest = []
            }
        }
        var comment = String(decoding: rest, as: UTF8.self)
        if let r = comment.range(of: "/A=") {
            let digits = comment[r.upperBound...].prefix(6)
            if digits.count == 6, let a = Double(digits.replacingOccurrences(of: "O", with: "0")) {
                p.altitudeFeet = a
                comment.removeSubrange(r.lowerBound..<comment.index(r.upperBound, offsetBy: 6))
            }
        }
        p.comment = comment.trimmingCharacters(in: .whitespaces)
        return true
    }

    /// "DDMM.mmN", spaces allowed for position ambiguity.
    static func parseLat(_ b: [UInt8]) -> (Double, Int)? {
        guard b.count == 8, b[4] == UInt8(ascii: ".") else { return nil }
        let hemi = b[7]
        guard hemi == UInt8(ascii: "N") || hemi == UInt8(ascii: "S") else { return nil }
        var amb = 0
        let digits = [b[0], b[1], b[2], b[3], b[5], b[6]].map { c -> UInt8 in
            if c == UInt8(ascii: " ") { amb += 1; return UInt8(ascii: "0") }
            return c
        }
        guard digits.allSatisfy({ (48...57).contains($0) }) else { return nil }
        let s = String(decoding: digits, as: UTF8.self)
        let deg = Double(s.prefix(2))!
        let min = Double(s.dropFirst(2).prefix(2))! + Double(s.suffix(2))! / 100
        guard deg <= 90, min < 60 else { return nil }
        let v = deg + min / 60
        return (hemi == UInt8(ascii: "S") ? -v : v, amb)
    }

    /// "DDDMM.mmW".
    static func parseLon(_ b: [UInt8]) -> Double? {
        guard b.count == 9, b[5] == UInt8(ascii: ".") else { return nil }
        let hemi = b[8]
        guard hemi == UInt8(ascii: "E") || hemi == UInt8(ascii: "W") else { return nil }
        let digits = [b[0], b[1], b[2], b[3], b[4], b[6], b[7]].map { $0 == UInt8(ascii: " ") ? UInt8(ascii: "0") : $0 }
        guard digits.allSatisfy({ (48...57).contains($0) }) else { return nil }
        let s = String(decoding: digits, as: UTF8.self)
        let deg = Double(s.prefix(3))!
        let min = Double(s.dropFirst(3).prefix(2))! + Double(s.suffix(2))! / 100
        guard deg <= 180, min < 60 else { return nil }
        let v = deg + min / 60
        return hemi == UInt8(ascii: "W") ? -v : v
    }

    static func base91(_ b: [UInt8]) -> Int? {
        var v = 0
        for c in b {
            guard c >= 33, c <= 124 else { return nil }
            v = v * 91 + Int(c) - 33
        }
        return v
    }

    // MARK: Mic-E

    static let micEMessages = ["Emergency", "Priority", "Special", "Committed", "Returning", "In Service", "En Route", "Off Duty"]

    static func parseMicE(destination: String, info b: [UInt8], into p: inout APRSPacket) -> Bool {
        let dest = Array(destination.split(separator: "-").first.map { String($0) }?.utf8 ?? "".utf8)
        guard dest.count == 6, b.count >= 9 else { return false }
        var digits: [Int] = []
        var msgBits = 0
        var custom = false
        for (i, c) in dest.enumerated() {
            var d: Int
            var bit = 0
            switch c {
            case 48...57: d = Int(c) - 48
            case 65...74: d = Int(c) - 65; bit = 1; custom = true
            case 75: d = 0; bit = 1; custom = true // K = space
            case 76: d = 0 // L = space
            case 80...89: d = Int(c) - 80; bit = 1
            case 90: d = 0; bit = 1 // Z = space
            default: return false
            }
            if i < 3 { msgBits = msgBits << 1 | bit }
            digits.append(d)
        }
        let latDeg = Double(digits[0] * 10 + digits[1])
        let latMin = Double(digits[2] * 10 + digits[3]) + Double(digits[4] * 10 + digits[5]) / 100
        var lat = latDeg + latMin / 60
        let north = dest[3] >= 80
        let lonOffset = dest[4] >= 80
        let west = dest[5] >= 80
        if !north { lat = -lat }

        var d = Int(b[1]) - 28
        if lonOffset { d += 100 }
        if (180...189).contains(d) { d -= 80 } else if (190...199).contains(d) { d -= 190 }
        var m = Int(b[2]) - 28
        if m >= 60 { m -= 60 }
        let h = Int(b[3]) - 28
        var lon = Double(d) + (Double(m) + Double(h) / 100) / 60
        if west { lon = -lon }
        guard abs(lat) <= 90, abs(lon) <= 180, d >= 0, m >= 0, h >= 0 else { return false }

        let sp = Int(b[4]) - 28
        let dc = Int(b[5]) - 28
        let se = Int(b[6]) - 28
        var speed = sp * 10 + dc / 10
        var course = (dc % 10) * 100 + se
        if speed >= 800 { speed -= 800 }
        if course >= 400 { course -= 400 }

        p.kind = .micE
        p.messaging = true
        p.position = APRSPosition(latitude: lat, longitude: lon, symbolTable: Character(UnicodeScalar(b[8])),
                                  symbolCode: Character(UnicodeScalar(b[7])))
        if course > 0 && course <= 360 { p.courseDegrees = course == 360 ? 0 : course }
        p.speedKnots = Double(speed)
        p.micEStatus = custom ? "Custom \(7 - msgBits)" : micEMessages[msgBits]

        var rest = Array(b.dropFirst(9))
        // Optional altitude "xxx}" (base 91, metres above -10 km).
        if rest.count >= 4, rest[3] == UInt8(ascii: "}"), let alt = base91(Array(rest[0..<3])) {
            p.altitudeFeet = Double(alt - 10000) * 3.28084
            rest = Array(rest.dropFirst(4))
        } else if rest.count >= 5, rest[0] == UInt8(ascii: ">") || rest[0] == UInt8(ascii: "]") || rest[0] == UInt8(ascii: "`"),
                  rest[4] == UInt8(ascii: "}"), let alt = base91(Array(rest[1..<4])) {
            p.altitudeFeet = Double(alt - 10000) * 3.28084
            rest = Array(rest.dropFirst(5))
        }
        var comment = String(decoding: rest, as: UTF8.self)
        // Radio type markers (Kenwood ">" "]", Yaesu "`...", "'...").
        if let f = comment.first, f == ">" || f == "]" { comment.removeFirst() }
        if comment.hasSuffix("=") || comment.hasSuffix("^") { comment.removeLast() }
        if comment.first == "`" || comment.first == "'" {
            comment.removeFirst()
            if comment.count >= 2 { comment.removeLast(2) }
        }
        p.comment = comment.trimmingCharacters(in: .whitespaces)
        return true
    }

    // MARK: Messages

    static func parseMessage(_ body: String, into p: inout APRSPacket) {
        let b = Array(body)
        guard b.count >= 10, b[9] == ":" else {
            p.kind = .other
            p.comment = body
            return
        }
        let addressee = String(b[0..<9]).trimmingCharacters(in: .whitespaces)
        var text = String(b[10...])
        p.addressee = addressee
        let lower = text.lowercased()
        if lower.hasPrefix("ack"), text.count <= 8, !text.contains(" ") {
            p.kind = .ack
            p.messageID = String(text.dropFirst(3)).components(separatedBy: "}").first
            return
        }
        if lower.hasPrefix("rej"), text.count <= 8, !text.contains(" ") {
            p.kind = .reject
            p.messageID = String(text.dropFirst(3)).components(separatedBy: "}").first
            return
        }
        if let brace = text.lastIndex(of: "{") {
            let idPart = text[text.index(after: brace)...]
            let id = idPart.split(separator: "}", omittingEmptySubsequences: false).first.map(String.init) ?? ""
            if !id.isEmpty, id.count <= 5, id.allSatisfy({ $0.isLetter || $0.isNumber }) {
                p.messageID = id
                text = String(text[..<brace])
            }
        }
        p.text = text
        p.kind = addressee.uppercased().hasPrefix("BLN") || addressee.uppercased().hasPrefix("NWS") ? .bulletin : .message
    }

    // MARK: Objects and items

    static func parseObject(_ body: String, into p: inout APRSPacket) -> Bool {
        let b = Array(body)
        guard b.count >= 18 else { return false }
        let name = String(b[0..<9]).trimmingCharacters(in: .whitespaces)
        let alive = b[9] == "*"
        guard b[9] == "*" || b[9] == "_" else { return false }
        guard parsePositionBody(String(b[17...]), into: &p) else { return false }
        if p.kind == .position { p.kind = .object }
        p.objectName = name
        p.objectAlive = alive
        return true
    }

    static func parseItem(_ body: String, into p: inout APRSPacket) -> Bool {
        guard let end = body.firstIndex(where: { $0 == "!" || $0 == "_" }),
              (3...9).contains(body.distance(from: body.startIndex, to: end)) else { return false }
        let name = String(body[..<end])
        guard parsePositionBody(String(body[body.index(after: end)...]), into: &p) else { return false }
        if p.kind == .position { p.kind = .item }
        p.objectName = name
        p.objectAlive = body[end] == "!"
        return true
    }

    // MARK: Weather

    static func parseWeather(_ s: String, windFromCourse: (Int, Int)?) -> APRSWeather {
        var w = APRSWeather()
        if let (c, sp) = windFromCourse { w.windDirection = c; w.windSpeedMph = sp }
        let chars = Array(s)
        var i = 0
        func number(_ n: Int) -> String? {
            guard i + n <= chars.count else { return nil }
            let v = String(chars[i..<(i + n)])
            i += n
            return v
        }
        while i < chars.count {
            let key = chars[i]
            i += 1
            switch key {
            case "c": if let v = number(3) { w.windDirection = Int(v) }
            case "s": if let v = number(3) { w.windSpeedMph = Int(v) }
            case "g": if let v = number(3) { w.gustMph = Int(v) }
            case "t": if let v = number(3) { w.temperatureF = Int(v.trimmingCharacters(in: .whitespaces)) }
            case "r": if let v = number(3), let n = Double(v) { w.rainLastHourIn = n / 100 }
            case "p": if let v = number(3), let n = Double(v) { w.rain24hIn = n / 100 }
            case "P": _ = number(3)
            case "h": if let v = number(2), let n = Int(v) { w.humidity = n == 0 ? 100 : n }
            case "b": if let v = number(5), let n = Double(v) { w.pressureMbar = n / 10 }
            case "L", "l": _ = number(3)
            default: i = chars.count // the rest is the station's comment / software id
            }
        }
        return w
    }

    // MARK: - Encode

    static func latString(_ lat: Double) -> String {
        let a = min(abs(lat), 89.99999)
        let deg = Int(a)
        let minutes = (a - Double(deg)) * 60
        var mm = (minutes * 100).rounded() / 100
        var d = deg
        if mm >= 60 { mm = 0; d += 1 }
        return String(format: "%02d%05.2f%@", d, mm, lat < 0 ? "S" : "N")
    }

    static func lonString(_ lon: Double) -> String {
        let a = min(abs(lon), 179.99999)
        let deg = Int(a)
        let minutes = (a - Double(deg)) * 60
        var mm = (minutes * 100).rounded() / 100
        var d = deg
        if mm >= 60 { mm = 0; d += 1 }
        return String(format: "%03d%05.2f%@", d, mm, lon < 0 ? "W" : "E")
    }

    /// "=" position report (we can receive messages), optional course/speed and comment.
    static func positionInfo(lat: Double, lon: Double, table: Character = "/", code: Character = "[",
                             courseDegrees: Int? = nil, speedKnots: Double? = nil, altitudeFeet: Double? = nil,
                             comment: String = "") -> String {
        var s = "=" + latString(lat) + String(table) + lonString(lon) + String(code)
        if let c = courseDegrees, let sp = speedKnots, sp >= 1 {
            s += String(format: "%03d/%03d", c == 0 ? 360 : c, min(999, Int(sp.rounded())))
        }
        if let a = altitudeFeet { s += String(format: "/A=%06d", max(0, min(999_999, Int(a.rounded())))) }
        if !comment.isEmpty { s += String(comment.prefix(43)) }
        return s
    }

    static func paddedAddressee(_ to: String) -> String {
        let t = String(to.uppercased().prefix(9))
        return t + String(repeating: " ", count: 9 - t.count)
    }

    /// A message; APRS text is printable ASCII, up to 67 characters, without | ~ {.
    static func messageInfo(to: String, text: String, id: String?) -> String {
        var clean = String(text.unicodeScalars.map { s -> Character in
            if s.isASCII, s.value >= 0x20, s.value < 0x7F, !"|~{".unicodeScalars.contains(s) { return Character(s) }
            return "?"
        }.prefix(67))
        if let id { clean += "{" + id }
        return ":" + paddedAddressee(to) + ":" + clean
    }

    static func ackInfo(to: String, id: String) -> String { ":" + paddedAddressee(to) + ":ack" + id }
    static func statusInfo(_ text: String) -> String { ">" + String(text.prefix(62)) }
}

// MARK: - Helpers

enum HamGeo {
    static func distanceKm(_ a: (Double, Double), _ b: (Double, Double)) -> Double {
        let r = 6371.0
        let dLat = (b.0 - a.0) * .pi / 180
        let dLon = (b.1 - a.1) * .pi / 180
        let x = sin(dLat / 2) * sin(dLat / 2) + cos(a.0 * .pi / 180) * cos(b.0 * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(x), sqrt(1 - x))
    }

    static func bearing(_ a: (Double, Double), _ b: (Double, Double)) -> Double {
        let l1 = a.0 * .pi / 180, l2 = b.0 * .pi / 180
        let dLon = (b.1 - a.1) * .pi / 180
        let y = sin(dLon) * cos(l2)
        let x = cos(l1) * sin(l2) - sin(l1) * cos(l2) * cos(dLon)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Six-character Maidenhead locator (Winlink and the ham world use it for "where").
    static func maidenhead(lat: Double, lon: Double) -> String {
        let lo = lon + 180, la = lat + 90
        let a = UnicodeScalar(UInt8(65 + Int(lo / 20)))
        let b = UnicodeScalar(UInt8(65 + Int(la / 10)))
        let c = Int(lo.truncatingRemainder(dividingBy: 20) / 2)
        let d = Int(la.truncatingRemainder(dividingBy: 10))
        let e = UnicodeScalar(UInt8(97 + Int(lo.truncatingRemainder(dividingBy: 2) * 12)))
        let f = UnicodeScalar(UInt8(97 + Int(la.truncatingRemainder(dividingBy: 1) * 24)))
        return "\(Character(a))\(Character(b))\(c)\(d)\(Character(e))\(Character(f))"
    }
}
