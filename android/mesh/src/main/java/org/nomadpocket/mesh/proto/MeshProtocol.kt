// MeshProtocol.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The handful of Meshtastic client-API messages this app needs, encoded and
// decoded by field number with MeshProtoWire. Ported from
// App/Sources/Mesh/MeshProtocol.swift, which was written from Meshtastic's
// public documentation (client API page and the Buf Schema Registry reference
// for buf.build/meshtastic/protobufs) to interoperate at the wire level. No
// Meshtastic source or .proto files are copied or generated from.
//
// Only fields the app uses are modelled. Everything else is skipped.
// uint32/fixed32 values are carried as Long in 0..0xFFFFFFFF.

package org.nomadpocket.mesh.proto

object MeshConstants {
    /** MeshPacket.to for "everyone on this channel". */
    const val BROADCAST_NUM: Long = 0xFFFF_FFFFL
    /**
     * Practical UTF-8 byte budget for one text message. The radio's data
     * payload tops out a little above this; 200 leaves room for overhead.
     */
    const val MAX_TEXT_BYTES = 200
    const val DEFAULT_HOP_LIMIT: Long = 3
    /** Config.DeviceConfig.Role values that rebroadcast for others: ROUTER, ROUTER_CLIENT, REPEATER, ROUTER_LATE. */
    val ROUTER_ROLES: Set<Long> = setOf(2L, 3L, 4L, 11L)
}

/** PortNum values (Data.portnum). */
object MeshPort {
    const val UNKNOWN: Long = 0
    const val TEXT_MESSAGE: Long = 1
    const val POSITION: Long = 3
    const val NODE_INFO: Long = 4
    const val ROUTING: Long = 5
    /** ADMIN_APP: settings reads and writes (portnums.proto). */
    const val ADMIN: Long = 6
    /** PRIVATE_APP: free for apps; the drive-test ping uses it. */
    const val PRIVATE_APP: Long = 256
}

/** Routing.Error values we name; anything else is shown by number. */
object MeshRoutingError {
    const val NONE: Long = 0
    const val NO_ROUTE: Long = 1
    const val GOT_NAK: Long = 2
    const val TIMEOUT: Long = 3
    const val NO_INTERFACE: Long = 4
    const val MAX_RETRANSMIT: Long = 5
    const val NO_CHANNEL: Long = 6
    const val TOO_LARGE: Long = 7
    const val NO_RESPONSE: Long = 8
    const val DUTY_CYCLE_LIMIT: Long = 9
    const val BAD_REQUEST: Long = 32
    const val NOT_AUTHORIZED: Long = 33
    const val PKI_FAILED: Long = 34
    const val PKI_UNKNOWN_PUBKEY: Long = 35
    const val ADMIN_BAD_SESSION_KEY: Long = 36
    const val ADMIN_PUBLIC_KEY_UNAUTHORIZED: Long = 37
    const val RATE_LIMIT_EXCEEDED: Long = 38

    fun describe(raw: Long): String = when (raw) {
        NONE -> "OK"
        NO_ROUTE -> "No route to that radio"
        GOT_NAK -> "Rejected by the mesh"
        TIMEOUT -> "Timed out"
        NO_INTERFACE -> "Radio has no way to send"
        MAX_RETRANSMIT -> "Their radio didn't answer"
        NO_CHANNEL -> "Channel mismatch"
        TOO_LARGE -> "Message too long"
        NO_RESPONSE -> "No response"
        DUTY_CYCLE_LIMIT -> "Radio is over its legal airtime limit, try later"
        BAD_REQUEST -> "Bad request"
        NOT_AUTHORIZED -> "Not authorized"
        PKI_FAILED -> "Encryption failed"
        PKI_UNKNOWN_PUBKEY -> "Their radio's key is unknown yet"
        ADMIN_BAD_SESSION_KEY -> "The radio's settings session expired, try again"
        ADMIN_PUBLIC_KEY_UNAUTHORIZED -> "This radio does not accept settings from this phone"
        RATE_LIMIT_EXCEEDED -> "Sending too fast, slow down"
        else -> "Error $raw"
    }
}

// MARK: - Data (portnum 1, payload 2, want_response 3, dest 4, source 5,
// request_id 6, reply_id 7, emoji 8, bitfield 9)

