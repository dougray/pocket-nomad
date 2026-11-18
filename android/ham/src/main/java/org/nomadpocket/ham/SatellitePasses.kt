// SatellitePasses.kt
// Pocket Nomad - Satellites module
// SPDX-License-Identifier: Apache-2.0
//
// Where a satellite is in the sky for an observer, and when it next passes.
// Pure maths on top of SGP4; no network. Port of SatellitePasses.swift, verified
// against the Python Skyfield library.

package org.nomadpocket.ham

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Observer(val latitude: Double, val longitude: Double, val altitudeMetres: Double = 0.0)

/** Azimuth degrees clockwise from north, elevation degrees above the horizon, range km. */
data class SkyPoint(val azimuth: Double, val elevation: Double, val rangeKm: Double)

data class SatellitePass(
    val noradId: Int, val name: String, val startMillis: Long, val peakMillis: Long, val endMillis: Long,
    val startAzimuth: Double, val peakAzimuth: Double, val endAzimuth: Double, val maxElevation: Double, val inProgress: Boolean = false,
) {
    val id: String get() = "$noradId-${startMillis / 1000}"
    val durationSeconds: Double get() = (endMillis - startMillis) / 1000.0
}

object SatelliteMath {
    private const val A = 6378.137
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)
    private const val RAD = PI / 180

    private fun ecef(o: Observer): DoubleArray {
        val lat = o.latitude * RAD; val lon = o.longitude * RAD
        val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
        val h = o.altitudeMetres / 1000
        return doubleArrayOf((n + h) * cos(lat) * cos(lon), (n + h) * cos(lat) * sin(lon), (n * (1 - E2) + h) * sin(lat))
    }

    fun look(s: Sgp4.State, atMillis: Long, o: Observer): SkyPoint {
        val theta = Sgp4.gmst(Sgp4.julianDate(atMillis))
        val c = cos(theta); val sn = sin(theta)
        val ex = c * s.x + sn * s.y; val ey = -sn * s.x + c * s.y; val ez = s.z
        val me = ecef(o)
        val dx = ex - me[0]; val dy = ey - me[1]; val dz = ez - me[2]
        val lat = o.latitude * RAD; val lon = o.longitude * RAD
        val sl = sin(lat); val cl = cos(lat); val so = sin(lon); val co = cos(lon)
        val east = -so * dx + co * dy
        val north = -sl * co * dx - sl * so * dy + cl * dz
        val up = cl * co * dx + cl * so * dy + sl * dz
        val range = sqrt(dx * dx + dy * dy + dz * dz)
        var az = Math.toDegrees(atan2(east, north))
        if (az < 0) az += 360
        return SkyPoint(az, Math.toDegrees(asin(up / range)), range)
    }

    fun look(sgp4: Sgp4, atMillis: Long, o: Observer): SkyPoint? = sgp4.propagateAt(atMillis)?.let { look(it, atMillis, o) }

    /** Passes above [minElevation] between [fromMillis] and [fromMillis] + [hours], soonest first. Rise and set are at the horizon. */
    fun passes(tle: Tle, o: Observer, fromMillis: Long, hours: Double = 48.0, minElevation: Double = 10.0, stepSeconds: Int = 20): List<SatellitePass> {
        val sgp4 = Sgp4.create(tle) ?: return emptyList()
        val end = fromMillis + (hours * 3_600_000).toLong()
        fun el(t: Long): Double = look(sgp4, t, o)?.elevation ?: -90.0
        fun cross(lo: Long, hi: Long): Long {
            var a = lo; var b = hi
            val aUp = el(a) > 0
            while (b - a > 500) {
                val m = a + (b - a) / 2
                if ((el(m) > 0) == aUp) a = m else b = m
            }
            return b
        }
        fun peak(a: Long, b: Long): Pair<Long, SkyPoint>? {
            val g = (sqrt(5.0) - 1) / 2
            var lo = a.toDouble(); var hi = b.toDouble()
            var x1 = hi - g * (hi - lo); var x2 = lo + g * (hi - lo)
            var f1 = el(x1.toLong()); var f2 = el(x2.toLong())
            while (hi - lo > 1000) {
                if (f1 < f2) { lo = x1; x1 = x2; f1 = f2; x2 = lo + g * (hi - lo); f2 = el(x2.toLong()) }
                else { hi = x2; x2 = x1; f2 = f1; x1 = hi - g * (hi - lo); f1 = el(x1.toLong()) }
            }
            val t = ((lo + hi) / 2).toLong()
            return look(sgp4, t, o)?.let { t to it }
        }

        val out = ArrayList<SatellitePass>()
        var t = fromMillis
        var prev = el(t)
        var riseAt: Long? = if (prev > 0) fromMillis else null
        var inProgress = prev > 0
        while (t < end) {
            val next = minOf(t + stepSeconds * 1000L, end)
            val e = el(next)
            if (prev <= 0 && e > 0) {
                riseAt = cross(t, next); inProgress = false
            } else if (prev > 0 && e <= 0 && riseAt != null) {
                val set = cross(t, next)
                val pk = peak(riseAt, set)
                val a = look(sgp4, riseAt, o); val b = look(sgp4, set, o)
                if (pk != null && pk.second.elevation >= minElevation && a != null && b != null) {
                    out.add(SatellitePass(tle.noradId, tle.name, riseAt, pk.first, set, a.azimuth, pk.second.azimuth, b.azimuth, pk.second.elevation, inProgress))
                }
                riseAt = null; inProgress = false
            }
            prev = e
            t = next
        }
        return out
    }

    fun describe(p: SatellitePass) =
        "Rises ${compass(p.startAzimuth)}, peaks ${Math.round(p.maxElevation)}° to the ${compass(p.peakAzimuth)}, sets ${compass(p.endAzimuth)}"

    fun compass(az: Double): String {
        val names = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[((((az % 360) + 360) % 360) / 22.5 + 0.5).toInt() % 16]
    }

    /** "5 min", "3 h 2 min", "2 d 5 h". */
    fun countdown(seconds: Double): String {
        val m = (maxOf(0.0, seconds) / 60).toInt()
        return when {
            m < 60 -> "$m min"
            m < 1440 -> "${m / 60} h ${m % 60} min"
            else -> "${m / 1440} d ${(m % 1440) / 60} h"
        }
    }

    /** "just now", "10 min ago", "2 h ago", "3 d ago". */
    fun age(whenMillis: Long, nowMillis: Long): String {
        val s = maxOf(0L, (nowMillis - whenMillis) / 1000)
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }
}
