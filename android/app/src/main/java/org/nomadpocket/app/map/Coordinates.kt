// Coordinates.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Reads and writes positions in the formats people use: decimal degrees,
// degrees-minutes-seconds, degrees and decimal minutes, UTM, MGRS (the military
// grid) and Maidenhead (the ham radio grid square). Works with no network.
// Port of Coordinates.swift; UTM/MGRS are checked against the Python `utm` and
// `mgrs` libraries.

package org.nomadpocket.app.map

import org.nomadpocket.ham.HamGeo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class PositionFormat(val label: String) {
    DECIMAL("Decimal degrees"), DMS("Degrees, minutes, seconds"), DMM("Degrees, decimal minutes"), UTM("UTM"), MGRS("MGRS"), MAIDENHEAD("Maidenhead grid"),
}

data class ParsedCoordinate(val latitude: Double, val longitude: Double, val format: PositionFormat)

object Coordinates {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private const val E2 = F * (2 - F)
    private const val EP2 = E2 / (1 - E2)
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"

    data class UTM(val zone: Int, val band: Char, val easting: Double, val northing: Double) {
        val isNorth: Boolean get() = band >= 'N'
    }

    /** The UTM zone for a position, including Norway's and Svalbard's exceptions. */
    fun zone(latitude: Double, longitude: Double): Int {
        var lon = longitude
        if (lon >= 180) lon -= 360
        var z = floor((lon + 180) / 6).toInt() + 1
        if (latitude >= 56 && latitude < 64 && lon >= 3 && lon < 12) z = 32
        if (latitude >= 72 && latitude < 84) {
            z = when { lon >= 0 && lon < 9 -> 31; lon >= 9 && lon < 21 -> 33; lon >= 21 && lon < 33 -> 35; lon >= 33 && lon < 42 -> 37; else -> z }
        }
        return min(60, max(1, z))
    }

    fun band(latitude: Double): Char? =
        if (latitude < -80 || latitude > 84) null else BANDS[min(BANDS.length - 1, floor((latitude + 80) / 8).toInt())]

    private fun centralMeridian(zone: Int) = (zone - 1) * 6 - 180 + 3.0