data class MeshPBData(
    val portnum: Long = 0,
    val payload: ByteArray = ByteArray(0),
    val wantResponse: Boolean = false,
    val dest: Long = 0,
    val source: Long = 0,
    val requestID: Long = 0,
    val replyID: Long = 0,
    val emoji: Long = 0,
    val bitfield: Long? = null,
) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        w.writeUInt32IfNonZero(1, portnum)
        w.writeBytesIfNotEmpty(2, payload)
        w.writeBoolIfTrue(3, wantResponse)
        w.writeFixed32IfNonZero(4, dest)
        w.writeFixed32IfNonZero(5, source)
        w.writeFixed32IfNonZero(6, requestID)
        w.writeFixed32IfNonZero(7, replyID)
        w.writeFixed32IfNonZero(8, emoji)
        bitfield?.let { w.writeUInt32(9, it) }
        return w.data
    }

    override fun equals(other: Any?): Boolean = other is MeshPBData && portnum == other.portnum &&
        payload.contentEquals(other.payload) && wantResponse == other.wantResponse && dest == other.dest &&
        source == other.source && requestID == other.requestID && replyID == other.replyID &&
        emoji == other.emoji && bitfield == other.bitfield

    override fun hashCode(): Int = listOf(portnum, payload.contentHashCode(), wantResponse, dest, source, requestID, replyID, emoji, bitfield).hashCode()

    companion object {
        fun decode(data: ByteArray): MeshPBData {
            var d = MeshPBData()
            MeshProtoReader.forEachField(data) { f ->
                d = when (f.number) {
                    1 -> d.copy(portnum = f.uint32)
                    2 -> d.copy(payload = f.bytes)
                    3 -> d.copy(wantResponse = f.bool)
                    4 -> d.copy(dest = f.uint32)
                    5 -> d.copy(source = f.uint32)
                    6 -> d.copy(requestID = f.uint32)
                    7 -> d.copy(replyID = f.uint32)
                    8 -> d.copy(emoji = f.uint32)
                    9 -> d.copy(bitfield = f.uint32)
                    else -> d
                }
            }
            return d
        }
    }
}

// MARK: - MeshPacket (from 1 fixed32, to 2 fixed32, channel 3, decoded 4,
// encrypted 5, id 6 fixed32, rx_time 7 fixed32, rx_snr 8 float, hop_limit 9,
// want_ack 10, priority 11, rx_rssi 12 int32, via_mqtt 14, hop_start 15)

data class MeshPBPacket(
    val from: Long = 0,
    val to: Long = 0,
    val channel: Long = 0,
    val decoded: MeshPBData? = null,
    val encrypted: ByteArray? = null,
    val id: Long = 0,
    val rxTime: Long = 0,
    val rxSNR: Float = 0f,
    val hopLimit: Long = 0,
    val wantAck: Boolean = false,
    val priority: Long = 0,
    val rxRSSI: Int = 0,
    val viaMQTT: Boolean = false,
    val hopStart: Long = 0,
) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        w.writeFixed32IfNonZero(1, from)
        w.writeFixed32IfNonZero(2, to)
        w.writeUInt32IfNonZero(3, channel)
        decoded?.let { w.writeBytes(4, it.encode()) }
        encrypted?.let { w.writeBytes(5, it) }
        w.writeFixed32IfNonZero(6, id)
        w.writeFixed32IfNonZero(7, rxTime)
        if (rxSNR != 0f) w.writeFloat(8, rxSNR)
        w.writeUInt32IfNonZero(9, hopLimit)
        w.writeBoolIfTrue(10, wantAck)
        w.writeUInt32IfNonZero(11, priority)
        if (rxRSSI != 0) w.writeInt32(12, rxRSSI)
        w.writeBoolIfTrue(14, viaMQTT)
        w.writeUInt32IfNonZero(15, hopStart)
        return w.data
    }

    /** Hops the packet took to reach us, when the firmware reports hop_start. */
    val hopsAway: Long?
        get() = if (hopStart > 0 && hopStart >= hopLimit) hopStart - hopLimit else null

    override fun equals(other: Any?): Boolean = other is MeshPBPacket && from == other.from && to == other.to &&
        channel == other.channel && decoded == other.decoded &&
        (encrypted?.contentEquals(other.encrypted ?: return false) ?: (other.encrypted == null)) &&
        id == other.id && rxTime == other.rxTime && rxSNR == other.rxSNR && hopLimit == other.hopLimit &&
        wantAck == other.wantAck && priority == other.priority && rxRSSI == other.rxRSSI &&
        viaMQTT == other.viaMQTT && hopStart == other.hopStart

    override fun hashCode(): Int = listOf(from, to, channel, decoded, encrypted?.contentHashCode(), id, rxTime, rxSNR, hopLimit, wantAck, priority, rxRSSI, viaMQTT, hopStart).hashCode()

    companion object {
        fun decode(data: ByteArray): MeshPBPacket {
            var p = MeshPBPacket()
            MeshProtoReader.forEachField(data) { f ->
                p = when (f.number) {
                    1 -> p.copy(from = f.uint32)
                    2 -> p.copy(to = f.uint32)
                    3 -> p.copy(channel = f.uint32)
                    4 -> p.copy(decoded = MeshPBData.decode(f.bytes))
                    5 -> p.copy(encrypted = f.bytes)
                    6 -> p.copy(id = f.uint32)
                    7 -> p.copy(rxTime = f.uint32)
                    8 -> p.copy(rxSNR = f.float)
                    9 -> p.copy(hopLimit = f.uint32)
                    10 -> p.copy(wantAck = f.bool)
                    11 -> p.copy(priority = f.uint32)
                    12 -> p.copy(rxRSSI = f.int32)
                    14 -> p.copy(viaMQTT = f.bool)
                    15 -> p.copy(hopStart = f.uint32)
                    else -> p
                }
            }
            return p
        }
    }
}

