// HamBLEProfile.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Which Bluetooth LE service carries KISS on a given TNC or radio. Radios
// disagree, so after connecting we look at every service and pick, in order:
//  1. BLE KISS (Mobilinkd; Benshi-family radios: BTech UV-Pro, Vero VR-N76, Radioddity GA-5WB)
//  2. Nordic UART (many ESP32 / LoRa APRS TNCs and newer radios)
//  3. Microchip "transparent UART" (ISSC modules)
//  4. HM-10 style FFE0/FFE1 serial (common in Chinese radio Bluetooth modules)
//  5. Any other vendor service with one notify and one write characteristic
// Pure logic, so it is unit-tested; the transports feed it what they discover.

import Foundation

struct HamBLEChar: Equatable, Sendable {
    var uuid: String
    var notify: Bool
    var write: Bool
}

struct HamBLEServiceInfo: Equatable, Sendable {
    var uuid: String
    var characteristics: [HamBLEChar]
}

struct HamBLEProfile: Equatable, Sendable {
    var name: String
    var service: String
    /// Phone → TNC.
    var write: String
    /// TNC → phone (notify or indicate).
    var notify: String

    static let bleKISS = HamBLEProfile(name: "BLE KISS", service: "00000001-BA2A-46C9-AE49-01B0961F68BB",
                                       write: "00000002-BA2A-46C9-AE49-01B0961F68BB", notify: "00000003-BA2A-46C9-AE49-01B0961F68BB")
    static let nordicUART = HamBLEProfile(name: "Nordic UART", service: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
                                          write: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E", notify: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    static let microchipUART = HamBLEProfile(name: "Microchip UART", service: "49535343-FE7D-4AE5-8FA9-9FAFD205E455",
                                             write: "49535343-8841-43F4-A8D4-ECBE34729BB3", notify: "49535343-1E4D-4BD9-BA61-23C647249616")
    static let hm10 = HamBLEProfile(name: "Serial (FFE0)", service: "0000FFE0-0000-1000-8000-00805F9B34FB",
                                    write: "0000FFE1-0000-1000-8000-00805F9B34FB", notify: "0000FFE1-0000-1000-8000-00805F9B34FB")

    static let known = [bleKISS, nordicUART, microchipUART, hm10]

    /// Services the scan looks for when "show all devices" is off.
    static let scanServices = known.map(\.service)

    /// Standard Bluetooth SIG services that never carry KISS (GAP, GATT, device info, battery, HID, ...).
    static func isStandard(_ uuid: String) -> Bool {
        let u = normalize(uuid)
        guard u.hasSuffix("-0000-1000-8000-00805F9B34FB"), u.hasPrefix("0000") else { return false }
        let short = String(u.dropFirst(4).prefix(4))
        // FExx/FFxx are vendor-assigned 16-bit UUIDs (FFE0 serial among them), not SIG profiles.
        return !(short.hasPrefix("FE") || short.hasPrefix("FF"))
    }

    /// 16-bit and 32-bit short forms ("FFE0") expanded to the full 128-bit form, upper-cased.
    static func normalize(_ uuid: String) -> String {
        let u = uuid.uppercased()
        if u.count == 4 { return "0000\(u)-0000-1000-8000-00805F9B34FB" }
        if u.count == 8 { return "\(u)-0000-1000-8000-00805F9B34FB" }
        return u
    }

    /// The best profile for what the device offers, or nil if nothing looks like a serial link.
    static func choose(_ services: [HamBLEServiceInfo]) -> HamBLEProfile? {
        let norm = services.map { s in
            HamBLEServiceInfo(uuid: normalize(s.uuid), characteristics: s.characteristics.map {
                HamBLEChar(uuid: normalize($0.uuid), notify: $0.notify, write: $0.write)
            })
        }
        for p in known {
            guard let s = norm.first(where: { $0.uuid == p.service }) else { continue }
            let w = s.characteristics.first { $0.uuid == p.write && $0.write }
            let n = s.characteristics.first { $0.uuid == p.notify && $0.notify }
            if w != nil, n != nil { return p }
        }
        // Unknown vendor service: one characteristic to write and one that notifies.
        for s in norm where !isStandard(s.uuid) {
            let notifiers = s.characteristics.filter(\.notify)
            let writers = s.characteristics.filter(\.write)
            guard let n = notifiers.first, let w = writers.first(where: { $0.uuid != n.uuid }) ?? writers.first else { continue }
            return HamBLEProfile(name: "Generic serial (\(s.uuid.prefix(8)))", service: s.uuid, write: w.uuid, notify: n.uuid)
        }
        return nil
    }

    /// One line per service for the packet monitor, so a new radio's layout can be read off the phone.
    static func describe(_ services: [HamBLEServiceInfo]) -> [String] {
        services.map { s in
            "Service \(normalize(s.uuid)): " + s.characteristics.map { c in
                c.uuid + "[" + (c.write ? "w" : "") + (c.notify ? "n" : "") + "]"
            }.joined(separator: " ")
        }
    }
}
