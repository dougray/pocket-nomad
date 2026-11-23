package org.nomadpocket.app.tools

import java.util.Locale

/**
 * Antenna lengths and solar power sums for the radio tools. The same arithmetic as the "Antennas for Your Radios" and
 * "Solar Mesh Node" guides. A port of App/Sources/Tools/RadioMath.swift; keep the two in step.
 */
object RadioMath {
    val validFrequencyMHz = 0.1..6000.0
    const val V_DIPOLE_ANGLE_DEGREES = 120

    /** Quarter-wave whip and half-wave dipole lengths. The 0.95 shortening is the usual allowance for a wire or rod in air. */
    class AntennaLengths(val freqMHz: Double) {
        val wavelengthMetres get() = 299.792458 / freqMHz
        val quarterWaveMetres get() = 71.3 / freqMHz
        val halfWaveTotalMetres get() = 142.65 / freqMHz
        val halfWaveLegMetres get() = halfWaveTotalMetres / 2
        /** A V-dipole (satellite antenna) uses two quarter-wave elements about 120 degrees apart. */
        val vDipoleElementMetres get() = quarterWaveMetres
        fun cutLong(metres: Double) = metres * 1.03
    }

    fun antenna(freqMHz: Double): AntennaLengths? = if (freqMHz in validFrequencyMHz) AntennaLengths(freqMHz) else null

    /** "49.4 cm (19.4 in)" under a metre, "10.14 m (33 ft 3 in)" above. */
    fun lengthText(metres: Double): String {
        val inches = metres / 0.0254
        if (metres < 1) return String.format(Locale.US, "%.1f cm (%.1f in)", metres * 100, inches)
        var feet = (inches / 12).toInt()
        var rest = Math.round(inches - feet * 12).toInt()
        if (rest == 12) { feet += 1; rest = 0 }
        return String.format(Locale.US, "%.2f m (%d ft %d in)", metres, feet, rest)
    }

    sealed class Verdict {
        /** The panel makes at least 30 % more than the node uses. */
        object KeepsUp : Verdict()
        /** About what the node uses: a few cloudy days will drain the battery. */
        object Tight : Verdict()
        /** Less than the node uses; a full battery lasts this many days. */
        data class RunsDown(val daysUntilEmpty: Double) : Verdict()
    }

    class SolarResult(val loadWhPerDay: Double, val batteryWh: Double, val autonomyDays: Double, val panelWhPerDay: Double, val verdict: Verdict)

    /**
     * panelWatts: the rating; sunHours: hours of full sun a day (about 2 in winter or cloud, 3 to 4 average, 5 in summer);
     * batteryVolts and batteryMAh: all cells together (cells in parallel add capacity, not voltage); drawMA: the node's average current.
     */
    fun solar(panelWatts: Double, sunHours: Double, batteryVolts: Double, batteryMAh: Double, drawMA: Double): SolarResult? {
        if (panelWatts < 0 || sunHours < 0 || batteryVolts <= 0 || batteryMAh <= 0 || drawMA <= 0) return null
        val load = batteryVolts * drawMA / 1000 * 24
        val battery = batteryVolts * batteryMAh / 1000
        val panel = panelWatts * sunHours
        val verdict = when {
            panel >= load * 1.3 -> Verdict.KeepsUp
            panel >= load -> Verdict.Tight
            else -> Verdict.RunsDown(battery / (load - panel))
        }
        return SolarResult(load, battery, battery / load, panel, verdict)
    }
}
