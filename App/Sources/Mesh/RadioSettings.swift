// RadioSettings.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The data behind the Radio settings screen: MeshCore radio parameters and
// their limits, the official MeshCore regional presets, "match radios near
// you" from the downloaded known-radio list, the hint on the radio card, and
// the Meshtastic region and modem preset names.
//
// Sources (checked 2026-11-04):
//  - MeshCore companion firmware, tag companion-v1.18.0 (MIT):
//    examples/companion_radio/MyMesh.cpp (CMD_SET_RADIO_PARAMS limits),
//    examples/companion_radio/MyMesh.h and platformio.ini (defaults).
//  - MeshCore presets: docs/radio_presets.md says the official app reads them
//    from https://api.meshcore.nz/api/v1/config; the list below is a snapshot
//    of that response. Its optional `network_settings.path_hash_size` is not
//    applied by this app.
//  - Meshtastic: config.proto in meshtastic/protobufs (RegionCode and
//    ModemPreset numbers). Names below are written for this app.

import Foundation

// MARK: - MeshCore radio parameters

/// One MeshCore radio setting, in the integer units the companion protocol
/// uses on the wire: frequency in kHz (MHz x 1000) and bandwidth in Hz
/// (kHz x 1000).
struct MeshCoreRadioParams: Codable, Hashable, Sendable {
    var frequencyKHz: UInt32
    var bandwidthHz: UInt32
    var spreadingFactor: UInt8
    var codingRate: UInt8

    init(frequencyKHz: UInt32, bandwidthHz: UInt32, spreadingFactor: UInt8, codingRate: UInt8) {
        self.frequencyKHz = frequencyKHz
        self.bandwidthHz = bandwidthHz
        self.spreadingFactor = spreadingFactor
        self.codingRate = codingRate
    }

    init(frequencyMHz: Double, bandwidthKHz: Double, spreadingFactor: UInt8, codingRate: UInt8) {
        self.init(frequencyKHz: UInt32(clamping: Int((frequencyMHz * 1000).rounded())),
                  bandwidthHz: UInt32(clamping: Int((bandwidthKHz * 1000).rounded())),
                  spreadingFactor: spreadingFactor, codingRate: codingRate)
    }

    var frequencyMHz: Double { Double(frequencyKHz) / 1000 }
    var bandwidthKHz: Double { Double(bandwidthHz) / 1000 }

    /// LoRa bandwidths the SX126x/SX127x radios support, in kHz. The firmware
    /// takes any value from 7 to 500 kHz; these are the real steps.
    static let allowedBandwidthsKHz: [Double] = [7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250, 500]
    static let frequencyRangeMHz: ClosedRange<Double> = 150 ... 960
    static let spreadingFactors: ClosedRange<UInt8> = 5 ... 12
    static let codingRates: ClosedRange<UInt8> = 5 ... 8
    /// Lowest TX power the firmware takes (CMD_SET_RADIO_TX_POWER: -9 ... MAX_LORA_TX_POWER).
    static let minTxPowerDBm: Int8 = -9

    /// The companion firmware's own defaults (v1.18.0): platformio.ini sets
    /// 869.618 MHz, BW 62.5, SF 8 for every board; CR stays at MyMesh.h's 5.
    /// A freshly flashed radio in the Americas is on the wrong frequency.
    static let firmwareDefault = MeshCoreRadioParams(frequencyKHz: 869_618, bandwidthHz: 62_500,
                                                     spreadingFactor: 8, codingRate: 5)

    /// Plain-words problem with these settings, or nil when they can be sent.
    var validationError: String? {
        if !Self.frequencyRangeMHz.contains(frequencyMHz) {
            return "Frequency must be between 150 and 960 MHz."
        }
        if !Self.allowedBandwidthsKHz.contains(where: { abs($0 * 1000 - Double(bandwidthHz)) < 1 }) {
            return "Choose one of the listed bandwidths."
        }
        if !Self.spreadingFactors.contains(spreadingFactor) { return "Spreading factor must be 5 to 12." }
        if !Self.codingRates.contains(codingRate) { return "Coding rate must be 5 to 8." }
        return nil
    }

