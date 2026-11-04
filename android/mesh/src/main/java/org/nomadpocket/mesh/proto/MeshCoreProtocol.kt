// MeshCoreProtocol.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Encoder and decoder for MeshCore's companion radio protocol (the BLE link
// between a phone and a MeshCore radio). Ported from
// App/Sources/Mesh/MeshCoreProtocol.swift, written from MeshCore's MIT-licensed
// docs/companion_protocol.md and examples/companion_radio/MyMesh.cpp.
//
// Each BLE write is one command frame and each notification is one response
// frame: [code: 1 byte][payload]. Integers are little-endian, text is UTF-8.
// Times are epoch seconds; uint32 values are carried as Long.

package org.nomadpocket.mesh.proto

import java.io.ByteArrayOutputStream

object MeshCoreCommand {
    /** First byte of each command frame. */
    object Code {
        const val APP_START: Int = 1
        const val SEND_TEXT: Int = 2
        const val SEND_CHANNEL_TEXT: Int = 3
        const val GET_CONTACTS: Int = 4
        const val SET_DEVICE_TIME: Int = 6
        const val SEND_SELF_ADVERT: Int = 7
        const val SYNC_NEXT_MESSAGE: Int = 10
        const val DEVICE_QUERY: Int = 22
        const val GET_CHANNEL: Int = 31
    }

    /** The protocol version this app understands; asks for the V3 message formats. */
    const val APP_PROTOCOL_VERSION: Int = 3

    fun appStart(appName: String): ByteArray =
        byteArrayOf(Code.APP_START.toByte(), 0, 0, 0, 0, 0, 0, 0) + appName.toByteArray(Charsets.UTF_8)

    fun deviceQuery(): ByteArray = byteArrayOf(Code.DEVICE_QUERY.toByte(), APP_PROTOCOL_VERSION.toByte())

    fun setDeviceTime(epochSeconds: Long): ByteArray =
        frame(Code.SET_DEVICE_TIME) { it.le32(clampU32(epochSeconds)) }

    fun getContacts(): ByteArray = byteArrayOf(Code.GET_CONTACTS.toByte())

    fun getChannel(index: Int): ByteArray = byteArrayOf(Code.GET_CHANNEL.toByte(), index.toByte())

    fun syncNextMessage(): ByteArray = byteArrayOf(Code.SYNC_NEXT_MESSAGE.toByte())

    /** Flood-advertises this radio so others add it as a contact. */
    fun sendSelfAdvert(flood: Boolean = true): ByteArray =
        byteArrayOf(Code.SEND_SELF_ADVERT.toByte(), if (flood) 1 else 0)

    /** Direct text to a contact, addressed by the first 6 bytes of its public key. */
    fun sendText(text: String, toKeyPrefix: ByteArray, epochSeconds: Long, attempt: Int = 0): ByteArray =
        frame(Code.SEND_TEXT) {
            it.write(MeshCoreTextType.PLAIN)
            it.write(attempt)
            it.le32(clampU32(epochSeconds))
            it.write(toKeyPrefix.copyOfRange(0, minOf(6, toKeyPrefix.size)))
            it.write(text.toByteArray(Charsets.UTF_8))
        }

    fun sendChannelText(text: String, channel: Int, epochSeconds: Long): ByteArray =
        frame(Code.SEND_CHANNEL_TEXT) {
            it.write(MeshCoreTextType.PLAIN)
            it.write(channel)
            it.le32(clampU32(epochSeconds))
            it.write(text.toByteArray(Charsets.UTF_8))
        }

    private fun clampU32(v: Long): Long = v.coerceIn(0, 0xFFFFFFFFL)

