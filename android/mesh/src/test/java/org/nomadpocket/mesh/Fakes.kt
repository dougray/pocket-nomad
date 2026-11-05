// Test doubles: a scripted Meshtastic radio (port of FakeMeshTransport in
// MeshTests/MeshServiceTests.swift) and a scripted MeshCore radio (port of
// FakeMeshCoreRadio and CoreFrames in MeshTests/MeshCoreTests.swift).
package org.nomadpocket.mesh

import kotlinx.coroutines.test.TestScope
import org.nomadpocket.mesh.proto.MeshPBFromRadio
import org.nomadpocket.mesh.proto.MeshPBFromRadioVariant
import org.nomadpocket.mesh.proto.MeshPBPacket
import org.nomadpocket.mesh.proto.MeshPBToRadio
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Plays the radio side of the Meshtastic BLE contract. */
class FakeMeshTransport : MeshTransport {
    override var onEvent: ((MeshTransportEvent) -> Unit)? = null
    override var availability = MeshBluetoothAvailability.POWERED_ON
    val writes = mutableListOf<ByteArray>()
    /** FromRadio packets waiting to be read. */
    val queue = ArrayDeque<ByteArray>()
    var pendingReads = 0
    var connectedID: String? = null
    var scanning = false

    override fun start() { onEvent?.invoke(MeshTransportEvent.Availability(MeshBluetoothAvailability.POWERED_ON)) }
    override fun startScan() { scanning = true }
    override fun stopScan() { scanning = false }
    override fun connect(id: String) {
        connectedID = id
        onEvent?.invoke(MeshTransportEvent.Connecting(id, "Test Radio"))
    }
    override fun disconnect() {
        connectedID?.let { onEvent?.invoke(MeshTransportEvent.Disconnected(it, null, false)) }
        connectedID = null
    }
    override fun write(toRadio: ByteArray) { writes.add(toRadio) }
    override fun readFromRadio() { pendingReads += 1 }

    fun emit(e: MeshTransportEvent) { onEvent?.invoke(e) }

    fun becomeReady() = emit(MeshTransportEvent.Ready(connectedID ?: UUID.randomUUID().toString(), "Test Radio"))

    fun enqueue(variant: MeshPBFromRadioVariant) {
        queue.addLast(MeshPBFromRadio(id = (queue.size + 1).toLong(), variant = variant).encode())
    }

    /** Answers reads until the service stops asking (like reading until empty). */
    fun pump() {
        var guard = 0
        while (pendingReads > 0 && guard < 1000) {
            pendingReads -= 1
            guard += 1
            emit(MeshTransportEvent.FromRadio(queue.removeFirstOrNull() ?: ByteArray(0)))
        }
    }

    fun decodedWrites(): List<MeshPBToRadio> = writes.mapNotNull { MeshPBToRadio.decode(it) }

    fun lastWantConfigID(): Long? =
        decodedWrites().reversed().firstNotNullOfOrNull { (it as? MeshPBToRadio.WantConfigID)?.id }

    fun lastSentPacket(): MeshPBPacket? =
        decodedWrites().reversed().firstNotNullOfOrNull { (it as? MeshPBToRadio.Packet)?.packet }
}

/** Builds radio-side frames the way MeshCore firmware writes them. Coordinates are made up. */
object CoreFrames {
    fun le(v: Long): List<Int> = listOf((v and 0xFF).toInt(), (v shr 8 and 0xFF).toInt(), (v shr 16 and 0xFF).toInt(), (v shr 24 and 0xFF).toInt())
    fun le(v: Int): List<Int> = le(v.toLong() and 0xFFFFFFFFL)
    fun padded(s: String, n: Int): List<Int> {
        val b = s.toByteArray().take(n).map { it.toInt() and 0xFF }
        return b + List(n - b.size) { 0 }
    }

    fun key(first: Int): List<Int> = listOf(first, 0x22, 0x33, 0x44, 0x55, 0x66) + List(26) { first }

    fun contact(code: Int = 3, key: List<Int>, name: String, type: Int = 1, pathLen: Int = 2,
                lat: Int = 10_123_400, lon: Int = -20_654_300, advert: Long = 1_790_000_000): ByteArray =
        b(listOf(code) + key + listOf(type, 0, pathLen) + List(64) { 0 } + padded(name, 32) +
            le(advert) + le(lat) + le(lon) + le(1L))

    fun selfInfo(key: List<Int>, name: String, freqKHz: Long = 910_525L, bwHz: Long = 62_500L, sf: Int = 7, cr: Int = 5,
                 txPower: Int = 20, maxTx: Int = 22): ByteArray =
        b(listOf(5, 1, txPower and 0xFF, maxTx) + key + le(0) + le(0) + listOf(0, 0, 0, 0) +
            le(freqKHz) + le(bwHz) + listOf(sf, cr) + name.toByteArray().map { it.toInt() and 0xFF })

    fun deviceInfo(maxChannels: Int): ByteArray =
        b(listOf(13, 9, 50, maxChannels) + le(123456L) + padded("01 Oct 2026", 12) +
            padded("Heltec V4", 40) + padded("v1.12.0", 20) + listOf(0, 0))

    fun channelInfo(index: Int, name: String): ByteArray =
        b(listOf(18, index) + padded(name, 32) + List(16) { if (name.isEmpty()) 0 else 0x8B })

