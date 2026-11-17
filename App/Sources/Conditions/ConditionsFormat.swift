// ConditionsFormat.swift
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// Units follow the phone's region: Fahrenheit, mph, inches and miles in the US,
// Celsius, km/h, millimetres and kilometres elsewhere.

import Foundation

struct ConditionsFormat: Sendable {
    var metric: Bool

    static var current: ConditionsFormat { ConditionsFormat(metric: Locale.current.measurementSystem != .us) }

    func temperature(_ c: Double) -> String { metric ? "\(Int(c.rounded()))°C" : "\(Int((c * 9 / 5 + 32).rounded()))°F" }
    func speed(_ kmh: Double) -> String { metric ? "\(Int(kmh.rounded())) km/h" : "\(Int((kmh * 0.621371).rounded())) mph" }
    func rain(_ mm: Double) -> String {
        if metric { return mm < 0.1 ? "0 mm" : String(format: "%.1f mm", mm) }
        let inches = mm / 25.4
        return inches < 0.01 ? "0 in" : String(format: "%.2f in", inches)
    }
    func distance(_ km: Double) -> String {
        let v = metric ? km : km * 0.621371
        let unit = metric ? "km" : "mi"
        return v < 10 ? String(format: "%.1f %@", v, unit) : "\(Int(v.rounded())) \(unit)"
    }
    func radius(_ km: Int) -> String { metric ? "\(km) km" : "\(Int((Double(km) * 0.621371).rounded())) mi" }
}
