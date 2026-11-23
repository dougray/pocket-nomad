// RtlAis.kt
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

/**
 * Ships from a dongle plugged into THIS phone, through an rtl_tcp driver app (see [RtlSpectrum]). AIS uses two channels,
 * 161.975 and 162.025 MHz; one dongle listens to one at a time, so this switches between them every few seconds. Receive only.
 */
object RtlAis {
    const val SAMPLE_RATE = 250_000
    private const val AVERAGE = 4
    const val AUDIO_RATE = SAMPLE_RATE / AVERAGE
    val CHANNELS_HZ = intArrayOf(161_975_000, 162_025_000)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _reports = MutableStateFlow(0)
    /** Ship reports decoded since you started. */
    val reports: StateFlow<Int> = _reports

    private fun cmd(out: java.io.OutputStream, id: Int, value: Int) {
        out.write(byteArrayOf(id.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())); out.flush()
    }

    /** Receive loop on an open rtl_tcp connection. [channelMillis] is how long to stay on each channel; separate from [start] for tests. */
    fun receive(sock: Socket, alive: () -> Boolean, channelMillis: Long = 5000, now: () -> Long = System::currentTimeMillis, onShip: (SdrShip) -> Unit) {
        val inp = DataInputStream(sock.getInputStream()); val out = sock.getOutputStream()
        val head = ByteArray(12); inp.readFully(head)
        require(String(head, 0, 4) == "RTL0") { "not rtl_tcp" }
        cmd(out, 0x02, SAMPLE_RATE)
        cmd(out, 0x01, CHANNELS_HZ[0])
        cmd(out, 0x03, 0)
        cmd(out, 0x08, 0)
        val tracker = AisTracker { _reports.value += 1; onShip(it) }
        val demod = AisDemodulator(AUDIO_RATE) { f -> tracker.feed(f, now()) }
        val fm = FmChannel(AVERAGE) { a, n -> demod.feed(a, n) }
        val buf = ByteArray(1 shl 16)
        var channel = 0
        var switchAt = now() + channelMillis
        while (alive()) {
            val n = inp.read(buf); if (n < 0) break
            fm.feed(buf, n)
            if (now() >= switchAt) { channel = 1 - channel; cmd(out, 0x01, CHANNELS_HZ[channel]); switchAt = now() + channelMillis }
        }
    }

    fun start(host: String = "127.0.0.1", port: Int = RtlSpectrum.DEFAULT_PORT) {
        RtlSpectrum.stop(); RtlAdsb.stop(); RtlAprs.stop(); stop()
        _error.value = null; _reports.value = 0; _running.value = true
        job = scope.launch {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 3000); s.soTimeout = 10000
                    receive(s, { isActive }) { SdrState.apply(SdrEvent.Ship(it)) }
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
