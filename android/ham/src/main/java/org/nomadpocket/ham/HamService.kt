// HamService.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Everything the Ham screens show: the TNC link, packet monitor, APRS
// stations/messages/bulletins, Winlink mail and the packet terminal.
// Listen-only is enforced in transmit(). Port of HamService.swift. Main thread only.

package org.nomadpocket.ham

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

data class HamMonitorEntry(val id: Long, val time: Long, val line: String, val outgoing: Boolean, val kind: AprsPacket.Kind?)

data class HamStation(
    val id: String, val source: String, val latitude: Double? = null, val longitude: Double? = null, val symbol: String = "/>",
    val comment: String = "", val status: String = "", val lastHeard: Long, val firstHeard: Long, val path: List<String> = emptyList(),
    val packets: Int = 0, val courseDegrees: Int? = null, val speedKnots: Double? = null, val altitudeFeet: Double? = null,
    val weather: String? = null, val micEStatus: String? = null, val isObject: Boolean = false, val emergency: Boolean = false,
) {
    val hasPosition get() = latitude != null && longitude != null
    fun toJson(): JSONObject = JSONObject().put("id", id).put("source", source).put("lat", latitude).put("lon", longitude)
        .put("symbol", symbol).put("comment", comment).put("status", status).put("last", lastHeard).put("first", firstHeard)
        .put("path", JSONArray(path)).put("packets", packets).put("course", courseDegrees).put("speed", speedKnots)
        .put("alt", altitudeFeet).put("wx", weather).put("mice", micEStatus).put("object", isObject).put("sos", emergency)
    companion object {
        fun fromJson(o: JSONObject) = HamStation(o.getString("id"), o.getString("source"),
            o.optDouble("lat").takeIf { !it.isNaN() }, o.optDouble("lon").takeIf { !it.isNaN() }, o.optString("symbol", "/>"),
            o.optString("comment"), o.optString("status"), o.getLong("last"), o.getLong("first"),
            o.optJSONArray("path")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), o.optInt("packets"),
            if (o.has("course")) o.optInt("course") else null, o.optDouble("speed").takeIf { !it.isNaN() },
            o.optDouble("alt").takeIf { !it.isNaN() }, o.optString("wx").ifEmpty { null }, o.optString("mice").ifEmpty { null },
            o.optBoolean("object"), o.optBoolean("sos"))
    }
}

data class AprsChat(
    val id: String = java.util.UUID.randomUUID().toString(), val peer: String, val outgoing: Boolean, val text: String,
    val msgId: String?, val time: Long, val status: Status, val tries: Int = 0, val nextTry: Long? = null,
) {
    enum class Status { SENDING, ACKED, FAILED, REJECTED, RECEIVED }
    fun toJson(): JSONObject = JSONObject().put("id", id).put("peer", peer).put("out", outgoing).put("text", text).put("msgId", msgId)
        .put("time", time).put("status", status.name).put("tries", tries).put("next", nextTry)
    companion object {
        fun fromJson(o: JSONObject) = AprsChat(o.getString("id"), o.getString("peer"), o.getBoolean("out"), o.getString("text"),
            o.optString("msgId").ifEmpty { null }, o.getLong("time"), Status.valueOf(o.getString("status")), o.optInt("tries"),
            if (o.has("next")) o.optLong("next") else null)
    }
}

data class AprsBulletin(val source: String, val addressee: String, val text: String, val time: Long) {
    val id get() = "$source/$addressee"
}

enum class WinlinkFolder(val title: String) { INBOX("Inbox"), OUTBOX("Outbox"), SENT("Sent") }

