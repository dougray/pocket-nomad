// MeshService.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Owns one radio link, the handshake (Meshtastic or MeshCore), the node list
// and chat history. Ported from App/Sources/Mesh/MeshService.swift. Screens
// observe the StateFlows. All calls and transport events run on one thread
// (the main thread in the app); timers run in `scope`.
//
// With two radios (one Meshtastic, one MeshCore) there is one MeshService per
// radio, each with its own transport (so its own GATT connection and operation
// queue), prefs and store; MeshRadios holds them and merges what :app sees.

package org.nomadpocket.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nomadpocket.mesh.proto.Bytes
import org.nomadpocket.mesh.proto.MeshConstants
import org.nomadpocket.mesh.proto.MeshCoreCommand
import org.nomadpocket.mesh.proto.MeshCoreContact
import org.nomadpocket.mesh.proto.MeshCoreContactType
import org.nomadpocket.mesh.proto.MeshCoreFrame
import org.nomadpocket.mesh.proto.MeshCoreRawPacket
import org.nomadpocket.mesh.proto.MeshCoreRxLog
import org.nomadpocket.mesh.proto.MeshPBAdmin
import org.nomadpocket.mesh.proto.MeshPBChannel
import org.nomadpocket.mesh.proto.MeshPBLoRaConfig
import org.nomadpocket.mesh.proto.MeshPBData
import org.nomadpocket.mesh.proto.MeshPBFromRadio
import org.nomadpocket.mesh.proto.MeshPBFromRadioVariant
import org.nomadpocket.mesh.proto.MeshPBNodeInfo
import org.nomadpocket.mesh.proto.MeshPBPacket
import org.nomadpocket.mesh.proto.MeshPBPosition
import org.nomadpocket.mesh.proto.MeshPBRouting
import org.nomadpocket.mesh.proto.MeshPBToRadio
import org.nomadpocket.mesh.proto.MeshPBUser
import org.nomadpocket.mesh.proto.MeshPort
import org.nomadpocket.mesh.proto.MeshRoutingError
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class MeshService(
    private val transport: MeshTransport,
    private val store: MeshStore,
    private val prefs: MeshPrefs,
    private val scope: CoroutineScope,
    /** One-shot GPS fix for an SOS; null when refused or no fix. */
    private val location: suspend () -> Fix? = { null },
    private val locationDenied: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    // MARK: Observable state

    private val _availability = MutableStateFlow(MeshBluetoothAvailability.UNKNOWN)
    private val _connection = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Idle)
    private val _display = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Idle)
    private val _discovered = MutableStateFlow<List<MeshDiscoveredDevice>>(emptyList())
    private val _nodes = MutableStateFlow<Map<Long, MeshNode>>(emptyMap())
    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    private val _radio = MutableStateFlow(MeshRadioInfo())
    private val _lastRead = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _blocked = MutableStateFlow<Set<Long>>(emptySet())
    private val _pairingHint = MutableStateFlow<String?>(null)
    private val _notice = MutableStateFlow<String?>(null)
    private val _isScanning = MutableStateFlow(false)
    private val _lastDevice = MutableStateFlow<Pair<String, String>?>(null)
    private val _apply = MutableStateFlow<RadioApplyState>(RadioApplyState.Idle)

    val availabilityFlow: StateFlow<MeshBluetoothAvailability> = _availability.asStateFlow()
    /** The link as it is right now; drive mode and sending go by this. */
    val connectionFlow: StateFlow<MeshConnectionState> = _connection.asStateFlow()
    /**
     * The link as the user sees it: a drop that recovers within FLICKER_MILLIS
     * (walking to the edge of Bluetooth range) still reads Connected, so cards,
     * notes and the drive log don't flicker every few seconds.
     */
    val displayConnectionFlow: StateFlow<MeshConnectionState> = _display.asStateFlow()
    val discoveredFlow: StateFlow<List<MeshDiscoveredDevice>> = _discovered.asStateFlow()
    val nodesFlow: StateFlow<Map<Long, MeshNode>> = _nodes.asStateFlow()
    val messagesFlow: StateFlow<List<MeshMessage>> = _messages.asStateFlow()
    val radioFlow: StateFlow<MeshRadioInfo> = _radio.asStateFlow()
    val lastReadFlow: StateFlow<Map<String, Long>> = _lastRead.asStateFlow()
    /** Radios the user blocked: their messages are dropped and they leave the node list. */
    val blockedFlow: StateFlow<Set<Long>> = _blocked.asStateFlow()
    /** Shown when pairing is needed or went wrong. */
    val pairingHintFlow: StateFlow<String?> = _pairingHint.asStateFlow()
    /** One-line notice from the radio or about a failure. UI may clear it. */
    val noticeFlow: StateFlow<String?> = _notice.asStateFlow()
    val isScanningFlow: StateFlow<Boolean> = _isScanning.asStateFlow()
    /** Progress of the last Radio settings Apply. */
    val applyFlow: StateFlow<RadioApplyState> = _apply.asStateFlow()
    /** (id, name) of the remembered radio. */
    val lastDeviceFlow: StateFlow<Pair<String, String>?> = _lastDevice.asStateFlow()

    val availability get() = _availability.value
    val connection get() = _connection.value
    val displayConnection get() = _display.value
    /** Drops that came back within FLICKER_MILLIS, since launch (drive mode's heartbeat counts them). */
    var briefDrops = 0
        private set
    /** Why the link last went down, in plain words, for the drive log. */
    var lastDropReason: String? = null
        private set
    /** "Not with me today": no reconnecting this app session. Cleared by Connect or the next launch. */
    val notWithMe: Boolean get() = _connection.value is MeshConnectionState.NotWithMe
    val discovered get() = _discovered.value
    val nodes get() = _nodes.value
    val messages get() = _messages.value
    val radio get() = _radio.value
    val blocked get() = _blocked.value
    val pairingHint get() = _pairingHint.value
    var notice: String?
        get() = _notice.value
        set(v) { _notice.value = v }
    val isScanning get() = _isScanning.value
    val lastDeviceID: String? get() = _lastDevice.value?.first
    val lastDeviceName: String? get() = _lastDevice.value?.second

    /** Called each time the radio hears another node live (for the coverage scanner). */
    var onHeard: ((MeshHeard) -> Unit)? = null

    /**
     * Called when a radio's link is up, with the firmware found on it. Returning
     * false drops it here (MeshRadios moves it to the other firmware's slot).
     */
    var onReady: ((id: String, name: String, firmware: Firmware) -> Boolean)? = null

    // MARK: Internals

    private var started = false
    private var autoReconnect: Boolean
    private var configID = 0L
    private var handshakeDone = false
    private var handshakeAttempts = 0
    private var isReading = false
    private var readAgain = false
    private var currentDeviceName = ""
    private var saveJob: Job? = null
    private var handshakeTimeout: Job? = null
    /** When the remembered radio was last lost (or first looked for); null while linked or idle. */
    private var unseenSince: Long? = null
    /** When the current drop started, while the displayed state still says Connected. */
    private var dropSince: Long? = null
    private var notWithMeToday = false

    // MeshCore: one command in flight at a time, each waiting for its reply.
    private class CoreCommand(
        val frame: ByteArray,
        /** Response codes that finish this command (errors always do). */
        val finishedBy: Set<Int>,
        val timeoutMillis: Long = 5_000,
        val onReply: ((MeshCoreFrame) -> Unit)? = null,
        val onTimeout: (() -> Unit)? = null,
    )
    private val coreQueue = ArrayDeque<CoreCommand>()
    private var corePending: CoreCommand? = null
    private var coreTimer: Job? = null

    private object Keys {
        const val LAST_DEVICE_ID = "mesh.lastDeviceID"
        const val LAST_DEVICE_NAME = "mesh.lastDeviceName"
        const val AUTO_RECONNECT = "mesh.autoReconnect"
        const val BLOCKED = "mesh.blocked"
    }

    init {
        autoReconnect = prefs.getBoolean(Keys.AUTO_RECONNECT, false)
        val id = prefs.getString(Keys.LAST_DEVICE_ID)
        if (id != null) _lastDevice.value = id to (prefs.getString(Keys.LAST_DEVICE_NAME) ?: "radio")
        _blocked.value = (prefs.getString(Keys.BLOCKED) ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

        val snap = store.load()
        _messages.value = snap.messages
        _nodes.value = snap.nodes.associateBy { it.num }
        _radio.value = snap.radio
        _lastRead.value = snap.lastRead

        transport.onEvent = { handle(it) }
    }

    // MARK: - Public API

    /** Brings Bluetooth up and reconnects to the last radio if the user had one connected. */
    fun start() {
        if (started) return
        started = true
        transport.start()
        val id = lastDeviceID
        if (autoReconnect && id != null) {
            setConnection(MeshConnectionState.Reconnecting(lastDeviceName ?: "radio"))
            transport.connect(id)
        }
    }

    fun startScan() {
        start()
        _discovered.value = emptyList()
        _isScanning.value = true
        if (connection is MeshConnectionState.Idle) setConnection(MeshConnectionState.Scanning)
        transport.startScan()
    }

    fun stopScan() {
        _isScanning.value = false
        transport.stopScan()
        if (connection is MeshConnectionState.Scanning) setConnection(MeshConnectionState.Idle)
    }

    fun connect(device: MeshDiscoveredDevice) = connect(device.id, device.name)

    /** True while this link has a remembered radio or a connection in progress. */
    val inUse: Boolean get() = lastDeviceID != null || (connection !is MeshConnectionState.Idle && connection !is MeshConnectionState.Scanning)

    fun reconnectLast() {
        val id = lastDeviceID ?: return
        connect(id, lastDeviceName ?: "radio")
    }

    fun disconnect() {
        autoReconnect = false
        prefs.putBoolean(Keys.AUTO_RECONNECT, false)
        notWithMeToday = false
        val wasConnected = connection !is MeshConnectionState.Idle && connection !is MeshConnectionState.Scanning
        handshakeTimeout?.cancel()
        coreReset()
        transport.setLowPower(false)
        transport.disconnect()
        if (wasConnected) lastDropReason = "user disconnect"
        setConnection(MeshConnectionState.Idle)
    }

    /**
     * "Not with me today": stops reconnecting to the remembered radio for this
     * app session only. Auto-reconnect stays on, so the next launch (or Connect)
     * looks for it again.
     */
    fun notWithMeToday() {
        if (lastDeviceID == null) return
        notWithMeToday = true
        lastDropReason = "not with me today"
        handshakeTimeout?.cancel()
        coreReset()
        transport.setLowPower(false)
        transport.disconnect()
        setConnection(MeshConnectionState.NotWithMe(lastDeviceName ?: "radio"))
    }

    /**
     * Re-checks the timers: a drop older than FLICKER_MILLIS is shown, and a
     * remembered radio unseen for NOT_NEARBY_MILLIS becomes Not nearby. Runs by
     * itself; public so tests can drive it with their own clock.
     */
    fun checkLink() {
        updateDisplay()
        val since = unseenSince ?: return
        val c = connection
        if ((c is MeshConnectionState.Reconnecting || c is MeshConnectionState.Connecting) &&
            autoReconnect && lastDeviceID != null && now() - since >= NOT_NEARBY_MILLIS) {
            // Keep a cheap background attempt going so it comes back on its own.
            transport.setLowPower(true)
            setConnection(MeshConnectionState.NotNearby(lastDeviceName ?: currentDeviceName.ifEmpty { "radio" }))
        }
    }

    private fun setConnection(new: MeshConnectionState) {
        val old = _connection.value
        _connection.value = new
        when (new) {
            is MeshConnectionState.Reconnecting, is MeshConnectionState.Connecting -> if (unseenSince == null) {
                unseenSince = now()
                later(NOT_NEARBY_MILLIS)
            }
            is MeshConnectionState.NotNearby -> {}
            else -> unseenSince = null
        }
        if (old is MeshConnectionState.NotNearby && new !is MeshConnectionState.NotNearby) transport.setLowPower(false)
        updateDisplay()
    }

    /** Debounces the displayed state: drops shorter than FLICKER_MILLIS keep showing Connected. */
    private fun updateDisplay() {
        val raw = connection
        if (raw is MeshConnectionState.Connected) {
            if (dropSince != null) { briefDrops += 1; dropSince = null }
            _display.value = raw
            return
        }
        val trying = raw is MeshConnectionState.Reconnecting || raw is MeshConnectionState.Connecting || raw is MeshConnectionState.Syncing
        if (_display.value is MeshConnectionState.Connected && trying) {
            val since = dropSince ?: now().also { dropSince = it; later(FLICKER_MILLIS) }
            if (now() - since < FLICKER_MILLIS) return
        }
        dropSince = null
        _display.value = raw
    }

    private fun later(millis: Long) {
        scope.launch {
            delay(millis)
            checkLink()
        }
    }

    /** Forgets the remembered radio entirely. */
    fun forgetDevice() {
        disconnect()
        _lastDevice.value = null
        prefs.remove(Keys.LAST_DEVICE_ID)
        prefs.remove(Keys.LAST_DEVICE_NAME)
    }

    val myNodeNum: Long get() = radio.myNodeNum
    val myNode: MeshNode? get() = nodes[radio.myNodeNum]

    /** Other radios, most recently heard first. */
    val sortedNodes: List<MeshNode> get() = sortedNodes(nodes, radio.myNodeNum, blocked)

    /** Blocked radios, for the list where they can be unblocked. */
    val blockedNodes: List<MeshNode> get() = blocked.map { node(it) }.sortedBy { it.displayName }

    fun isBlocked(num: Long): Boolean = blocked.contains(num)

    /**
     * Hides a radio on this phone: deletes its messages, drops new ones and takes it
     * off the node list. Nothing is sent; the other radio can't tell.
     */
    fun block(num: Long) {
        if (num == 0L || num == radio.myNodeNum) return
        _blocked.value = blocked + num
        _messages.value = messages.filterNot { it.from == num || (it.isOutgoing && it.to == num) }
        saveBlocked()
        scheduleSave()
    }

    fun unblock(num: Long) {
        _blocked.value = blocked - num
        saveBlocked()
    }

    private fun saveBlocked() {
        prefs.putString(Keys.BLOCKED, blocked.joinToString(","))
    }

    fun node(num: Long): MeshNode = nodes[num] ?: MeshNode(num = num)

    fun messages(conversation: MeshConversation): List<MeshMessage> =
        messages.filter { conversation(it, radio.myNodeNum) == conversation }

    /** Direct-message threads, newest first. */
    val directThreads: List<Pair<MeshNode, MeshMessage>>
        get() = directThreads(messages, radio.myNodeNum).map { node(it.first) to it.second }

    fun unreadCount(conversation: MeshConversation): Int {
        val since = _lastRead.value[conversation.key] ?: Long.MIN_VALUE
        return messages(conversation).count { !it.isOutgoing && it.dateMillis > since }
    }

    val totalUnread: Int
        get() {
            val keys = mutableSetOf<MeshConversation>(MeshConversation.Channel(0))
            for ((n, _) in directThreads) keys.add(MeshConversation.Direct(n.num))
            return keys.sumOf { unreadCount(it) }
        }

    fun markRead(conversation: MeshConversation) {
        // Strictly after anything already received at this instant.
        _lastRead.value = _lastRead.value + (conversation.key to now())
        scheduleSave()
    }

    val canSend: Boolean get() = connection.isConnected

    /** Which firmware the current (or last) radio runs. */
    val firmware: Firmware get() = radio.firmware ?: Firmware.MESHTASTIC

    val maxTextBytes: Int get() = firmware.maxTextBytes

    /** Sends a text. Returns false when not connected or the text is too long. */
    fun send(rawText: String, conversation: MeshConversation): Boolean {
        val text = rawText.trim()
        if (text.isEmpty() || MeshText.byteCount(text) > maxTextBytes) return false
        if (!connection.isLinkUp) {
            notice = "Not connected to a radio."
            return false
        }
        if (firmware == Firmware.MESHCORE) return coreSend(text, conversation)
        val (to, channel) = when (conversation) {
            is MeshConversation.Channel -> MeshConstants.BROADCAST_NUM to conversation.index
            is MeshConversation.Direct -> conversation.node to 0L
        }
        val id = newPacketID()
        val packet = MeshPBPacket(
            to = to, channel = channel,
            decoded = MeshPBData(portnum = MeshPort.TEXT_MESSAGE, payload = text.toByteArray(Charsets.UTF_8)),
            id = id, hopLimit = hopLimit, wantAck = true,
        )
        transport.write(MeshPBToRadio.Packet(packet).encode())
        _messages.value = messages + MeshMessage(packetID = id, from = radio.myNodeNum, to = to, channel = channel,
            text = text, dateMillis = now(), isOutgoing = true, delivery = MeshDeliveryState.Sent)
        trimHistory()
        scheduleSave()
        startReading()
        return true
    }

    private val hopLimit: Long get() = radio.hopLimit?.takeIf { it > 0 } ?: MeshConstants.DEFAULT_HOP_LIMIT

    /** Text that the SOS button will send (without location). */
    val sosPreview: String get() = MeshSOS.text(null, null, myNode?.longName)

    val isLocationDenied: Boolean get() = locationDenied()

    /**
     * Sends the SOS to everyone on the primary channel, with a GPS fix if
     * allowed. Returns the text sent, or null if it could not be sent.
     */
    suspend fun sendSOS(includeLocation: Boolean): String? {
        val fix = if (includeLocation) location() else null
        return sendSOS(fix?.latitude, fix?.longitude)
    }

    /** Sends the SOS with a position already found (MeshRadios looks it up once for both radios). */
    fun sendSOS(latitude: Double?, longitude: Double?): String? {
        val text = MeshText.truncated(MeshSOS.text(latitude, longitude, myNode?.longName), maxTextBytes)
        return if (send(text, MeshConversation.Channel(0))) text else null
    }

    // MARK: Drive-test pings

    private class PendingPing(val target: Long?, var best: PingOutcome = PingOutcome.NONE, val done: (PingOutcome) -> Unit) {
        var timeout: Job? = null
    }
    private val pendingPings = mutableMapOf<Long, PendingPing>()

    /**
     * Sends one drive-test ping and reports what came back. With no target
     * (Meshtastic only) it asks whether any radio repeats it: only neighbours
     * relay it once (hop limit 1), so it doesn't flood the mesh. With a target,
     * REACHED means that radio acknowledged it. MeshCore pings are a short
     * direct text, so they need a target and show up on that radio.
     * Returns false if the ping could not be sent.
     */
    fun ping(target: Long?, done: (PingOutcome) -> Unit): Boolean {
        if (connection !is MeshConnectionState.Connected) return false
        if (firmware == Firmware.MESHCORE) {
            if (target == null) return false
            // A repeater answers a trace without a login; anyone else gets a direct text.
            traceHash(target)?.let { hash ->
                val tag = Random.nextLong(1, 0xFFFF_FFFFL)
                coreEnqueue(MeshCoreCommand.sendTrace(tag, hash.array), setOf(6)) { reply ->
                    if (reply is MeshCoreFrame.Sent) {
                        val wait = minOf(30.0, maxOf(10.0, reply.timeoutMS / 1000.0 * 2))
                        trackPing(tag, target, wait, done)
                    } else {
                        done(PingOutcome.NONE)
                    }
                }
                return true
            }
            val prefix = nodes[target]?.publicKeyPrefix ?: return false
            coreEnqueue(MeshCoreCommand.sendText(PING_TEXT, prefix.array, now() / 1000), setOf(6)) { reply ->
                if (reply is MeshCoreFrame.Sent && reply.expectedAck != 0L) {
                    val wait = minOf(60.0, maxOf(20.0, reply.timeoutMS / 1000.0 * 1.5))
                    trackPing(reply.expectedAck, target, wait, done)
                } else {
                    done(PingOutcome.NONE)
                }
            }
            return true
        }
        val id = newPacketID()
        val packet = MeshPBPacket(
            to = target ?: MeshConstants.BROADCAST_NUM,
            channel = 0,
            decoded = MeshPBData(portnum = PING_PORT, payload = "pn-ping".toByteArray()),
            id = id,
            hopLimit = if (target == null) 1 else hopLimit,
            wantAck = true,
        )
        transport.write(MeshPBToRadio.Packet(packet).encode())
        // The radio retries for about 30 s before giving up.
        trackPing(id, target, 45.0, done)
        startReading()
        return true
    }

    /** A MeshCore repeater heard directly (the last hop of a flood packet). */
    data class Neighbor(val hash: Bytes, val lastHeardMillis: Long, val snr: Float)

    /** Keyed by `MeshCoreContact.nodeNumForHash`. */
    private val _neighbors = MutableStateFlow<Map<Long, Neighbor>>(emptyMap())
    val neighbors get() = _neighbors.value

    /** The repeater to ping when none was chosen: the one heard most recently, within 10 minutes. */
    fun autoPingTarget(): Long? {
        if (firmware != Firmware.MESHCORE) return null
        val t = now()
        return neighbors.filterValues { t - it.lastHeardMillis <= NEIGHBOR_WINDOW_MS }.maxByOrNull { it.value.lastHeardMillis }?.key
    }

    /** Path hash for a trace ping: a repeater heard in range, or a repeater contact (first key byte, MeshCore's default hash size). */
    private fun traceHash(target: Long): Bytes? {
        neighbors[target]?.let { return it.hash }
        val node = nodes[target] ?: return null
        val prefix = node.publicKeyPrefix ?: return null
        return if (node.meshCoreType == MeshCoreContactType.REPEATER && !prefix.isEmpty()) prefix.prefix(1) else null
    }

    /**
     * Every packet the radio hears is a coverage point, whoever it was for: the
     * sender is the repeater that passed it to us (or, for a straight-from-the-source
     * advert, the node itself).
     */
    private fun coreHeardPacket(log: MeshCoreRxLog) {
        val packet = log.packet ?: return
        // A repeater is recorded by its hash (as a stand-in number), never guessed
        // to be the first contact whose key starts with it: many radios across the
        // mesh share a hash. HeardResolver picks the one in range of where the
        // phone was, when the map is drawn.
        val node = when (val from = packet.heardFrom) {
            is MeshCoreRawPacket.HeardFrom.Repeater -> {
                val num = MeshCoreContact.nodeNumForHash(from.hash.array)
                _neighbors.value = neighbors + (num to Neighbor(from.hash, now(), log.snr))
                num
            }
            is MeshCoreRawPacket.HeardFrom.Node -> MeshCoreContact.nodeNum(from.publicKey.array).also { num ->
                nodes[num]?.let { putNode(it.copy(lastHeardMillis = now(), snr = log.snr)) }
            }
            null -> 0L
        }
        onHeard?.invoke(MeshHeard(node, log.snr, packet.path.size.toLong(), Firmware.MESHCORE))
    }

    private fun trackPing(id: Long, target: Long?, waitSeconds: Double, done: (PingOutcome) -> Unit) {
        val p = PendingPing(target, done = done)
        pendingPings[id] = p
        p.timeout = scope.launch {
            delay((waitSeconds * 1000).toLong())
            finishPing(id)
        }
    }

    private fun finishPing(id: Long, outcome: PingOutcome? = null) {
        val p = pendingPings.remove(id) ?: return
        p.timeout?.cancel()
        p.done(outcome ?: p.best)
    }

    /** Routing answers for a ping. Returns true if the packet was a ping's. */
    private fun handlePingRouting(p: MeshPBPacket, data: MeshPBData): Boolean {
        val pending = pendingPings[data.requestID] ?: return false
        val reason = runCatching { MeshPBRouting.decode(data.payload) }.getOrNull()?.errorReason ?: return true
        if (reason == MeshRoutingError.NONE) {
            when {
                pending.target != null && p.from == pending.target -> finishPing(data.requestID, PingOutcome.REACHED)
                pending.target == null -> finishPing(data.requestID, PingOutcome.RELAYED)
                // a neighbour repeated it; keep waiting for the target
                else -> pending.best = PingOutcome.RELAYED
            }
        } else {
            finishPing(data.requestID)
        }
        return true
    }

    fun clearPairingHint() { _pairingHint.value = null }

    /**
     * Deletes every stored message and node from this phone. The radio keeps
     * its own node list; reconnecting downloads that again.
     */
    fun deleteHistory() {
        _messages.value = emptyList()
        _nodes.value = nodes.filter { it.key == radio.myNodeNum && it.key != 0L }
        _lastRead.value = emptyMap()
        saveNow()
    }

    // MARK: - Transport events

    fun handle(event: MeshTransportEvent) {
        when (event) {
            is MeshTransportEvent.Availability -> {
                _availability.value = event.availability
                if (event.availability != MeshBluetoothAvailability.POWERED_ON) {
                    isReading = false
                    if (connection.isLinkUp) {
                        lastDropReason = "Bluetooth turned off"
                        setConnection(MeshConnectionState.Reconnecting(currentDeviceName))
                    }
                }
            }

            is MeshTransportEvent.Discovered -> {
                val d = event.device
                val list = discovered.toMutableList()
                val i = list.indexOfFirst { it.id == d.id }
                if (i >= 0) list[i] = d else list.add(d)
                _discovered.value = list.sortedByDescending { if (it.rssi == 0) -999 else it.rssi }
            }

            is MeshTransportEvent.Connecting -> {
                currentDeviceName = event.name
                // A background attempt while away doesn't bring the radio back; Ready does.
                if (!connection.isAway) setConnection(MeshConnectionState.Connecting(event.name))
            }

            is MeshTransportEvent.Ready -> {
                if (onReady?.invoke(event.id, event.name, event.firmware) == false) return
                currentDeviceName = event.name
                rememberDevice(event.id, event.name)
                _pairingHint.value = null
                if (isScanning) stopScan()
                _radio.value = radio.copy(firmware = event.firmware)
                if (event.firmware == Firmware.MESHCORE) coreBeginHandshake() else beginHandshake()
            }

            is MeshTransportEvent.Frame -> coreHandle(event.data)

            is MeshTransportEvent.Disconnected -> {
                isReading = false
                handshakeTimeout?.cancel()
                coreReset()
                if (notWithMeToday) {
                    setConnection(MeshConnectionState.NotWithMe(lastDeviceName ?: currentDeviceName))
                    return
                }
                if (connection.isLinkUp) lastDropReason = event.reason ?: "the link closed"
                if (connection is MeshConnectionState.NotNearby && event.willReconnect) return
                if (_apply.value is RadioApplyState.Working && firmware == Firmware.MESHCORE) {
                    failApply("The radio disconnected before it answered. Check its settings after it reconnects.")
                }
                if (_apply.value == RadioApplyState.Restarting && !event.willReconnect && autoReconnect) {
                    // The radio restarts for its new settings: come back to it.
                    setConnection(MeshConnectionState.Reconnecting(currentDeviceName))
                    scope.launch {
                        delay(RESTART_RECONNECT_DELAY)
                        if (connection is MeshConnectionState.Reconnecting) lastDeviceID?.let { transport.connect(it) }
                    }
                    return
                }
                if (event.willReconnect) {
                    setConnection(MeshConnectionState.Reconnecting(currentDeviceName))
                } else {
                    setConnection(MeshConnectionState.Idle)
                    event.reason?.let { notice = it }
                }
            }

            is MeshTransportEvent.ConnectFailed -> {
                if (notWithMeToday) return
                // Away, or already waiting for a remembered radio: one "Waiting" on the card is
                // enough, so repeated failures don't pile up notices.
                if (connection.isAway) return
                val quiet = autoReconnect && connection is MeshConnectionState.Reconnecting
                if (!quiet) notice = event.reason
                if (connection.isLinkUp) lastDropReason = event.reason
                setConnection(if (autoReconnect) MeshConnectionState.Reconnecting(currentDeviceName)
                    else MeshConnectionState.Failed(event.reason))
            }

            is MeshTransportEvent.FromRadio -> {
                if (event.data.isEmpty()) {
                    isReading = false
                    if (readAgain) {
                        readAgain = false
                        startReading()
                    } else if (!handshakeDone && connection.isLinkUp) {
                        // Radio may still be preparing its config; poll briefly.
                        scope.launch {
                            delay(300)
                            startReading()
                        }
                    }
                    return
                }
                try {
                    handleFromRadio(MeshPBFromRadio.decode(event.data))
                } catch (_: Exception) {
                    // Corrupt or partial packet: skip it and keep draining.
                }
                transport.readFromRadio()
            }

            MeshTransportEvent.FromNumChanged -> startReading()

            is MeshTransportEvent.PairingProblem -> _pairingHint.value = event.message

            is MeshTransportEvent.WriteFailed -> {
                notice = "Could not send to the radio: ${event.message}"
                val i = messages.indexOfLast { it.isOutgoing && it.delivery == MeshDeliveryState.Sent }
                if (i >= 0) updateMessage(i) { it.copy(delivery = MeshDeliveryState.Failed("Not sent")) }
            }
        }
    }

    // MARK: - Handshake and reading

    private fun connect(id: String, name: String) {
        start()
        notWithMeToday = false
        autoReconnect = true
        prefs.putBoolean(Keys.AUTO_RECONNECT, true)
        rememberDevice(id, name)
        currentDeviceName = name
        setConnection(MeshConnectionState.Connecting(name))
        transport.connect(id)
    }

    private fun rememberDevice(id: String, name: String) {
        _lastDevice.value = id to name
        prefs.putString(Keys.LAST_DEVICE_ID, id)
        prefs.putString(Keys.LAST_DEVICE_NAME, name)
    }

    private fun beginHandshake() {
        handshakeDone = false
        handshakeAttempts += 1
        setConnection(MeshConnectionState.Syncing(currentDeviceName))
        configID = newPacketID()
        transport.write(MeshPBToRadio.WantConfigID(configID).encode())
        startReading()

        handshakeTimeout?.cancel()
        val attempt = handshakeAttempts
        handshakeTimeout = scope.launch {
            delay(30_000)
            if (handshakeDone || attempt != handshakeAttempts) return@launch
            if (attempt < 2) {
                beginHandshake()
            } else {
                // Messages may still flow; let the user try.
                handshakeDone = true
                setConnection(MeshConnectionState.Connected(currentDeviceName))
                notice = "The radio did not finish sending its settings. Messages may still work."
            }
        }
    }

    private fun startReading() {
        if (!connection.isLinkUp) return
        if (isReading) {
            readAgain = true
            return
        }
        isReading = true
        transport.readFromRadio()
    }

    private fun handleFromRadio(msg: MeshPBFromRadio) {
        when (val v = msg.variant) {
            is MeshPBFromRadioVariant.MyInfo -> _radio.value = radio.copy(myNodeNum = v.info.myNodeNum)
            is MeshPBFromRadioVariant.NodeInfo -> upsert(v.info)
            is MeshPBFromRadioVariant.Channel -> {
                val ch = v.channel
                _radio.value = if (ch.role == MeshPBChannel.Role.DISABLED) {
                    radio.copy(channelNames = radio.channelNames - ch.index)
                } else {
                    radio.copy(
                        channelNames = radio.channelNames + (ch.index to ch.name),
                        primaryChannelName = if (ch.role == MeshPBChannel.Role.PRIMARY) ch.name else radio.primaryChannelName,
                    )
                }
            }
            is MeshPBFromRadioVariant.Metadata -> _radio.value = radio.copy(firmwareVersion = v.metadata.firmwareVersion)
            is MeshPBFromRadioVariant.LoraConfig -> applyLora(v.config)
            is MeshPBFromRadioVariant.ConfigCompleteID -> {
                if (v.id != configID) return
                handshakeDone = true
                handshakeAttempts = 0
                handshakeTimeout?.cancel()
                setConnection(MeshConnectionState.Connected(currentDeviceName))
                if (meshtasticConfirm()) {
                    // Radio settings just changed: the result is shown there.
                } else if (radio.region == 0L) {
                    notice = NO_REGION_NOTICE
                }
                scheduleSave()
            }
            MeshPBFromRadioVariant.Rebooted -> if (connection.isLinkUp) beginHandshake()
            is MeshPBFromRadioVariant.QueueStatus -> {
                val q = v.status
                if (q.res != 0 && q.meshPacketID != 0L) {
                    val i = messages.indexOfLast { it.isOutgoing && it.packetID == q.meshPacketID }
                    if (i >= 0) {
                        updateMessage(i) { it.copy(delivery = MeshDeliveryState.Failed("Radio is busy, try again")) }
                        scheduleSave()
                    }
                }
            }
            is MeshPBFromRadioVariant.ClientNotification -> if (v.message.isNotEmpty()) notice = v.message
            is MeshPBFromRadioVariant.Packet -> handlePacket(v.packet)
            MeshPBFromRadioVariant.OtherConfig, is MeshPBFromRadioVariant.LogRecord, is MeshPBFromRadioVariant.Unhandled, null -> {}
        }
    }

    private fun putNode(n: MeshNode) { _nodes.value = nodes + (n.num to n) }

    private fun upsert(info: MeshPBNodeInfo) {
        if (info.num == 0L) return
        var n = node(info.num)
        info.user?.let { n = apply(it, n) }
        info.position?.takeIf { it.hasFix }?.let { n = apply(it, n) }
        if (info.lastHeard != 0L) {
            val heard = info.lastHeard * 1000
            n = n.copy(lastHeardMillis = maxOf(heard, n.lastHeardMillis ?: Long.MIN_VALUE))
        }
        if (info.snr != 0f) n = n.copy(snr = info.snr)
        info.hopsAway?.let { n = n.copy(hopsAway = it) }
        info.deviceMetrics?.batteryLevel?.let { n = n.copy(batteryLevel = it) }
        putNode(n)
        scheduleSave()
    }

    private fun apply(u: MeshPBUser, n: MeshNode): MeshNode = n.copy(
        userID = u.id.ifEmpty { n.userID },
        longName = u.longName.ifEmpty { n.longName },
        shortName = u.shortName.ifEmpty { n.shortName },
        hwModel = if (u.hwModel != 0L) u.hwModel else n.hwModel,
        hasPublicKey = n.hasPublicKey || u.publicKey.isNotEmpty(),
        role = u.role,
    )

    private fun apply(p: MeshPBPosition, n: MeshNode): MeshNode =
        n.copy(latitude = p.latitude, longitude = p.longitude, altitude = p.altitude)

    private fun handlePacket(p: MeshPBPacket) {
        // Any packet tells us the sender is alive.
        if (p.from != 0L && p.from != radio.myNodeNum) {
            var n = node(p.from)
            n = n.copy(lastHeardMillis = if (p.rxTime != 0L) p.rxTime * 1000 else now())
            if (p.rxSNR != 0f) n = n.copy(snr = p.rxSNR)
            p.hopsAway?.let { n = n.copy(hopsAway = it) }
            putNode(n)
            // Packets the radio stored while the phone was away arrive with old
            // receive times; only fresh ones say where the phone is now.
            if (p.rxTime == 0L || now() / 1000.0 - p.rxTime < 120) {
                onHeard?.invoke(MeshHeard(p.from, if (p.rxSNR != 0f) p.rxSNR else null, p.hopsAway, Firmware.MESHTASTIC))
            }
        }
        val data = p.decoded ?: return // still encrypted: not for us
        when (data.portnum) {
            MeshPort.TEXT_MESSAGE -> handleText(p, data)
            MeshPort.POSITION -> {
                val pos = runCatching { MeshPBPosition.decode(data.payload) }.getOrNull()
                if (pos != null && pos.hasFix && p.from != 0L) putNode(apply(pos, node(p.from)))
            }
            MeshPort.NODE_INFO -> {
                val user = runCatching { MeshPBUser.decode(data.payload) }.getOrNull()
                if (user != null && p.from != 0L) putNode(apply(user, node(p.from)))
            }
            MeshPort.ROUTING -> handleRouting(p, data)
            MeshPort.ADMIN -> handleAdmin(p, data)
        }
        scheduleSave()
    }

    private fun handleText(p: MeshPBPacket, data: MeshPBData) {
        val myNum = radio.myNodeNum
        // Drop duplicates (the radio replays its queue after a reconnect).
        if (p.id != 0L && messages.any { it.packetID == p.id && it.from == p.from }) return
        // Only broadcasts and messages addressed to us (or sent by us), never from a blocked radio.
        if (!(p.to == MeshConstants.BROADCAST_NUM || p.to == myNum || p.from == myNum)) return
        if (blocked.contains(p.from)) return
        val text = String(data.payload, Charsets.UTF_8)
        val date = if (p.rxTime != 0L) p.rxTime * 1000 else now()
        _messages.value = messages + MeshMessage(
            packetID = p.id, from = p.from, to = p.to, channel = p.channel, text = text,
            dateMillis = minOf(date, now()), isOutgoing = p.from == myNum && myNum != 0L,
            delivery = if (p.from == myNum) MeshDeliveryState.Sent else MeshDeliveryState.Received,
            rxSNR = if (p.rxSNR != 0f) p.rxSNR else null, hopsAway = p.hopsAway,
        )
        trimHistory()
    }

    private fun handleRouting(p: MeshPBPacket, data: MeshPBData) {
        if (data.requestID != 0L && handlePingRouting(p, data)) return
        if (data.requestID != 0L && data.requestID == adminSetID) {
            val reason = runCatching { MeshPBRouting.decode(data.payload) }.getOrNull()?.errorReason
            if (reason != null && reason != MeshRoutingError.NONE) {
                failApply("The radio refused the new settings: ${MeshRoutingError.describe(reason)}.")
            }
            return
        }
        if (data.requestID == 0L) return
        val i = messages.indexOfLast { it.isOutgoing && it.packetID == data.requestID }
        if (i < 0) return
        val reason = runCatching { MeshPBRouting.decode(data.payload) }.getOrNull()?.errorReason ?: return
        val m = messages[i]
        val delivery = if (reason == MeshRoutingError.NONE) {
            if (!m.isBroadcast && p.from == m.to) {
                MeshDeliveryState.Delivered
            } else if (m.delivery != MeshDeliveryState.Delivered) {
                // Implicit ACK: our radio heard a neighbour repeat it.
                MeshDeliveryState.HeardByMesh
            } else m.delivery
        } else if (m.delivery != MeshDeliveryState.Delivered && m.delivery != MeshDeliveryState.HeardByMesh) {
            // A broadcast that ran out of retries was still transmitted; the radio
            // just never heard anyone repeat it. Only direct messages truly fail.
            if (m.isBroadcast && reason == MeshRoutingError.MAX_RETRANSMIT) MeshDeliveryState.NotRepeated
            else MeshDeliveryState.Failed(MeshRoutingError.describe(reason))
        } else m.delivery
        updateMessage(i) { it.copy(delivery = delivery) }
    }

    // MARK: - MeshCore

    /**
     * MeshCore startup: identify, learn limits, set the clock, then fetch
     * contacts, channels and any queued messages.
     */
    private fun coreBeginHandshake() {
        coreReset()
        handshakeDone = false
        handshakeAttempts += 1
        setConnection(MeshConnectionState.Syncing(currentDeviceName))

        coreEnqueue(MeshCoreCommand.appStart("Pocket Nomad"), setOf(5))
        coreEnqueue(MeshCoreCommand.deviceQuery(), setOf(13)) { frame ->
            if (frame !is MeshCoreFrame.DeviceInfo) return@coreEnqueue
            val info = frame.info
            if (info.version.isNotEmpty()) _radio.value = radio.copy(firmwareVersion = "MeshCore ${info.version}")
            // Channel slots vary by board (1, 8 or 40); list the first eight.
            for (i in 0 until minOf(maxOf(info.maxChannels, 1), 8)) {
                coreEnqueue(MeshCoreCommand.getChannel(i), setOf(18))
            }
        }
        coreEnqueue(MeshCoreCommand.setDeviceTime(now() / 1000), setOf(0))
        coreEnqueue(MeshCoreCommand.getContacts(), setOf(4), timeoutMillis = 20_000) { frame ->
            if (frame !is MeshCoreFrame.EndOfContacts) return@coreEnqueue
            handshakeDone = true
            handshakeAttempts = 0
            handshakeTimeout?.cancel()
            setConnection(MeshConnectionState.Connected(currentDeviceName))
            coreSyncMessages()
            scheduleSave()
        }

        handshakeTimeout?.cancel()
        val attempt = handshakeAttempts
        handshakeTimeout = scope.launch {
            delay(45_000)
            if (handshakeDone || attempt != handshakeAttempts) return@launch
            handshakeDone = true
            setConnection(MeshConnectionState.Connected(currentDeviceName))
            notice = "The radio did not finish sending its contacts. Messages may still work."
        }
    }

    private fun coreEnqueue(frame: ByteArray, finishedBy: Set<Int>, timeoutMillis: Long = 5_000,
                            onTimeout: (() -> Unit)? = null, onReply: ((MeshCoreFrame) -> Unit)? = null) {
        coreQueue.addLast(CoreCommand(frame, finishedBy, timeoutMillis, onReply, onTimeout))
        coreSendNext()
    }

    private fun coreSendNext() {
        if (corePending != null || coreQueue.isEmpty() || !connection.isLinkUp) return
        val command = coreQueue.removeFirst()
        corePending = command
        transport.write(command.frame)
        coreTimer?.cancel()
        coreTimer = scope.launch {
            delay(command.timeoutMillis)
            // No answer: give up on this command and move on.
            corePending = null
            command.onTimeout?.invoke()
            coreSendNext()
        }
    }

    private fun coreReset() {
        coreQueue.clear()
        corePending = null
        coreTimer?.cancel()
    }

    /** Pulls every queued message off the radio, one SYNC at a time. */
    private fun coreSyncMessages() {
        val syncCode = MeshCoreCommand.Code.SYNC_NEXT_MESSAGE.toByte()
        if (coreQueue.any { it.frame.firstOrNull() == syncCode } || corePending?.frame?.firstOrNull() == syncCode) return
        coreEnqueue(MeshCoreCommand.syncNextMessage(), setOf(7, 8, 10, 16, 17, 27)) { frame ->
            if (frame is MeshCoreFrame.NoMoreMessages) return@coreEnqueue
            coreSyncMessages()
        }
    }

    private fun coreHandle(data: ByteArray) {
        val code = (data.firstOrNull() ?: return).toInt() and 0xFF
        val frame = MeshCoreFrame.decode(data) ?: return
        when (frame) {
            is MeshCoreFrame.SelfInfo -> {
                val me = frame.info
                val num = MeshCoreContact.nodeNum(me.publicKey.array)
                _radio.value = radio.copy(myNodeNum = num,
                    regionName = String.format(Locale.ROOT, "%.3f MHz", me.frequencyMHz),
                    meshCoreParams = MeshCoreRadioParams(me.frequencyMHz, me.bandwidthKHz, me.spreadingFactor, me.codingRate),
                    txPowerDbm = me.txPowerDbm, maxTxPowerDbm = me.maxTxPowerDbm.takeIf { it > 0 })
                var n = node(num).copy(longName = me.name, shortName = me.name.take(4),
                    publicKeyPrefix = me.publicKey.prefix(6), hasPublicKey = true)
                if (me.latitude != null && me.longitude != null) n = n.copy(latitude = me.latitude, longitude = me.longitude)
                putNode(n)
            }
            is MeshCoreFrame.Contact -> coreUpsert(frame.contact)
            // An advert may have come from far away through many repeaters, so it is
            // a coverage point only; the raw packet log names who was in range.
            is MeshCoreFrame.NewAdvert -> {
                coreUpsert(frame.contact)
                onHeard?.invoke(MeshHeard(0, null, null, Firmware.MESHCORE))
            }
            is MeshCoreFrame.Advert -> {
                val num = MeshCoreContact.nodeNum(frame.publicKey.array)
                nodes[num]?.let { putNode(it.copy(lastHeardMillis = now())) }
                onHeard?.invoke(MeshHeard(0, null, null, Firmware.MESHCORE))
            }
            is MeshCoreFrame.ChannelInfo -> {
                val ch = frame.info
                _radio.value = radio.copy(
                    channelNames = if (ch.isEmpty) radio.channelNames - ch.index else radio.channelNames + (ch.index to ch.name),
                    primaryChannelName = if (ch.index == 0) ch.name else radio.primaryChannelName,
                )
            }
            is MeshCoreFrame.DirectMessage -> {
                val m = frame.message
                coreReceive(coreNode(m.senderKeyPrefix), radio.myNodeNum, 0, m.timestamp, m.text, null, m.snr, m.pathLength)
            }
            is MeshCoreFrame.ChannelMessage -> {
                val m = frame.message
                val sender = m.senderName?.let { name -> nodes.values.firstOrNull { it.longName == name }?.num } ?: 0L
                coreReceive(sender, MeshConstants.BROADCAST_NUM, m.channel.toLong(), m.timestamp, m.text, m.senderName, m.snr, m.pathLength)
            }
            is MeshCoreFrame.SendConfirmed -> {
                if (pendingPings.containsKey(frame.ack)) {
                    finishPing(frame.ack, PingOutcome.REACHED)
                } else {
                    val i = messages.indexOfLast { it.isOutgoing && it.packetID == frame.ack && frame.ack != 0L }
                    if (i >= 0) {
                        updateMessage(i) { it.copy(delivery = MeshDeliveryState.Delivered) }
                        scheduleSave()
                    }
                }
            }
            MeshCoreFrame.MessagesWaiting -> coreSyncMessages()
            is MeshCoreFrame.RxLog -> coreHeardPacket(frame.log)
            is MeshCoreFrame.TraceData -> if (pendingPings.containsKey(frame.tag)) finishPing(frame.tag, PingOutcome.REACHED)
            else -> {}
        }
        // Push frames (0x80 and up) arrive at any time; anything else answers the pending command.
        if (code >= 0x80) return
        val pending = corePending ?: return
        val finished = pending.finishedBy.contains(code) || code == 1
        if (finished) {
            coreTimer?.cancel()
            corePending = null
        }
        pending.onReply?.invoke(frame)
        if (finished) coreSendNext()
    }

    private fun coreUpsert(c: MeshCoreContact) {
        val num = c.nodeNum
        if (num == 0L) return
        var n = node(num).copy(
            longName = c.name, shortName = c.name.take(4),
            userID = c.publicKey.prefix(6).hex(), publicKeyPrefix = c.publicKey.prefix(6),
            hasPublicKey = true, meshCoreType = c.type,
        )
        c.lastAdvert?.let { n = n.copy(lastHeardMillis = maxOf(it * 1000, n.lastHeardMillis ?: Long.MIN_VALUE)) }
        if (c.latitude != null && c.longitude != null) n = n.copy(latitude = c.latitude, longitude = c.longitude)
        c.pathLength?.let { n = n.copy(hopsAway = it.toLong()) }
        putNode(n)
        scheduleSave()
    }

    private fun coreNode(prefix: Bytes): Long =
        nodes.values.firstOrNull { it.publicKeyPrefix == prefix }?.num ?: MeshCoreContact.nodeNum(prefix.array)

    private fun coreReceive(from: Long, to: Long, channel: Long, timestamp: Long, text: String,
                            senderName: String?, snr: Float?, path: Int) {
        // The radio's timestamp plus sender and text identify a message well enough to drop repeats.
        if (messages.any { it.packetID == timestamp && it.from == from && it.text == text }) return
        if (blocked.contains(from)) return
        val t = now()
        val hops = if (path == 0xFF) null else (path and 0x3F).toLong()
        _messages.value = messages + MeshMessage(
            packetID = timestamp, from = from, to = to, channel = channel, text = text,
            dateMillis = if (timestamp == 0L) t else minOf(timestamp * 1000, t), isOutgoing = false,
            delivery = MeshDeliveryState.Received, rxSNR = snr, hopsAway = hops, senderName = senderName,
        )
        if (from != 0L) nodes[from]?.let { n -> putNode(n.copy(lastHeardMillis = t, snr = snr ?: n.snr)) }
        // Messages queued on the radio while the phone was away are old; skip those.
        if (timestamp == 0L || abs(t / 1000.0 - timestamp) < 120) {
            // Only a zero-hop message was heard from its sender; otherwise it is a coverage point.
            onHeard?.invoke(MeshHeard(if (hops == 0L) from else 0, snr, hops, Firmware.MESHCORE))
        }
        trimHistory()
        scheduleSave()
    }

    private fun coreSend(text: String, conversation: MeshConversation): Boolean {
        val t = now()
        val frame: ByteArray
        val to: Long
        val channel: Long
        when (conversation) {
            is MeshConversation.Channel -> {
                frame = MeshCoreCommand.sendChannelText(text, conversation.index.coerceIn(0, 255).toInt(), t / 1000)
                to = MeshConstants.BROADCAST_NUM; channel = conversation.index
            }
            is MeshConversation.Direct -> {
                val prefix = nodes[conversation.node]?.publicKeyPrefix
                if (prefix == null) {
                    notice = "This contact has no MeshCore key yet. Wait until its radio is heard, then try again."
                    return false
                }
                frame = MeshCoreCommand.sendText(text, prefix.array, t / 1000)
                to = conversation.node; channel = 0
            }
        }
        val message = MeshMessage(packetID = 0, from = radio.myNodeNum, to = to, channel = channel,
            text = text, dateMillis = t, isOutgoing = true, delivery = MeshDeliveryState.Sent)
        val id = message.id
        _messages.value = messages + message
        coreEnqueue(frame, setOf(6)) { reply ->
            val i = messages.indexOfFirst { it.id == id }
            if (i < 0) return@coreEnqueue
            when (reply) {
                // Direct messages are acknowledged later with this code.
                is MeshCoreFrame.Sent -> updateMessage(i) { it.copy(packetID = reply.expectedAck) }
                is MeshCoreFrame.Error -> updateMessage(i) { it.copy(delivery = MeshDeliveryState.Failed("Radio is busy, try again")) }
                else -> {}
            }
            scheduleSave()
        }
        trimHistory()
        scheduleSave()
        return true
    }

    // MARK: - Radio settings

    /** Meshtastic Apply in progress: what to set, and where it is. */
    private var loraTarget: MeshPBLoRaConfig? = null
    private var adminGetID = 0L
    private var adminSetID = 0L
    private var applyTimer: Job? = null

    private fun applyLora(c: MeshPBLoRaConfig) {
        if (c.region != MeshtasticRegions.UNSET && notice == NO_REGION_NOTICE) notice = null
        _radio.value = radio.copy(region = c.region, regionName = c.regionName, hopLimit = c.hopLimit,
            modemPreset = c.modemPreset, usePreset = c.usePreset, txEnabled = c.txEnabled)
    }

    /** Clears a finished or failed Apply so the screen starts fresh. */
    fun clearApply() {
        if (!_apply.value.isBusy) _apply.value = RadioApplyState.Idle
    }

    private fun failApply(reason: String) {
        applyTimer?.cancel()
        loraTarget = null
        adminGetID = 0; adminSetID = 0
        _apply.value = RadioApplyState.Failed(reason)
    }

    private fun finishApply(confirmed: String) {
        applyTimer?.cancel()
        loraTarget = null
        adminGetID = 0; adminSetID = 0
        _apply.value = RadioApplyState.Done(confirmed)
    }

    /**
     * MeshCore: sends frequency, bandwidth, SF and CR (and transmit power when
     * given and different), waits for OK after each, then asks for SELF_INFO
     * again and reports what the radio now says. The companion firmware
     * applies these at once and needs no restart (MyMesh.cpp, companion-v1.18.0).
     */
    fun applyMeshCore(params: MeshCoreRadioParams, txPowerDbm: Int? = null) {
        if (_apply.value.isBusy) return
        if (firmware != Firmware.MESHCORE || !connection.isConnected) {
            _apply.value = RadioApplyState.Failed("Connect the MeshCore radio first.")
            return
        }
        val problems = MeshCoreRadioLimits.problems(params, txPowerDbm, radio.maxTxPowerDbm)
        if (problems.isNotEmpty()) {
            _apply.value = RadioApplyState.Failed(problems.joinToString(" "))
            return
        }
        val target = params.normalized()
        val noAnswer = { failApply("The radio did not answer. Nothing may have changed; try again.") }
        _apply.value = RadioApplyState.Working("Sending the new settings to the radio…")
        coreEnqueue(MeshCoreCommand.setRadioParams(target.frequencyKHz, target.bandwidthHz, target.spreadingFactor, target.codingRate),
            setOf(0), onTimeout = noAnswer) { reply ->
            when (reply) {
                is MeshCoreFrame.Ok -> {
                    if (txPowerDbm != null && txPowerDbm != radio.txPowerDbm) {
                        _apply.value = RadioApplyState.Working("Setting transmit power…")
                        coreEnqueue(MeshCoreCommand.setTxPower(txPowerDbm), setOf(0), onTimeout = noAnswer) { r ->
                            when (r) {
                                is MeshCoreFrame.Ok -> coreConfirm(target, txPowerDbm)
                                is MeshCoreFrame.Error -> failApply("The frequency changed, but the radio refused that transmit power (${coreErrorText(r.code)}).")
                                else -> {}
                            }
                        }
                    } else {
                        coreConfirm(target, null)
                    }
                }
                is MeshCoreFrame.Error -> failApply("The radio refused these settings (${coreErrorText(reply.code)}). Nothing changed.")
                else -> {}
            }
        }
    }

    /** Re-reads SELF_INFO and checks the radio holds what was sent. */
    private fun coreConfirm(target: MeshCoreRadioParams, txPowerDbm: Int?) {
        _apply.value = RadioApplyState.Working("Checking the radio's settings…")
        coreEnqueue(MeshCoreCommand.appStart("Pocket Nomad"), setOf(5),
            onTimeout = { failApply("The radio took the settings but did not report back. Reconnect to check them.") }) { reply ->
            if (reply !is MeshCoreFrame.SelfInfo) return@coreEnqueue
            val now = radio.meshCoreParams
            when {
                now == null || !now.sameAs(target) ->
                    failApply("The radio now reports ${now?.summary ?: "nothing"}, not ${target.summary}.")
                txPowerDbm != null && radio.txPowerDbm != txPowerDbm ->
                    failApply("The radio now reports ${radio.txPowerDbm} dBm, not $txPowerDbm dBm.")
                else -> finishApply("The radio now uses ${now.summary}" +
                    (radio.txPowerDbm?.let { ", $it dBm" } ?: "") + ".")
            }
            scheduleSave()
        }
    }

    private fun coreErrorText(code: Int?): String = when (code) {
        6 -> "a value is out of range"
        1 -> "this firmware does not support it"
        null -> "no reason given"
        else -> "error $code"
    }

    /**
     * Meshtastic: asks the radio for its LoRa config (the answer carries the
     * session passkey), changes region, modem preset and use_preset in that
     * config, and sends it back with the passkey. The radio saves it and
     * restarts; the link reconnects and the handshake's LoRa config confirms it.
     */
    fun applyMeshtastic(region: Long, modemPreset: Long, usePreset: Boolean = true) {
        if (_apply.value.isBusy) return
        if (firmware != Firmware.MESHTASTIC || !connection.isConnected || radio.myNodeNum == 0L) {
            _apply.value = RadioApplyState.Failed("Connect the Meshtastic radio first.")
            return
        }
        if (MeshtasticRegions.of(region) == null) {
            _apply.value = RadioApplyState.Failed("Pick your region first.")
            return
        }
        loraTarget = MeshPBLoRaConfig(region = region, modemPreset = modemPreset, usePreset = usePreset)
        adminSetID = 0
        adminGetID = newPacketID()
        _apply.value = RadioApplyState.Working("Reading the radio's settings…")
        sendAdmin(adminGetID, MeshPBAdmin.getLoraConfig(), wantResponse = true)
        applyTimer?.cancel()
        applyTimer = scope.launch {
            delay(ADMIN_TIMEOUT)
            if (adminGetID != 0L && adminSetID == 0L) failApply("The radio did not answer. Nothing changed; try again.")
        }
    }

    private fun sendAdmin(id: Long, admin: MeshPBAdmin, wantResponse: Boolean) {
        // To our own node; `from` stays 0, which the firmware treats as the local phone link.
        val packet = MeshPBPacket(
            to = radio.myNodeNum, channel = 0,
            decoded = MeshPBData(portnum = MeshPort.ADMIN, payload = admin.encode(), wantResponse = wantResponse),
            id = id, hopLimit = hopLimit, wantAck = true,
        )
        transport.write(MeshPBToRadio.Packet(packet).encode())
        startReading()
    }

    private fun handleAdmin(p: MeshPBPacket, data: MeshPBData) {
        if (p.from != radio.myNodeNum && p.from != 0L) return
        val admin = runCatching { MeshPBAdmin.decode(data.payload) }.getOrNull() ?: return
        val current = admin.loraConfigResponse ?: return
        applyLora(current)
        val target = loraTarget ?: return
        if (adminGetID == 0L || adminSetID != 0L) return
        if (data.requestID != 0L && data.requestID != adminGetID) return
        val updated = current.copy(
            region = target.region, modemPreset = target.modemPreset, usePreset = target.usePreset,
            // The firmware turns transmit on itself when a region is first set.
            txEnabled = current.txEnabled || current.region == MeshtasticRegions.UNSET,
        )
        if (updated == current) {
            finishApply("The radio already uses ${radio.settingsSummary ?: "these settings"}.")
            return
        }
        adminSetID = newPacketID()
        _apply.value = RadioApplyState.Restarting
        sendAdmin(adminSetID, MeshPBAdmin.setLora(updated, admin.sessionPasskey), wantResponse = false)
        applyTimer?.cancel()
        applyTimer = scope.launch {
            // Some changes apply without a restart: read the config again.
            delay(NO_RESTART_RECHECK)
            if (_apply.value == RadioApplyState.Restarting && connection.isConnected) beginHandshake()
            delay(RESTART_GIVE_UP)
            if (_apply.value == RadioApplyState.Restarting) {
                failApply("The radio has not come back yet. When it reconnects, check its settings here.")
            }
        }
    }

    /** After the handshake: finishes a Meshtastic Apply. Returns true if one was waiting. */
    private fun meshtasticConfirm(): Boolean {
        val target = loraTarget ?: return false
        if (_apply.value != RadioApplyState.Restarting) return false
        val r = radio
        if (r.region == target.region && (r.modemPreset ?: 0) == target.modemPreset && (r.usePreset ?: false) == target.usePreset) {
            finishApply("The radio now uses ${r.settingsSummary}.")
        } else {
            failApply("The radio came back with ${r.settingsSummary ?: "different settings"}. Try again.")
        }
        return true
    }

    /**
     * Screenshots and demos: shows this link as connected to a made-up radio,
     * with a couple of nodes and messages. Nothing is sent; no transport is used.
     */
    fun loadDemoRadio(firmware: Firmware, name: String, myNum: Long, battery: Long, channel: String,
                      others: List<MeshNode>, chat: List<String>) {
        currentDeviceName = name
        _lastDevice.value = "demo-${firmware.rawValue}" to name
        _radio.value = MeshRadioInfo(firmware = firmware, myNodeNum = myNum, firmwareVersion = "demo",
            primaryChannelName = channel, channelNames = mapOf(0 to channel),
            // Demo settings: Meshtastic set up for the US, MeshCore still on its out-of-the-box frequency.
            region = if (firmware == Firmware.MESHTASTIC) 1L else null, modemPreset = 0L, usePreset = true, txEnabled = true,
            meshCoreParams = if (firmware == Firmware.MESHCORE) MeshCoreRadioParams.FIRMWARE_DEFAULT else null,
            txPowerDbm = if (firmware == Firmware.MESHCORE) 10 else null, maxTxPowerDbm = if (firmware == Firmware.MESHCORE) 22 else null)
        putNode(MeshNode(num = myNum, longName = name, shortName = name.take(4), batteryLevel = battery))
        others.forEach { putNode(it) }
        val t = now()
        _messages.value = chat.mapIndexed { i, text ->
            val from = others[i % others.size]
            MeshMessage(packetID = i + 1L, from = from.num, to = MeshConstants.BROADCAST_NUM, channel = 0, text = text,
                dateMillis = t - (chat.size - i) * 300_000L, isOutgoing = false, delivery = MeshDeliveryState.Received)
        }
        setConnection(MeshConnectionState.Connected(name))
    }

    /** Screenshots and demos: made-up nodes at made-up places (open ocean). */
    fun loadDemoNodes() {
        val demo = listOf(
            Triple("Ridge Repeater", 10.1480, -20.3970),
            Triple("Ana", 10.1560, -20.3870),
            Triple("Base Camp", 10.1410, -20.4060),
            Triple("Creek", 10.1170, -20.3530),
        )
        demo.forEachIndexed { i, d ->
            putNode(MeshNode(num = 0xA000L + i, longName = d.first, shortName = d.first.take(4),
                latitude = d.second, longitude = d.third, lastHeardMillis = now() - i * 420_000L))
        }
    }

    // MARK: - Helpers

    private fun updateMessage(i: Int, change: (MeshMessage) -> MeshMessage) {
        val list = messages.toMutableList()
        list[i] = change(list[i])
        _messages.value = list
    }

    private fun trimHistory() {
        if (messages.size > MeshStore.MAX_MESSAGES) _messages.value = MeshStore.capped(messages)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            saveNow()
        }
    }

    fun saveNow() {
        val snap = MeshSnapshot(messages, nodes.values.toList(), radio, _lastRead.value)
        try { store.save(snap) } catch (_: Exception) {}
    }

    companion object {
        /** Meshtastic's private app port: other apps ignore it, so pings never show up as chat. */
        const val PING_PORT: Long = MeshPort.PRIVATE_APP
        const val PING_TEXT = "Pocket Nomad ping"
        /** How long a repeater counts as "in range" for an automatic drive-test ping. */
        const val NEIGHBOR_WINDOW_MS = 10 * 60 * 1000L
        const val NO_REGION_NOTICE = "This radio has no region set, so it will not transmit. " +
            "Tap Radio settings on its card and pick your region (US 915 MHz in the United States)."
        /** Radio settings timings (ms). */
        const val ADMIN_TIMEOUT = 10_000L
        const val NO_RESTART_RECHECK = 20_000L
        const val RESTART_GIVE_UP = 70_000L
        const val RESTART_RECONNECT_DELAY = 4_000L
        /** A remembered radio unseen this long is Not nearby (left at home, or in the car while you're in a store). */
        const val NOT_NEARBY_MILLIS = 2 * 60_000L
        /** Drops that recover within this long don't change what the user sees, notes or the drive log. */
        const val FLICKER_MILLIS = 20_000L

        fun conversation(m: MeshMessage, myNum: Long): MeshConversation {
            if (m.to == MeshConstants.BROADCAST_NUM) return MeshConversation.Channel(m.channel)
            if (m.isOutgoing || (myNum != 0L && m.from == myNum)) return MeshConversation.Direct(m.to)
            return MeshConversation.Direct(m.from)
        }

        fun newPacketID(): Long = Random.nextLong(1, 0xFFFF_FFFFL)

        fun sortedNodes(nodes: Map<Long, MeshNode>, myNum: Long, blocked: Set<Long>): List<MeshNode> =
            nodes.values.filter { it.num != myNum && !blocked.contains(it.num) }
                .sortedByDescending { it.lastHeardMillis ?: Long.MIN_VALUE }

        /** (node, last message) per direct thread, newest first. */
        fun directThreads(messages: List<MeshMessage>, myNum: Long): List<Pair<Long, MeshMessage>> {
            val latest = mutableMapOf<Long, MeshMessage>()
            for (m in messages) {
                val c = conversation(m, myNum) as? MeshConversation.Direct ?: continue
                val cur = latest[c.node]
                if (cur != null && cur.dateMillis > m.dateMillis) continue
                latest[c.node] = m
            }
            return latest.entries.map { it.key to it.value }.sortedByDescending { it.second.dateMillis }
        }
    }
}
