// Ais.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.app.sdr

import org.nomadpocket.ham.HdlcDeframer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Receive-only decoder for AIS ship reports (9600 baud GMSK on 161.975 and 162.025 MHz) from FM-demodulated samples.
 * Input is the output of an FM discriminator (a positive value for the higher tone); a clock that nudges itself on every
 * transition picks the bit instants; [HdlcDeframer] rebuilds the frames and checks the CRC. Written from ITU-R M.1371 and
 * the HDLC description. Frames come out without their check sequence, bits least-significant first in received order.
 */
class AisDemodulator(sampleRate: Int, onFrame: (ByteArray) -> Unit) {
    private val bitSamples = sampleRate / 9600.0
    private val window = max(2, (bitSamples * 0.6).roundToInt())
    private val ring = DoubleArray(window)
    private var pos = 0
    private var sum = 0.0
    private var hi = 0.0
    private var lo = 0.0
    private var phase = 0.0
    private var sampled = false
    private var lastSign = 0
    private var count = 0L
    private val hdlc = HdlcDeframer(20, 140, onFrame)

    fun feed(x: FloatArray, length: Int = x.size) { for (k in 0 until length) step(x[k].toDouble()) }

    // A phase turn bigger than a real signal can make (7 kHz at this rate) is an FM click or noise, not data: clip it
    private val limit = 2 * Math.PI * 7000 / sampleRate

    private fun step(sample: Double) {
        val raw = sample.coerceIn(-limit, limit)
        count++
        sum += raw - ring[pos]; ring[pos] = raw
        pos = (pos + 1) % window
        if (count and 0x3FFF == 0L) { var s = 0.0; for (r in ring) s += r; sum = s }
        val smooth = sum / window
        // slice halfway between the recent highest and lowest levels: this also absorbs a receiver that is tuned a little off
        hi = if (smooth > hi) smooth else hi + 0.002 * (smooth - hi)
        lo = if (smooth < lo) smooth else lo + 0.002 * (smooth - lo)
        val mid = (hi + lo) / 2
        val sign = if (smooth > mid) 1 else -1
        if (sign != lastSign) {
            val err = if (phase < bitSamples / 2) phase else phase - bitSamples
            phase -= err * 0.3
            lastSign = sign
        }
        phase += 1.0
        if (phase >= bitSamples) { phase -= bitSamples; sampled = false }
        if (!sampled && phase >= bitSamples / 2) { sampled = true; hdlc.bit(if (smooth > mid) 1 else 0) }
    }
}

/** Turns decoded AIS frames into ship reports. Remembers names and types from the slower "static" messages. */
class AisTracker(private val onShip: (SdrShip) -> Unit) {
    private class Static(var name: String = "", var kind: String = "")
    private val statics = HashMap<Int, Static>()
    var messages = 0
        private set

    /** The name heard for a vessel so far, or empty. */
    fun nameOf(mmsi: Int) = statics[mmsi]?.name ?: ""

    private fun bitsAt(f: ByteArray, start: Int, len: Int): Long {
        var v = 0L
        for (i in start until start + len) v = (v shl 1) or (((f[i shr 3].toInt() shr (i and 7)) and 1).toLong())
        return v
    }

    private fun signed(f: ByteArray, start: Int, len: Int): Long { val v = bitsAt(f, start, len); return if (v >= (1L shl (len - 1))) v - (1L shl len) else v }

    private val sixbit = "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^- !\"#$%&'()*+,-./0123456789:;<=>?"

    private fun text(f: ByteArray, start: Int, chars: Int): String =
        (0 until chars).joinToString("") { sixbit[bitsAt(f, start + 6 * it, 6).toInt()].toString() }.replace('@', ' ').trim()

