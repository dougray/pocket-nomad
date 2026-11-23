// AfskDemodulator.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.ham

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns audio into a stream of raw tone decisions: for each bit time, which of two tones (mark or space) was stronger.
 * Each tone is mixed down and averaged over one bit time, scaled by its own recent peak (so a receiver that favours one tone
 * still works); a clock that nudges itself on every tone change picks the sampling moment. Shared by [AfskDemodulator] (packet
 * radio, 1200 baud) and [SameDecoder] (NOAA Weather Radio alerts, 520.83 baud).
 */
class ToneSlicer(sampleRate: Int, baud: Double, markHz: Double, spaceHz: Double, private val onRawBit: (Int) -> Unit) {
    private val bitSamples = sampleRate / baud
    private val window = max(2, bitSamples.roundToInt())
    private val ring = Array(4) { DoubleArray(window) }
    private val sum = DoubleArray(4)
    private var pos = 0
    private val markRot = doubleArrayOf(cos(2 * PI * markHz / sampleRate), sin(2 * PI * markHz / sampleRate))
    private val spaceRot = doubleArrayOf(cos(2 * PI * spaceHz / sampleRate), sin(2 * PI * spaceHz / sampleRate))
    private var mc = 1.0; private var ms = 0.0; private var sc = 1.0; private var ss = 0.0
    private var peakMark = 1e-9; private var peakSpace = 1e-9
    private var phase = 0.0
    private var sampled = false
    private var lastSign = 0
    private var count = 0L

    fun step(x: Double) {
        var t = mc * markRot[0] - ms * markRot[1]; ms = mc * markRot[1] + ms * markRot[0]; mc = t
        t = sc * spaceRot[0] - ss * spaceRot[1]; ss = sc * spaceRot[1] + ss * spaceRot[0]; sc = t
        count++
        if (count and 0x3FFF == 0L) {   // keep the oscillators at unit length and the running sums honest
            val a = sqrt(mc * mc + ms * ms); mc /= a; ms /= a
            val b = sqrt(sc * sc + ss * ss); sc /= b; ss /= b
            for (i in 0 until 4) { var s = 0.0; for (v in ring[i]) s += v; sum[i] = s }
        }
        val cur = doubleArrayOf(x * mc, x * ms, x * sc, x * ss)
        for (i in 0 until 4) { sum[i] += cur[i] - ring[i][pos]; ring[i][pos] = cur[i] }
        pos = (pos + 1) % window
        val em = sum[0] * sum[0] + sum[1] * sum[1]
        val es = sum[2] * sum[2] + sum[3] * sum[3]
        peakMark = max(em, peakMark * 0.99995); peakSpace = max(es, peakSpace * 0.99995)
        val d = em / (peakMark + 1e-9) - es / (peakSpace + 1e-9)
        val sign = if (d > 0) 1 else -1
        if (sign != lastSign) {   // a tone change: pull the bit clock toward a bit boundary
            val err = if (phase < bitSamples / 2) phase else phase - bitSamples
            phase -= err * 0.3
            lastSign = sign
        }
        phase += 1.0
        if (phase >= bitSamples) { phase -= bitSamples; sampled = false }
        if (!sampled && phase >= bitSamples / 2) { sampled = true; onRawBit(if (d > 0) 1 else 0) }
    }
}

/**
 * Rebuilds HDLC frames (AX.25 packet radio, AIS ship reports) from raw tone decisions: NRZI decoding, flag detection,
 * bit-unstuffing and the CRC-16 check. Bits are fed in received order; a frame is passed on (without its 2-byte check
 * sequence) only if the CRC is good and the length is between [minBytes] and [maxBytes] including the check sequence.
 * Bytes hold the bits least-significant first, in received order.
 */
class HdlcDeframer(private val minBytes: Int, private val maxBytes: Int, private val onFrame: (ByteArray) -> Unit) {
    private var prevRaw = 0
    private var ones = 0
    private var inFrame = false
    private val bits = ArrayList<Int>(2700)

    fun bit(raw: Int) {
        val b = if (raw == prevRaw) 1 else 0   // NRZI: no change is a one
        prevRaw = raw
        if (b == 1) {
            ones++
            if (inFrame) bits.add(1)
            if (ones >= 7) { inFrame = false; bits.clear() }   // seven ones in a row is an abort
            return
        }
        when (ones) {
            6 -> {   // a flag: finish the frame in progress and start a new one
                if (inFrame && bits.size >= 7) {
                    for (i in 0 until 7) bits.removeAt(bits.size - 1)   // the flag's six ones and the zero that opened it
                    finish()
                }
                inFrame = true; bits.clear()
            }
            5 -> {}   // a stuffed zero: drop it
            else -> if (inFrame) bits.add(0)
        }
        ones = 0
    }

    private fun finish() {
        val n = bits.size
        if (n % 8 != 0 || n < minBytes * 8 || n > maxBytes * 8) return
        val data = ByteArray(n / 8)
        for (i in data.indices) { var v = 0; for (j in 0 until 8) v = v or (bits[i * 8 + j] shl j); data[i] = v.toByte() }
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        if (crc == 0xF0B8) onFrame(data.copyOf(data.size - 2))   // good CRC: hand over the frame without it
    }
}

/**
 * Receive-only decoder for 1200 baud packet radio (Bell 202 AFSK: mark 1200 Hz, space 2200 Hz) from audio samples.
 *
 * Audio in, AX.25 frames out (without the 2-byte check sequence, the same shape a KISS TNC delivers), so the frames
 * go straight to [HamService.receive]. It never transmits. Written from the public AX.25 and HDLC descriptions.
 * App/Sources/Ham/AfskDemodulator.swift is the iOS twin; keep them in step.
 */
class AfskDemodulator(sampleRate: Int, onFrame: (ByteArray) -> Unit) {
    private val hdlc = HdlcDeframer(17, 330, onFrame)
    private val slicer = ToneSlicer(sampleRate, 1200.0, 1200.0, 2200.0) { hdlc.bit(it) }

    /** Samples are floats in -1..1. */
    fun feed(samples: FloatArray, length: Int = samples.size) {
        for (k in 0 until length) slicer.step(samples[k].toDouble())
    }

    /** Samples are signed 16-bit PCM. */
    fun feed(samples: ShortArray, length: Int = samples.size) {
        for (k in 0 until length) slicer.step(samples[k] / 32768.0)
    }
}