    private fun ByteArrayOutputStream.le32(v: Long) {
        for (i in 0 until 4) write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private inline fun frame(code: Int, body: (ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(code)
        body(out)
        return out.toByteArray()
    }
}

object MeshCoreTextType {
    const val PLAIN: Int = 0
    const val SIGNED_PLAIN: Int = 2
}

/** Contact kinds from the radio's adverts. */
enum class MeshCoreContactType(val raw: Int) {
    NONE(0), CHAT(1), REPEATER(2), ROOM(3), SENSOR(4);

    companion object {
        fun of(raw: Int): MeshCoreContactType = entries.firstOrNull { it.raw == raw } ?: NONE
    }
}

data class MeshCoreSelfInfo(
    val publicKey: Bytes,
    val latitude: Double?,
    val longitude: Double?,
    val frequencyMHz: Double,
    val bandwidthKHz: Double,
    val spreadingFactor: Int,
    val codingRate: Int,
    val name: String,
)

data class MeshCoreDeviceInfo(
    val firmwareVersionCode: Int,
    val maxContacts: Int,
    val maxChannels: Int,
    val firmwareBuild: String,
    val model: String,
    val version: String,
)

data class MeshCoreContact(
    val publicKey: Bytes,
    val type: MeshCoreContactType,
    val name: String,
    /** Epoch seconds. */
    val lastAdvert: Long?,
    val latitude: Double?,
    val longitude: Double?,
    /** Hops on the stored route, null when the radio would flood. */
    val pathLength: Int?,
) {
    /** Node number used across the app: the key's first four bytes, little-endian. */
    val nodeNum: Long get() = nodeNum(publicKey.array)

    companion object {
        fun nodeNum(key: ByteArray): Long {
            if (key.size < 4) return 0
            return (key[0].toLong() and 0xFF) or ((key[1].toLong() and 0xFF) shl 8) or
                ((key[2].toLong() and 0xFF) shl 16) or ((key[3].toLong() and 0xFF) shl 24)
        }
    }
}

data class MeshCoreChannelInfo(val index: Int, val name: String, val secret: Bytes) {
    /** Unused slots have no name and an all-zero secret. */
    val isEmpty: Boolean get() = name.isEmpty() && secret.array.all { it.toInt() == 0 }
}

data class MeshCoreDirectMessage(
    val senderKeyPrefix: Bytes,
    val pathLength: Int,
    val timestamp: Long,
    val text: String,
    val snr: Float?,
)

data class MeshCoreChannelMessage(
    val channel: Int,
    val pathLength: Int,
    val timestamp: Long,
    /** The sender's name, which MeshCore puts in front of the text ("Ana: hi"). */
    val senderName: String?,
    val text: String,
    val snr: Float?,
)

/** One frame from the radio. */
sealed class MeshCoreFrame {
    data class Ok(val value: Long?) : MeshCoreFrame()
    data class Error(val code: Int?) : MeshCoreFrame()
    data class ContactsStart(val count: Long) : MeshCoreFrame()
    data class Contact(val contact: MeshCoreContact) : MeshCoreFrame()
    object EndOfContacts : MeshCoreFrame()
    data class SelfInfo(val info: MeshCoreSelfInfo) : MeshCoreFrame()
    /**
     * A text left this radio. `expectedAck` matches a later `SendConfirmed`
     * (0 for channel messages, which are never acknowledged).
     */
    data class Sent(val flood: Boolean, val expectedAck: Long, val timeoutMS: Long) : MeshCoreFrame()
    data class DirectMessage(val message: MeshCoreDirectMessage) : MeshCoreFrame()
    data class ChannelMessage(val message: MeshCoreChannelMessage) : MeshCoreFrame()
    object NoMoreMessages : MeshCoreFrame()
    data class DeviceInfo(val info: MeshCoreDeviceInfo) : MeshCoreFrame()
    data class ChannelInfo(val info: MeshCoreChannelInfo) : MeshCoreFrame()
    /** A known contact advertised again. */
    data class Advert(val publicKey: Bytes) : MeshCoreFrame()
    /** A new contact was heard (and added, unless the radio is in manual-add mode). */
    data class NewAdvert(val contact: MeshCoreContact) : MeshCoreFrame()
    data class SendConfirmed(val ack: Long, val roundTripMS: Long) : MeshCoreFrame()
    object MessagesWaiting : MeshCoreFrame()
    data class Other(val code: Int) : MeshCoreFrame()

    companion object {
        fun decode(data: ByteArray): MeshCoreFrame? {
            val r = MeshCoreReader(data)
            val code = r.u8() ?: return null
            return when (code) {
                0 -> Ok(r.u32())
                1 -> Error(r.u8())
                2 -> ContactsStart(r.u32() ?: 0)
                3 -> decodeContact(r)?.let { Contact(it) }
                4 -> EndOfContacts
                5 -> decodeSelfInfo(r)?.let { SelfInfo(it) }
                6 -> {
                    val route = r.u8() ?: return null
                    val ack = r.u32() ?: return null
                    Sent(route == 1, ack, r.u32() ?: 0)
                }
                7, 16 -> decodeDirect(r, v3 = code == 16)?.let { DirectMessage(it) }
                8, 17 -> decodeChannel(r, v3 = code == 17)?.let { ChannelMessage(it) }
                10 -> NoMoreMessages
                13 -> decodeDeviceInfo(r)?.let { DeviceInfo(it) }
                18 -> {
                    val index = r.u8() ?: return null
                    val name = r.fixedString(32) ?: return null
                    val secret = r.bytes(16) ?: return null
                    ChannelInfo(MeshCoreChannelInfo(index, name, Bytes(secret)))
                }
                0x80 -> Advert(Bytes(r.bytes(32) ?: return null))
                0x8A -> decodeContact(r)?.let { NewAdvert(it) }
                0x82 -> {
                    val ack = r.u32() ?: return null
                    SendConfirmed(ack, r.u32() ?: 0)
                }
                0x83 -> MessagesWaiting
                else -> Other(code)
            }
        }

        private fun decodeContact(r: MeshCoreReader): MeshCoreContact? {
            val key = r.bytes(32) ?: return null
            val type = r.u8() ?: return null
            r.u8() ?: return null // flags
            val outPathLen = r.u8() ?: return null
            r.bytes(64) ?: return null // path
            val name = r.fixedString(32) ?: return null
            val advert = r.u32() ?: return null
            val lat = r.i32() ?: return null
            val lon = r.i32() ?: return null
            val hasPosition = lat != 0 || lon != 0
            return MeshCoreContact(
                publicKey = Bytes(key),
                type = MeshCoreContactType.of(type),
                name = name,
                lastAdvert = if (advert == 0L) null else advert,
                latitude = if (hasPosition) lat / 1e6 else null,
                longitude = if (hasPosition) lon / 1e6 else null,
                // 0xFF means "no known route, flood".
                pathLength = if (outPathLen == 0xFF) null else outPathLen and 0x3F,
            )
        }

        private fun decodeSelfInfo(r: MeshCoreReader): MeshCoreSelfInfo? {
            // advert type, tx power, max tx power
            r.u8() ?: return null; r.u8() ?: return null; r.u8() ?: return null
            val key = r.bytes(32) ?: return null
            val lat = r.i32() ?: return null
            val lon = r.i32() ?: return null
            r.bytes(4) ?: return null // multi-acks, location policy, telemetry, manual add
            val freq = r.u32() ?: return null
            val bw = r.u32() ?: return null
            val sf = r.u8() ?: return null
            val cr = r.u8() ?: return null
            val hasPosition = lat != 0 || lon != 0
            return MeshCoreSelfInfo(
                publicKey = Bytes(key),
                latitude = if (hasPosition) lat / 1e6 else null,
                longitude = if (hasPosition) lon / 1e6 else null,
                frequencyMHz = freq / 1000.0,
                bandwidthKHz = bw / 1000.0,
                spreadingFactor = sf, codingRate = cr,
                name = r.restString(),
            )
        }

        private fun decodeDeviceInfo(r: MeshCoreReader): MeshCoreDeviceInfo? {
            val version = r.u8() ?: return null
            val info = MeshCoreDeviceInfo(version, 0, 0, "", "", "")
            if (version < 3) return info
            val contacts = r.u8() ?: return info
            val channels = r.u8() ?: return info
            r.u32() ?: return info // BLE PIN
            val build = r.fixedString(12) ?: return info
            val model = r.fixedString(40) ?: return info
            val ver = r.fixedString(20) ?: return info
            return info.copy(maxContacts = contacts * 2, maxChannels = channels, firmwareBuild = build, model = model, version = ver)
        }

        private fun decodeDirect(r: MeshCoreReader, v3: Boolean): MeshCoreDirectMessage? {
            var snr: Float? = null
            if (v3) {
                val s = r.i8() ?: return null
                r.bytes(2) ?: return null
                snr = s / 4f
            }
            val prefix = r.bytes(6) ?: return null
            val path = r.u8() ?: return null
            val type = r.u8() ?: return null
            val ts = r.u32() ?: return null
            if (type == MeshCoreTextType.SIGNED_PLAIN) r.bytes(4) ?: return null
            return MeshCoreDirectMessage(Bytes(prefix), path, ts, r.restString(), snr)
        }

        private fun decodeChannel(r: MeshCoreReader, v3: Boolean): MeshCoreChannelMessage? {
            var snr: Float? = null
            if (v3) {
                val s = r.i8() ?: return null
                r.bytes(2) ?: return null
                snr = s / 4f
            }
            val channel = r.u8() ?: return null
            val path = r.u8() ?: return null
            r.u8() ?: return null
            val ts = r.u32() ?: return null
            val (sender, text) = splitSender(r.restString())
            return MeshCoreChannelMessage(channel, path, ts, sender, text, snr)
        }

        /** "Ana: meet at the bridge" -> ("Ana", "meet at the bridge"). */
        fun splitSender(raw: String): Pair<String?, String> {
            val idx = raw.indexOf(": ")
            // Swift measures the distance in Characters (grapheme clusters).
            if (idx < 0 || graphemeCount(raw.substring(0, idx)) > 32) return null to raw
            val name = raw.substring(0, idx)
            if (name.isEmpty() || name.contains("\n")) return null to raw
            return name to raw.substring(idx + 2)
        }

        private fun graphemeCount(s: String): Int {
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(s)
            var n = 0
            while (it.next() != java.text.BreakIterator.DONE) n++
            return n
        }
    }
}

/** Bounds-checked little-endian reader. Every read returns null past the end. */
class MeshCoreReader(private val bytes: ByteArray) {
    private var pos = 0

    fun u8(): Int? {
        if (pos >= bytes.size) return null
        return bytes[pos++].toInt() and 0xFF
    }

    fun i8(): Int? {
        if (pos >= bytes.size) return null
        return bytes[pos++].toInt()
    }

    fun u32(): Long? {
        val b = bytes(4) ?: return null
        var v = 0L
        for (i in 0 until 4) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun i32(): Int? = u32()?.toInt()

    fun bytes(n: Int): ByteArray? {
        if (n < 0 || pos + n > bytes.size) return null
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** A NUL-padded fixed-width string field. */
    fun fixedString(n: Int): String? {
        val raw = bytes(n) ?: return null
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.UTF_8).trim(' ', '\t')
    }

    /** Everything left, as text (trailing NULs dropped). */
    fun restString(): String {
        val rest = if (pos < bytes.size) bytes.copyOfRange(pos, bytes.size) else ByteArray(0)
        pos = bytes.size
        val end = rest.indexOf(0).let { if (it < 0) rest.size else it }
        return String(rest, 0, end, Charsets.UTF_8)
    }
}

private fun ByteArray.indexOf(b: Int): Int {
    for (i in indices) if (this[i].toInt() == b) return i
    return -1
}

/** Immutable bytes with content equality, for use in data classes. */
class Bytes(bytes: ByteArray) {
    private val data = bytes.copyOf()
    val array: ByteArray get() = data.copyOf()
    val size: Int get() = data.size
    fun isEmpty(): Boolean = data.isEmpty()
    fun prefix(n: Int): Bytes = Bytes(data.copyOfRange(0, minOf(n, data.size)))
    fun startsWith(other: Bytes): Boolean =
        other.size <= size && (0 until other.size).all { data[it] == other.data[it] }
    fun hex(): String = data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    override fun equals(other: Any?): Boolean = other is Bytes && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "Bytes(${hex()})"

    companion object {
        fun fromHex(hex: String): Bytes? {
            if (hex.length % 2 != 0) return null
            return try {
                Bytes(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
            } catch (_: NumberFormatException) { null }
        }
    }
}
