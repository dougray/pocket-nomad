// Nvis.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// NVIS (Near Vertical Incidence Skywave): short-range HF, roughly 0 to 300 miles, by sending the signal almost
// straight up so the ionosphere bounces it back down around you. A rule-of-thumb planner, not a propagation
// prediction: which band to try now, wire lengths for it, and a map ring. Port of Nvis.swift.

package org.nomadpocket.ham

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class NvisBand(val name: String, val mhz: Double, val js8mhz: Double, val ft8mhz: Double)

object Nvis {
    /** The HF bands that work for NVIS, lowest first. 60 m uses its channel near 5.357 MHz. */
    val bands = listOf(
        NvisBand("160 m", 1.9, 1.842, 1.840), NvisBand("80 m", 3.6, 3.578, 3.573), NvisBand("60 m", 5.357, 5.357, 5.357),
        NvisBand("40 m", 7.1, 7.078, 7.074), NvisBand("30 m", 10.12, 10.130, 10.136),
    )

    /** 300 miles in kilometres: the outer edge of NVIS coverage. */
    const val RANGE_KM = 482.803

    /** Elevation of the sun above the horizon in degrees (NOAA's low-precision formulas). */
    fun sunElevation(latitude: Double, longitude: Double, epochMillis: Long): Double {
        val jd = epochMillis / 86_400_000.0 + 2440587.5
        val n = jd - 2451545.0
        val l = (280.460 + 0.9856474 * n) % 360
        val g = ((357.528 + 0.9856003 * n) % 360) * PI / 180
        val lambda = (l + 1.915 * sin(g) + 0.020 * sin(2 * g)) * PI / 180
        val eps = (23.439 - 0.0000004 * n) * PI / 180
        val dec = asin(sin(eps) * sin(lambda))
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val gmst = (18.697374558 + 24.06570982441908 * n) % 24
        val hour = (gmst * 15 + longitude) * PI / 180 - ra
        val lat = latitude * PI / 180
        return asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hour)) * 180 / PI
    }

    data class Advice(val foF2: Double, val fot: Double, val luf: Double, val day: Double, val best: NvisBand?, val alternate: NvisBand?, val stormed: Boolean, val summary: String)

    /** A rough guess at which band works for NVIS right now. sfi: 10.7 cm solar flux (about 65 to 250); kp 0 to 9. */
    fun advise(sfi: Double, kp: Double, sunElevation: Double): Advice {
        val day = max(0.0, min(1.0, (sunElevation + 6) / 26))
        val dayF = 4.5 + (sfi - 70) * 0.035
        val nightF = 2.4 + (sfi - 70) * 0.012
        var fo = nightF + day * (dayF - nightF)
        val stormed = kp >= 5
        if (kp >= 7) fo *= 0.6 else if (kp >= 5) fo *= 0.8
        val fot = fo * 0.85
        val luf = 1.8 + day * 1.7
        val usable = bands.filter { it.mhz <= fot && it.mhz >= luf }
        val best = usable.lastOrNull()
        val alt = usable.dropLast(1).lastOrNull()
        val summary = when {
            best != null -> "${best.name} should work for NVIS now" + (alt?.let { ", with ${it.name} as a backup" } ?: "") + "."
            fot < luf -> "NVIS looks poor right now: the usable window is closed. Try again after dusk or dawn, or try ${if (day > 0.5) "60 m or 80 m" else "160 m or 80 m"} anyway."
            else -> "No standard band sits in the window right now. Try the nearest one below ${"%.1f".format(fot)} MHz."
        }
        return Advice(fo, fot, luf, day, best, alt, stormed, summary)
    }

    data class Antenna(val band: NvisBand, val dipoleMetres: Double, val dipoleFeet: Double, val heightMinMetres: Double, val heightMaxMetres: Double)

    /** Half-wave dipole / end-fed half-wave length, and the height range that favours NVIS (a tenth to a quarter wavelength). */
    fun antenna(band: NvisBand): Antenna {
        val wavelength = 299.792458 / band.mhz
        val metres = 142.65 / band.mhz
        return Antenna(band, metres, metres * 3.28084, 0.1 * wavelength, 0.25 * wavelength)
    }

    /** Points on a circle on the Earth's surface (for the map ring), as (latitude, longitude). */
    fun circle(latitude: Double, longitude: Double, radiusKm: Double, points: Int = 72): List<Pair<Double, Double>> {
        val d = radiusKm / 6371.0088
        val lat1 = latitude * PI / 180; val lon1 = longitude * PI / 180
        return (0..points).map { i ->
            val b = 2 * PI * i / points
            val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
            val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
            (lat2 * 180 / PI) to ((lon2 * 180 / PI + 540) % 360 - 180)
        }
    }

    fun parseFlux(json: String): Double? = runCatching {
        val a = org.json.JSONArray(json)
        a.getJSONObject(0).getDouble("flux").takeIf { it in 50.0..500.0 }
    }.getOrNull()
}
