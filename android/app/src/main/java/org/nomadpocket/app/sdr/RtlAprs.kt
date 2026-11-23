// RtlAprs.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.app.sdr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.nomadpocket.ham.AfskDemodulator
import org.nomadpocket.ham.SameDecoder
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.atan2

/**
 * APRS (1200 baud packet radio) and NOAA Weather Radio alerts from a dongle plugged into THIS phone, through an rtl_tcp driver app (see [RtlSpectrum]).
 * Narrow FM on 144.390 MHz (North America): the samples are FM-demodulated here, thinned to a 50 kHz audio stream and handed to the
 * same [AfskDemodulator] that decodes radio audio. Receive only. Decoded frames go to [onFrame] (the Ham tab's service in the app).
 */
object RtlAprs {
    const val SAMPLE_RATE = 250_000
    private const val DECIMATE = 5
    const val AUDIO_RATE = SAMPLE_RATE / DECIMATE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _frames = MutableStateFlow(0)
    /** Packets decoded since you started. */
    val frames: StateFlow<Int> = _frames

    private fun cmd(out: java.io.OutputStream, id: Int, value: Int) {
        out.write(byteArrayOf(id.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())); out.flush()
    }

    /** Turns raw unsigned 8-bit I/Q into a 50 kHz FM-demodulated stream for the AFSK decoder. Keeps its state between calls. */
    class FmAudio(private val decimate: Int = DECIMATE, private val dcAlpha: Double = 0.001, private val out: (FloatArray, Int) -> Unit) {
        private var pi = 0.0; private var pq = 0.0
        private var acc = 0.0; private var n = 0
        private var dc = 0.0
        private val buf = FloatArray(1 shl 15)
        private var bn = 0
        fun feed(iq: ByteArray, length: Int) {
            var k = 0
            while (k + 1 < length) {
                val i = ((iq[k].toInt() and 0xFF) - 127.5) / 127.5
                val q = ((iq[k + 1].toInt() and 0xFF) - 127.5) / 127.5
                k += 2
                // phase change since the last sample = instantaneous frequency
                val re = i * pi + q * pq
                val im = q * pi - i * pq
                pi = i; pq = q
                val f = atan2(im, re) * 6.0   // about +-1 at the usual APRS deviation
                acc += f
                if (++n == decimate) {
                    val a = acc / decimate
                    dc += dcAlpha * (a - dc)     // slow DC removal: a slightly mistuned receiver offsets the whole signal
                    buf[bn++] = (a - dc).toFloat()
                    acc = 0.0; n = 0
                    if (bn == buf.size) { out(buf, bn); bn = 0 }
                }
            }
            if (bn > 0) { out(buf, bn); bn = 0 }
        }
    }

    /** Receive loop on an open rtl_tcp connection until it closes or [alive] says stop. Separate from [start] so a fake driver can test it. */
    fun receive(sock: Socket, mhz: Double, alive: () -> Boolean, onFrame: (ByteArray) -> Unit) {
        val inp = DataInputStream(sock.getInputStream()); val out = sock.getOutputStream()
        val head = ByteArray(12); inp.readFully(head)
        require(String(head, 0, 4) == "RTL0") { "not rtl_tcp" }
        cmd(out, 0x02, SAMPLE_RATE)
        cmd(out, 0x01, (mhz * 1e6).toInt())
        cmd(out, 0x03, 0)      // automatic tuner gain
        cmd(out, 0x08, 0)      // RTL AGC off: it pumps the level and smears FM
        val demod = AfskDemodulator(AUDIO_RATE) { f -> _frames.value += 1; onFrame(f) }
        // NOAA Weather Radio alerts ride the same kind of signal on 162.4 to 162.55 MHz, so listen for them too
        val same = SameDecoder(AUDIO_RATE) { a ->
            _frames.value += 1
            SdrState.apply(SdrEvent.Alert(SdrAlert("dongle", mhz, a.header, a.text, System.currentTimeMillis())))
        }
        val fm = FmAudio(DECIMATE) { a, n -> demod.feed(a, n); same.feed(a, n) }
        val buf = ByteArray(1 shl 16)
        while (alive()) {
            val n = inp.read(buf); if (n < 0) break
            fm.feed(buf, n)
        }
    }

    fun start(mhz: Double = 144.39, host: String = "127.0.0.1", port: Int = RtlSpectrum.DEFAULT_PORT, onFrame: (ByteArray) -> Unit) {
        RtlSpectrum.stop(); RtlAdsb.stop(); RtlAis.stop(); stop()
        _error.value = null; _frames.value = 0; _running.value = true
        job = scope.launch {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 3000); s.soTimeout = 10000
                    receive(s, mhz, { isActive }, onFrame)
                }
            } catch (e: java.io.IOException) {
                if (isActive) _error.value = "Couldn't reach the driver app. Start it, plug in the dongle and allow USB, then try again."
            } catch (e: IllegalArgumentException) {
                _error.value = "That is not an rtl_tcp driver."
            } finally { _running.value = false }
        }
    }

    fun stop() { job?.cancel(); job = null; _running.value = false }
}
