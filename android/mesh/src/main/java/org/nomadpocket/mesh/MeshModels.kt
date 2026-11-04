// MeshModels.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// App-level models: what the UI shows and what is saved to disk. Ported from
// App/Sources/Mesh/MeshModels.swift. Times are epoch milliseconds; node numbers
// and packet ids are uint32 carried as Long.

package org.nomadpocket.mesh

import org.nomadpocket.mesh.proto.Bytes
import org.nomadpocket.mesh.proto.MeshConstants
import org.nomadpocket.mesh.proto.MeshCoreContactType
import java.util.Locale
import java.util.UUID

/** Whether the phone's Bluetooth can be used right now. */
enum class MeshBluetoothAvailability { UNKNOWN, RESETTING, UNSUPPORTED, UNAUTHORIZED, POWERED_OFF, POWERED_ON }

/** Stored/exported name of the firmware ("meshtastic", "meshcore"). */
val Firmware.rawValue: String get() = name.lowercase(Locale.ROOT)

val Firmware.displayName: String
    get() = when (this) {
        Firmware.MESHTASTIC -> "Meshtastic"
        Firmware.MESHCORE -> "MeshCore"
    }

/**
 * Longest text the radio accepts. MeshCore's limit is lower and its channel
 * messages also carry the sender's name.
 */
val Firmware.maxTextBytes: Int
    get() = when (this) {
        Firmware.MESHTASTIC -> MeshConstants.MAX_TEXT_BYTES
        Firmware.MESHCORE -> 133
    }

fun firmwareOf(raw: String?): Firmware? = Firmware.entries.firstOrNull { it.rawValue == raw }

val PingOutcome.rawValue: String get() = name.lowercase(Locale.ROOT)
fun pingOutcomeOf(raw: String?): PingOutcome? = PingOutcome.entries.firstOrNull { it.rawValue == raw }

/** A mesh radio seen while scanning. `id` is the Bluetooth address. */
data class MeshDiscoveredDevice(
    val id: String,
    val name: String,
    val rssi: Int,
    val lastSeenMillis: Long,
    val firmware: Firmware = Firmware.MESHTASTIC,
)

/** Overall link state shown at the top of the Mesh tab. */
sealed class MeshConnectionState {
    object Idle : MeshConnectionState()
    object Scanning : MeshConnectionState()
    data class Connecting(val name: String) : MeshConnectionState()
    /** Connected over BLE, downloading nodes and settings from the radio. */
    data class Syncing(val name: String) : MeshConnectionState()
    data class Connected(val name: String) : MeshConnectionState()
    /** Lost the link; the transport keeps trying and will reconnect. */
    data class Reconnecting(val name: String) : MeshConnectionState()
    data class Failed(val reason: String) : MeshConnectionState()

    val isConnected: Boolean get() = this is Connected
    val isLinkUp: Boolean get() = this is Syncing || this is Connected
}

/** Delivery state of an outgoing text. */
sealed class MeshDeliveryState {
    /** Handed to the radio over Bluetooth. */
    object Sent : MeshDeliveryState()
    /** Another radio rebroadcast it (or our radio confirmed it went out). */
    object HeardByMesh : MeshDeliveryState()
    /**
     * A channel message that went out, but no other radio was heard repeating it
     * (nobody in range). Not a failure: anyone who can hear you still got it.
     */
    object NotRepeated : MeshDeliveryState()
    /** The destination radio acknowledged it (direct messages only). */
    object Delivered : MeshDeliveryState()
    data class Failed(val reason: String) : MeshDeliveryState()
    /** Received from someone else. */
    object Received : MeshDeliveryState()

    override fun toString(): String = when (this) {
        Sent -> "sent"; HeardByMesh -> "heardByMesh"; NotRepeated -> "notRepeated"
        Delivered -> "delivered"; Received -> "received"; is Failed -> "failed($reason)"
    }
}

