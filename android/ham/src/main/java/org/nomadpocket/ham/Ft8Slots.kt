// Ft8Slots.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.ham

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Turns live audio at any common sample rate into 12 kHz mono floats (a low-pass filter, then linear interpolation). */
class Resampler12k(private val inputRate: Int) {
    private val taps = 63
    private val coef: FloatArray
    private val hist = FloatArray(taps)
    private var h = 0
    private var phase = 0.0
    private val step = inputRate / 12000.0
    private var prev = 0f; private var prevValid = false

    init {
        // Windowed-sinc low-pass at 5.4 kHz: well under the 6 kHz Nyquist of the output, wide enough for FT8's 200 to 3000 Hz.
        val fc = 5400.0 / inputRate
        val c = FloatArray(taps)
        var sum = 0.0
        for (i in 0 until taps) {
            val n = i - (taps - 1) / 2.0
            val sinc = if (n == 0.0) 2 * fc else sin(2 * PI * fc * n) / (PI * n)
            val w = 0.54 - 0.46 * cos(2 * PI * i / (taps - 1))
            c[i] = (sinc * w).toFloat(); sum += c[i]
        }
        for (i in 0 until taps) c[i] = (c[i] / sum).toFloat()
        coef = c
    }

    /** Appends the resampled audio to [out]. */
    fun feed(samples: ShortArray, length: Int, out: (Float) -> Unit) {
        if (inputRate == 12000) { for (i in 0 until length) out(samples[i] / 32768f); return }
        for (i in 0 until length) {
            hist[h] = samples[i] / 32768f
            h = (h + 1) % taps
            var acc = 0f
            var idx = h
            for (t in 0 until taps) { acc += coef[t] * hist[idx]; idx = (idx + 1) % taps }
            // phase counts input samples since the last output; emit when it crosses 1 (interpolating between filtered samples)
            if (!prevValid) { prev = acc; prevValid = true; continue }
            phase += 1.0
            while (phase >= step) {
                phase -= step
                val frac = (1.0 - phase).toFloat().coerceIn(0f, 1f)   // how far past prev the output instant is
                out(prev + (acc - prev) * frac)
            }
            prev = acc
        }
    }
}

/**
 * Collects live audio and decodes each 15 second FT8 slot when it ends, on UTC boundaries (the phone's clock must be right;
 * a phone on a network is). Decoding runs on the caller's thread when a slot ends: call [feed] from a worker, not the UI.
 */
class Ft8SlotRunner(inputRate: Int, private val now: () -> Long = System::currentTimeMillis, private val onSlot: (List<Ft8Message>) -> Unit) {
    private val resampler = Resampler12k(inputRate)
    private val decoder = Ft8Decoder()
    private val slotSamples = 15 * 12000
    private val buf = FloatArray(slotSamples + 12000)   // the current slot plus a second of lead-in
    private var count = 0                                  // samples of the current slot collected so far
    private var slotStartMs = -1L

    fun feed(samples: ShortArray, length: Int) {
        val t = now()
        val slot = (t / 15000) * 15000
        if (slotStartMs < 0) { slotStartMs = slot; count = 0 }
        if (slot != slotStartMs) {
            // a new slot began: decode the one that just ended (if we heard most of it), then start over
            if (count > slotSamples * 3 / 4) onSlot(decoder.decode(buf, minOf(count, slotSamples)))
            slotStartMs = slot; count = 0
        }
        // the audio just handed to us ends "now"; skip what belongs before this slot began
        resampler.feed(samples, length) { v -> if (count < buf.size) buf[count++] = v }
    }
}