    fun channelMessageV3(channel: Int, text: String, ts: Long, snr: Int = 22): ByteArray =
        b(listOf(17, snr and 0xFF, 0, 0, channel, 1, 0) + le(ts) + text.toByteArray().map { it.toInt() and 0xFF })

    fun directMessageV3(prefix: List<Int>, text: String, ts: Long, signed: Boolean = false): ByteArray =
        b(listOf(16, 20, 0, 0) + prefix + listOf(0xFF, if (signed) 2 else 0) + le(ts) +
            (if (signed) listOf(9, 9, 9, 9) else emptyList()) + text.toByteArray().map { it.toInt() and 0xFF })

    fun sent(ack: Long): ByteArray = b(listOf(6, 0) + le(ack) + le(5000L))
    fun sendConfirmed(ack: Long): ByteArray = b(listOf(0x82) + le(ack) + le(800L))

    /** An over-the-air packet: header (route | payload type << 2), path length, path, payload. */
    fun packet(route: Int, type: Int, path: List<List<Int>> = emptyList(), payload: List<Int>): List<Int> {
        val size = path.firstOrNull()?.size ?: 1
        return listOf(route or (type shl 2)) + (if (route == 0 || route == 3) listOf(9, 9, 9, 9) else emptyList()) +
            listOf(((size - 1) shl 6) or path.size) + path.flatten() + payload
    }

    fun rxLog(snr: Float, raw: List<Int>, rssi: Int = -100): ByteArray =
        b(listOf(0x88, (snr * 4).toInt() and 0xFF, rssi and 0xFF) + raw)

    fun traceData(tag: Long, hashes: List<Int>): ByteArray =
        b(listOf(0x89, 0, hashes.size, 0) + le(tag) + le(0L) + hashes + List(hashes.size) { 24 } + listOf(-12 and 0xFF))

    fun b(v: List<Int>): ByteArray = ByteArray(v.size) { v[it].toByte() }
}

/** Answers the app's MeshCore commands like a radio would. */
class FakeMeshCoreRadio(val fake: FakeMeshTransport) {
    var answered = 0
    var waiting = ArrayDeque<ByteArray>()
    val me = CoreFrames.key(0x11)
    val ana = CoreFrames.key(0xAA)
    var nextAck = 0xA0A0_0001L
    // Radio settings, changed by CMD_SET_RADIO_PARAMS / CMD_SET_RADIO_TX_POWER like the firmware does.
    var freqKHz = 910_525L
    var bwHz = 62_500L
    var sf = 7
    var cr = 5
    var txPower = 20
    /** Answer CMD_SET_RADIO_PARAMS with ERR_CODE_ILLEGAL_ARG (6). */
    var refuseParams = false
    /** Take the settings but report the old ones (a radio that did not apply them). */
    var ignoreParams = false

    /** Replies to every write not yet answered, until the app goes quiet. */
    fun run() {
        var guard = 0
        while (answered < fake.writes.size && guard < 200) {
            guard += 1
            val cmd = fake.writes[answered]
            answered += 1
            for (reply in replies(cmd)) fake.emit(MeshTransportEvent.Frame(reply))
        }
    }

    private fun replies(cmd: ByteArray): List<ByteArray> = when (cmd[0].toInt() and 0xFF) {
        1 -> listOf(CoreFrames.selfInfo(me, "Base Camp", freqKHz, bwHz, sf, cr, txPower))
        11 -> if (refuseParams) listOf(bytes(1, 6)) else {
            if (!ignoreParams) {
                freqKHz = u32(cmd, 1); bwHz = u32(cmd, 5); sf = cmd[9].toInt() and 0xFF; cr = cmd[10].toInt() and 0xFF
            }
            listOf(bytes(0))
        }
        12 -> { txPower = cmd[1].toInt(); listOf(bytes(0)) }
        22 -> listOf(CoreFrames.deviceInfo(2))
        31 -> listOf(if (cmd[1].toInt() == 0) CoreFrames.channelInfo(0, "Public") else CoreFrames.channelInfo(cmd[1].toInt(), ""))
        6 -> listOf(bytes(0))
        36 -> listOf(CoreFrames.sent(u32(cmd, 1)))
        4 -> listOf(CoreFrames.b(listOf(2) + CoreFrames.le(1L)), CoreFrames.contact(key = ana, name = "Ana"), bytes(4, 0, 0, 0, 0))
        10 -> listOf(waiting.removeFirstOrNull() ?: bytes(10))
        2, 3 -> {
            val ack = if (cmd[0].toInt() == 2) nextAck else 0L
            nextAck += 1
            listOf(CoreFrames.sent(ack))
        }
        else -> listOf(bytes(1, 1))
    }
}

private fun u32(b: ByteArray, at: Int): Long =
    (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

/** A service with a temp store, in-memory prefs and a test scope whose timers never fire. */
class ServiceHarness {
    val dir: File = Files.createTempDirectory("mesh-tests").toFile()
    val prefs = MemoryMeshPrefs()
    val scope = TestScope()

    fun service(fake: MeshTransport = FakeMeshTransport(), store: MeshStore = MeshStore(dir)): MeshService =
        MeshService(fake, store, prefs, scope)

    fun cleanup() { dir.deleteRecursively() }
}
