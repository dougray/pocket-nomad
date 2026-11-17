// Aprs.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Decodes and builds APRS packets. Port of APRS.swift (APRS101 + addenda):
// plain/timestamped/compressed positions, Mic-E, messages and acks,
// bulletins, status, objects, items, weather and third-party packets.
// Parsing works on the info bytes as ISO-8859-1 (one char per byte); free
// text is turned back into UTF-8 at the end.

package org.nomadpocket.ham

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class AprsPosition(val latitude: Double, val longitude: Double, val symbolTable: Char = '/', val symbolCode: Char = '>', val ambiguity: Int = 0)

data class AprsWeather(
    var windDirection: Int? = null, var windSpeedMph: Int? = null, var gustMph: Int? = null, var temperatureF: Int? = null,
    var rainLastHourIn: Double? = null, var rain24hIn: Double? = null, var humidity: Int? = null, var pressureMbar: Double? = null,
) {
    val summary: String
        get() {
            val parts = ArrayList<String>()
            temperatureF?.let { parts.add("$it°F") }
            humidity?.let { parts.add("$it% humidity") }
            windSpeedMph?.let { s ->
                var w = "wind $s mph"
                windDirection?.let { w += " from $it°" }
                gustMph?.takeIf { it > 0 }?.let { w += ", gusts $it" }
                parts.add(w)
            }
            pressureMbar?.let { parts.add("%.1f mbar".format(it)) }
            rainLastHourIn?.takeIf { it > 0 }?.let { parts.add("rain %.2f in/h".format(it)) }
            return parts.joinToString(", ")
        }
}

data class AprsPacket(
    var source: String,
    var destination: String,
    var path: List<String> = emptyList(),
    var kind: Kind = Kind.OTHER,
    var position: AprsPosition? = null,
    var courseDegrees: Int? = null,
    var speedKnots: Double? = null,
    var altitudeFeet: Double? = null,
    var comment: String = "",
    var addressee: String? = null,
    var text: String? = null,
    var messageId: String? = null,
    var objectName: String? = null,
    var objectAlive: Boolean = true,
    var weather: AprsWeather? = null,
    var micEStatus: String? = null,
    var messaging: Boolean = false,
    var raw: String = "",
) {
    enum class Kind { POSITION, MIC_E, MESSAGE, ACK, REJECT, BULLETIN, STATUS, OBJECT, ITEM, WEATHER, TELEMETRY, QUERY, OTHER }

    val subject: String get() = objectName ?: source
    val isEmergency: Boolean get() = micEStatus == "Emergency" || comment.uppercase().contains("EMERGENCY")
}

object Aprs {
    const val TOCALL = "APZNMD"
    const val DEFAULT_PATH = "WIDE1-1,WIDE2-1"
    private val L1 = Charsets.ISO_8859_1

    private fun utf(s: String) = String(s.toByteArray(L1), Charsets.UTF_8)

    fun decode(f: Ax25Frame): AprsPacket? {
        if (f.control !is Ax25Control.UI || f.pid != 0xF0 || f.info.isEmpty()) return null
        return decode(f.source.toString(), f.destination.toString(), f.path.map { "$it${if (it.repeated) "*" else ""}" }, f.info)
    }

    fun decodeTnc2(line: String): AprsPacket? {
        val colon = line.indexOf(':')
        val gt = line.indexOf('>')
        if (colon < 0 || gt < 0 || gt > colon) return null
        val hdr = line.substring(gt + 1, colon).split(',')
        return decode(line.substring(0, gt), hdr[0], hdr.drop(1), line.substring(colon + 1).toByteArray(Charsets.UTF_8))
    }

