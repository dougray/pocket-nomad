// ModeS.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.app.sdr

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * ADS-B (Mode S extended squitter, 1090 MHz) from raw I/Q samples, written from the public description of the format
 * ("The 1090 MHz Riddle", ICAO Annex 10). No GPL code is used. Only DF17/DF18 messages whose 24-bit parity check passes
 * are accepted, so noise almost never produces a false aircraft.
 */
object ModeS {
    const val SAMPLE_RATE = 2_000_000          // two samples per microsecond
    private const val PREAMBLE = 16            // 8 microseconds
    private const val LONG_BITS = 112
    private val POLY = 0x1FFF409

    /** 24-bit parity remainder over a whole message; 0 means the message is intact. */
    fun crc(msg: ByteArray, bits: Int = LONG_BITS): Int {
        var rem = 0
        for (i in 0 until bits) {
            val bit = (msg[i shr 3].toInt() shr (7 - (i and 7))) and 1
            rem = (rem shl 1) or bit
            if (rem and 0x1000000 != 0) rem = rem xor POLY
        }
        return rem
    }

    private fun bits(m: ByteArray, from: Int, count: Int): Int {
        var v = 0
        for (i in from until from + count) v = (v shl 1) or ((m[i shr 3].toInt() shr (7 - (i and 7))) and 1)
        return v
    }

    private val MAG = IntArray(65536).also { for (i in 0..255) for (q in 0..255) it[(i shl 8) or q] = Math.round(sqrt((i - 127.5) * (i - 127.5) + (q - 127.5) * (q - 127.5)) * 360).toInt() }

    /** Finds messages in a stream of unsigned 8-bit I/Q at [SAMPLE_RATE], keeping the tail between calls. */
    class Demodulator {
        private var tail = IntArray(0)

        fun feed(iq: ByteArray, length: Int = iq.size): List<ByteArray> {
            val n = length / 2
            val m = IntArray(tail.size + n)
            System.arraycopy(tail, 0, m, 0, tail.size)
            for (k in 0 until n) m[tail.size + k] = MAG[((iq[2 * k].toInt() and 0xFF) shl 8) or (iq[2 * k + 1].toInt() and 0xFF)]
            val out = ArrayList<ByteArray>()
            val need = PREAMBLE + LONG_BITS * 2 + 2
            var j = 0
            val last = m.size - need
            while (j < last) {
                if (!(m[j] > m[j + 1] && m[j + 1] < m[j + 2] && m[j + 2] > m[j + 3] && m[j + 3] < m[j] && m[j + 4] < m[j] && m[j + 5] < m[j] && m[j + 6] < m[j] &&
                            m[j + 7] > m[j + 8] && m[j + 8] < m[j + 9] && m[j + 9] > m[j + 6])) { j++; continue }
                val high = (m[j] + m[j + 2] + m[j + 7] + m[j + 9]) / 6
                if (m[j + 4] > high || m[j + 5] > high || m[j + 11] > high || m[j + 12] > high || m[j + 13] > high || m[j + 14] > high) { j++; continue }
                val msg = ByteArray(LONG_BITS / 8)
                var prev = 0
                var ok = true
                for (b in 0 until LONG_BITS) {
                    val lo = m[j + PREAMBLE + 2 * b]; val hi = m[j + PREAMBLE + 2 * b + 1]
                    val delta = Math.abs(lo - hi)
                    val bit = when {
                        b > 0 && delta < 256 -> prev
                        lo == hi -> { ok = false; 0 }
                        lo > hi -> 1
                        else -> 0
                    }
                    if (!ok) break
                    prev = bit
                    if (bit == 1) msg[b shr 3] = (msg[b shr 3].toInt() or (0x80 shr (b and 7))).toByte()
                }
                val df = (msg[0].toInt() and 0xFF) shr 3
                if (ok && (df == 17 || df == 18) && crc(msg) == 0) { out.add(msg); j += (8 + LONG_BITS) * 2 } else j++
            }
            tail = m.copyOfRange(maxOf(0, last.coerceAtLeast(0)), m.size)
            return out
        }
    }

    // ---- decoding a good message --------------------------------------------------------------------------------
    private const val CHARS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######"

    fun icao(m: ByteArray) = "%06X".format(bits(m, 8, 24))
    fun typeCode(m: ByteArray) = bits(m, 32, 5)

    fun callsign(m: ByteArray): String? {
        if (typeCode(m) !in 1..4) return null
        val sb = StringBuilder()
        for (i in 0 until 8) sb.append(CHARS[bits(m, 40 + 6 * i, 6)])
        return sb.toString().replace('_', ' ').replace('#', ' ').trim()
    }

    /** Barometric altitude in feet (25 ft steps only; the rare 100 ft Gillham code returns null). */
    fun altitudeFeet(m: ByteArray): Int? {
        if (typeCode(m) !in 9..18) return null
        val code = bits(m, 40, 12)
        if (code and 0x10 == 0) return null
        val n = ((code shr 5) shl 4) or (code and 0xF)
        return n * 25 - 1000
    }