    private fun kindOf(t: Int) = when (t) {
        30 -> "Fishing"; 31, 32 -> "Towing"; 33 -> "Dredging"; 34 -> "Diving"; 35 -> "Military"; 36 -> "Sailing"; 37 -> "Pleasure"
        50 -> "Pilot"; 51 -> "Search and rescue"; 52 -> "Tug"; 53 -> "Port tender"; 54 -> "Anti-pollution"; 55 -> "Law enforcement"; 58 -> "Medical"
        in 40..49 -> "High speed"; in 60..69 -> "Passenger"; in 70..79 -> "Cargo"; in 80..89 -> "Tanker"
        else -> ""
    }

    fun feed(f: ByteArray, nowMillis: Long) {
        val nbits = f.size * 8
        if (nbits < 38) return
        val type = bitsAt(f, 0, 6).toInt()
        val mmsi = bitsAt(f, 8, 30).toInt()
        messages++
        when (type) {
            1, 2, 3 -> if (nbits >= 137) report(f, mmsi, 50, 61, 89, 116, nowMillis)
            18, 19 -> if (nbits >= 124) {
                if (type == 19 && nbits >= 271) { val s = statics.getOrPut(mmsi) { Static() }; s.name = text(f, 143, 20); s.kind = kindOf(bitsAt(f, 263, 8).toInt()).ifEmpty { s.kind } }
                report(f, mmsi, 46, 57, 85, 112, nowMillis)
            }
            5 -> if (nbits >= 240) { val s = statics.getOrPut(mmsi) { Static() }; s.name = text(f, 112, 20); s.kind = kindOf(bitsAt(f, 232, 8).toInt()) }
            24 -> if (nbits >= 160) {
                val part = bitsAt(f, 38, 2).toInt()
                val s = statics.getOrPut(mmsi) { Static() }
                if (part == 0) s.name = text(f, 40, 20) else if (part == 1 && nbits >= 48) s.kind = kindOf(bitsAt(f, 40, 8).toInt())
            }
        }
        if (statics.size > 500) statics.keys.take(100).forEach { statics.remove(it) }
    }

    private fun report(f: ByteArray, mmsi: Int, sogAt: Int, lonAt: Int, latAt: Int, cogAt: Int, now: Long) {
        val lon = signed(f, lonAt, 28); val lat = signed(f, latAt, 27)
        if (lon == 108_600_000L || lat == 54_600_000L) return           // position not available
        val la = lat / 600000.0; val lo = lon / 600000.0
        if (la !in -90.0..90.0 || lo !in -180.0..180.0 || (la == 0.0 && lo == 0.0)) return
        val sog = bitsAt(f, sogAt, 10).toInt(); val cog = bitsAt(f, cogAt, 12).toInt()
        val s = statics[mmsi]
        onShip(SdrShip(mmsi, s?.name ?: "", la, lo, if (sog in 0..1022) sog / 10.0 else null, if (cog in 0..3599) cog / 10.0 else null, s?.kind ?: "", now))
    }
}

/**
 * An FM front end for narrow channels: averages raw 8-bit I/Q over [average] samples (a cheap channel filter that keeps the noise
 * out of the discriminator), then measures how far the carrier's phase turned between averaged samples. Output rate is the input
 * rate divided by [average]. A phase turn of one radian is scaled to about 1.
 */
class FmChannel(private val average: Int, private val out: (FloatArray, Int) -> Unit) {
    private var pi = 0.0; private var pq = 0.0
    private var ai = 0.0; private var aq = 0.0; private var n = 0
    private val buf = FloatArray(1 shl 14)
    private var bn = 0

    fun feed(iq: ByteArray, length: Int) {
        var k = 0
        while (k + 1 < length) {
            ai += ((iq[k].toInt() and 0xFF) - 127.5) / 127.5
            aq += ((iq[k + 1].toInt() and 0xFF) - 127.5) / 127.5
            k += 2
            if (++n == average) {
                val i = ai / average; val q = aq / average
                ai = 0.0; aq = 0.0; n = 0
                val re = i * pi + q * pq
                val im = q * pi - i * pq
                pi = i; pq = q
                buf[bn++] = Math.atan2(im, re).toFloat()
                if (bn == buf.size) { out(buf, bn); bn = 0 }
            }
        }
        if (bn > 0) { out(buf, bn); bn = 0 }
    }
}