    fun decode(source: String, destination: String, path: List<String>, info: ByteArray): AprsPacket? {
        if (info.isEmpty()) return null
        val s = String(info, L1)
        val p = AprsPacket(source, destination, path, raw = String(info, Charsets.UTF_8))
        val type = s[0]
        val body = s.substring(1)
        when (type) {
            '!', '=' -> { p.messaging = type == '='; if (!parsePositionBody(body, p)) return fallback(p, body) }
            '/', '@' -> {
                p.messaging = type == '@'
                if (body.length <= 7 || !parsePositionBody(body.substring(7), p)) return fallback(p, body)
            }
            '`', '\'', '\u001c', '\u001d' -> if (!parseMicE(destination, s, p)) return fallback(p, body)
            ':' -> parseMessage(body, p)
            '>' -> { p.kind = AprsPacket.Kind.STATUS; p.comment = utf(body).trim() }
            ';' -> if (!parseObject(body, p)) return fallback(p, body)
            ')' -> if (!parseItem(body, p)) return fallback(p, body)
            '_' -> { p.kind = AprsPacket.Kind.WEATHER; p.weather = parseWeather(body.drop(8), null) }
            'T' -> { p.kind = AprsPacket.Kind.TELEMETRY; p.comment = utf(body) }
            '?' -> { p.kind = AprsPacket.Kind.QUERY; p.comment = utf(body) }
            '}' -> {
                val inner = decodeTnc2(utf(body)) ?: return fallback(p, body)
                inner.raw = p.raw
                return inner
            }
            else -> {
                val bang = s.indexOf('!')
                if (!(bang in 0 until 40 && parsePositionBody(s.substring(bang + 1), p))) return fallback(p, s)
            }
        }
        return p
    }

    private fun fallback(p: AprsPacket, body: String): AprsPacket {
        p.kind = AprsPacket.Kind.OTHER
        p.position = null
        p.comment = utf(body)
        return p
    }

    fun parsePositionBody(body: String, p: AprsPacket): Boolean {
        if (body.isEmpty()) return false
        p.kind = AprsPacket.Kind.POSITION
        var rest: String
        val first = body[0]
        if (first.isDigit() || first == ' ') {
            if (body.length < 19) return false
            val (lat, amb) = parseLat(body.substring(0, 8)) ?: return false
            val lon = parseLon(body.substring(9, 18)) ?: return false
            p.position = AprsPosition(lat, lon, body[8], body[18], amb)
            rest = body.substring(19)
            val wx = body[18] == '_'
            val c = rest.takeIf { it.length >= 7 && it[3] == '/' }?.substring(0, 3)?.toIntOrNull()
            val sp = rest.takeIf { it.length >= 7 && it[3] == '/' }?.substring(4, 7)?.toIntOrNull()
            if (c != null && sp != null) {
                if (wx) {
                    p.weather = parseWeather(rest.substring(7), c to sp)
                    p.kind = AprsPacket.Kind.WEATHER
                    rest = ""
                } else {
                    if (c > 0) p.courseDegrees = if (c == 360) 0 else c
                    p.speedKnots = sp.toDouble()
                    rest = rest.substring(7)
                }
            } else if (wx) {
                p.weather = parseWeather(rest, null)
                p.kind = AprsPacket.Kind.WEATHER
                rest = ""
            }
        } else {
            if (body.length < 13) return false
            val table = body[0]
            if (!(table == '/' || table == '\\' || table in 'A'..'Z' || table in 'a'..'j')) return false
            val y = base91(body.substring(1, 5)) ?: return false
            val x = base91(body.substring(5, 9)) ?: return false
            val lat = 90.0 - y / 380926.0
            val lon = -180.0 + x / 190463.0
            if (abs(lat) > 90 || abs(lon) > 180) return false
            val t = if (table in 'a'..'j') ('0' + (table - 'a')) else table
            p.position = AprsPosition(lat, lon, t, body[9])
            val c = body[10].code; val s = body[11].code; val typ = body[12].code
            if (c != ' '.code) {
                if (((typ - 33) and 0x18) == 0x10) {
                    p.altitudeFeet = 1.002.pow(((c - 33) * 91 + (s - 33)).toDouble())
                } else if (c in '!'.code..'z'.code) {
                    val course = (c - 33) * 4
                    p.courseDegrees = if (course == 360) 0 else course
                    p.speedKnots = 1.08.pow((s - 33).toDouble()) - 1
                }
            }
            rest = body.substring(13)
            if (body[9] == '_') {
                p.weather = parseWeather(rest, null)
                p.kind = AprsPacket.Kind.WEATHER
                rest = ""
            }
        }
        var comment = rest
        val a = comment.indexOf("/A=")
        if (a >= 0 && comment.length >= a + 9) {
            comment.substring(a + 3, a + 9).replace('O', '0').toDoubleOrNull()?.let {
                p.altitudeFeet = it
                comment = comment.removeRange(a, a + 9)
            }
        }
        p.comment = utf(comment).trim()
        return true
    }