// MARK: - User (id 1, long_name 2, short_name 3, hw_model 5, is_licensed 6,
// role 7, public_key 8)

data class MeshPBUser(
    val id: String = "",
    val longName: String = "",
    val shortName: String = "",
    val hwModel: Long = 0,
    val isLicensed: Boolean = false,
    val role: Long = 0,
    val publicKey: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        w.writeStringIfNotEmpty(1, id)
        w.writeStringIfNotEmpty(2, longName)
        w.writeStringIfNotEmpty(3, shortName)
        w.writeUInt32IfNonZero(5, hwModel)
        w.writeBoolIfTrue(6, isLicensed)
        w.writeUInt32IfNonZero(7, role)
        w.writeBytesIfNotEmpty(8, publicKey)
        return w.data
    }

    override fun equals(other: Any?): Boolean = other is MeshPBUser && id == other.id && longName == other.longName &&
        shortName == other.shortName && hwModel == other.hwModel && isLicensed == other.isLicensed &&
        role == other.role && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = listOf(id, longName, shortName, hwModel, isLicensed, role, publicKey.contentHashCode()).hashCode()

    companion object {
        fun decode(data: ByteArray): MeshPBUser {
            var u = MeshPBUser()
            MeshProtoReader.forEachField(data) { f ->
                u = when (f.number) {
                    1 -> u.copy(id = f.string)
                    2 -> u.copy(longName = f.string)
                    3 -> u.copy(shortName = f.string)
                    5 -> u.copy(hwModel = f.uint32)
                    6 -> u.copy(isLicensed = f.bool)
                    7 -> u.copy(role = f.uint32)
                    8 -> u.copy(publicKey = f.bytes)
                    else -> u
                }
            }
            return u
        }
    }
}

// MARK: - Position (latitude_i 1 sfixed32, longitude_i 2 sfixed32,
// altitude 3 int32, time 4 fixed32). Degrees are stored as value * 1e-7.