    /// Same channel as another radio. The firmware keeps MHz in a float, so a
    /// read-back can be 1 kHz off what was sent.
    func sameChannel(as other: MeshCoreRadioParams) -> Bool {
        abs(Int(frequencyKHz) - Int(other.frequencyKHz)) <= 1
            && abs(Int(bandwidthHz) - Int(other.bandwidthHz)) <= 100
            && spreadingFactor == other.spreadingFactor && codingRate == other.codingRate
    }

    /// "910.525 MHz"
    var frequencyText: String { Self.format(frequencyMHz, maxDecimals: 3) + " MHz" }
    /// "62.5"
    var bandwidthText: String { Self.format(bandwidthKHz, maxDecimals: 2) }
    /// "910.525 MHz · SF7 · BW 62.5"
    var summary: String { "\(frequencyText) · SF\(spreadingFactor) · BW \(bandwidthText)" }
    /// "910.525 MHz · SF7 · BW 62.5 · CR5"
    var fullSummary: String { "\(summary) · CR\(codingRate)" }

    /// Dot decimal separator in every locale, no trailing zeros.
    static func format(_ v: Double, maxDecimals: Int) -> String {
        var s = String(format: "%.\(maxDecimals)f", locale: Locale(identifier: "en_US_POSIX"), v)
        if s.contains(".") {
            while s.hasSuffix("0") { s.removeLast() }
            if s.hasSuffix(".") { s.removeLast() }
        }
        return s
    }

    /// Reads a typed frequency ("910.525" or "910,525"); nil if it is not a number.
    static func parseMHz(_ text: String) -> Double? {
        let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard !t.isEmpty, let v = Double(t), v.isFinite else { return nil }
        return v
    }

    /// The US ISM band LoRa may use (FCC Part 15.247).
    static let usBandMHz: ClosedRange<Double> = 902 ... 928
}

/// One of the official MeshCore regional presets.
struct MeshCoreRadioPreset: Identifiable, Hashable, Sendable {
    var title: String
    var params: MeshCoreRadioParams
    var id: String { title }

    private static func p(_ title: String, _ mhz: Double, _ bw: Double, _ sf: UInt8, _ cr: UInt8) -> MeshCoreRadioPreset {
        MeshCoreRadioPreset(title: title, params: MeshCoreRadioParams(frequencyMHz: mhz, bandwidthKHz: bw,
                                                                      spreadingFactor: sf, codingRate: cr))
    }

    /// Snapshot of api.meshcore.nz/api/v1/config (suggested_radio_settings), 2026-11-04.
    /// docs/faq.md also names the "USA/Canada (Recommended)" preset as
    /// 910.525 MHz, SF7, BW62.5, CR5.
    static let official: [MeshCoreRadioPreset] = [
        p("USA", 910.525, 62.5, 7, 5),
        p("Canada", 910.525, 62.5, 7, 5),
        p("USA - Southern California", 927.875, 62.5, 7, 5),
        p("Costa Rica", 910.525, 125, 11, 5),
        p("Brazil", 923.125, 62.5, 8, 8),
        p("Australia", 915.800, 250, 10, 5),
        p("Australia (Narrow)", 916.575, 62.5, 7, 7),
        p("Australia (Mid)", 915.075, 125, 9, 5),
        p("Australia: SA, WA", 923.125, 62.5, 8, 8),
        p("Australia: QLD", 923.125, 62.5, 8, 5),
        p("New Zealand (Narrow)", 917.375, 62.5, 7, 5),
        p("New Zealand (Gisborne)", 917.375, 250, 11, 5),
        p("EU/UK (Narrow)", 869.618, 62.5, 8, 8),
        p("EU/UK (Deprecated)", 869.525, 250, 11, 5),
        p("EU 433MHz (Long Range)", 433.650, 250, 11, 5),
        p("EU 433MHz (Narrow)", 433.650, 62.5, 8, 8),
        p("Czech Republic (Narrow)", 869.432, 62.5, 7, 5),
        p("Hungary", 869.618, 62.5, 7, 5),
        p("Netherlands", 869.618, 62.5, 7, 5),
        p("Netherlands (Limburg)", 869.618, 62.5, 8, 8),
        p("Portugal 433", 433.375, 62.5, 9, 6),
        p("Portugal 868", 869.618, 62.5, 7, 6),
        p("Slovakia", 869.618, 62.5, 7, 5),
        p("Switzerland", 869.618, 62.5, 8, 8),
        p("Thailand", 921.000, 62.5, 7, 5),
        p("Vietnam (Narrow)", 920.250, 62.5, 8, 5),
        p("Vietnam (Deprecated)", 920.250, 250, 11, 5),
    ]