    fun parseLat(s: String): Pair<Double, Int>? {
        if (s.length != 8 || s[4] != '.' || (s[7] != 'N' && s[7] != 'S')) return null
        var amb = 0
        val d = listOf(s[0], s[1], s[2], s[3], s[5], s[6]).map { if (it == ' ') { amb++; '0' } else it }
        if (!d.all { it.isDigit() }) return null
        val deg = "${d[0]}${d[1]}".toDouble()
        val min = "${d[2]}${d[3]}".toDouble() + "${d[4]}${d[5]}".toDouble() / 100
        if (deg > 90 || min >= 60) return null
        val v = deg + min / 60
        return (if (s[7] == 'S') -v else v) to amb
    }

    fun parseLon(s: String): Double? {
        if (s.length != 9 || s[5] != '.' || (s[8] != 'E' && s[8] != 'W')) return null
        val d = listOf(s[0], s[1], s[2], s[3], s[4], s[6], s[7]).map { if (it == ' ') '0' else it }
        if (!d.all { it.isDigit() }) return null
        val deg = "${d[0]}${d[1]}${d[2]}".toDouble()
        val min = "${d[3]}${d[4]}".toDouble() + "${d[5]}${d[6]}".toDouble() / 100
        if (deg > 180 || min >= 60) return null
        val v = deg + min / 60
        return if (s[8] == 'W') -v else v
    }

    fun base91(s: String): Int? {
        var v = 0
        for (c in s) {
            if (c.code < 33 || c.code > 124) return null
            v = v * 91 + c.code - 33
        }
        return v
    }

    val micEMessages = listOf("Emergency", "Priority", "Special", "Committed", "Returning", "In Service", "En Route", "Off Duty")