data class MeshPBPosition(
    val latitudeI: Int? = null,
    val longitudeI: Int? = null,
    val altitude: Int? = null,
    val time: Long = 0,
) {
    val latitude: Double? get() = latitudeI?.let { it.toDouble() * 1e-7 }
    val longitude: Double? get() = longitudeI?.let { it.toDouble() * 1e-7 }

    /** A usable fix: both coordinates present and not the 0,0 "no fix" placeholder. */
    val hasFix: Boolean
        get() {
            val la = latitudeI ?: return false
            val lo = longitudeI ?: return false
            return !(la == 0 && lo == 0)
        }

    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        latitudeI?.let { w.writeSFixed32(1, it) }
        longitudeI?.let { w.writeSFixed32(2, it) }
        altitude?.let { w.writeInt32(3, it) }
        w.writeFixed32IfNonZero(4, time)
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBPosition {
            var p = MeshPBPosition()
            MeshProtoReader.forEachField(data) { f ->
                p = when (f.number) {
                    1 -> p.copy(latitudeI = f.sfixed32)
                    2 -> p.copy(longitudeI = f.sfixed32)
                    3 -> p.copy(altitude = f.int32)
                    4 -> p.copy(time = f.uint32)
                    else -> p
                }
            }
            return p
        }
    }
}

// MARK: - DeviceMetrics (battery_level 1, voltage 2 float)

data class MeshPBDeviceMetrics(val batteryLevel: Long? = null, val voltage: Float? = null) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        batteryLevel?.let { w.writeUInt32(1, it) }
        voltage?.let { w.writeFloat(2, it) }
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBDeviceMetrics {
            var m = MeshPBDeviceMetrics()
            MeshProtoReader.forEachField(data) { f ->
                m = when (f.number) {
                    1 -> m.copy(batteryLevel = f.uint32)
                    2 -> m.copy(voltage = f.float)
                    else -> m
                }
            }
            return m
        }
    }
}

// MARK: - NodeInfo (num 1, user 2, position 3, snr 4 float, last_heard 5
// fixed32, device_metrics 6, channel 7, via_mqtt 8, hops_away 9)

data class MeshPBNodeInfo(
    val num: Long = 0,
    val user: MeshPBUser? = null,
    val position: MeshPBPosition? = null,
    val snr: Float = 0f,
    val lastHeard: Long = 0,
    val deviceMetrics: MeshPBDeviceMetrics? = null,
    val channel: Long = 0,
    val viaMQTT: Boolean = false,
    val hopsAway: Long? = null,
) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        w.writeUInt32IfNonZero(1, num)
        user?.let { w.writeBytes(2, it.encode()) }
        position?.let { w.writeBytes(3, it.encode()) }
        if (snr != 0f) w.writeFloat(4, snr)
        w.writeFixed32IfNonZero(5, lastHeard)
        deviceMetrics?.let { w.writeBytes(6, it.encode()) }
        w.writeUInt32IfNonZero(7, channel)
        w.writeBoolIfTrue(8, viaMQTT)
        hopsAway?.let { w.writeUInt32(9, it) }
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBNodeInfo {
            var n = MeshPBNodeInfo()
            MeshProtoReader.forEachField(data) { f ->
                n = when (f.number) {
                    1 -> n.copy(num = f.uint32)
                    2 -> n.copy(user = MeshPBUser.decode(f.bytes))
                    3 -> n.copy(position = MeshPBPosition.decode(f.bytes))
                    4 -> n.copy(snr = f.float)
                    5 -> n.copy(lastHeard = f.uint32)
                    6 -> n.copy(deviceMetrics = MeshPBDeviceMetrics.decode(f.bytes))
                    7 -> n.copy(channel = f.uint32)
                    8 -> n.copy(viaMQTT = f.bool)
                    9 -> n.copy(hopsAway = f.uint32)
                    else -> n
                }
            }
            return n
        }
    }
}

// MARK: - MyNodeInfo (my_node_num 1, reboot_count 8, min_app_version 11)

data class MeshPBMyNodeInfo(val myNodeNum: Long = 0, val rebootCount: Long = 0, val minAppVersion: Long = 0) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        w.writeUInt32IfNonZero(1, myNodeNum)
        w.writeUInt32IfNonZero(8, rebootCount)
        w.writeUInt32IfNonZero(11, minAppVersion)
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBMyNodeInfo {
            var m = MeshPBMyNodeInfo()
            MeshProtoReader.forEachField(data) { f ->
                m = when (f.number) {
                    1 -> m.copy(myNodeNum = f.uint32)
                    8 -> m.copy(rebootCount = f.uint32)
                    11 -> m.copy(minAppVersion = f.uint32)
                    else -> m
                }
            }
            return m
        }
    }
}