    static var usa: MeshCoreRadioPreset { official[0] }

    /// The first preset with exactly these settings.
    static func matching(_ p: MeshCoreRadioParams) -> MeshCoreRadioPreset? {
        official.first { $0.params.sameChannel(as: p) }
    }
}

// MARK: - Match radios near you

/// The most common MeshCore settings among the downloaded nearby radios.
struct MeshCoreNearbyConsensus: Equatable, Sendable {
    var params: MeshCoreRadioParams
    /// Radios using exactly these settings.
    var count: Int
    /// Radios that reported settings at all.
    var total: Int
    /// True when only radios heard in the last `recentDays` were counted.
    var recentOnly: Bool

    static let recentDays = 30

    /// "Used by 413 of 436 nearby radios"
    var usageText: String {
        "Used by \(count) of \(total) nearby radio\(total == 1 ? "" : "s")\(recentOnly ? " heard in the last \(Self.recentDays) days" : "")"
    }

    /// Picks the settings most nearby MeshCore radios use. Radios heard in the
    /// last 30 days count when there are any (old map entries may still show
    /// settings a mesh has since moved away from). Radios with no or invalid
    /// settings are skipped. Ties go to an official preset, then to the
    /// settings heard most recently, then to the lowest frequency.
    static func compute(_ radios: [KnownRadio], now: Date = Date()) -> MeshCoreNearbyConsensus? {
        let usable = radios.filter { $0.firmware == .meshcore && $0.params?.validationError == nil && $0.params != nil }
        let cutoff = now.addingTimeInterval(-Double(recentDays) * 86_400)
        let recent = usable.filter { ($0.lastSeen ?? .distantPast) >= cutoff }
        let pool = recent.isEmpty ? usable : recent
        guard !pool.isEmpty else { return nil }

        var groups: [MeshCoreRadioParams: (count: Int, latest: Date)] = [:]
        for r in pool {
            guard let p = r.params else { continue }
            let g = groups[p] ?? (0, .distantPast)
            groups[p] = (g.count + 1, max(g.latest, r.lastSeen ?? .distantPast))
        }
        let best = groups.max { a, b in
            // `max` wants "a is less than b".
            if a.value.count != b.value.count { return a.value.count < b.value.count }
            let aOfficial = MeshCoreRadioPreset.matching(a.key) != nil
            let bOfficial = MeshCoreRadioPreset.matching(b.key) != nil
            if aOfficial != bOfficial { return !aOfficial }
            if a.value.latest != b.value.latest { return a.value.latest < b.value.latest }
            return (a.key.frequencyKHz, a.key.bandwidthHz, a.key.spreadingFactor, a.key.codingRate)
                > (b.key.frequencyKHz, b.key.bandwidthHz, b.key.spreadingFactor, b.key.codingRate)
        }
        guard let best else { return nil }
        return MeshCoreNearbyConsensus(params: best.key, count: best.value.count, total: pool.count,
                                       recentOnly: !recent.isEmpty && recent.count < usable.count)
    }
}

/// The gentle note on a MeshCore radio card when it is not on the settings
/// most radios nearby use.
enum MeshCoreRadioHint {
    /// At least this many nearby radios must agree before the card says anything.
    static let minimumRadios = 2

    static func text(current: MeshCoreRadioParams?, nearby: MeshCoreNearbyConsensus?) -> String? {
        guard let current, let nearby, nearby.count >= minimumRadios,
              !current.sameChannel(as: nearby.params) else { return nil }
        if abs(Int(current.frequencyKHz) - Int(nearby.params.frequencyKHz)) > 1 {
            return "Most radios near you use \(nearby.params.frequencyText)"
        }
        return "Most radios near you use \(nearby.params.fullSummary)"
    }
}

// MARK: - Meshtastic regions and modem presets

