// MeshRadios.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Up to two radios at once: one Meshtastic and one MeshCore. Each firmware has
// a slot with its own MeshService (its own transport, so its own GATT
// connection and operation queue, handshake, node list, channels and chat).
// This class scans for radios, puts each one in its firmware's slot, remembers
// both and reconnects both at launch, and merges what the map, SOS and drive
// mode need. Connecting a second radio of the same firmware replaces the first
// (the UI asks first, see `replacing`).
//
// No bridging: a message heard on one network is never sent on the other. A
// future opt-in bridge would go in `create`, collecting each link's
// messagesFlow and re-sending chosen channels on the other link.

package org.nomadpocket.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nomadpocket.mesh.proto.MeshCoreContact
import java.io.File

/** What an SOS did on one radio: the text sent, or null if it could not be sent. */
data class MeshSOSResult(val firmware: Firmware, val radioName: String, val text: String?)

class MeshRadios(
    /** Only scans; each slot's own transport does the connecting. */
    private val scanner: MeshTransport,
    /** One link per firmware. */
    val links: Map<Firmware, MeshService>,
    private val location: suspend () -> Fix? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _availability = MutableStateFlow(MeshBluetoothAvailability.UNKNOWN)
    private val _discovered = MutableStateFlow<List<MeshDiscoveredDevice>>(emptyList())
    private val _isScanning = MutableStateFlow(false)

    val availabilityFlow: StateFlow<MeshBluetoothAvailability> = _availability.asStateFlow()
    val discoveredFlow: StateFlow<List<MeshDiscoveredDevice>> = _discovered.asStateFlow()
    val isScanningFlow: StateFlow<Boolean> = _isScanning.asStateFlow()
    val availability get() = _availability.value
    val discovered get() = _discovered.value
    val isScanning get() = _isScanning.value

    private var started = false
    /** The ids last handed to the scanner's reconnect scan. */
    private var reconnectScanIDs: Set<String> = emptySet()

    init {
        scanner.onEvent = { handleScan(it) }
        for ((slot, link) in links) {
            link.onReady = { id, name, firmware -> if (firmware == slot) true else { move(link, id, name, firmware); false } }
            link.onLinkChanged = { updateReconnectScan() }
        }
    }

    /**
     * Remembered radios that should be connected and aren't (MeshService.wantsReconnect).
     * The scanner keeps a low-power background scan going for exactly these.
     */
    val reconnectWanted: Set<String>
        get() = links.values.filter { it.wantsReconnect }.mapNotNull { it.lastDeviceID }.toSet()

    /** Starts, retargets or stops the reconnect scan; stopped once every wanted radio is connected. */
    private fun updateReconnectScan() {
        if (!started) return
        val ids = reconnectWanted
        if (ids == reconnectScanIDs) return
        reconnectScanIDs = ids
        scanner.setReconnectScan(ids)
    }

    fun link(firmware: Firmware): MeshService = links.getValue(firmware)

    /** Which slot a link is in. */
    fun firmwareOf(link: MeshService): Firmware = links.entries.first { it.value === link }.key

    /** Brings Bluetooth up and reconnects every remembered radio that was connected. */
    fun start() {
        if (started) return
        started = true
        scanner.start()
        links.values.forEach { it.start() }
        updateReconnectScan()
    }

    fun startScan() {
        start()
        _discovered.value = emptyList()
        _isScanning.value = true
        scanner.startScan()
    }

    fun stopScan() {
        _isScanning.value = false
        scanner.stopScan()
    }

    /**
     * The radio that connecting `device` would replace (same firmware, a
     * different radio), or null. The UI asks "Replace <name>?" first.
     */
    fun replacing(device: MeshDiscoveredDevice): String? = links[device.firmware]?.replacingName(device)

    /** Connects in the device's firmware slot, replacing whatever radio was there. */
    fun connect(device: MeshDiscoveredDevice) {
        val link = links[device.firmware] ?: return
        if (isScanning) stopScan()
        if (replacing(device) != null) link.forgetDevice()
        link.connect(device)
    }

    /**
     * The scan guessed the firmware wrong (the advert had no service UUID):
     * hand the radio to the right slot, which replaces what was there.
     */
    private fun move(from: MeshService, id: String, name: String, firmware: Firmware) {
        val to = links[firmware] ?: return
        from.forgetDevice()
        val device = MeshDiscoveredDevice(id, name, 0, now(), firmware)
        val replaced = to.replacingName(device)
        if (replaced != null) to.forgetDevice()
        to.connect(device)
        replaced?.let { to.notice = "$name is a ${firmware.displayName} radio, so it replaced $it." }
    }

    private fun MeshService.replacingName(device: MeshDiscoveredDevice): String? =
        lastDeviceID?.takeIf { it != device.id }?.let { lastDeviceName ?: device.firmware.displayName }

    private fun handleScan(event: MeshTransportEvent) {
        when (event) {
            is MeshTransportEvent.Availability -> _availability.value = event.availability
            is MeshTransportEvent.Discovered -> {
                val d = event.device
                val list = discovered.toMutableList()
                val i = list.indexOfFirst { it.id == d.id }
                if (i >= 0) list[i] = d else list.add(d)
                _discovered.value = list.sortedByDescending { if (it.rssi == 0) -999 else it.rssi }
            }
            // A wanted radio advertising: the link that remembers it connects now.
            is MeshTransportEvent.Sighted -> links.values.forEach { it.radioSighted(event.id) }
            else -> {}
        }
    }

    // MARK: Merged views

    /** Links with a remembered radio or a connection in progress, in slot order. */
    val inUse: List<Pair<Firmware, MeshService>> get() = links.entries.filter { it.value.inUse }.map { it.key to it.value }

    /** Links whose radio is connected and synced. */
    val connected: List<Pair<Firmware, MeshService>> get() = links.entries.filter { it.value.connection.isConnected }.map { it.key to it.value }

    /** A free slot remains for another radio. */
    val canAdd: Boolean get() = links.values.any { !it.inUse }

    /** Other radios from both networks, most recently heard first. */
    val sortedNodes: List<Pair<Firmware, MeshNode>>
        get() = links.flatMap { (fw, link) -> link.sortedNodes.map { fw to it } }
            .sortedByDescending { it.second.lastHeardMillis ?: Long.MIN_VALUE }

    /**
     * The user's own radios, on either network: a node number that is either
     * link's own (so your Meshtastic radio heard by your MeshCore one counts
     * too), or on MeshCore a repeater path hash that matches the start of your
     * radio's public key. Coverage never records these and the map never draws them.
     */
    fun isOwnRadio(node: Long, firmware: Firmware): Boolean {
        if (node == 0L) return false
        if (links.values.any { it.myNodeNum != 0L && it.myNodeNum == node }) return true
        if (firmware != Firmware.MESHCORE) return false
        val core = links[Firmware.MESHCORE] ?: return false
        val key = core.myNode?.publicKeyPrefix ?: return false
        if (key.isEmpty()) return false
        core.neighbors[node]?.let { if (key.startsWith(it.hash)) return true }
        // A 1-3 byte path hash is keyed by MeshCoreContact.nodeNumForHash.
        return (1..3).any { MeshCoreContact.nodeNumForHash(key.prefix(it).array) == node }
    }

    /**
     * Every radio either link knows that has a position (heard directly or
     * only through the mesh), never the user's own (on either network) or blocked ones,
     * most recently heard first. A node is its network plus number.
     */
    val radiosHeard: List<MapNode>
        get() = links.flatMap { (fw, link) -> link.sortedNodes.mapNotNull { it.toMapNode(fw) } }
            .filterNot { isOwnRadio(it.num, it.firmware) }
            .sortedByDescending { it.lastHeardMillis ?: Long.MIN_VALUE }

    /**
     * The map's two radio layers (HeardResolver): radios heard directly, from
     * the coverage recordings, from MeshCore repeaters heard in the last 10
     * minutes (placed by `fix`, the phone's position now) and from zero-hop
     * entries on each radio's own list; and the other located radios, known
     * only through the mesh. Never the user's own radios or blocked ones.
     */
    fun heard(samples: List<CoverageRecord>, fix: Fix?): HeardRadios {
        val contacts = links.mapValues { (_, link) -> link.sortedNodes.associateBy { it.num } }
        val t = now()
        val live = if (fix == null) emptyList() else links[Firmware.MESHCORE]?.neighbors?.values
            ?.filter { t - it.lastHeardMillis <= MeshService.NEIGHBOR_WINDOW_MS }
            ?.map { CoverageRecord(it.lastHeardMillis, fix.latitude, fix.longitude, fix.accuracy, Firmware.MESHCORE,
                MeshCoreContact.nodeNumForHash(it.hash.array), it.snr, 1) } ?: emptyList()
        val zeroHop = links.flatMap { (fw, link) -> link.sortedNodes.filter { it.hopsAway == 0L }.map { fw to it } }
        return HeardResolver.derive(samples + live, contacts, ::isOwnRadio, zeroHop)
    }

    /** What identifies the user's radios on the community maps (and the map's own radio layer). */
    val ownRadios: OwnRadios
        get() = OwnRadios(
            nodeNums = links.values.map { it.myNodeNum }.filter { it != 0L }.toSet(),
            meshCoreKeyPrefixes = setOfNotNull(links[Firmware.MESHCORE]?.myNode?.publicKeyPrefix?.takeIf { !it.isEmpty() }?.hex()),
        )

    /** A node's name on either network, for the coverage opt-out check. */
    fun nodeName(num: Long): String =
        links.values.firstNotNullOfOrNull { l -> l.nodes[num]?.longName?.takeIf { it.isNotEmpty() } } ?: ""

    val totalUnread: Int get() = links.values.sumOf { it.totalUnread }

    val sosPreview: String get() = connected.firstOrNull()?.second?.sosPreview ?: MeshSOS.text(null, null, null)

    /**
     * Sends the SOS on the primary channel of every connected radio, with one
     * GPS lookup for all of them. One result per radio; empty if none is connected.
     */
    suspend fun sendSOS(includeLocation: Boolean): List<MeshSOSResult> {
        val targets = connected
        if (targets.isEmpty()) return emptyList()
        val fix = if (includeLocation) location() else null
        return targets.map { (fw, link) ->
            MeshSOSResult(fw, link.lastDeviceName ?: fw.displayName, link.sendSOS(fix?.latitude, fix?.longitude))
        }
    }

    fun deleteHistory() = links.values.forEach { it.deleteHistory() }

    fun saveNow() = links.values.forEach { it.saveNow() }

    /** Screenshots and demos: both slots show a made-up connected radio. */
    fun loadDemo() {
        val t = now()
        link(Firmware.MESHTASTIC).loadDemoRadio(Firmware.MESHTASTIC, "Demo Meshtastic", 0xD0001, 84, "LongFast",
            listOf(MeshNode(0xA001, longName = "Ana", shortName = "ANA", lastHeardMillis = t - 120_000, snr = 6.5f),
                MeshNode(0xA003, longName = "Cherry Creek", shortName = "CC", lastHeardMillis = t - 900_000, snr = -4f)),
            listOf("Anyone on the trail today?", "Signal check from the creek"))
        link(Firmware.MESHCORE).loadDemoRadio(Firmware.MESHCORE, "Demo MeshCore", 0xD0002, 61, "Public",
            listOf(MeshNode(0xB000, longName = "Ridge Repeater", shortName = "RR", lastHeardMillis = t - 60_000,
                meshCoreType = org.nomadpocket.mesh.proto.MeshCoreContactType.REPEATER),
                MeshNode(0xB002, longName = "Base Camp", shortName = "BC", lastHeardMillis = t - 400_000)),
            listOf("Repeater back up after the storm"))
    }

    companion object {
        private const val MIGRATED = "mesh.slots.v2"

        /** The keys MeshService keeps per radio. */
        private val STRING_KEYS = listOf("mesh.lastDeviceID", "mesh.lastDeviceName", "mesh.blocked")
        private const val AUTO_RECONNECT = "mesh.autoReconnect"

        fun prefix(firmware: Firmware): String = firmware.rawValue + "."

        /**
         * One-off move from one remembered radio to per-firmware slots: the old
         * files/mesh/mesh.json goes into files/mesh/<firmware>/, and the
         * remembered radio, auto-reconnect, blocked list and drive-mode ping
         * target move to that slot's keys. The firmware is the one saved with
         * the history; with none saved, Meshtastic (the only kind before
         * MeshCore support). If that guess is wrong, `move` fixes it on connect.
         */
        fun migrate(prefs: MeshPrefs, dir: File) {
            if (prefs.getBoolean(MIGRATED, false)) return
            val old = File(dir, "mesh.json")
            val firmware = if (old.exists()) MeshStore(dir).load().radio.firmware ?: Firmware.MESHTASTIC else Firmware.MESHTASTIC
            if (old.exists()) {
                val slotDir = File(dir, firmware.rawValue)
                slotDir.mkdirs()
                val target = File(slotDir, "mesh.json")
                if (!target.exists() && !old.renameTo(target)) {
                    atomicWrite(target, old.readText())
                    old.delete()
                }
            }
            val slot = ScopedMeshPrefs(prefs, prefix(firmware))
            for (key in STRING_KEYS) {
                prefs.getString(key)?.let { if (!slot.contains(key)) slot.putString(key, it) }
                prefs.remove(key)
            }
            if (prefs.contains(AUTO_RECONNECT)) {
                if (!slot.contains(AUTO_RECONNECT)) slot.putBoolean(AUTO_RECONNECT, prefs.getBoolean(AUTO_RECONNECT, false))
                prefs.remove(AUTO_RECONNECT)
            }
            DriveMode.migrateTarget(prefs, firmware)
            prefs.putBoolean(MIGRATED, true)
        }

        /** Migrates, then builds one link per firmware with its own transport, store and prefs. */
        fun create(
            prefs: MeshPrefs,
            dir: File,
            scope: CoroutineScope,
            scanner: MeshTransport,
            transport: (Firmware) -> MeshTransport,
            location: suspend () -> Fix? = { null },
            locationDenied: () -> Boolean = { false },
            now: () -> Long = System::currentTimeMillis,
        ): MeshRadios {
            migrate(prefs, dir)
            val links = Firmware.entries.associateWith { fw ->
                MeshService(transport(fw), MeshStore(File(dir, fw.rawValue)), ScopedMeshPrefs(prefs, prefix(fw)), scope,
                    location = location, locationDenied = locationDenied, now = now)
            }
            return MeshRadios(scanner, links, location, now)
        }
    }
}