    class Velocity(val knots: Int, val track: Double, val climbFpm: Int)

    fun velocity(m: ByteArray): Velocity? {
        if (typeCode(m) != 19) return null
        val sub = bits(m, 37, 3)
        if (sub != 1 && sub != 2) return null
        val mult = if (sub == 2) 4 else 1
        val ewRaw = bits(m, 46, 10); val nsRaw = bits(m, 57, 10)
        if (ewRaw == 0 || nsRaw == 0) return null
        val ew = (ewRaw - 1) * mult * (if (bits(m, 45, 1) == 1) -1 else 1)
        val ns = (nsRaw - 1) * mult * (if (bits(m, 56, 1) == 1) -1 else 1)
        val vrRaw = bits(m, 69, 9)
        val vr = if (vrRaw == 0) 0 else (vrRaw - 1) * 64 * (if (bits(m, 68, 1) == 1) -1 else 1)
        val track = (Math.toDegrees(atan2(ew.toDouble(), ns.toDouble())) + 360.0) % 360.0
        return Velocity(Math.round(sqrt((ew * ew + ns * ns).toDouble())).toInt(), track, vr)
    }

    class Cpr(val odd: Boolean, val lat: Int, val lon: Int)

    fun cpr(m: ByteArray): Cpr? = if (typeCode(m) in 9..18) Cpr(bits(m, 53, 1) == 1, bits(m, 54, 17), bits(m, 71, 17)) else null

    fun nl(lat: Double): Int {
        val a = Math.abs(lat)
        if (a == 0.0) return 59
        if (a == 87.0) return 2
        if (a > 87.0) return 1
        val nz = 15.0
        val aa = 1 - cos(PI / (2 * nz))
        val b = cos(PI / 180 * a).let { it * it }
        return floor(2 * PI / acos(1 - aa / b)).toInt()
    }

    /** Global position from an even and an odd message; [newestIsOdd] says which one was heard last. Null if they straddle a zone edge. */
    fun globalPosition(even: Cpr, odd: Cpr, newestIsOdd: Boolean): Pair<Double, Double>? {
        val lat0 = even.lat / 131072.0; val lat1 = odd.lat / 131072.0
        val lon0 = even.lon / 131072.0; val lon1 = odd.lon / 131072.0
        val j = floor(59 * lat0 - 60 * lat1 + 0.5).toInt()
        var latE = 360.0 / 60 * (Math.floorMod(j, 60) + lat0)
        var latO = 360.0 / 59 * (Math.floorMod(j, 59) + lat1)
        if (latE >= 270) latE -= 360
        if (latO >= 270) latO -= 360
        if (nl(latE) != nl(latO)) return null
        val lat: Double; var lon: Double
        if (!newestIsOdd) {
            val ni = maxOf(nl(latE), 1); val mm = floor(lon0 * (nl(latE) - 1) - lon1 * nl(latE) + 0.5).toInt()
            lon = 360.0 / ni * (Math.floorMod(mm, ni) + lon0); lat = latE
        } else {
            val ni = maxOf(nl(latO) - 1, 1); val mm = floor(lon0 * (nl(latO) - 1) - lon1 * nl(latO) + 0.5).toInt()
            lon = 360.0 / ni * (Math.floorMod(mm, ni) + lon1); lat = latO
        }
        if (lon >= 180) lon -= 360
        return if (lat in -90.0..90.0) lat to lon else null
    }
}

/** Builds a plane list from decoded messages: a position needs an even and an odd message within 10 seconds. */
class AdsbTracker(private val onAircraft: (SdrAircraft) -> Unit) {
    private class Track(var callsign: String = "", var alt: Int = 0, var gs: Int = 0, var trk: Int = 0,
                        var even: ModeS.Cpr? = null, var evenAt: Long = 0, var odd: ModeS.Cpr? = null, var oddAt: Long = 0)

    private val tracks = HashMap<String, Track>()
    var messages = 0
        private set

    fun feed(msg: ByteArray, nowMillis: Long) {
        messages++
        val id = ModeS.icao(msg)
        val t = tracks.getOrPut(id) { Track() }
        ModeS.callsign(msg)?.let { t.callsign = it }
        ModeS.altitudeFeet(msg)?.let { t.alt = it }
        ModeS.velocity(msg)?.let { t.gs = it.knots; t.trk = Math.round(it.track).toInt() % 360 }
        val c = ModeS.cpr(msg) ?: return
        if (c.odd) { t.odd = c; t.oddAt = nowMillis } else { t.even = c; t.evenAt = nowMillis }
        val e = t.even; val o = t.odd
        if (e == null || o == null || Math.abs(t.evenAt - t.oddAt) > 10_000) return
        val p = ModeS.globalPosition(e, o, t.oddAt > t.evenAt) ?: return
        onAircraft(SdrAircraft(id, t.callsign, p.first, p.second, t.alt, t.gs, t.trk, "", nowMillis))
        if (tracks.size > 400) tracks.entries.removeAll { nowMillis - maxOf(it.value.evenAt, it.value.oddAt) > 120_000 }
    }
}
