import Foundation

/// One NOAA Weather Radio alert (SAME header) heard on the air.
struct SameAlert: Equatable, Sendable {
    var header: String, org: String, code: String, event: String
    var areas: [String]
    var minutes: Int
    var issued: String, station: String
    /// A one-line summary, the same wording the SDR box uses.
    var text: String { "\(event) for \(areas.count) area\(areas.count == 1 ? "" : "s"), valid \(minutes) min" }
}

/// Receive-only decoder for NOAA Weather Radio alerts (SAME, the digital header before a warning) from audio samples: AFSK at
/// 520.83 baud, 2083.33 Hz for a one and 1562.5 Hz for a zero, ASCII letters sent least-significant bit first after a 0xAB preamble.
/// Every alert is sent three times; an alert is reported when two identical copies arrive, or when one clean copy is all that came.
/// android/ham/.../SameDecoder.kt is the Android twin; keep them in step.
final class SameDecoder: @unchecked Sendable {
    private var slicer: ToneSlicer!
    private let sampleRate: Double
    private let onAlert: (SameAlert) -> Void
    private var reg24 = 0
    private var synced = false
    private var reg8 = 0
    private var nbits = 0
    private var text = ""
    private var samples: Int = 0
    private var pending: SameAlert?
    private var pendingAt = 0
    private var lastEmitted: String?
    private var lastEmittedAt = -1_000_000_000

    init(sampleRate: Double, onAlert: @escaping (SameAlert) -> Void) {
        self.sampleRate = sampleRate
        self.onAlert = onAlert
        slicer = ToneSlicer(sampleRate: sampleRate, baud: 520.8333, markHz: 2083.3333, spaceHz: 1562.5) { [unowned self] in self.bit($0) }
    }

    func feed(_ s: UnsafeBufferPointer<Float>) { for x in s { tick(Double(x)) } }
    func feed(_ s: [Float]) { s.withUnsafeBufferPointer { feed($0) } }
    func feed(_ s: [Int16]) { for x in s { tick(Double(x) / 32768) } }

    private func tick(_ x: Double) {
        slicer.step(x)
        samples += 1
        if let p = pending, Double(samples - pendingAt) > 12 * sampleRate { pending = nil; emit(p) }   // the other copies never came: report the one we have
    }

    private func bit(_ b: Int) {
        if !synced {
            reg24 = (reg24 >> 1) | (b << 23)
            if reg24 == 0xABABAB { synced = true; reg8 = 0; nbits = 0; text = "" }
            return
        }
        reg8 = (reg8 >> 1) | (b << 7)
        nbits += 1
        if nbits < 8 { return }
        nbits = 0
        let c = reg8
        if c == 0xAB && text.isEmpty { return }   // more preamble
        if (0x20...0x7E).contains(c) && text.count < 268 { text.append(Character(UnicodeScalar(UInt8(c)))); return }
        finishMessage()
    }

    private func finishMessage() {
        let m = text
        synced = false; reg24 = 0; text = ""
        guard m.hasPrefix("ZCZC"), let a = Self.parse(m) else { return }
        if let p = pending, p.header == a.header { pending = nil; emit(a) } else { pending = a; pendingAt = samples }
    }

    private func emit(_ a: SameAlert) {
        if a.header == lastEmitted && Double(samples - lastEmittedAt) < 60 * sampleRate { return }   // the same alert again
        lastEmitted = a.header; lastEmittedAt = samples
        onAlert(a)
    }

    static let eventNames: [String: String] = [
        "TOR": "Tornado Warning", "TOA": "Tornado Watch", "SVR": "Severe Thunderstorm Warning", "SVA": "Severe Thunderstorm Watch",
        "FFW": "Flash Flood Warning", "FFA": "Flash Flood Watch", "FLW": "Flood Warning", "FLA": "Flood Watch",
        "SVS": "Severe Weather Statement", "SMW": "Special Marine Warning", "EWW": "Extreme Wind Warning",
        "HUW": "Hurricane Warning", "HUA": "Hurricane Watch", "TRW": "Tropical Storm Warning", "TRA": "Tropical Storm Watch",
        "SSW": "Storm Surge Warning", "SSA": "Storm Surge Watch", "WSW": "Winter Storm Warning", "WSA": "Winter Storm Watch",
        "BZW": "Blizzard Warning", "ISW": "Ice Storm Warning", "HWW": "High Wind Warning", "HWA": "High Wind Watch",
        "DSW": "Dust Storm Warning", "FRW": "Fire Warning", "EVI": "Evacuation Immediate", "SPS": "Special Weather Statement",
        "CEM": "Civil Emergency Message", "LAE": "Local Area Emergency", "SPW": "Shelter In Place Warning", "RHW": "Radiological Hazard Warning",
        "CAE": "Child Abduction Emergency", "EAN": "Emergency Action Notification", "NPT": "National Periodic Test",
        "RMT": "Required Monthly Test", "RWT": "Required Weekly Test", "DMO": "Practice/Demo Warning", "ADR": "Administrative Message",
    ]

    private static let pattern = try! NSRegularExpression(pattern: #"ZCZC-([A-Z0-9]{3})-([A-Z0-9]{3})((?:-\d{6})+)\+(\d{4})-(\d{7})-([A-Z0-9/ ]{1,8})-?"#)

    static func parse(_ text: String) -> SameAlert? {
        let ns = text as NSString
        guard let m = pattern.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return nil }
        func g(_ i: Int) -> String { ns.substring(with: m.range(at: i)) }
        let dur = g(4)
        let minutes = (Int(dur.prefix(2)) ?? 0) * 60 + (Int(dur.suffix(2)) ?? 0)
        var whole = ns.substring(with: m.range)
        while whole.hasSuffix("-") { whole.removeLast() }
        let station = g(6).trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        return SameAlert(header: whole + "-", org: g(1), code: g(2), event: eventNames[g(2)] ?? "Alert \(g(2))",
                         areas: g(3).split(separator: "-").map(String.init), minutes: minutes, issued: g(5), station: station)
    }
}
