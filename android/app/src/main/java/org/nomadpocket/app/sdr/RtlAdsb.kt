// RtlAdsb.kt
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

/** Aircraft from a dongle plugged into THIS phone, through an rtl_tcp driver app (see [RtlSpectrum]). 1090 MHz at 2 MS/s. */
object RtlAdsb {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _messages = MutableStateFlow(0)
    /** Good messages decoded so far, so a quiet antenna can be told from a broken link. */
    val messages: StateFlow<Int> = _messages

    private fun cmd(out: java.io.OutputStream, id: Int, value: Int) {
        out.write(byteArrayOf(id.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())); out.flush()
    }

    /** Runs the receive loop on [sock] until it closes or [alive] says stop. Separate from [start] so it can be tested against a fake driver. */
    fun receive(sock: Socket, alive: () -> Boolean, now: () -> Long = System::currentTimeMillis, onAircraft: (SdrAircraft) -> Unit, onMessages: (Int) -> Unit = {}) {
        val inp = DataInputStream(sock.getInputStream()); val out = sock.getOutputStream()
        val head = ByteArray(12); inp.readFully(head)
        require(String(head, 0, 4) == "RTL0") { "not rtl_tcp" }
        cmd(out, 0x02, ModeS.SAMPLE_RATE)
        cmd(out, 0x01, 1_090_000_000)
        cmd(out, 0x03, 0)      // tuner gain: automatic
        cmd(out, 0x08, 1)      // RTL AGC on: ADS-B levels vary a lot
        val demod = ModeS.Demodulator(); val tracker = AdsbTracker(onAircraft)
        val buf = ByteArray(1 shl 16)
        while (alive()) {
            val n = inp.read(buf); if (n < 0) break
            for (m in demod.feed(buf, n)) { tracker.feed(m, now()); onMessages(tracker.messages) }
        }
    }

    fun start(host: String = "127.0.0.1", port: Int = RtlSpectrum.DEFAULT_PORT) {
        RtlSpectrum.stop(); RtlAprs.stop(); RtlAis.stop(); stop()
        _error.value = null; _messages.value = 0; _running.value = true
        job = scope.launch {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 3000); s.soTimeout = 10000
                    receive(s, { isActive }, onAircraft = { SdrState.apply(SdrEvent.Aircraft(it)) }, onMessages = { _messages.value = it })
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
