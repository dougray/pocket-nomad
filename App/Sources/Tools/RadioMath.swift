import Foundation

/// Antenna lengths and solar power sums for the radio tools. The same arithmetic as the "Antennas for Your Radios"
/// and "Solar Mesh Node" guides. A port lives in android/app/.../tools/RadioMath.kt; keep the two in step.
enum RadioMath {
    // MARK: antennas

    /// Quarter-wave whip and half-wave dipole lengths from the frequency. The 0.95 shortening is the usual
    /// allowance for a wire or rod in air; cut about 3 % long and trim for the best signal.
    struct AntennaLengths: Equatable {
        var freqMHz: Double
        var wavelengthMetres: Double { 299.792458 / freqMHz }
        var quarterWaveMetres: Double { 71.3 / freqMHz }
        var halfWaveTotalMetres: Double { 142.65 / freqMHz }
        var halfWaveLegMetres: Double { halfWaveTotalMetres / 2 }
        /// A V-dipole (satellite antenna) uses two quarter-wave elements spread about 120 degrees apart.
        var vDipoleElementMetres: Double { quarterWaveMetres }
        static let vDipoleAngleDegrees = 120
        /// Where to start cutting.
        func cutLong(_ metres: Double) -> Double { metres * 1.03 }
    }

    static let validFrequencyMHz: ClosedRange<Double> = 0.1...6000

    static func antenna(freqMHz: Double) -> AntennaLengths? {
        validFrequencyMHz.contains(freqMHz) ? AntennaLengths(freqMHz: freqMHz) : nil
    }

    /// "49.4 cm (19.5 in)" under a metre, "10.14 m (33 ft 3 in)" above.
    static func lengthText(_ metres: Double) -> String {
        let inches = metres / 0.0254
        if metres < 1 { return String(format: "%.1f cm (%.1f in)", metres * 100, inches) }
        let feet = Int(inches / 12)
        let rest = Int((inches - Double(feet) * 12).rounded())
        return rest == 12 ? String(format: "%.2f m (%d ft 0 in)", metres, feet + 1) : String(format: "%.2f m (%d ft %d in)", metres, feet, rest)
    }

    // MARK: solar node

    enum Verdict: Equatable {
        /// The panel makes at least 30 % more than the node uses.
        case keepsUp
        /// It makes about what the node uses: a few cloudy days will drain the battery.
        case tight
        /// The panel makes less than the node uses; a full battery lasts this many days.
        case runsDown(daysUntilEmpty: Double)
    }

    struct SolarResult: Equatable {
        var loadWhPerDay: Double
        var batteryWh: Double
        var autonomyDays: Double
        var panelWhPerDay: Double
        var verdict: Verdict
    }

    /// panelWatts: the panel's rating; sunHours: hours of full sun a day (about 2 in winter or cloud, 3 to 4 average, 5 in summer);
    /// batteryVolts and batteryMAh: all cells together (cells in parallel add capacity, not voltage); drawMA: the node's average current.
    static func solar(panelWatts: Double, sunHours: Double, batteryVolts: Double, batteryMAh: Double, drawMA: Double) -> SolarResult? {
        guard panelWatts >= 0, sunHours >= 0, batteryVolts > 0, batteryMAh > 0, drawMA > 0 else { return nil }
        let load = batteryVolts * drawMA / 1000 * 24
        let battery = batteryVolts * batteryMAh / 1000
        let panel = panelWatts * sunHours
        let verdict: Verdict
        if panel >= load * 1.3 { verdict = .keepsUp } else if panel >= load { verdict = .tight } else { verdict = .runsDown(daysUntilEmpty: battery / (load - panel)) }
        return SolarResult(loadWhPerDay: load, batteryWh: battery, autonomyDays: battery / load, panelWhPerDay: panel, verdict: verdict)
    }
}
