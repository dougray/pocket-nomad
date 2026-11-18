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
    private val _mode = MutableStateFlow<String?>(null)
    val mode: StateFlow<String?> = _mode
    private var socket: Socket? = null
    private var job: Job? = null

    fun savedHost(context: Context) = context.getSharedPreferences("sdr", Context.MODE_PRIVATE).getString("host", "") ?: ""
    fun savedPort(context: Context) = context.getSharedPreferences("sdr", Context.MODE_PRIVATE).getInt("port", 8731)
    fun save(context: Context, host: String, port: Int) { context.getSharedPreferences("sdr", Context.MODE_PRIVATE).edit().putString("host", host).putInt("port", port).apply() }

    fun connect(host: String, port: Int) {
        disconnect()
        val h = host.trim()
        if (h.isEmpty()) { _link.value = SdrLink.Failed("Enter the box's address."); return }
        _link.value = SdrLink.Connecting
        SdrState.reset()
        job = scope.launch {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(h, port), 5000)
                socket = s
                _link.value = SdrLink.Connected
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

    fun disconnect() {
        val s = socket
        socket = null
        job?.cancel(); job = null
        runCatching { s?.close() }
        _mode.value = null
        _link.value = SdrLink.Idle
    }

    fun start(mode: String) { send(JSONObject().put("cmd", "start").put("mode", mode)); _mode.value = mode }
    fun stop() { send(JSONObject().put("cmd", "stop")); _mode.value = null }

    private fun send(cmd: JSONObject) {
        val s = socket ?: return
        scope.launch { runCatching { s.getOutputStream().apply { write((cmd.toString() + "\n").toByteArray()); flush() } } }
    }

    /** Apply one event. APRS frames go to the Ham tab's decoder, but never from a simulated box. */
    fun handle(e: SdrEvent) {
        SdrState.apply(e)
        if (e is SdrEvent.Aprs && !SdrState.simulated.value && org.nomadpocket.ham.Ham.isInitialized) org.nomadpocket.ham.Ham.service.receive(e.frame)
    }
}