// MARK: - Channel (index 1 int32, settings 2, role 3) and
// ChannelSettings (psk 2 bytes, name 3 string)

data class MeshPBChannel(val index: Int = 0, val name: String = "", val hasPSK: Boolean = false, val role: Long = 0) {
    object Role { const val DISABLED: Long = 0; const val PRIMARY: Long = 1; const val SECONDARY: Long = 2 }

    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        if (index != 0) w.writeInt32(1, index)
        w.writeMessage(2) { s ->
            if (hasPSK) s.writeBytes(2, byteArrayOf(1))
            s.writeStringIfNotEmpty(3, name)
        }
        w.writeUInt32IfNonZero(3, role)
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBChannel {
            var c = MeshPBChannel()
            MeshProtoReader.forEachField(data) { f ->
                when (f.number) {
                    1 -> c = c.copy(index = f.int32)
                    2 -> MeshProtoReader.forEachField(f.bytes) { s ->
                        when (s.number) {
                            2 -> c = c.copy(hasPSK = s.bytes.isNotEmpty())
                            3 -> c = c.copy(name = s.string)
                        }
                    }
                    3 -> c = c.copy(role = f.uint32)
                }
            }
            return c
        }
    }
}

// MARK: - DeviceMetadata (firmware_version 1, role 7, hw_model 9, hasPKC 11)

data class MeshPBDeviceMetadata(val firmwareVersion: String = "", val role: Long = 0, val hwModel: Long = 0, val hasPKC: Boolean = false) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        w.writeStringIfNotEmpty(1, firmwareVersion)
        w.writeUInt32IfNonZero(7, role)
        w.writeUInt32IfNonZero(9, hwModel)
        w.writeBoolIfTrue(11, hasPKC)
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBDeviceMetadata {
            var m = MeshPBDeviceMetadata()
            MeshProtoReader.forEachField(data) { f ->
                m = when (f.number) {
                    1 -> m.copy(firmwareVersion = f.string)
                    7 -> m.copy(role = f.uint32)
                    9 -> m.copy(hwModel = f.uint32)
                    11 -> m.copy(hasPKC = f.bool)
                    else -> m
                }
            }
            return m
        }
    }
}

// MARK: - Config.lora (Config field 6) -> LoRaConfig. Field numbers from
// meshtastic/protobufs config.proto: use_preset 1, modem_preset 2,
// bandwidth 3, spread_factor 4, coding_rate 5, region 7, hop_limit 8,
// tx_enabled 9, tx_power 10 (int32). Region 0 = UNSET (the radio will not
// transmit), 1 = US (902-928 MHz). Modem preset 0 = LONG_FAST.
//
// set_config replaces the radio's whole LoRaConfig, so every field we do not
// model is kept byte for byte in `other` and written back unchanged.

