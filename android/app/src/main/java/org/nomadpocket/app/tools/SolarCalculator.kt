package org.nomadpocket.app.tools

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise, sunset and twilight from the NOAA solar equations. No network needed.
 * A port of SolarCalculator in App/Sources/Tools/DaylightView.swift.
 */
object SolarCalculator {
    data class Day(val sunrise: Instant?, val sunset: Instant?, val civilDawn: Instant?, val civilDusk: Instant?)

    /** The day containing [date] in [zone], at latitude/longitude. */
    fun day(date: Instant, lat: Double, lon: Double, zone: ZoneId = ZoneId.systemDefault()): Day {
        val local = date.atZone(zone).toLocalDate()
        return Day(
            sunrise = event(local, lat, lon, 90.833, true),
            sunset = event(local, lat, lon, 90.833, false),
            civilDawn = event(local, lat, lon, 96.0, true),
            civilDusk = event(local, lat, lon, 96.0, false),
        )
    }

    private fun event(day: LocalDate, latitude: Double, longitude: Double, zenith: Double, rising: Boolean): Instant? {
        val rad = Math.PI / 180
        val noonUTC = day.atTime(12, 0).toEpochSecond(ZoneOffset.UTC).toDouble()
        // Julian century from J2000.
        val jd = noonUTC / 86400 + 2_440_587.5 - longitude / 360
        val t = (jd - 2_451_545) / 36525
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).rem(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val center = sin(m * rad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m * rad) * (0.019993 - 0.000101 * t) + sin(3 * m * rad) * 0.000289
        val trueLong = l0 + center
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(omega * rad)
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = eps0 + 0.00256 * cos(omega * rad)
        val decl = asin(sin(eps * rad) * sin(lambda * rad))
        val y = tan(eps * rad / 2).pow(2)
        val eqTime = 4 / rad * (y * sin(2 * l0 * rad) - 2 * e * sin(m * rad) + 4 * e * y * sin(m * rad) * cos(2 * l0 * rad)
            - 0.5 * y * y * sin(4 * l0 * rad) - 1.25 * e * e * sin(2 * m * rad))
        val lat = latitude * rad
        val cosH = (cos(zenith * rad) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))
        if (cosH < -1 || cosH > 1) return null // polar day or night
        val h = acos(cosH) / rad
        val minutes = 720 - 4 * longitude - eqTime + (if (rising) -4 * h else 4 * h)
        val midnight = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return Instant.ofEpochMilli(midnight * 1000 + (minutes * 60_000).toLong())
    }

    /** 0 = new moon, 0.5 = full. */
    fun moonPhase(date: Instant): Double {
        val synodic = 29.530588853
        val knownNew = 947_182_440.0 // 2000-01-06 18:14 UTC
        val days = (date.toEpochMilli() / 1000.0 - knownNew) / 86400
        val p = days.rem(synodic) / synodic
        return if (p < 0) p + 1 else p
    }

    fun moonName(p: Double): String = when {
        p < 0.03 || p >= 0.97 -> "New moon"
        p < 0.22 -> "Waxing crescent"
        p < 0.28 -> "First quarter"
        p < 0.47 -> "Waxing gibbous"
        p < 0.53 -> "Full moon"
        p < 0.72 -> "Waning gibbous"
        p < 0.78 -> "Last quarter"
        else -> "Waning crescent"
    }

    /** 90 -> "1 h 30 min", 45 -> "45 min". */
    fun duration(seconds: Long): String {
        val m = (seconds / 60).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
}
