// HamSettings.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// The station's settings. The callsign plus the licence confirmation decide
// whether anything is ever transmitted; without them the app only listens.
// The Winlink password lives in the Keychain, on this phone only.

import Foundation
import Observation
import Security

struct HamSavedStation: Codable, Hashable, Identifiable, Sendable {
    var id = UUID()
    var call: String
    var path = ""
    var note = ""
}

@MainActor
@Observable
final class HamSettings {
    static let shared = HamSettings()

    private let defaults: UserDefaults
    private let keychainService: String

    init(defaults: UserDefaults = .standard, keychainService: String = "org.nomadpocket.ham") {
        self.defaults = defaults
        self.keychainService = keychainService
        callsign = defaults.string(forKey: "ham.callsign") ?? ""
        licensed = defaults.bool(forKey: "ham.licensed")
        aprsSSID = defaults.object(forKey: "ham.aprsSSID") as? Int ?? 7
        aprsPath = defaults.string(forKey: "ham.aprsPath") ?? APRS.defaultPath
        symbol = defaults.string(forKey: "ham.symbol") ?? "/["
        beaconComment = defaults.string(forKey: "ham.beaconComment") ?? "Pocket Nomad"
        beaconMinutes = defaults.object(forKey: "ham.beaconMinutes") as? Int ?? 0
        txDelay = defaults.object(forKey: "ham.txDelay") as? Int ?? 30
        persistence = defaults.object(forKey: "ham.persistence") as? Int ?? 63
        slotTime = defaults.object(forKey: "ham.slotTime") as? Int ?? 10
        window = defaults.object(forKey: "ham.window") as? Int ?? 4
        paclen = defaults.object(forKey: "ham.paclen") as? Int ?? 128
        acceptIncoming = defaults.bool(forKey: "ham.acceptIncoming")
        autoAck = defaults.object(forKey: "ham.autoAck") as? Bool ?? true
        gateways = Self.load(defaults, "ham.gateways") ?? []
        nodes = Self.load(defaults, "ham.nodes") ?? []
        lastDevice = defaults.string(forKey: "ham.lastDevice").flatMap(UUID.init(uuidString:))
    }

    var callsign: String { didSet { defaults.set(callsign, forKey: "ham.callsign") } }
    /// The user confirmed they hold an amateur licence for this callsign.
    var licensed: Bool { didSet { defaults.set(licensed, forKey: "ham.licensed") } }
    var aprsSSID: Int { didSet { defaults.set(aprsSSID, forKey: "ham.aprsSSID") } }
    var aprsPath: String { didSet { defaults.set(aprsPath, forKey: "ham.aprsPath") } }
    /// Two characters: symbol table then code ("/[" = person, "/>" = car).
    var symbol: String { didSet { defaults.set(symbol, forKey: "ham.symbol") } }
    var beaconComment: String { didSet { defaults.set(beaconComment, forKey: "ham.beaconComment") } }
    /// 0 = only when you tap Beacon.
    var beaconMinutes: Int { didSet { defaults.set(beaconMinutes, forKey: "ham.beaconMinutes") } }
    var txDelay: Int { didSet { defaults.set(txDelay, forKey: "ham.txDelay") } }
    var persistence: Int { didSet { defaults.set(persistence, forKey: "ham.persistence") } }
    var slotTime: Int { didSet { defaults.set(slotTime, forKey: "ham.slotTime") } }
    var window: Int { didSet { defaults.set(window, forKey: "ham.window") } }
    var paclen: Int { didSet { defaults.set(paclen, forKey: "ham.paclen") } }
    var acceptIncoming: Bool { didSet { defaults.set(acceptIncoming, forKey: "ham.acceptIncoming") } }
    var autoAck: Bool { didSet { defaults.set(autoAck, forKey: "ham.autoAck") } }
    var gateways: [HamSavedStation] { didSet { Self.save(defaults, "ham.gateways", gateways) } }
    var nodes: [HamSavedStation] { didSet { Self.save(defaults, "ham.nodes", nodes) } }
    var lastDevice: UUID? { didSet { defaults.set(lastDevice?.uuidString, forKey: "ham.lastDevice") } }

    /// Upper-cased, SSID and spaces removed.
    var baseCall: String {
        callsign.trimmingCharacters(in: .whitespaces).uppercased().split(separator: "-").first.map(String.init) ?? ""
    }

    var callsignValid: Bool { Self.isValidCallsign(baseCall) }
    /// Transmitting needs a valid callsign and the licence confirmation.
    var canTransmit: Bool { licensed && callsignValid }

    var aprsAddress: AX25Address? { canTransmit ? AX25Address(call: baseCall, ssid: UInt8(clamping: aprsSSID)) : nil }
    var stationAddress: AX25Address? { canTransmit ? AX25Address(call: baseCall) : nil }

    var linkConfig: AX25LinkConfig {
        var c = AX25LinkConfig()
        c.window = max(1, min(7, window))
        c.paclen = max(32, min(256, paclen))
        return c
    }

    /// Amateur callsigns: a prefix (letters and digits) with a digit, then a suffix ending in a letter.
    static func isValidCallsign(_ c: String) -> Bool {
        c.range(of: "^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,3}[A-Z]$", options: .regularExpression) != nil && c.count <= 6
    }

    // MARK: Winlink password (Keychain)

    var winlinkPassword: String? {
        get {
            var q = keychainQuery
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: AnyObject?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
            return String(decoding: d, as: UTF8.self)
        }
        set {
            SecItemDelete(keychainQuery as CFDictionary)
            guard let newValue, !newValue.isEmpty else { return }
            var q = keychainQuery
            q[kSecValueData as String] = Data(newValue.utf8)
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(q as CFDictionary, nil)
        }
    }

    var hasWinlinkPassword: Bool { winlinkPassword?.isEmpty == false }

    private var keychainQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: keychainService,
         kSecAttrAccount as String: "winlink"]
    }

    private static func load<T: Decodable>(_ d: UserDefaults, _ key: String) -> T? {
        d.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }

    private static func save<T: Encodable>(_ d: UserDefaults, _ key: String, _ v: T) {
        d.set(try? JSONEncoder().encode(v), forKey: key)
    }
}
