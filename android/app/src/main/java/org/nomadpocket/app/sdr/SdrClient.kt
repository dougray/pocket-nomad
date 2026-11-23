// SdrClient.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0
//
// Connects to an SDR box over the network and feeds what it hears into the app. Receive-only: the
// only things sent to the box are "start listening to X" and "stop". Port of SdrClient.swift.

package org.nomadpocket.app.sdr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

sealed class SdrLink {
    object Idle : SdrLink()
    object Connecting : SdrLink()
    object Connected : SdrLink()
    data class Failed(val message: String) : SdrLink()
}

object SdrClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _link = MutableStateFlow<SdrLink>(SdrLink.Idle)
    val link: StateFlow<SdrLink> = _link
    private val _dongle = MutableStateFlow(0)
    /** Which dongle the picker is working on (0 is the first). Only matters when the box has more than one. */
    val dongle: StateFlow<Int> = _dongle
    fun selectDongle(i: Int) { _dongle.value = i }
    private fun count() = (SdrState.hello.value?.dongles ?: 1).coerceAtLeast(1)
    private val _bleDevices = MutableStateFlow<List<SdrBleDevice>>(emptyList())
    val bleDevices: StateFlow<List<SdrBleDevice>> = _bleDevices
    private var ble: SdrBle? = null
    private var bleReader = SdrLineReader()
    private var useBluetooth = false
    private var currentToken = ""
    private var socket: Socket? = null
    private var job: Job? = null

    /** The box's pairing token, sealed with the Android Keystore. */
    fun savedToken(context: Context): String =
        context.getSharedPreferences("sdr", Context.MODE_PRIVATE).getString("token", null)?.let { org.nomadpocket.ham.KeystoreSecretBox().open(it) } ?: ""
    fun saveToken(context: Context, token: String) {
        val t = token.trim()
        context.getSharedPreferences("sdr", Context.MODE_PRIVATE).edit().apply {
            if (t.isEmpty()) remove("token") else putString("token", org.nomadpocket.ham.KeystoreSecretBox().seal(t))
        }.apply()
    }

    fun savedHost(context: Context) = context.getSharedPreferences("sdr", Context.MODE_PRIVATE).getString("host", "") ?: ""
    fun savedPort(context: Context) = context.getSharedPreferences("sdr", Context.MODE_PRIVATE).getInt("port", 8731)
    fun save(context: Context, host: String, port: Int) { context.getSharedPreferences("sdr", Context.MODE_PRIVATE).edit().putString("host", host).putInt("port", port).apply() }

    fun connect(host: String, port: Int, token: String = "") {
        disconnect()
        val h = host.trim()
        if (h.isEmpty()) { _link.value = SdrLink.Failed("Enter the box's address."); return }
        useBluetooth = false
        _link.value = SdrLink.Connecting
        SdrState.reset()
        job = scope.launch {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(h, port), 5000)
                socket = s
                _link.value = SdrLink.Connected
                send(JSONObject().put("cmd", "auth").put("token", token))
                val reader = SdrLineReader()
                val buf = ByteArray(8192)
                val input = s.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    reader.feed(buf.copyOf(n)).forEach { j -> SdrParser.event(j)?.let(::handle) }
                }
                if (socket === s) _link.value = SdrLink.Failed("The box closed the connection.")
            } catch (e: Exception) {
                if (socket === s || socket == null) _link.value = SdrLink.Failed("Can't reach the box (${e.message ?: "no answer"}).")
            } finally {
                runCatching { s.close() }
                if (socket === s) socket = null
            }
        }
    }

    // Bluetooth

    fun scanBluetooth(context: Context) {
        val b = ble ?: SdrBle(context.applicationContext, { _bleDevices.value = it }, { bytes ->
            bleReader.feed(bytes).forEach { j -> SdrParser.event(j)?.let(::handle) }
        }, { s ->
            _link.value = s
            if (s is SdrLink.Connected) send(JSONObject().put("cmd", "auth").put("token", currentToken))
        }).also { ble = it }
        _bleDevices.value = emptyList()
        b.startScan()
    }

    fun connectBluetooth(device: SdrBleDevice, token: String) {
        SdrState.reset(); bleReader = SdrLineReader(); useBluetooth = true; currentToken = token
        ble?.connect(device)
    }

    fun disconnect() {
        ble?.disconnect()
        val s = socket
        socket = null
        job?.cancel(); job = null
        runCatching { s?.close() }
        SdrState.setActive(0, null); _dongle.value = 0
        _link.value = SdrLink.Idle
    }

    /** With one dongle the commands are exactly what older boxes expect; with several they name the dongle. */
    fun start(mode: String) {
        val d = _dongle.value.coerceAtMost(count() - 1)
        send(JSONObject().put("cmd", "start").put("mode", mode).also { if (count() > 1) it.put("dongle", d) }); SdrState.setActive(d, mode)
    }
    fun stop() {
        val d = _dongle.value.coerceAtMost(count() - 1)
        send(JSONObject().put("cmd", "stop").also { if (count() > 1) it.put("dongle", d) }); SdrState.setActive(d, null)
    }

    /** Fetch a weather picture from the box over HTTP (Wi-Fi only). A plain socket: Android blocks cleartext HTTP in its URL classes. */
    suspend fun fetchImage(host: String, token: String, image: SdrImage): ByteArray? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (useBluetooth || host.isBlank()) return@withContext null
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host.trim(), 8732), 5000); s.soTimeout = 30000
                val auth = if (token.isNotEmpty()) "Authorization: Bearer $token\r\n" else ""
                s.getOutputStream().apply { write("GET ${image.url} HTTP/1.1\r\nHost: ${host.trim()}\r\n${auth}Connection: close\r\n\r\n".toByteArray()); flush() }
                val all = s.getInputStream().readBytes()
                val end = String(all, 0, minOf(all.size, 2048), Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                if (end < 0 || !String(all, 0, 15, Charsets.ISO_8859_1).contains(" 200")) null else all.copyOfRange(end + 4, all.size)
            }
        }.getOrNull()
    }

    /** Choose the band the spectrum view sweeps (MHz). The box checks the range. */
    fun sendSpectrumRange(lowMHz: Double, highMHz: Double) {
        send(JSONObject().put("cmd", "settings").put("spectrum_lo_mhz", lowMHz).put("spectrum_hi_mhz", highMHz))
        SdrState.resetPeak()
    }

    /** Tell the box roughly where you are (rounded to 0.1 degree, kept only on the box) so it knows when satellites pass. */
    fun sendArea(latitude: Double, longitude: Double) {
        send(JSONObject().put("cmd", "settings").put("lat", Math.round(latitude * 10) / 10.0).put("lon", Math.round(longitude * 10) / 10.0))
    }

    private fun send(cmd: JSONObject) {
        if (useBluetooth) { ble?.write((cmd.toString() + "\n").toByteArray()); return }
        val s = socket ?: return
        scope.launch { runCatching { s.getOutputStream().apply { write((cmd.toString() + "\n").toByteArray()); flush() } } }
    }

    /** Apply one event. APRS frames go to the Ham tab's decoder, but never from a simulated box. */
    fun handle(e: SdrEvent) {
        if (e is SdrEvent.Error) {
            _link.value = SdrLink.Failed(if (e.code == "blocked") "Too many wrong tries. Wait a minute and try again." else "The box didn't accept the pairing token.")
            val s = socket; socket = null; runCatching { s?.close() }
            return
        }
        SdrState.apply(e)
        if (e is SdrEvent.Aprs && !SdrState.simulated.value && org.nomadpocket.ham.Ham.isInitialized) org.nomadpocket.ham.Ham.service.receive(e.frame)
    }
}