data class MeshPBLoRaConfig(
    val modemPreset: Long = 0,
    val region: Long = 0,
    val hopLimit: Long = 0,
    val usePreset: Boolean = false,
    val bandwidth: Long = 0,
    val spreadFactor: Long = 0,
    val codingRate: Long = 0,
    val txEnabled: Boolean = false,
    val txPower: Int = 0,
    /** Encoded fields this app does not model (frequency offset, channel_num, ...). */
    val other: Bytes = Bytes(ByteArray(0)),
) {
    val regionName: String
        get() = when (region) {
            0L -> "Not set"
            1L -> "US 915 MHz"
            2L -> "EU 433 MHz"
            3L -> "EU 868 MHz"
            6L -> "ANZ"
            else -> "Region $region"
        }

    /** The LoRaConfig message itself. Proto3: zero values are left out. */
    fun encode(): ByteArray {
        val l = MeshProtoWriter()
        l.writeBoolIfTrue(1, usePreset)
        l.writeUInt32IfNonZero(2, modemPreset)
        l.writeUInt32IfNonZero(3, bandwidth)
        l.writeUInt32IfNonZero(4, spreadFactor)
        l.writeUInt32IfNonZero(5, codingRate)
        l.writeUInt32IfNonZero(7, region)
        l.writeUInt32IfNonZero(8, hopLimit)
        l.writeBoolIfTrue(9, txEnabled)
        if (txPower != 0) l.writeInt32(10, txPower)
        return l.data + other.array
    }

    /** Encodes as a full `Config` message with only the lora variant set. */
    fun encodeAsConfig(): ByteArray {
        val w = MeshProtoWriter()
        w.writeBytes(6, encode())
        return w.data
    }

    companion object {
        private val MODELLED = setOf(1, 2, 3, 4, 5, 7, 8, 9, 10)

        fun decode(data: ByteArray): MeshPBLoRaConfig {
            var l = MeshPBLoRaConfig()
            val rest = MeshProtoWriter()
            MeshProtoReader.forEachField(data) { s ->
                l = when (s.number) {
                    1 -> l.copy(usePreset = s.bool)
                    2 -> l.copy(modemPreset = s.uint32)
                    3 -> l.copy(bandwidth = s.uint32)
                    4 -> l.copy(spreadFactor = s.uint32)
                    5 -> l.copy(codingRate = s.uint32)
                    7 -> l.copy(region = s.uint32)
                    8 -> l.copy(hopLimit = s.uint32)
                    9 -> l.copy(txEnabled = s.bool)
                    10 -> l.copy(txPower = s.int32)
                    else -> l
                }
                if (s.number !in MODELLED) when (s.wireType) {
                    MeshProtoWireType.VARINT -> rest.writeVarint(s.number, s.scalar)
                    MeshProtoWireType.FIXED32 -> rest.writeFixed32(s.number, s.uint32)
                    MeshProtoWireType.FIXED64 -> rest.writeFixed64(s.number, s.scalar)
                    MeshProtoWireType.LENGTH_DELIMITED -> rest.writeBytes(s.number, s.bytes)
                    else -> {}
                }
            }
            return l.copy(other = Bytes(rest.data))
        }

        /** Decodes a `Config` message; returns null when it is not the lora variant. */
        fun decodeConfig(data: ByteArray): MeshPBLoRaConfig? {
            var result: MeshPBLoRaConfig? = null
            MeshProtoReader.forEachField(data) { f -> if (f.number == 6) result = decode(f.bytes) }
            return result
        }
    }
}

// MARK: - AdminMessage (ADMIN_APP, portnum 6). From meshtastic/protobufs
// admin.proto: get_config_request 5 (ConfigType; LORA_CONFIG = 5),
// get_config_response 6 (Config), set_config 34 (Config), session_passkey 101
// (bytes). The radio puts a fresh 8-byte passkey in every get_*_response
// (AdminModule::setPassKey) and, since firmware 2.5, wants it echoed in set_*
// messages; it is valid for 300 s. The firmware (AdminModule.cpp) only checks
// it for packets that did not come from the phone link itself (from != 0),
// but echoing it is what the protocol asks for and costs nothing.

object MeshAdminConfigType {
    const val DEVICE: Long = 0
    const val LORA: Long = 5
}

data class MeshPBAdmin(
    val getConfigRequest: Long? = null,
    /** get_config_response when it carries the lora variant. */
    val loraConfigResponse: MeshPBLoRaConfig? = null,
    /** True for any get_config_response (lora or not). */
    val isConfigResponse: Boolean = false,
    val setLoraConfig: MeshPBLoRaConfig? = null,
    val sessionPasskey: Bytes = Bytes(ByteArray(0)),
) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        // oneof members: written even when zero.
        getConfigRequest?.let { w.writeUInt32(5, it) }
        loraConfigResponse?.let { w.writeBytes(6, it.encodeAsConfig()) }
        setLoraConfig?.let { w.writeBytes(34, it.encodeAsConfig()) }
        w.writeBytesIfNotEmpty(101, sessionPasskey.array)
        return w.data
    }

    companion object {
        fun getLoraConfig(): MeshPBAdmin = MeshPBAdmin(getConfigRequest = MeshAdminConfigType.LORA)

        fun setLora(config: MeshPBLoRaConfig, passkey: Bytes): MeshPBAdmin =
            MeshPBAdmin(setLoraConfig = config, sessionPasskey = passkey)

        fun decode(data: ByteArray): MeshPBAdmin {
            var a = MeshPBAdmin()
            MeshProtoReader.forEachField(data) { f ->
                a = when (f.number) {
                    5 -> a.copy(getConfigRequest = f.uint32)
                    6 -> a.copy(isConfigResponse = true, loraConfigResponse = MeshPBLoRaConfig.decodeConfig(f.bytes))
                    34 -> a.copy(setLoraConfig = MeshPBLoRaConfig.decodeConfig(f.bytes))
                    101 -> a.copy(sessionPasskey = Bytes(f.bytes))
                    else -> a
                }
            }
            return a
        }
    }
}