data class MeshMessage(
    val id: String = UUID.randomUUID().toString(),
    /** MeshPacket.id, used to match ACKs and drop duplicates. */
    val packetID: Long,
    val from: Long,
    val to: Long,
    val channel: Long,
    val text: String,
    val dateMillis: Long,
    val isOutgoing: Boolean,
    val delivery: MeshDeliveryState,
    val rxSNR: Float? = null,
    val hopsAway: Long? = null,
    /** MeshCore channel messages name their sender in the text instead of by node. */
    val senderName: String? = null,
) {
    val isBroadcast: Boolean get() = to == MeshConstants.BROADCAST_NUM
}

/** A radio known to the mesh. */
data class MeshNode(
    val num: Long,
    val userID: String = "",
    val longName: String = "",
    val shortName: String = "",
    val hwModel: Long = 0,
    val lastHeardMillis: Long? = null,
    val snr: Float? = null,
    val hopsAway: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Int? = null,
    val batteryLevel: Long? = null,
    val hasPublicKey: Boolean = false,
    /** MeshCore: first 6 bytes of the contact's public key, used to address direct messages. */
    val publicKeyPrefix: Bytes? = null,
    /** MeshCore: what kind of node advertised (person, repeater, room server, sensor). */
    val meshCoreType: MeshCoreContactType? = null,
) {
    /** "!a1b2c3d4", the Meshtastic way of writing a node number. */
    val hexID: String get() = hexID(num)

    val displayName: String
        get() = when {
            longName.isNotEmpty() -> longName
            shortName.isNotEmpty() -> shortName
            else -> hexID
        }

    val badge: String get() = shortName.ifEmpty { String.format(Locale.ROOT, "%04x", num and 0xFFFF) }

    val hasPosition: Boolean get() = latitude != null && longitude != null

    companion object {
        fun hexID(num: Long): String = String.format(Locale.ROOT, "!%08x", num and 0xFFFFFFFFL)
    }
}

/** Which chat thread a message belongs to. */
sealed class MeshConversation {
    data class Channel(val index: Long) : MeshConversation()
    data class Direct(val node: Long) : MeshConversation()

    val key: String
        get() = when (this) {
            is Channel -> "ch$index"
            is Direct -> "dm$node"
        }
}

/** Settings and identity learned from the connected radio. */
data class MeshRadioInfo(
    val firmware: Firmware? = null,
    val myNodeNum: Long = 0,
    val firmwareVersion: String = "",
    val region: Long? = null,
    val regionName: String? = null,
    val hopLimit: Long? = null,
    val primaryChannelName: String = "",
    val channelNames: Map<Int, String> = emptyMap(),
)

/** Builds the emergency text. Kept short to fit the 200-byte limit. */
object MeshSOS {
    fun text(latitude: Double?, longitude: Double?, senderName: String?): String {
        val parts = mutableListOf("SOS - I need help.")
        if (latitude != null && longitude != null) {
            parts.add("Location: ${format(latitude)}, ${format(longitude)}")
        } else {
            parts.add("Location unknown.")
        }
        if (!senderName.isNullOrEmpty()) parts.add("From $senderName.")
        parts.add("Please reply.")
        return MeshText.truncated(parts.joinToString(" "), MeshConstants.MAX_TEXT_BYTES)
    }

    /** Decimal degrees, exactly five places, with a dot separator in any locale. */
    fun format(degrees: Double): String = String.format(Locale.ROOT, "%.5f", degrees)
}

object MeshText {
    fun byteCount(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** Trims a string to fit `maxBytes` of UTF-8 without splitting a character. */
    fun truncated(s: String, maxBytes: Int): String {
        if (byteCount(s) <= maxBytes) return s
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        val out = StringBuilder()
        var used = 0
        var start = it.first()
        var end = it.next()
        while (end != java.text.BreakIterator.DONE) {
            val ch = s.substring(start, end)
            val n = byteCount(ch)
            if (used + n > maxBytes) break
            out.append(ch)
            used += n
            start = end
            end = it.next()
        }
        return out.toString()
    }
}
