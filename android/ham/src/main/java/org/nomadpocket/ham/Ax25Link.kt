// Ax25Link.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// One AX.25 connected-mode link (BBS, node, Winlink gateway). Modulo 8,
// window k, go-back-N, T1 retries up to N2, T3 keep-alive. Driven by
// receive()/tick() with explicit times so tests run on a fake clock.
// Port of AX25Link.swift. Call from one thread (the main thread in the app).

package org.nomadpocket.ham

data class Ax25LinkConfig(
    val window: Int = 4,
    val paclen: Int = 128,
    val n2: Int = 10,
    val t1BaseMs: Long = 8_000,
    val t1PerHopMs: Long = 6_000,
    val t3Ms: Long = 180_000,
    val ackDelayMs: Long = 1_000,
)

class Ax25Link(local: Ax25Address, remote: Ax25Address, path: List<Ax25Address>, val config: Ax25LinkConfig = Ax25LinkConfig()) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    val local = local.base
    val remote = remote.base
    val path = path.map { it.base }
    var state = State.DISCONNECTED
        private set
    var endReason: String? = null
        private set

    var send: (Ax25Frame) -> Unit = {}
    var onData: (ByteArray) -> Unit = {}
    var onStateChange: (State) -> Unit = {}

    private var vs = 0
    private var vr = 0
    private var va = 0
    private val queue = ArrayDeque<ByteArray>()
    private val outstanding = HashMap<Int, ByteArray>()
    private var t1: Long? = null
    private var t3: Long? = null
    private var ackDue: Long? = null
    private var retries = 0
    private var rejSent = false
    private var peerBusy = false
    private var disconnectWhenFlushed = false
    private var now = 0L

    val t1Interval: Long get() = config.t1BaseMs + config.t1PerHopMs * path.size
    val pendingBytes: Int get() = queue.sumOf { it.size } + outstanding.values.sumOf { it.size }

    fun connect(now: Long) {
        this.now = now
        if (state != State.DISCONNECTED) return
        reset(); endReason = null
        set(State.CONNECTING)
        sendFrame(Ax25Control.SABM(true), true)
        t1 = now + t1Interval
    }

    fun accept(sabm: Ax25Frame, now: Long) {
        this.now = now
        reset(); endReason = null
        sendFrame(Ax25Control.UA((sabm.control as? Ax25Control.SABM)?.poll ?: true), false)
        set(State.CONNECTED)
        t3 = now + config.t3Ms
    }

    fun write(bytes: ByteArray, now: Long) {
        this.now = now
        var i = 0
        val n = maxOf(1, config.paclen)
        while (i < bytes.size) {
            val end = minOf(i + n, bytes.size)
            queue.addLast(bytes.copyOfRange(i, end))
            i = end
        }
        pump()
    }

    fun disconnect(now: Long, flush: Boolean = true) {
        this.now = now
        when (state) {
            State.CONNECTED -> if (flush && pendingBytes > 0) disconnectWhenFlushed = true else startDisconnect()
            State.CONNECTING -> { sendFrame(Ax25Control.DISC(true), true); finish(null) }
            else -> {}
        }
    }

    fun receive(f: Ax25Frame, now: Long) {
        this.now = now
        when (val c = f.control) {
            is Ax25Control.SABM -> {
                reset()
                sendFrame(Ax25Control.UA(c.poll), false)
                set(State.CONNECTED)
                t3 = now + config.t3Ms
            }
            is Ax25Control.DISC -> if (state == State.DISCONNECTED) sendFrame(Ax25Control.DM(c.poll), false) else {
                sendFrame(Ax25Control.UA(c.poll), false)
                finish(if (state == State.DISCONNECTING) null else "The other station ended the connection.")
            }
            is Ax25Control.UA -> when (state) {
                State.CONNECTING -> { reset(); set(State.CONNECTED); t3 = now + config.t3Ms; pump() }
                State.DISCONNECTING -> finish(null)
                else -> {}
            }
            is Ax25Control.DM -> when (state) {
                State.CONNECTING -> finish("Connection refused: the station is busy or not accepting connects.")
                State.DISCONNECTING -> finish(null)
                State.CONNECTED -> finish("The other station reset the link.")
                else -> {}
            }
            is Ax25Control.FRMR -> if (state != State.DISCONNECTED) {
                sendFrame(Ax25Control.DISC(true), true)
                finish("The other station rejected a frame (FRMR).")
            }
            is Ax25Control.I -> {
                if (state != State.CONNECTED && state != State.DISCONNECTING) {
                    if (f.isCommand) sendFrame(Ax25Control.DM(c.poll), false)
                    return
                }
                handleNR(c.nr)
                if (c.ns == vr) {
                    vr = (vr + 1) and 7
                    rejSent = false
                    onData(f.info)
                    if (c.poll) { sendFrame(Ax25Control.RR(vr, true), false); ackDue = null }
                    else if (ackDue == null) ackDue = now + config.ackDelayMs
                } else if (!rejSent) {
                    rejSent = true
                    sendFrame(Ax25Control.REJ(vr, c.poll), false)
                    ackDue = null
                } else if (c.poll) sendFrame(Ax25Control.RR(vr, true), false)
                pump()
            }
            is Ax25Control.RR, is Ax25Control.RNR, is Ax25Control.REJ -> {
                val (nr, pf) = when (c) {
                    is Ax25Control.RR -> c.nr to c.pf
                    is Ax25Control.RNR -> c.nr to c.pf
                    is Ax25Control.REJ -> c.nr to c.pf
                    else -> 0 to false
                }
                if (state != State.CONNECTED && state != State.DISCONNECTING) {
                    if (f.isCommand && pf) sendFrame(Ax25Control.DM(true), false)
                    return
                }
                peerBusy = c is Ax25Control.RNR
                handleNR(nr)
                if (c is Ax25Control.REJ) resendOutstanding()
                if (f.isCommand && pf) { sendFrame(Ax25Control.RR(vr, true), false); ackDue = null }
                else if (!f.isCommand && pf) resendOutstanding()
                pump()
            }
            else -> {}
        }
    }

    fun tick(now: Long) {
        this.now = now
        ackDue?.let { if (now >= it) { ackDue = null; if (state == State.CONNECTED) sendFrame(Ax25Control.RR(vr, false), false) } }
        t1?.let { deadline ->
            if (now < deadline) return@let
            retries += 1
            when (state) {
                State.CONNECTING -> if (retries > config.n2) finish("No answer from $remote. Check the callsign, path and frequency.")
                    else { sendFrame(Ax25Control.SABM(true), true); t1 = now + t1Interval }
                State.DISCONNECTING -> if (retries > config.n2) finish(null)
                    else { sendFrame(Ax25Control.DISC(true), true); t1 = now + t1Interval }
                State.CONNECTED -> if (retries > config.n2) {
                    sendFrame(Ax25Control.DISC(true), true)
                    finish("Link lost: no acknowledgement after ${config.n2} tries.")
                } else {
                    val data = outstanding[va]
                    if (data != null) { send(frame(Ax25Control.I(va, vr, true), true, data)); ackDue = null }
                    else sendFrame(Ax25Control.RR(vr, true), true)
                    t1 = now + t1Interval
                }
                State.DISCONNECTED -> t1 = null
            }
        }
        val t3v = t3
        if (t3v != null && now >= t3v && state == State.CONNECTED && t1 == null) {
            t3 = null
            retries = 0
            sendFrame(Ax25Control.RR(vr, true), true)
            t1 = now + t1Interval
        }
    }

    private fun reset() {
        vs = 0; vr = 0; va = 0
        outstanding.clear()
        t1 = null; t3 = null; ackDue = null
        rejSent = false; peerBusy = false; retries = 0
    }

    private fun set(s: State) { if (s != state) { state = s; onStateChange(s) } }

    private fun finish(reason: String?) {
        endReason = reason
        queue.clear()
        reset()
        disconnectWhenFlushed = false
        set(State.DISCONNECTED)
    }

    private fun startDisconnect() {
        disconnectWhenFlushed = false
        queue.clear(); outstanding.clear()
        retries = 0
        set(State.DISCONNECTING)
        sendFrame(Ax25Control.DISC(true), true)
        t1 = now + t1Interval
        t3 = null
    }

    private fun handleNR(nr0: Int) {
        val nr = nr0 and 7
        val inFlight = (vs - va) and 7
        val acked = (nr - va) and 7
        if (acked > inFlight) return
        if (acked > 0) {
            var n = va
            while (n != nr) { outstanding.remove(n); n = (n + 1) and 7 }
            va = nr
            retries = 0
        }
        if (outstanding.isEmpty()) {
            t1 = null
            t3 = now + config.t3Ms
            if (disconnectWhenFlushed && queue.isEmpty()) startDisconnect()
        } else if (acked > 0) t1 = now + t1Interval
    }

    private fun resendOutstanding() {
        var n = va
        while (n != vs) {
            outstanding[n]?.let { send(frame(Ax25Control.I(n, vr, false), true, it)) }
            n = (n + 1) and 7
        }
        if (outstanding.isNotEmpty()) { ackDue = null; t1 = now + t1Interval }
    }

    private fun pump() {
        if (state != State.CONNECTED || peerBusy) return
        val k = config.window.coerceIn(1, 7)
        while (queue.isNotEmpty() && outstanding.size < k) {
            val chunk = queue.removeFirst()
            outstanding[vs] = chunk
            send(frame(Ax25Control.I(vs, vr, false), true, chunk))
            ackDue = null
            vs = (vs + 1) and 7
            if (t1 == null) t1 = now + t1Interval
        }
        if (t1 != null) t3 = null
    }

    private fun frame(c: Ax25Control, command: Boolean, info: ByteArray = ByteArray(0)) =
        Ax25Frame(remote, local, path, command, c, Ax25Frame.NO_LAYER3, info)

    private fun sendFrame(c: Ax25Control, command: Boolean) = send(frame(c, command))
}