// MARK: - Routing (route_request 1, route_reply 2, error_reason 3)

data class MeshPBRouting(
    /** null when the message is a route request/reply rather than an ack/nak. */
    val errorReason: Long? = null,
) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        // error_reason is a oneof member, so even NONE (0) is written.
        errorReason?.let { w.writeUInt32(3, it) }
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBRouting {
            var reason: Long? = null
            var sawRoute = false
            MeshProtoReader.forEachField(data) { f ->
                when (f.number) {
                    1, 2 -> sawRoute = true
                    3 -> reason = f.uint32
                }
            }
            // An empty Routing body carries no error: treat it as an ACK.
            if (reason == null && !sawRoute) reason = 0
            return MeshPBRouting(reason)
        }
    }
}

// MARK: - QueueStatus (res 1 int32, free 2, maxlen 3, mesh_packet_id 4)

data class MeshPBQueueStatus(val res: Int = 0, val free: Long = 0, val maxlen: Long = 0, val meshPacketID: Long = 0) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        if (res != 0) w.writeInt32(1, res)
        w.writeUInt32IfNonZero(2, free)
        w.writeUInt32IfNonZero(3, maxlen)
        w.writeUInt32IfNonZero(4, meshPacketID)
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBQueueStatus {
            var q = MeshPBQueueStatus()
            MeshProtoReader.forEachField(data) { f ->
                q = when (f.number) {
                    1 -> q.copy(res = f.int32)
                    2 -> q.copy(free = f.uint32)
                    3 -> q.copy(maxlen = f.uint32)
                    4 -> q.copy(meshPacketID = f.uint32)
                    else -> q
                }
            }
            return q
        }
    }
}

// MARK: - FromRadio (id 1, packet 2, my_info 3, node_info 4, config 5,
// log_record 6, config_complete_id 7, rebooted 8, channel 10, queueStatus 11,
// metadata 13, clientNotification 16 -> message 4)

sealed class MeshPBFromRadioVariant {
    data class Packet(val packet: MeshPBPacket) : MeshPBFromRadioVariant()
    data class MyInfo(val info: MeshPBMyNodeInfo) : MeshPBFromRadioVariant()
    data class NodeInfo(val info: MeshPBNodeInfo) : MeshPBFromRadioVariant()
    data class LoraConfig(val config: MeshPBLoRaConfig) : MeshPBFromRadioVariant()
    object OtherConfig : MeshPBFromRadioVariant()
    data class LogRecord(val message: String) : MeshPBFromRadioVariant()
    data class ConfigCompleteID(val id: Long) : MeshPBFromRadioVariant()
    object Rebooted : MeshPBFromRadioVariant()
    data class Channel(val channel: MeshPBChannel) : MeshPBFromRadioVariant()
    data class QueueStatus(val status: MeshPBQueueStatus) : MeshPBFromRadioVariant()
    data class Metadata(val metadata: MeshPBDeviceMetadata) : MeshPBFromRadioVariant()
    data class ClientNotification(val message: String) : MeshPBFromRadioVariant()
    /** A variant we do not model (module config, file info, and so on). */
    data class Unhandled(val field: Int) : MeshPBFromRadioVariant()
}