class HamService(
    private val transport: HamTransport,
    val settings: HamSettings,
    private val dir: File,
    private val scope: CoroutineScope,
    /** One location fix (lat, lon, course°, speed m/s, altitude m), or null. */
    private val locate: suspend () -> DoubleArray? = { null },
) {
    sealed class TncState {
        object Idle : TncState()
        data class Connecting(val name: String) : TncState()
        data class Connected(val name: String) : TncState()
        data class Failed(val reason: String) : TncState()
    }

    sealed class WinlinkState {
        object Idle : WinlinkState()
        data class Connecting(val gw: String) : WinlinkState()
        data class Exchanging(val gw: String) : WinlinkState()
        data class Done(val summary: String) : WinlinkState()
        data class Failed(val reason: String) : WinlinkState()
        val busy get() = this is Connecting || this is Exchanging
    }

    var tnc by mutableStateOf<TncState>(TncState.Idle); private set
    val tncConnected get() = tnc is TncState.Connected
    val devices = mutableStateListOf<HamDevice>()
    val monitor = mutableStateListOf<HamMonitorEntry>()
    var packetsHeard by mutableStateOf(0); private set
    val stations = mutableStateListOf<HamStation>()
    val chats = mutableStateListOf<AprsChat>()
    val bulletins = mutableStateListOf<AprsBulletin>()
    val inbox = mutableStateListOf<WinlinkMessage>()
    val outbox = mutableStateListOf<WinlinkMessage>()
    val sent = mutableStateListOf<WinlinkMessage>()
    val readMids = mutableStateListOf<String>()
    val unread = mutableStateMapOf<String, Int>()
    var winlink by mutableStateOf<WinlinkState>(WinlinkState.Idle); private set
    val winlinkLog = mutableStateListOf<String>()
    var winlinkProgress by mutableStateOf<Triple<String, Int, Int>?>(null); private set
    var terminalText by mutableStateOf(""); private set
    var terminalState by mutableStateOf(Ax25Link.State.DISCONNECTED); private set
    var terminalPeer by mutableStateOf<String?>(null); private set
    var myPosition by mutableStateOf<Pair<Double, Double>?>(null); private set
    var lastBeacon by mutableStateOf<Long?>(null); private set
    var notice by mutableStateOf<String?>(null)

    val unreadTotal get() = unread.values.sum()
    val unreadMail get() = inbox.count { it.mid !in readMids }

    var now: () -> Long = { System.currentTimeMillis() }
    private var decoder = KissDecoder()
    private val links = HashMap<String, Ax25Link>()
    private var winlinkLink: Ax25Link? = null
    private var terminalLink: Ax25Link? = null
    private var winlinkJob: Job? = null
    private var ticker: Job? = null
    private val seenMessages = HashMap<String, Long>()
    private var monitorId = 0L

    init {
        dir.mkdirs()
        load()
        transport.onEvent = { handle(it) }
    }

    // MARK: Persistence (JSON files in no-backup storage)

    private fun file(name: String) = File(dir, name)
    private fun read(name: String): JSONArray? = try { JSONArray(file(name).readText()) } catch (_: Exception) { null }
    private fun write(name: String, a: JSONArray) { try { file(name).writeText(a.toString()) } catch (_: Exception) {} }
    private fun <T> JSONArray.map(f: (JSONObject) -> T) = (0 until length()).mapNotNull { try { f(getJSONObject(it)) } catch (_: Exception) { null } }

    private fun load() {
        read("stations.json")?.map(HamStation::fromJson)?.let { stations.addAll(it) }
        read("aprs-messages.json")?.map(AprsChat::fromJson)?.let { chats.addAll(it) }
        read("winlink-inbox.json")?.map(WinlinkMessage::fromJson)?.let { inbox.addAll(it) }
        read("winlink-outbox.json")?.map(WinlinkMessage::fromJson)?.let { outbox.addAll(it) }
        read("winlink-sent.json")?.map(WinlinkMessage::fromJson)?.let { sent.addAll(it) }
        read("winlink-read.json")?.let { a -> readMids.addAll((0 until a.length()).map { a.getString(it) }) }
    }

    private fun saveStations() = write("stations.json", JSONArray(stations.map { it.toJson() }))
    private fun saveChats() = write("aprs-messages.json", JSONArray(chats.map { it.toJson() }))
    private fun saveMail() {
        write("winlink-inbox.json", JSONArray(inbox.map { it.toJson() }))
        write("winlink-outbox.json", JSONArray(outbox.map { it.toJson() }))
        write("winlink-sent.json", JSONArray(sent.map { it.toJson() }))
        write("winlink-read.json", JSONArray(readMids.toList()))
    }

    // MARK: TNC

    /** Show every named Bluetooth device, not only known TNC services. */
    var showAllDevices by mutableStateOf(false)

    fun startScan() { devices.clear(); transport.startScan(showAllDevices) }
    fun stopScan() = transport.stopScan()

    fun connect(d: HamDevice) {
        stopScan()
        settings.lastDevice = JSONObject().put("a", d.address).put("n", d.name).put("c", d.classic).toString()
        settings.save()
        tnc = TncState.Connecting(d.name)
        transport.connect(d)
    }

    fun reconnectLast() {
        if (tncConnected || tnc is TncState.Connecting) return
        val o = settings.lastDevice?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return
        val d = HamDevice(o.getString("a"), o.optString("n", "TNC"), 0, o.optBoolean("c"))
        tnc = TncState.Connecting(d.name)
        transport.connect(d)
    }

    fun disconnect() {
        abortWinlink()
        disconnectTerminal()
        settings.lastDevice = null
        settings.save()
        transport.disconnect()
        tnc = TncState.Idle
        ticker?.cancel(); ticker = null
    }

    private fun handle(e: HamTransportEvent) {
        when (e) {
            is HamTransportEvent.Discovered -> {
                val i = devices.indexOfFirst { it.address == e.device.address }
                if (i >= 0) devices[i] = e.device else devices.add(e.device)
            }
            is HamTransportEvent.Connecting -> tnc = TncState.Connecting(e.name)
            is HamTransportEvent.Ready -> {
                tnc = TncState.Connected(e.name)
                decoder = KissDecoder()
                if (settings.canTransmit) transport.write(Kiss.parameterFrames(settings.txDelay, settings.persistence, settings.slotTime))
                log("Connected to ${e.name}", false)
                ticker?.cancel()
                ticker = scope.launch { while (isActive) { delay(500); tick() } }
            }
            is HamTransportEvent.Disconnected -> {
                tnc = e.reason?.let { TncState.Failed(it) } ?: TncState.Idle
                for (l in links.values.toList()) l.disconnect(now(), flush = false)
            }
            is HamTransportEvent.Log -> log(e.line, false)
            is HamTransportEvent.Bytes -> for (f in decoder.feed(e.bytes)) if (f.command == Kiss.DATA) receive(f.payload)
        }
    }

    // MARK: Frames

    fun receive(bytes: ByteArray) {
        val f = Ax25Frame.decode(bytes) ?: run { log("[undecodable frame, ${bytes.size} bytes]", false); return }
        packetsHeard++
        val aprs = Aprs.decode(f)
        log(f.monitorLine, false, aprs?.kind)
        if (f.control is Ax25Control.UI) { aprs?.let { handle(it, f) }; return }
        val me = settings.stationAddress ?: return
        if (f.destination != me) return
        val key = f.source.base.toString()
        links[key]?.let { it.receive(f, now()); return }
        if (f.control is Ax25Control.SABM && settings.acceptIncoming && terminalLink == null) {
            val link = makeLink(f.source.base, f.replyPath)
            terminalLink = link
            terminalPeer = key
            terminalText += "\n*** $key connected to you\n"
            wireTerminal(link)
            link.accept(f, now())
            return
        }
        if (f.isCommand) {
            val poll = when (val c = f.control) { is Ax25Control.SABM -> c.poll; is Ax25Control.DISC -> c.poll; is Ax25Control.I -> c.poll; else -> false }
            transmit(Ax25Frame(f.source.base, me, f.replyPath, false, Ax25Control.DM(poll)))
        }
    }

    private fun requireTransmit(): Boolean {
        if (!settings.canTransmit) { notice = "Listen only: add your callsign and confirm your licence in Station settings to transmit."; return false }
        return true
    }

    private fun requireTnc(): Boolean {
        if (!tncConnected) { notice = "Connect a TNC first."; return false }
        return true
    }

    fun transmit(f: Ax25Frame): Boolean {
        if (!requireTransmit() || !requireTnc()) return false
        transport.write(Kiss.encode(f.encoded()))
        log(f.monitorLine, true, Aprs.decode(f)?.kind)
        return true
    }

    private fun log(line: String, outgoing: Boolean, kind: AprsPacket.Kind? = null) {
        monitor.add(HamMonitorEntry(monitorId++, now(), line, outgoing, kind))
        if (monitor.size > 600) monitor.removeRange(0, monitor.size - 500)
    }

    fun clearMonitor() = monitor.clear()

    // MARK: APRS in

    private fun isMe(addressee: String?): Boolean {
        val a = addressee?.uppercase() ?: return false
        if (!settings.callsignValid) return false
        settings.aprsAddress?.let { if (a == it.toString()) return true }
        return a == settings.baseCall
    }

    fun handle(p: AprsPacket, frame: Ax25Frame? = null) {
        val t = now()
        when (p.kind) {
            AprsPacket.Kind.MESSAGE -> if (isMe(p.addressee)) {
                val key = "${p.source}|${p.messageId ?: p.text}"
                val dup = seenMessages[key]?.let { t - it < 1_800_000 } ?: false
                if (!dup) {
                    seenMessages[key] = t
                    chats.add(AprsChat(peer = p.source, outgoing = false, text = p.text ?: "", msgId = p.messageId, time = t, status = AprsChat.Status.RECEIVED))
                    unread[p.source] = (unread[p.source] ?: 0) + 1
                    saveChats()
                }
                val id = p.messageId
                if (id != null && settings.autoAck && settings.canTransmit && tncConnected) sendAprs(Aprs.ackInfo(p.source, id))
            }
            AprsPacket.Kind.ACK, AprsPacket.Kind.REJECT -> if (isMe(p.addressee) && p.messageId != null) {
                val i = chats.indexOfLast { it.outgoing && it.msgId == p.messageId && it.peer.equals(p.source, true) }
                if (i >= 0) {
                    chats[i] = chats[i].copy(status = if (p.kind == AprsPacket.Kind.ACK) AprsChat.Status.ACKED else AprsChat.Status.REJECTED, nextTry = null)
                    saveChats()
                }
            }
            AprsPacket.Kind.BULLETIN -> {
                val b = AprsBulletin(p.source, p.addressee ?: "", p.text ?: "", t)
                bulletins.removeAll { it.id == b.id }
                bulletins.add(0, b)
                if (bulletins.size > 200) bulletins.removeAt(bulletins.size - 1)
            }
            else -> {}
        }
        updateStation(p, t)
    }

    private fun updateStation(p: AprsPacket, t: Long) {
        if (p.position == null && p.kind != AprsPacket.Kind.STATUS && p.weather == null) return
        val id = p.subject
        val old = stations.firstOrNull { it.id == id } ?: HamStation(id, p.source, lastHeard = t, firstHeard = t)
        var s = old.copy(source = p.source, lastHeard = t, packets = old.packets + 1, path = p.path,
            isObject = p.objectName != null, emergency = p.isEmergency)
        p.position?.let { pos ->
            s = s.copy(latitude = pos.latitude, longitude = pos.longitude, symbol = "${pos.symbolTable}${pos.symbolCode}",
                courseDegrees = p.courseDegrees, speedKnots = p.speedKnots, altitudeFeet = p.altitudeFeet ?: s.altitudeFeet,
                comment = p.comment.ifEmpty { s.comment })
        }
        if (p.kind == AprsPacket.Kind.STATUS) s = s.copy(status = p.comment)
        p.weather?.let { s = s.copy(weather = it.summary) }
        p.micEStatus?.let { s = s.copy(micEStatus = it) }
        stations.removeAll { it.id == id }
        stations.add(0, s)
        if (stations.size > 1000) stations.removeRange(1000, stations.size)
        saveStations()
    }

    fun forgetStations() { stations.clear(); saveStations() }

    // MARK: APRS out

    private fun sendAprs(info: String) {
        val me = settings.aprsAddress ?: return
        transmit(Ax25Frame(Ax25Address(Aprs.TOCALL), me, Ax25Path.parse(settings.aprsPath) ?: emptyList(),
            control = Ax25Control.UI(false), info = info.toByteArray(Charsets.UTF_8)))
    }

    private var msgCounter: Int
        get() = settings.let { (it.lastMsgCounter) }
        set(v) { settings.lastMsgCounter = v }

    fun sendMessage(to: String, text: String) {
        val peer = to.trim().uppercase()
        if (peer.isEmpty() || text.isEmpty()) return
        if (!requireTransmit()) return
        for (part in text.chunked(67)) {
            msgCounter = msgCounter % 99_999 + 1
            val id = msgCounter.toString()
            chats.add(AprsChat(peer = peer, outgoing = true, text = part, msgId = id, time = now(), status = AprsChat.Status.SENDING,
                tries = 1, nextTry = now() + 30_000))
            sendAprs(Aprs.messageInfo(peer, part, id))
        }
        saveChats()
    }

    fun markPeerRead(peer: String) { unread.remove(peer) }

    fun deleteConversation(peer: String) { chats.removeAll { it.peer == peer }; unread.remove(peer); saveChats() }

    val conversations: List<Pair<String, AprsChat>>
        get() = chats.groupBy { it.peer }.map { (k, v) -> k to v.maxBy { it.time } }.sortedByDescending { it.second.time }

    fun beacon() {
        if (!requireTransmit() || !requireTnc()) return
        scope.launch {
            val fix = locate() ?: run { notice = "No location fix. Allow location for Pocket Nomad, or step outside for GPS."; return@launch }
            myPosition = fix[0] to fix[1]
            val sym = settings.symbol
            sendAprs(Aprs.positionInfo(fix[0], fix[1], sym.getOrElse(0) { '/' }, sym.getOrElse(1) { '[' },
                fix.getOrNull(2)?.takeIf { it >= 0 }?.toInt(), fix.getOrNull(3)?.takeIf { it >= 0 }?.let { it * 1.943844 },
                fix.getOrNull(4)?.let { it * 3.28084 }, settings.beaconComment))
            lastBeacon = now()
        }
    }

    fun refreshPosition() { scope.launch { locate()?.let { myPosition = it[0] to it[1] } } }

    // MARK: Ticks

    fun tick() {
        val t = now()
        for (l in links.values.toList()) l.tick(t)
        var changed = false
        for (i in chats.indices) {
            val c = chats[i]
            if (c.status != AprsChat.Status.SENDING) continue
            val due = c.nextTry ?: continue
            if (t < due) continue
            changed = true
            if (c.tries >= 5) chats[i] = c.copy(status = AprsChat.Status.FAILED, nextTry = null)
            else if (tncConnected && settings.canTransmit) {
                chats[i] = c.copy(tries = c.tries + 1, nextTry = t + (30_000 * 2.0.pow(c.tries)).toLong())
                sendAprs(Aprs.messageInfo(c.peer, c.text, c.msgId))
            }
        }
        if (changed) saveChats()
        if (settings.beaconMinutes > 0 && settings.canTransmit && tncConnected && t - (lastBeacon ?: 0) >= settings.beaconMinutes * 60_000L) {
            lastBeacon = t
            beacon()
        }
    }

    // MARK: Links

    private fun makeLink(remote: Ax25Address, path: List<Ax25Address>): Ax25Link {
        val link = Ax25Link(settings.stationAddress ?: Ax25Address("NOCALL"), remote.base, path, settings.linkConfig)
        link.send = { transmit(it) }
        links[remote.base.toString()] = link
        return link
    }

    private fun dropLink(l: Ax25Link) { if (links[l.remote.toString()] === l) links.remove(l.remote.toString()) }

    // MARK: Terminal

    fun connectTerminal(call: String, path: String) {
        if (!requireTransmit() || !requireTnc()) return
        val remote = Ax25Address.parse(call)
        val via = Ax25Path.parse(path)
        if (terminalLink != null || remote == null || via == null) { notice = "Check the callsign and path (for example KF0XYZ-1 via WIDE1-1)."; return }
        if (links.containsKey(remote.base.toString())) { notice = "Already connected to $remote."; return }
        val link = makeLink(remote.base, via)
        terminalLink = link
        terminalPeer = remote.base.toString()
        terminalText += "\n*** Connecting to $remote${if (via.isEmpty()) "" else " via " + Ax25Path.text(via)}…\n"
        wireTerminal(link)
        link.connect(now())
    }

    private fun wireTerminal(link: Ax25Link) {
        terminalState = link.state
        link.onData = { b ->
            terminalText += String(b, Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
            if (terminalText.length > 60_000) terminalText = terminalText.takeLast(50_000)
        }
        link.onStateChange = { st ->
            terminalState = st
            when (st) {
                Ax25Link.State.CONNECTED -> terminalText += "*** Connected to ${link.remote}\n"
                Ax25Link.State.DISCONNECTED -> {
                    terminalText += "*** Disconnected${link.endReason?.let { ": $it" } ?: ""}\n"
                    dropLink(link)
                    if (terminalLink === link) terminalLink = null
                }
                else -> {}
            }
        }
    }

    fun sendTerminal(line: String) {
        val l = terminalLink ?: return
        if (l.state != Ax25Link.State.CONNECTED) return
        terminalText += line + "\n"
        l.write((line + "\r").toByteArray(), now())
    }

    fun disconnectTerminal() { terminalLink?.disconnect(now()) }
    fun clearTerminal() { terminalText = "" }

    // MARK: Winlink

    fun compose(to: List<String>, cc: List<String>, subject: String, body: String): Boolean {
        if (!settings.callsignValid) { notice = "Add your callsign in Station settings first."; return false }
        outbox.add(WinlinkMessage.compose(settings.baseCall, to, cc, subject, body, now()))
        saveMail()
        return true
    }

    fun delete(m: WinlinkMessage, folder: WinlinkFolder) {
        when (folder) {
            WinlinkFolder.INBOX -> inbox.removeAll { it.mid == m.mid }
            WinlinkFolder.OUTBOX -> outbox.removeAll { it.mid == m.mid }
            WinlinkFolder.SENT -> sent.removeAll { it.mid == m.mid }
        }
        saveMail()
    }

    fun markRead(mid: String) { if (mid !in readMids) { readMids.add(mid); saveMail() } }

    fun messages(folder: WinlinkFolder): List<WinlinkMessage> = when (folder) {
        WinlinkFolder.INBOX -> inbox.sortedByDescending { it.dateMillis }
        WinlinkFolder.OUTBOX -> outbox.toList()
        WinlinkFolder.SENT -> sent.sortedByDescending { it.dateMillis }
    }

    fun connectWinlink(gw: HamSavedStation) {
        if (winlink.busy) return
        if (!requireTransmit() || !requireTnc()) return
        val remote = Ax25Address.parse(gw.call)
        val path = Ax25Path.parse(gw.path)
        if (remote == null || path == null) { notice = "Check the gateway callsign and path."; return }
        if (links.containsKey(remote.base.toString())) return
        winlinkLog.clear()
        winlinkProgress = null
        winlink = WinlinkState.Connecting(remote.toString())
        val link = makeLink(remote.base, path)
        winlinkLink = link
        val channel = HamByteChannel()
        link.onData = { channel.feed(it) }
        link.onStateChange = { st ->
            if (st == Ax25Link.State.DISCONNECTED) {
                channel.close(link.endReason ?: "The gateway ended the connection.")
                dropLink(link)
                if (winlinkLink === link) winlinkLink = null
                if (winlink is WinlinkState.Connecting) winlink = WinlinkState.Failed(link.endReason ?: "Could not connect to $remote.")
            }
        }
        link.connect(now())
        val grid = myPosition?.let { HamGeo.maidenhead(it.first, it.second) } ?: ""
        val password = settings.winlinkPassword
        winlinkJob = scope.launch {
            while (link.state == Ax25Link.State.CONNECTING) delay(250)
            if (link.state != Ax25Link.State.CONNECTED) return@launch
            winlink = WinlinkState.Exchanging(remote.toString())
            val session = B2fSession(settings.baseCall, password, grid, remote.toString(), outbox.toList(), channel) { link.write(it, now()) }
            session.knownMids = (inbox.map { it.mid } + sent.map { it.mid }).toMutableSet()
            session.log = { winlinkLog.add(it) }
            session.progress = { l, d, tot -> winlinkProgress = Triple(l, d, tot) }
            session.onReceived = { m -> if (inbox.none { it.mid == m.mid }) { inbox.add(m); saveMail() } }
            session.onSent = { mid -> outbox.firstOrNull { it.mid == mid }?.let { outbox.remove(it); sent.add(it); saveMail() } }
            try {
                val out = session.run()
                val got = out.received.size; val gone = out.sent.size + out.alreadyDelivered.size
                winlink = WinlinkState.Done("Received $got message${if (got == 1) "" else "s"}, sent $gone.")
                link.disconnect(now())
            } catch (e: Exception) {
                if (winlink is WinlinkState.Exchanging) winlink = WinlinkState.Failed(e.message ?: "Session failed.")
                link.disconnect(now(), flush = false)
            }
            winlinkProgress = null
        }
    }

    fun abortWinlink() {
        winlinkJob?.cancel()
        winlinkLink?.let {
            it.disconnect(now(), flush = false)
            if (winlink.busy) winlink = WinlinkState.Failed("Stopped.")
        }
    }

    fun deleteAllData() {
        stations.clear(); chats.clear(); bulletins.clear(); inbox.clear(); outbox.clear(); sent.clear(); readMids.clear()
        monitor.clear(); terminalText = ""; unread.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun loadDemo() {
        val lines = listOf(
            "N0DEN-10>APRS,WIDE2-1:!3945.12N/10459.02W#PHG5130 Denver digi",
            "WX0DEN>APRS:=3944.10N/10458.50W_220/004g010t045r000p000P000h40b10150Weather",
            "KF0XYZ-7>APRS::BLN1     :ARES net tonight 19:00 on 146.52",
            "KF0EOC>APRS:;SHELTER  *092345z3943.80N/10457.90W;Red Cross shelter, cots + water",
            "KF0ABC-9>APRS:=3946.00N/10501.00W>090/025Heading to the trailhead",
        )
        for (l in lines) Aprs.decodeTnc2(l)?.let { handle(it); log(l, false, it.kind) }
        if (inbox.isEmpty()) {
            inbox.add(WinlinkMessage.compose("KF0XYZ", listOf("N0CALL"), subject = "Check-in received",
                body = "Thanks for the check-in. Net control logged you at 18:05.", dateMillis = now() - 3_600_000))
        }
    }
}
