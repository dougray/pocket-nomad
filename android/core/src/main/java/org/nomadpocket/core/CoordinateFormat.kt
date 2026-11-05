package org.nomadpocket.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Position formats. A port of Shared/CoordinateFormat.swift. */
object CoordinateFormat {
    fun decimal(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    fun dms(lat: Double, lon: Double): String {
        fun part(v: Double, pos: String, neg: String): String {
            val a = abs(v)
            val d = a.toInt()
            val m = ((a - d) * 60).toInt()
            val s = (a - d - m / 60.0) * 3600
            return String.format(Locale.US, "%d°%02d′%04.1f″%s", d, m, s, if (v >= 0) pos else neg)
        }
        return "${part(lat, "N", "S")} ${part(lon, "E", "W")}"
    }

    /** MGRS (the grid search-and-rescue teams use), 1 m precision. Valid 80°S–84°N. */
    fun mgrs(lat: Double, lon: Double): String? {
        if (lat < -80 || lat > 84) return null
        var zone = ((lon + 180) / 6).toInt() + 1
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32
        if (lat >= 72 && lat < 84) {
            if (lon >= 0 && lon < 9) zone = 31
            else if (lon >= 9 && lon < 21) zone = 33
            else if (lon >= 21 && lon < 33) zone = 35
            else if (lon >= 33 && lon < 42) zone = 37
        }
        val (e, n) = utm(lat, lon, zone)
        val bands = "CDEFGHJKLMNPQRSTUVWXX"
        val band = bands[min(20, max(0, ((lat + 80) / 8).toInt()))]
        val colSets = listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
        val rowLetters = "ABCDEFGHJKLMNPQRSTUV"
        val set = (zone - 1) % 3
        val col = colSets[set][((e / 100_000).toInt() - 1) % 8]
        val rowOffset = if (zone % 2 == 0) 5 else 0
        val row = rowLetters[((n / 100_000).toInt() % 20 + rowOffset) % 20]
        val east = e.toInt() % 100_000
        val north = n.toInt() % 100_000
        return String.format(Locale.US, "%d%c %c%c %05d %05d", zone, band, col, row, east, north)
    }

    private fun utm(lat: Double, lon: Double, zone: Int): Pair<Double, Double> {
        val a = 6_378_137.0
        val f = 1 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)
        val phi = lat * Math.PI / 180
        val lon0 = ((zone - 1) * 6 - 180 + 3).toDouble() * Math.PI / 180
        val bigN = a / sqrt(1 - e2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = ep2 * cos(phi) * cos(phi)
        val bigA = cos(phi) * (lon * Math.PI / 180 - lon0)
        val m = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * phi
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * phi)
            + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * phi)
            - (35 * e2 * e2 * e2 / 3072) * sin(6 * phi))
        val easting = k0 * bigN * (bigA + (1 - t + c) * bigA.pow(3) / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * bigA.pow(5) / 120) + 500_000
        var northing = k0 * (m + bigN * tan(phi) * (bigA * bigA / 2 + (5 - t + 9 * c + 4 * c * c) * bigA.pow(4) / 24
            + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * bigA.pow(6) / 720))
        if (lat < 0) northing += 10_000_000
        return easting to northing
    }

    private val cardinals = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** Swift's `.rounded()` rounds half away from zero, as does [Math.round] for positives. */
    fun cardinal(degrees: Double): String = cardinals[Math.round(degrees / 22.5).toInt().mod(16)]

    /** Whole degrees as shown on the compass, rounded like Swift's `.rounded()`. */
    fun wholeDegrees(degrees: Double): Int = degrees.roundToInt()
}