data class MeshPBFromRadio(val id: Long = 0, val variant: MeshPBFromRadioVariant? = null) {
    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        w.writeUInt32IfNonZero(1, id)
        when (val v = variant) {
            is MeshPBFromRadioVariant.Packet -> w.writeBytes(2, v.packet.encode())
            is MeshPBFromRadioVariant.MyInfo -> w.writeBytes(3, v.info.encode())
            is MeshPBFromRadioVariant.NodeInfo -> w.writeBytes(4, v.info.encode())
            is MeshPBFromRadioVariant.LoraConfig -> w.writeBytes(5, v.config.encodeAsConfig())
            MeshPBFromRadioVariant.OtherConfig -> w.writeMessage(5) { it.writeMessage(1) { } }
            is MeshPBFromRadioVariant.LogRecord -> w.writeMessage(6) { it.writeString(1, v.message) }
            is MeshPBFromRadioVariant.ConfigCompleteID -> w.writeUInt32(7, v.id)
            MeshPBFromRadioVariant.Rebooted -> w.writeBool(8, true)
            is MeshPBFromRadioVariant.Channel -> w.writeBytes(10, v.channel.encode())
            is MeshPBFromRadioVariant.QueueStatus -> w.writeBytes(11, v.status.encode())
            is MeshPBFromRadioVariant.Metadata -> w.writeBytes(13, v.metadata.encode())
            is MeshPBFromRadioVariant.ClientNotification -> w.writeMessage(16) { it.writeString(4, v.message) }
            is MeshPBFromRadioVariant.Unhandled -> w.writeBytes(v.field, ByteArray(0))
            null -> {}
        }
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBFromRadio {
            var id = 0L
            var variant: MeshPBFromRadioVariant? = null
            MeshProtoReader.forEachField(data) { f ->
                when (f.number) {
                    1 -> id = f.uint32
                    2 -> variant = MeshPBFromRadioVariant.Packet(MeshPBPacket.decode(f.bytes))
                    3 -> variant = MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo.decode(f.bytes))
                    4 -> variant = MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo.decode(f.bytes))
                    5 -> variant = MeshPBLoRaConfig.decodeConfig(f.bytes)?.let { MeshPBFromRadioVariant.LoraConfig(it) }
                        ?: MeshPBFromRadioVariant.OtherConfig
                    6 -> {
                        var msg = ""
                        MeshProtoReader.forEachField(f.bytes) { s -> if (s.number == 1) msg = s.string }
                        variant = MeshPBFromRadioVariant.LogRecord(msg)
                    }
                    7 -> variant = MeshPBFromRadioVariant.ConfigCompleteID(f.uint32)
                    8 -> variant = MeshPBFromRadioVariant.Rebooted
                    10 -> variant = MeshPBFromRadioVariant.Channel(MeshPBChannel.decode(f.bytes))
                    11 -> variant = MeshPBFromRadioVariant.QueueStatus(MeshPBQueueStatus.decode(f.bytes))
                    13 -> variant = MeshPBFromRadioVariant.Metadata(MeshPBDeviceMetadata.decode(f.bytes))
                    16 -> {
                        var msg = ""
                        MeshProtoReader.forEachField(f.bytes) { s -> if (s.number == 4) msg = s.string }
                        variant = MeshPBFromRadioVariant.ClientNotification(msg)
                    }
                    else -> variant = MeshPBFromRadioVariant.Unhandled(f.number)
                }
            }
            return MeshPBFromRadio(id, variant)
        }
    }
}

// MARK: - ToRadio (packet 1, want_config_id 3, disconnect 4, heartbeat 7)

sealed class MeshPBToRadio {
    data class Packet(val packet: MeshPBPacket) : MeshPBToRadio()
    data class WantConfigID(val id: Long) : MeshPBToRadio()
    object Disconnect : MeshPBToRadio()
    data class Heartbeat(val nonce: Long) : MeshPBToRadio()

    fun encode(): ByteArray {
        val w = MeshProtoWriter()
        when (this) {
            is Packet -> w.writeBytes(1, packet.encode())
            is WantConfigID -> w.writeUInt32(3, id)
            Disconnect -> w.writeBool(4, true)
            is Heartbeat -> w.writeMessage(7) { it.writeUInt32IfNonZero(1, nonce) }
        }
        return w.data
    }

    companion object {
        fun decode(data: ByteArray): MeshPBToRadio? {
            var result: MeshPBToRadio? = null
            MeshProtoReader.forEachField(data) { f ->
                when (f.number) {
                    1 -> result = Packet(MeshPBPacket.decode(f.bytes))
                    3 -> result = WantConfigID(f.uint32)
                    4 -> result = Disconnect
                    7 -> {
                        var nonce = 0L
                        MeshProtoReader.forEachField(f.bytes) { s -> if (s.number == 1) nonce = s.uint32 }
                        result = Heartbeat(nonce)
                    }
                }
            }
            return result
        }
    }
}
