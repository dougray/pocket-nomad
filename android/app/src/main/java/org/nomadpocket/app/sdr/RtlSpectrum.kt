// RtlSpectrum.kt
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
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** In-place radix-2 FFT (size must be a power of two). */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 1 && n and (n - 1) == 0)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * A spectrum sweep from a dongle plugged into THIS phone, read through an rtl_tcp driver app.
 *
 * Android lets only the app that owns the USB device talk to it, and the open-source drivers for the RTL-SDR are GPL,
 * so Pocket Nomad (Apache-2.0) does not contain one. Instead a free driver app that serves "rtl_tcp" on this phone
 * (localhost, port 1234) owns the dongle, and this class reads the raw samples over the loopback connection and does
 * the maths itself. Values are relative (dB below full scale), good for finding where signals are.
 */
object RtlSpectrum {
    const val DEFAULT_PORT = 1234
    const val SAMPLE_RATE = 2_048_000
    const val FFT_SIZE = 1024
    private const val USABLE_BINS = 800          // the middle 78 % of each hop: the edges roll off
    private const val AVERAGES = 6               // FFTs averaged per hop

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Hann-windowed power spectrum of unsigned 8-bit I/Q bytes, in dB below full scale, DC bin centred (FFT order shifted). */
    fun powerDb(iq: ByteArray, offset: Int = 0): DoubleArray {
        val re = DoubleArray(FFT_SIZE); val im = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (FFT_SIZE - 1))
            re[i] = ((iq[offset + 2 * i].toInt() and 0xFF) - 127.5) / 127.5 * w
            im[i] = ((iq[offset + 2 * i + 1].toInt() and 0xFF) - 127.5) / 127.5 * w
        }
        Fft.transform(re, im)
        val out = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            val p = (re[i] * re[i] + im[i] * im[i]) / (FFT_SIZE.toDouble() * FFT_SIZE) * 4
            out[(i + FFT_SIZE / 2) % FFT_SIZE] = 10 * log10(p + 1e-12)
        }
        return out
    }

    private fun cmd(out: java.io.OutputStream, id: Int, value: Int) {
        out.write(byteArrayOf(id.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())); out.flush()
    }

    /** One sweep over [loMHz, hiMHz] on an open rtl_tcp connection. Returns the frame, or null if the stream ends. */
    fun sweepOnce(sock: Socket, loMHz: Double, hiMHz: Double, gainTenthsDb: Int?): SdrSpectrumFrame? {
        val input = DataInputStream(sock.getInputStream()); val out = sock.getOutputStream()
        cmd(out, 0x03, if (gainTenthsDb == null) 0 else 1)          // gain mode: 0 = auto
        if (gainTenthsDb != null) cmd(out, 0x04, gainTenthsDb)
        val hopHz = USABLE_BINS * (SAMPLE_RATE / FFT_SIZE)
        val startHz = (loMHz * 1e6).toLong()
        val hops = ((hiMHz * 1e6 - startHz) / hopHz).toInt() + 1
        val all = ArrayList<Double>()
        val buf = ByteArray(FFT_SIZE * 2)
        val skipBuf = ByteArray(16384)
        for (h in 0 until hops) {
            val centre = startHz + hopHz * h + hopHz / 2
            cmd(out, 0x01, centre.toInt())
            var toSkip = SAMPLE_RATE / 10 * 2                        // let the tuner settle: drop ~0.1 s of samples
            while (toSkip > 0) { val n = input.read(skipBuf, 0, minOf(skipBuf.size, toSkip)); if (n < 0) return null; toSkip -= n }
            val acc = DoubleArray(FFT_SIZE)
            for (a in 0 until AVERAGES) {
                input.readFully(buf)
                val p = powerDb(buf)
                for (i in 0 until FFT_SIZE) acc[i] += Math.pow(10.0, p[i] / 10)
            }
            val from = (FFT_SIZE - USABLE_BINS) / 2
            for (i in from until from + USABLE_BINS) all.add(10 * log10(acc[i] / AVERAGES + 1e-12))
        }
        if (all.size < 2) return null
        val binHz = SAMPLE_RATE / FFT_SIZE
        val k = Math.ceil(all.size / 400.0).toInt().coerceAtLeast(1)
        val db = (0 until all.size step k).map { i -> Math.round(all.subList(i, minOf(all.size, i + k)).max()).toInt() }
        return SdrSpectrumFrame(startHz.toInt(), binHz * k, db)
    }

    fun start(loMHz: Double, hiMHz: Double, gainTenthsDb: Int? = null, host: String = "127.0.0.1", port: Int = DEFAULT_PORT, onFrame: (SdrSpectrumFrame) -> Unit = { SdrState.apply(SdrEvent.Spectrum(it.lowHz, it.stepHz, it.db)) }) {
        RtlAdsb.stop(); RtlAprs.stop(); RtlAis.stop(); stop()
        _error.value = null
        _running.value = true
        job = scope.launch {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 3000); s.soTimeout = 10000
                    val head = ByteArray(12); DataInputStream(s.getInputStream()).readFully(head)
                    if (String(head, 0, 4) != "RTL0") { _error.value = "That is not an rtl_tcp driver."; return@use }
                    val out = s.getOutputStream()
                    cmd(out, 0x02, SAMPLE_RATE)
                    while (isActive) { onFrame(sweepOnce(s, loMHz, hiMHz, gainTenthsDb) ?: break) }
                }
            } catch (e: java.io.IOException) {
                if (isActive) _error.value = "Couldn't reach the driver app. Start it, plug in the dongle and allow USB, then try again."
            } finally { _running.value = false }
        }
    }

    fun stop() { job?.cancel(); job = null; _running.value = false }
}