/// Config.LoRaConfig.RegionCode values (config.proto).
struct MeshtasticRegion: Identifiable, Hashable, Sendable {
    var code: UInt32
    /// Short form for cards: "US", "EU 868".
    var short: String
    var name: String
    /// Added in recent firmware: older radios may not know it.
    var recentFirmware = false
    var id: UInt32 { code }

    static let unset: UInt32 = 0
    static let us: UInt32 = 1

    static let all: [MeshtasticRegion] = [
        .init(code: 1, short: "US", name: "United States, Canada, Mexico (902–928 MHz)"),
        .init(code: 2, short: "EU 433", name: "Europe 433 MHz"),
        .init(code: 3, short: "EU 868", name: "Europe and UK 868 MHz"),
        .init(code: 4, short: "CN", name: "China"),
        .init(code: 5, short: "JP", name: "Japan"),
        .init(code: 6, short: "ANZ", name: "Australia and New Zealand (915–928 MHz)"),
        .init(code: 7, short: "KR", name: "Korea"),
        .init(code: 8, short: "TW", name: "Taiwan"),
        .init(code: 9, short: "RU", name: "Russia"),
        .init(code: 10, short: "IN", name: "India"),
        .init(code: 11, short: "NZ 865", name: "New Zealand 865 MHz"),
        .init(code: 12, short: "TH", name: "Thailand"),
        .init(code: 13, short: "2.4 GHz", name: "2.4 GHz (worldwide, 2.4 GHz radios only)"),
        .init(code: 14, short: "UA 433", name: "Ukraine 433 MHz"),
        .init(code: 15, short: "UA 868", name: "Ukraine 868 MHz (old)"),
        .init(code: 16, short: "MY 433", name: "Malaysia 433 MHz"),
        .init(code: 17, short: "MY 919", name: "Malaysia 919 MHz"),
        .init(code: 18, short: "SG 923", name: "Singapore 923 MHz"),
        .init(code: 19, short: "PH 433", name: "Philippines 433 MHz"),
        .init(code: 20, short: "PH 868", name: "Philippines 868 MHz"),
        .init(code: 21, short: "PH 915", name: "Philippines 915 MHz"),
        .init(code: 22, short: "ANZ 433", name: "Australia and New Zealand 433 MHz"),
        .init(code: 23, short: "KZ 433", name: "Kazakhstan 433 MHz"),
        .init(code: 24, short: "KZ 863", name: "Kazakhstan 863 MHz"),
        .init(code: 25, short: "NP 865", name: "Nepal 865 MHz"),
        .init(code: 26, short: "BR 902", name: "Brazil 902 MHz"),
        .init(code: 27, short: "ITU1 2m", name: "Amateur 2 m, ITU Region 1 (licensed)", recentFirmware: true),
        .init(code: 28, short: "ITU2 2m", name: "Amateur 2 m, ITU Region 2 (licensed)", recentFirmware: true),
        .init(code: 29, short: "EU 866", name: "Europe 866 MHz", recentFirmware: true),
        .init(code: 30, short: "EU 874", name: "Europe 874 MHz", recentFirmware: true),
        .init(code: 31, short: "EU 917", name: "Europe 917 MHz", recentFirmware: true),
        .init(code: 32, short: "EU 868 N", name: "Europe 868 MHz, narrow", recentFirmware: true),
        .init(code: 33, short: "ITU3 2m", name: "Amateur 2 m, ITU Region 3 (licensed)", recentFirmware: true),
        .init(code: 34, short: "ITU1 70cm", name: "Amateur 70 cm, ITU Region 1 (licensed)", recentFirmware: true),
        .init(code: 35, short: "ITU2 70cm", name: "Amateur 70 cm, ITU Region 2 (licensed)", recentFirmware: true),
        .init(code: 36, short: "ITU3 70cm", name: "Amateur 70 cm, ITU Region 3 (licensed)", recentFirmware: true),
        .init(code: 37, short: "ITU2 1.25m", name: "Amateur 1.25 m, ITU Region 2 (licensed)", recentFirmware: true),
    ]

    static func find(_ code: UInt32) -> MeshtasticRegion? { all.first { $0.code == code } }

    static func shortName(_ code: UInt32) -> String {
        code == unset ? "Region not set" : (find(code)?.short ?? "Region \(code)")
    }