    fun parseMicE(destination: String, s: String, p: AprsPacket): Boolean {
        val dest = destination.substringBefore('-')
        if (dest.length != 6 || s.length < 9) return false
        val digits = IntArray(6)
        var msgBits = 0
        var custom = false
        for ((i, c) in dest.withIndex()) {
            var bit = 0
            digits[i] = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'J' -> { bit = 1; custom = true; c - 'A' }
                'K' -> { bit = 1; custom = true; 0 }
                'L' -> 0
                in 'P'..'Y' -> { bit = 1; c - 'P' }
                'Z' -> { bit = 1; 0 }
                else -> return false
            }
            if (i < 3) msgBits = (msgBits shl 1) or bit
        }
        var lat = (digits[0] * 10 + digits[1]) + ((digits[2] * 10 + digits[3]) + (digits[4] * 10 + digits[5]) / 100.0) / 60
        if (dest[3] < 'P') lat = -lat
        val b = s.map { it.code }
        var d = b[1] - 28
        if (dest[4] >= 'P') d += 100
        if (d in 180..189) d -= 80 else if (d in 190..199) d -= 190
        var m = b[2] - 28
        if (m >= 60) m -= 60
        val h = b[3] - 28
        var lon = d + (m + h / 100.0) / 60
        if (dest[5] >= 'P') lon = -lon
        if (abs(lat) > 90 || abs(lon) > 180 || d < 0 || m < 0 || h < 0) return false
        val sp = b[4] - 28; val dc = b[5] - 28; val se = b[6] - 28
        var speed = sp * 10 + dc / 10
        var course = (dc % 10) * 100 + se
        if (speed >= 800) speed -= 800
        if (course >= 400) course -= 400
        p.kind = AprsPacket.Kind.MIC_E
        p.messaging = true
        p.position = AprsPosition(lat, lon, s[8], s[7])
        if (course in 1..360) p.courseDegrees = if (course == 360) 0 else course
        p.speedKnots = speed.toDouble()
        p.micEStatus = if (custom) "Custom ${7 - msgBits}" else micEMessages[msgBits]
        var rest = s.substring(9)
        if (rest.length >= 4 && rest[3] == '}') {
            base91(rest.substring(0, 3))?.let { p.altitudeFeet = (it - 10000) * 3.28084; rest = rest.substring(4) }
        } else if (rest.length >= 5 && rest[0] in ">]`" && rest[4] == '}') {
            base91(rest.substring(1, 4))?.let { p.altitudeFeet = (it - 10000) * 3.28084; rest = rest.substring(5) }
        }
        var comment = rest
        if (comment.startsWith(">") || comment.startsWith("]")) comment = comment.drop(1)
        if (comment.endsWith("=") || comment.endsWith("^")) comment = comment.dropLast(1)
        if (comment.startsWith("`") || comment.startsWith("'")) {
            comment = comment.drop(1)
            if (comment.length >= 2) comment = comment.dropLast(2)
        }
        p.comment = utf(comment).trim()
        return true
    }

    fun parseMessage(body: String, p: AprsPacket) {
        if (body.length < 10 || body[9] != ':') { p.kind = AprsPacket.Kind.OTHER; p.comment = utf(body); return }
        val addressee = body.substring(0, 9).trim()
        var text = utf(body.substring(10))
        p.addressee = addressee
        val lower = text.lowercase()
        if ((lower.startsWith("ack") || lower.startsWith("rej")) && text.length <= 8 && !text.contains(' ')) {
            p.kind = if (lower.startsWith("ack")) AprsPacket.Kind.ACK else AprsPacket.Kind.REJECT
            p.messageId = text.substring(3).substringBefore('}')
            return
        }
        val brace = text.lastIndexOf('{')
        if (brace >= 0) {
            val id = text.substring(brace + 1).substringBefore('}')
            if (id.isNotEmpty() && id.length <= 5 && id.all { it.isLetterOrDigit() }) {
                p.messageId = id
                text = text.substring(0, brace)
            }
        }
        p.text = text
        val up = addressee.uppercase()
        p.kind = if (up.startsWith("BLN") || up.startsWith("NWS")) AprsPacket.Kind.BULLETIN else AprsPacket.Kind.MESSAGE
    }

    fun parseObject(body: String, p: AprsPacket): Boolean {
        if (body.length < 18 || (body[9] != '*' && body[9] != '_')) return false
        if (!parsePositionBody(body.substring(17), p)) return false
        if (p.kind == AprsPacket.Kind.POSITION) p.kind = AprsPacket.Kind.OBJECT
        p.objectName = body.substring(0, 9).trim()
        p.objectAlive = body[9] == '*'
        return true
    }

    fun parseItem(body: String, p: AprsPacket): Boolean {
        val end = body.indexOfFirst { it == '!' || it == '_' }
        if (end !in 3..9) return false
        if (!parsePositionBody(body.substring(end + 1), p)) return false
        if (p.kind == AprsPacket.Kind.POSITION) p.kind = AprsPacket.Kind.ITEM
        p.objectName = body.substring(0, end)
        p.objectAlive = body[end] == '!'
        return true
    }

    fun parseWeather(s: String, wind: Pair<Int, Int>?): AprsWeather {
        val w = AprsWeather()
        wind?.let { w.windDirection = it.first; w.windSpeedMph = it.second }
        var i = 0
        fun num(n: Int): String? = if (i + n <= s.length) s.substring(i, i + n).also { i += n } else null
        while (i < s.length) {
            val key = s[i]
            i += 1
            when (key) {
                'c' -> num(3)?.let { w.windDirection = it.toIntOrNull() }
                's' -> num(3)?.let { w.windSpeedMph = it.toIntOrNull() }
                'g' -> num(3)?.let { w.gustMph = it.toIntOrNull() }
                't' -> num(3)?.let { w.temperatureF = it.trim().toIntOrNull() }
                'r' -> num(3)?.toDoubleOrNull()?.let { w.rainLastHourIn = it / 100 }
                'p' -> num(3)?.toDoubleOrNull()?.let { w.rain24hIn = it / 100 }
                'P', 'L', 'l' -> num(3)
                'h' -> num(2)?.toIntOrNull()?.let { w.humidity = if (it == 0) 100 else it }
                'b' -> num(5)?.toDoubleOrNull()?.let { w.pressureMbar = it / 10 }
                else -> i = s.length
            }
        }
        return w
    }

    // MARK: Encode

    private fun dm(v: Double, degDigits: Int, pos: Char, neg: Char, max: Double): String {
        val a = minOf(abs(v), max)
        var deg = a.toInt()
        var mm = ((a - deg) * 60 * 100).roundToInt() / 100.0
        if (mm >= 60) { mm = 0.0; deg += 1 }
        return "%0${degDigits}d%05.2f%c".format(java.util.Locale.US, deg, mm, if (v < 0) neg else pos)
    }

    fun latString(lat: Double) = dm(lat, 2, 'N', 'S', 89.99999)
    fun lonString(lon: Double) = dm(lon, 3, 'E', 'W', 179.99999)

    fun positionInfo(lat: Double, lon: Double, table: Char = '/', code: Char = '[', courseDegrees: Int? = null,
                     speedKnots: Double? = null, altitudeFeet: Double? = null, comment: String = ""): String {
        var s = "=" + latString(lat) + table + lonString(lon) + code
        if (courseDegrees != null && speedKnots != null && speedKnots >= 1) {
            s += String.format(java.util.Locale.US, "%03d/%03d", if (courseDegrees == 0) 360 else courseDegrees, minOf(999, speedKnots.roundToInt()))
        }
        if (altitudeFeet != null) s += String.format(java.util.Locale.US, "/A=%06d", altitudeFeet.roundToInt().coerceIn(0, 999_999))
        if (comment.isNotEmpty()) s += comment.take(43)
        return s
    }

    fun paddedAddressee(to: String) = to.uppercase().take(9).padEnd(9, ' ')

    fun messageInfo(to: String, text: String, id: String?): String {
        var clean = text.map { c -> if (c.code in 0x20..0x7E && c !in "|~{") c else '?' }.joinToString("").take(67)
        if (id != null) clean += "{$id"
        return ":" + paddedAddressee(to) + ":" + clean
    }

    fun ackInfo(to: String, id: String) = ":" + paddedAddressee(to) + ":ack" + id
}

object HamGeo {
    fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = (bLat - aLat) * PI / 180
        val dLon = (bLon - aLon) * PI / 180
        val x = sin(dLat / 2).pow(2) + cos(aLat * PI / 180) * cos(bLat * PI / 180) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * atan2(sqrt(x), sqrt(1 - x))
    }

    fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val l1 = aLat * PI / 180; val l2 = bLat * PI / 180
        val dLon = (bLon - aLon) * PI / 180
        val y = sin(dLon) * cos(l2)
        val x = cos(l1) * sin(l2) - sin(l1) * cos(l2) * cos(dLon)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    fun maidenhead(lat: Double, lon: Double): String {
        val lo = lon + 180; val la = lat + 90
        return "" + ('A' + (lo / 20).toInt()) + ('A' + (la / 10).toInt()) + ((lo % 20) / 2).toInt() + (la % 10).toInt() +
            ('a' + ((lo % 2) * 12).toInt()) + ('a' + ((la % 1) * 24).toInt())
    }
}