    /** Null outside 80°S to 84°N, where UTM doesn't apply. */
    fun utm(latitude: Double, longitude: Double): UTM? {
        val band = band(latitude) ?: return null
        val z = zone(latitude, longitude)
        val phi = Math.toRadians(latitude)
        var dl = longitude - centralMeridian(z)
        if (dl > 180) dl -= 360 else if (dl < -180) dl += 360
        val lam = Math.toRadians(dl)
        val s = sin(phi); val c = cos(phi); val t = tan(phi)
        val n = A / sqrt(1 - E2 * s * s)
        val tt = t * t; val cc = EP2 * c * c; val aa = lam * c
        val m = A * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256) * phi
            - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2.pow(3) / 1024) * sin(2 * phi)
            + (15 * E2 * E2 / 256 + 45 * E2.pow(3) / 1024) * sin(4 * phi)
            - (35 * E2.pow(3) / 3072) * sin(6 * phi))
        val x = K0 * n * (aa + (1 - tt + cc) * aa.pow(3) / 6 + (5 - 18 * tt + tt * tt + 72 * cc - 58 * EP2) * aa.pow(5) / 120)
        var y = K0 * (m + n * t * (aa * aa / 2 + (5 - tt + 9 * cc + 4 * cc * cc) * aa.pow(4) / 24 + (61 - 58 * tt + tt * tt + 600 * cc - 330 * EP2) * aa.pow(6) / 720))
        if (latitude < 0) y += 10_000_000
        return UTM(z, band, x + 500_000, y)
    }

    fun latLon(u: UTM): Pair<Double, Double> {
        val x = u.easting - 500_000
        val y = if (u.isNorth) u.northing else u.northing - 10_000_000
        val m = y / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) + (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val s = sin(phi1); val c = cos(phi1); val t = tan(phi1)
        val c1 = EP2 * c * c; val t1 = t * t
        val n1 = A / sqrt(1 - E2 * s * s)
        val r1 = A * (1 - E2) / (1 - E2 * s * s).pow(1.5)
        val d = x / (n1 * K0)
        val phi = phi1 - (n1 * t / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lam = (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120) / c
        return Math.toDegrees(phi) to (centralMeridian(u.zone) + Math.toDegrees(lam))
    }

    // MARK: MGRS

    private val columnSets = listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"

    /** MGRS with [digits] per axis (5 = 1 metre), truncated like the standard says. */
    fun mgrs(latitude: Double, longitude: Double, digits: Int = 5): String? {
        val u = utm(latitude, longitude) ?: return null
        val col = columnSets[(u.zone - 1) % 3][floor(u.easting / 100_000).toInt() - 1]
        val rowIndex = (floor(u.northing / 100_000).toInt() + if (u.zone % 2 == 0) 5 else 0) % 20
        val ee = floor(u.easting % 100_000).toInt()
        val nn = floor(u.northing % 100_000).toInt()
        val d = digits.coerceIn(0, 5)
        fun pad(v: Int) = if (d == 0) "" else String.format(Locale.US, "%05d", v).take(d)
        return String.format(Locale.US, "%02d", u.zone) + "${u.band}$col${ROW_LETTERS[rowIndex]}${pad(ee)}${pad(nn)}"
    }

    /** The centre of the MGRS square named (any precision from 100 km to 1 m). */
    fun fromMGRS(text: String): Pair<Double, Double>? {
        val s = text.uppercase().filter { !it.isWhitespace() }
        val m = Regex("^(\\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\\d*)$").find(s) ?: return null
        val zone = m.groupValues[1].toInt()
        val band = m.groupValues[2][0]
        val digits = m.groupValues[5]
        if (zone !in 1..60 || digits.length % 2 != 0 || digits.length > 10) return null
        val colIdx = columnSets[(zone - 1) % 3].indexOf(m.groupValues[3][0])
        val rowIdx = ROW_LETTERS.indexOf(m.groupValues[4][0])
        if (colIdx < 0 || rowIdx < 0) return null
        val half = digits.length / 2
        val size = 10.0.pow(5 - half)
        val e = (colIdx + 1) * 100_000.0 + (digits.take(half).toDoubleOrNull() ?: 0.0) * size + (if (half == 0) 50_000.0 else size / 2)
        var nPart = ((rowIdx - (if (zone % 2 == 0) 5 else 0)) % 20 + 20) % 20 * 100_000.0 +
            (digits.takeLast(half).toDoubleOrNull() ?: 0.0) * size + (if (half == 0) 50_000.0 else size / 2)
        val bandLat = BANDS.indexOf(band) * 8.0 - 80
        val probe = utm(bandLat, centralMeridian(zone))
        val minN = (probe?.northing ?: 0.0) - 100_000
        while (nPart < minN) nPart += 2_000_000
        return latLon(UTM(zone, band, e, nPart))
    }

    // MARK: Maidenhead

    fun fromMaidenhead(text: String): Pair<Double, Double>? {
        val s = text.trim()
        if (!Regex("^[A-Ra-r]{2}\\d{2}([A-Xa-x]{2}(\\d{2})?)?$").matches(s)) return null
        val c = s.uppercase()
        fun letter(ch: Char) = (ch - 'A').toDouble()
        var lon = letter(c[0]) * 20 - 180 + c[2].digitToInt() * 2
        var lat = letter(c[1]) * 10 - 90 + c[3].digitToInt()
        var lonStep = 2.0; var latStep = 1.0
        if (c.length >= 6) { lonStep = 2.0 / 24; latStep = 1.0 / 24; lon += letter(c[4]) * lonStep; lat += letter(c[5]) * latStep }
        if (c.length == 8) { lonStep /= 10; latStep /= 10; lon += c[6].digitToInt() * lonStep; lat += c[7].digitToInt() * latStep }
        return (lat + latStep / 2) to (lon + lonStep / 2)
    }

    // MARK: Parse

    private fun valid(lat: Double, lon: Double) = abs(lat) <= 90 && abs(lon) <= 180 && lat.isFinite() && lon.isFinite()

    /** Reads whatever a person types or pastes. Null if it isn't a position. */
    fun parse(raw: String): ParsedCoordinate? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (text.lowercase().contains("http")) {
            Regex("(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)").find(text)?.let { return parsePair(it.value, PositionFormat.DECIMAL) }
        }
        text = text.replace('−', '-')
        if (Regex("^\\s*\\d{1,2}\\s*[C-HJ-NP-Xc-hj-np-x]\\s*[A-HJ-NP-Za-hj-np-z]{2}\\s*[\\d\\s]*$").matches(text)) {
            fromMGRS(text)?.let { if (valid(it.first, it.second)) return ParsedCoordinate(it.first, it.second, PositionFormat.MGRS) }
        }
        parseUtm(text)?.let { return it }
        fromMaidenhead(text)?.let { return ParsedCoordinate(it.first, it.second, PositionFormat.MAIDENHEAD) }
        return parsePair(text, null)
    }

    private fun parseUtm(text: String): ParsedCoordinate? {
        val m = Regex("^\\s*(\\d{1,2})\\s*([C-HJ-NP-Xc-hj-np-x])\\s+(\\d{5,7}(?:\\.\\d+)?)\\s*m?\\s*E?\\s*[, ]\\s*(\\d{6,8}(?:\\.\\d+)?)\\s*m?\\s*N?\\s*$").find(text) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val band = m.groupValues[2].uppercase()[0]
        val r = latLon(UTM(zone, band, m.groupValues[3].toDouble(), m.groupValues[4].toDouble()))
        return if (valid(r.first, r.second)) ParsedCoordinate(r.first, r.second, PositionFormat.UTM) else null
    }

    private enum class Axis { LAT, LON }
    private class Angle(val value: Double, val axis: Axis?, val parts: Int)

    private fun parsePair(raw: String, forced: PositionFormat?): ParsedCoordinate? {
        var text = raw
        for (w in listOf("latitude", "longitude", "lat", "lon", "lng", "long")) text = text.replace(w, " ", ignoreCase = true)
        text = text.replace('=', ' ').replace(':', ' ')
        val parts: List<String>
        val comma = text.indexOfFirst { it == ',' || it == ';' }
        if (comma >= 0) {
            parts = listOf(text.substring(0, comma), text.substring(comma + 1))
        } else {
            val split = splitAfterFirstHemisphere(text)
            if (split != null) parts = split
            else {
                val nums = text.split(' ', '\t').filter { it.isNotEmpty() }
                if (nums.size < 2 || nums.size % 2 != 0) return null
                val half = nums.size / 2
                parts = listOf(nums.take(half).joinToString(" "), nums.drop(half).joinToString(" "))
            }
        }
        val a = angle(parts[0]) ?: return null
        val b = angle(parts[1]) ?: return null
        var lat = a; var lon = b
        if (a.axis == Axis.LON || b.axis == Axis.LAT) { lat = b; lon = a }
        if (lat.axis == Axis.LON || lon.axis == Axis.LAT) return null
        if (abs(lat.value) > 90 || abs(lon.value) > 180) return null
        val fmt = forced ?: when { lat.parts >= 3 -> PositionFormat.DMS; lat.parts == 2 -> PositionFormat.DMM; else -> PositionFormat.DECIMAL }
        return ParsedCoordinate(lat.value, lon.value, fmt)
    }

    private fun splitAfterFirstHemisphere(s: String): List<String>? {
        val idx = s.indexOfFirst { it in "NSEWnsew" }
        if (idx < 0) return null
        val before = s.substring(0, idx).trim()
        if (before.isEmpty()) {
            val second = s.drop(idx + 1).indexOfFirst { it in "NSEWnsew" }
            if (second < 0) return null
            val at = idx + 1 + second
            return listOf(s.substring(0, at), s.substring(at))
        }
        return listOf(s.substring(0, idx + 1), s.substring(idx + 1))
    }

    private fun angle(s: String): Angle? {
        var hemisphere: Char? = null
        val body = StringBuilder()
        for (ch in s) if (ch in "NSEWnsew") hemisphere = ch.uppercaseChar() else body.append(ch)
        val cleaned = body.map { if (it in "°º'′’\"″”,") ' ' else it }.joinToString("")
        val nums = cleaned.split(' ').filter { it.isNotEmpty() }
        if (nums.size !in 1..3) return null
        var negative = false
        val values = ArrayList<Double>()
        for ((i, n) in nums.withIndex()) {
            val v = n.toDoubleOrNull() ?: return null
            if (i == 0 && n.startsWith("-")) negative = true
            values.add(abs(v))
        }
        if (values.size > 1 && values[1] >= 60) return null
        if (values.size > 2 && values[2] >= 60) return null
        var total = values[0]
        if (values.size > 1) total += values[1] / 60
        if (values.size > 2) total += values[2] / 3600
        if (hemisphere == 'S' || hemisphere == 'W') negative = true
        val axis = when (hemisphere) { 'N', 'S' -> Axis.LAT; 'E', 'W' -> Axis.LON; else -> null }
        return Angle(if (negative) -total else total, axis, values.size)
    }

    // MARK: Format

    fun format(latitude: Double, longitude: Double, f: PositionFormat): String = when (f) {
        PositionFormat.DECIMAL -> String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        PositionFormat.DMS -> {
            fun dms(v: Double, pos: String, neg: String): String {
                val a = abs(v)
                var d = a.toInt()
                var m = ((a - d) * 60).toInt()
                var s = ((a - d) * 60 - m) * 60
                if (s >= 59.95) { s = 0.0; m += 1 }
                if (m >= 60) { m = 0; d += 1 }
                return String.format(Locale.US, "%d°%02d'%04.1f\"%s", d, m, s, if (v < 0) neg else pos)
            }
            dms(latitude, "N", "S") + " " + dms(longitude, "E", "W")
        }
        PositionFormat.DMM -> {
            fun dmm(v: Double, pos: String, neg: String): String {
                val a = abs(v)
                var d = a.toInt()
                var m = (a - d) * 60
                if (m >= 59.9995) { m = 0.0; d += 1 }
                return String.format(Locale.US, "%d°%06.3f'%s", d, m, if (v < 0) neg else pos)
            }
            dmm(latitude, "N", "S") + " " + dmm(longitude, "E", "W")
        }
        PositionFormat.UTM -> utm(latitude, longitude)?.let { "${it.zone}${it.band} ${it.easting.toInt()}E ${it.northing.toInt()}N" } ?: "Outside UTM (poles)"
        PositionFormat.MGRS -> mgrs(latitude, longitude)?.let { m ->
            val head = m.takeWhile { it.isDigit() }
            val rest = m.drop(head.length)
            "$head${rest[0]} ${rest.substring(1, 3)} ${rest.substring(3, 8)} ${rest.substring(8)}"
        } ?: "Outside MGRS (poles)"
        PositionFormat.MAIDENHEAD -> HamGeo.maidenhead(latitude, longitude)
    }
}