    /// A region to suggest for the phone's country (ISO 3166 code), or nil.
    static func suggested(forCountry country: String?) -> UInt32? {
        guard let c = country?.uppercased() else { return nil }
        let eu: Set<String> = ["AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR",
                               "HR", "HU", "IE", "IS", "IT", "LI", "LT", "LU", "LV", "MT", "NL", "NO", "PL", "PT",
                               "RO", "SE", "SI", "SK"]
        if eu.contains(c) { return 3 }
        switch c {
        case "US", "CA", "MX", "PR", "GU", "VI", "AS", "MP": return 1
        case "AU", "NZ": return 6
        case "BR": return 26
        case "CN": return 4
        case "JP": return 5
        case "KR": return 7
        case "TW": return 8
        case "RU": return 9
        case "IN": return 10
        case "TH": return 12
        case "MY": return 17
        case "SG": return 18
        case "PH": return 21
        case "KZ": return 24
        case "NP": return 25
        default: return nil
        }
    }
}

/// Config.LoRaConfig.ModemPreset values (config.proto).
struct MeshtasticModemPreset: Identifiable, Hashable, Sendable {
    var code: UInt32
    /// The name Meshtastic shows as the default channel name: "LongFast".
    var short: String
    var name: String
    var deprecated = false
    var recentFirmware = false
    var id: UInt32 { code }

    static let longFast: UInt32 = 0

    static let all: [MeshtasticModemPreset] = [
        .init(code: 0, short: "LongFast", name: "Long range, fast (default)"),
        .init(code: 7, short: "LongModerate", name: "Long range, moderate"),
        .init(code: 9, short: "LongTurbo", name: "Long range, turbo"),
        .init(code: 3, short: "MediumSlow", name: "Medium range, slow"),
        .init(code: 4, short: "MediumFast", name: "Medium range, fast"),
        .init(code: 5, short: "ShortSlow", name: "Short range, slow"),
        .init(code: 6, short: "ShortFast", name: "Short range, fast"),
        .init(code: 8, short: "ShortTurbo", name: "Short range, turbo"),
        .init(code: 1, short: "LongSlow", name: "Long range, slow (retired)", deprecated: true),
        .init(code: 2, short: "VeryLongSlow", name: "Very long range, slow (retired)", deprecated: true),
        .init(code: 16, short: "MediumTurbo", name: "Medium range, turbo", recentFirmware: true),
        .init(code: 10, short: "LiteFast", name: "Lite, fast", recentFirmware: true),
        .init(code: 11, short: "LiteSlow", name: "Lite, slow", recentFirmware: true),
        .init(code: 12, short: "NarrowFast", name: "Narrow, fast", recentFirmware: true),
        .init(code: 13, short: "NarrowSlow", name: "Narrow, slow", recentFirmware: true),
        .init(code: 14, short: "TinyFast", name: "Tiny, fast", recentFirmware: true),
        .init(code: 15, short: "TinySlow", name: "Tiny, slow", recentFirmware: true),
    ]

    static func find(_ code: UInt32) -> MeshtasticModemPreset? { all.first { $0.code == code } }

    static func shortName(_ code: UInt32) -> String { find(code)?.short ?? "Preset \(code)" }
}

// MARK: - Apply progress

/// Where a radio-settings change is, shown on the Radio settings screen.
enum RadioApplyState: Equatable, Sendable {
    case idle
    /// Talking to the radio.
    case working(String)
    /// Saved; the radio is restarting and will reconnect by itself.
    case waitingForRadio(String)
    /// The radio reported the new settings back.
    case done(String)
    case failed(String)

    var isBusy: Bool {
        switch self {
        case .working, .waitingForRadio: true
        default: false
        }
    }
}

extension MeshRadioInfo {
    /// What the radio card shows for its settings: "910.525 MHz · SF7 · BW 62.5"
    /// or "US · LongFast".
    var settingsSummary: String? {
        switch firmware {
        case .meshcore?:
            return meshCoreParams?.summary ?? regionName
        case .meshtastic?, nil:
            guard let region else { return regionName }
            if region == MeshtasticRegion.unset { return "Region not set" }
            let preset = usePreset == false ? "Custom" : MeshtasticModemPreset.shortName(modemPreset ?? 0)
            return "\(MeshtasticRegion.shortName(region)) · \(preset)"
        }
    }
}
