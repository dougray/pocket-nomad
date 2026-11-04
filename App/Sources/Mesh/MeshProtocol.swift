// MeshProtocol.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The handful of Meshtastic client-API messages this app needs, encoded and
// decoded by field number with MeshProtoWire. Written from Meshtastic's public
// documentation (client API page and the Buf Schema Registry reference for
// buf.build/meshtastic/protobufs) to interoperate at the wire level. No
// Meshtastic source or .proto files are copied or generated from.
//
// Only fields the app uses are modelled. Everything else is skipped.

import Foundation

enum MeshConstants {
    /// MeshPacket.to for "everyone on this channel".
    static let broadcastNum: UInt32 = 0xFFFF_FFFF
    /// Practical UTF-8 byte budget for one text message. The radio's data
    /// payload tops out a little above this; 200 leaves room for overhead.
    static let maxTextBytes = 200
    static let defaultHopLimit: UInt32 = 3
}

/// PortNum values (Data.portnum).
enum MeshPort: UInt32, Sendable {
    case unknown = 0
    case textMessage = 1
    case position = 3
    case nodeInfo = 4
    case routing = 5
}

/// Routing.Error values we name; anything else is shown by number.
enum MeshRoutingError: UInt32, Sendable {
    case none = 0
    case noRoute = 1
    case gotNak = 2
    case timeout = 3
    case noInterface = 4
    case maxRetransmit = 5
    case noChannel = 6
    case tooLarge = 7
    case noResponse = 8
    case dutyCycleLimit = 9
    case badRequest = 32
    case notAuthorized = 33
    case pkiFailed = 34
    case pkiUnknownPubkey = 35
    case rateLimitExceeded = 38

    static func describe(_ raw: UInt32) -> String {
        switch MeshRoutingError(rawValue: raw) {
        case .none?: return "OK"
        case .noRoute?: return "No route to that radio"
        case .gotNak?: return "Rejected by the mesh"
        case .timeout?: return "Timed out"
        case .noInterface?: return "Radio has no way to send"
        case .maxRetransmit?: return "Their radio didn't answer"
        case .noChannel?: return "Channel mismatch"
        case .tooLarge?: return "Message too long"
        case .noResponse?: return "No response"
        case .dutyCycleLimit?: return "Radio is over its legal airtime limit, try later"
        case .badRequest?: return "Bad request"
        case .notAuthorized?: return "Not authorized"
        case .pkiFailed?: return "Encryption failed"
        case .pkiUnknownPubkey?: return "Their radio's key is unknown yet"
        case .rateLimitExceeded?: return "Sending too fast, slow down"
        case nil: return "Error \(raw)"
        }
    }
}

// MARK: - Data (field numbers: portnum 1, payload 2, want_response 3, dest 4,
// source 5, request_id 6, reply_id 7, emoji 8, bitfield 9)

struct MeshPBData: Equatable, Sendable {
    var portnum: UInt32 = 0
    var payload = Data()
    var wantResponse = false
    var dest: UInt32 = 0
    var source: UInt32 = 0
    var requestID: UInt32 = 0
    var replyID: UInt32 = 0
    var emoji: UInt32 = 0
    var bitfield: UInt32?

    func encode() -> Data {
        var w = MeshProtoWriter()
        w.writeUInt32IfNonZero(1, portnum)
        w.writeBytesIfNotEmpty(2, payload)
        w.writeBoolIfTrue(3, wantResponse)
        w.writeFixed32IfNonZero(4, dest)
        w.writeFixed32IfNonZero(5, source)
        w.writeFixed32IfNonZero(6, requestID)
        w.writeFixed32IfNonZero(7, replyID)
        w.writeFixed32IfNonZero(8, emoji)
        if let bitfield { w.writeUInt32(9, bitfield) }
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBData {
        var d = MeshPBData()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: d.portnum = f.uint32
            case 2: d.payload = f.bytes
            case 3: d.wantResponse = f.bool
            case 4: d.dest = f.uint32
            case 5: d.source = f.uint32
            case 6: d.requestID = f.uint32
            case 7: d.replyID = f.uint32
            case 8: d.emoji = f.uint32
            case 9: d.bitfield = f.uint32
            default: break
            }
        }
        return d
    }
}

// MARK: - MeshPacket (from 1 fixed32, to 2 fixed32, channel 3, decoded 4,
// encrypted 5, id 6 fixed32, rx_time 7 fixed32, rx_snr 8 float, hop_limit 9,
// want_ack 10, priority 11, rx_rssi 12 int32, via_mqtt 14, hop_start 15)

struct MeshPBPacket: Equatable, Sendable {
    var from: UInt32 = 0
    var to: UInt32 = 0
    var channel: UInt32 = 0
    var decoded: MeshPBData?
    var encrypted: Data?
    var id: UInt32 = 0
    var rxTime: UInt32 = 0
    var rxSNR: Float = 0
    var hopLimit: UInt32 = 0
    var wantAck = false
    var priority: UInt32 = 0
    var rxRSSI: Int32 = 0
    var viaMQTT = false
    var hopStart: UInt32 = 0

    func encode() -> Data {
        var w = MeshProtoWriter()
        w.writeFixed32IfNonZero(1, from)
        w.writeFixed32IfNonZero(2, to)
        w.writeUInt32IfNonZero(3, channel)
        if let decoded { w.writeBytes(4, decoded.encode()) }
        if let encrypted { w.writeBytes(5, encrypted) }
        w.writeFixed32IfNonZero(6, id)
        w.writeFixed32IfNonZero(7, rxTime)
        if rxSNR != 0 { w.writeFloat(8, rxSNR) }
        w.writeUInt32IfNonZero(9, hopLimit)
        w.writeBoolIfTrue(10, wantAck)
        w.writeUInt32IfNonZero(11, priority)
        if rxRSSI != 0 { w.writeInt32(12, rxRSSI) }
        w.writeBoolIfTrue(14, viaMQTT)
        w.writeUInt32IfNonZero(15, hopStart)
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBPacket {
        var p = MeshPBPacket()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: p.from = f.uint32
            case 2: p.to = f.uint32
            case 3: p.channel = f.uint32
            case 4: p.decoded = try MeshPBData.decode(f.bytes)
            case 5: p.encrypted = f.bytes
            case 6: p.id = f.uint32
            case 7: p.rxTime = f.uint32
            case 8: p.rxSNR = f.float
            case 9: p.hopLimit = f.uint32
            case 10: p.wantAck = f.bool
            case 11: p.priority = f.uint32
            case 12: p.rxRSSI = f.int32
            case 14: p.viaMQTT = f.bool
            case 15: p.hopStart = f.uint32
            default: break
            }
        }
        return p
    }

    /// Hops the packet took to reach us, when the firmware reports hop_start.
    var hopsAway: UInt32? {
        guard hopStart > 0, hopStart >= hopLimit else { return nil }
        return hopStart - hopLimit
    }
}

// MARK: - User (id 1, long_name 2, short_name 3, hw_model 5, is_licensed 6,
// role 7, public_key 8)

struct MeshPBUser: Equatable, Sendable {
    var id = ""
    var longName = ""
    var shortName = ""
    var hwModel: UInt32 = 0
    var isLicensed = false
    var role: UInt32 = 0
    var publicKey = Data()

    func encode() -> Data {
        var w = MeshProtoWriter()
        w.writeStringIfNotEmpty(1, id)
        w.writeStringIfNotEmpty(2, longName)
        w.writeStringIfNotEmpty(3, shortName)
        w.writeUInt32IfNonZero(5, hwModel)
        w.writeBoolIfTrue(6, isLicensed)
        w.writeUInt32IfNonZero(7, role)
        w.writeBytesIfNotEmpty(8, publicKey)
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBUser {
        var u = MeshPBUser()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: u.id = f.string
            case 2: u.longName = f.string
            case 3: u.shortName = f.string
            case 5: u.hwModel = f.uint32
            case 6: u.isLicensed = f.bool
            case 7: u.role = f.uint32
            case 8: u.publicKey = f.bytes
            default: break
            }
        }
        return u
    }
}

// MARK: - Position (latitude_i 1 sfixed32, longitude_i 2 sfixed32,
// altitude 3 int32, time 4 fixed32). Degrees are stored as value * 1e-7.

struct MeshPBPosition: Equatable, Sendable {
    var latitudeI: Int32?
    var longitudeI: Int32?
    var altitude: Int32?
    var time: UInt32 = 0

    var latitude: Double? { latitudeI.map { Double($0) * 1e-7 } }
    var longitude: Double? { longitudeI.map { Double($0) * 1e-7 } }

    /// A usable fix: both coordinates present and not the 0,0 "no fix" placeholder.
    var hasFix: Bool {
        guard let la = latitudeI, let lo = longitudeI else { return false }
        return !(la == 0 && lo == 0)
    }

    func encode() -> Data {
        var w = MeshProtoWriter()
        if let latitudeI { w.writeSFixed32(1, latitudeI) }
        if let longitudeI { w.writeSFixed32(2, longitudeI) }
        if let altitude { w.writeInt32(3, altitude) }
        w.writeFixed32IfNonZero(4, time)
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBPosition {
        var p = MeshPBPosition()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: p.latitudeI = f.sfixed32
            case 2: p.longitudeI = f.sfixed32
            case 3: p.altitude = f.int32
            case 4: p.time = f.uint32
            default: break
            }
        }
        return p
    }
}

// MARK: - DeviceMetrics (battery_level 1, voltage 2 float)

struct MeshPBDeviceMetrics: Equatable, Sendable {
    var batteryLevel: UInt32?
    var voltage: Float?

    func encode() -> Data {
        var w = MeshProtoWriter()
        if let batteryLevel { w.writeUInt32(1, batteryLevel) }
        if let voltage { w.writeFloat(2, voltage) }
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBDeviceMetrics {
        var m = MeshPBDeviceMetrics()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: m.batteryLevel = f.uint32
            case 2: m.voltage = f.float
            default: break
            }
        }
        return m
    }
}

// MARK: - NodeInfo (num 1, user 2, position 3, snr 4 float, last_heard 5
// fixed32, device_metrics 6, channel 7, via_mqtt 8, hops_away 9)

struct MeshPBNodeInfo: Equatable, Sendable {
    var num: UInt32 = 0
    var user: MeshPBUser?
    var position: MeshPBPosition?
    var snr: Float = 0
    var lastHeard: UInt32 = 0
    var deviceMetrics: MeshPBDeviceMetrics?
    var channel: UInt32 = 0
    var viaMQTT = false
    var hopsAway: UInt32?

    func encode() -> Data {
        var w = MeshProtoWriter()
        w.writeUInt32IfNonZero(1, num)
        if let user { w.writeBytes(2, user.encode()) }
        if let position { w.writeBytes(3, position.encode()) }
        if snr != 0 { w.writeFloat(4, snr) }
        w.writeFixed32IfNonZero(5, lastHeard)
        if let deviceMetrics { w.writeBytes(6, deviceMetrics.encode()) }
        w.writeUInt32IfNonZero(7, channel)
        w.writeBoolIfTrue(8, viaMQTT)
        if let hopsAway { w.writeUInt32(9, hopsAway) }
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBNodeInfo {
        var n = MeshPBNodeInfo()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: n.num = f.uint32
            case 2: n.user = try MeshPBUser.decode(f.bytes)
            case 3: n.position = try MeshPBPosition.decode(f.bytes)
            case 4: n.snr = f.float
            case 5: n.lastHeard = f.uint32
            case 6: n.deviceMetrics = try MeshPBDeviceMetrics.decode(f.bytes)
            case 7: n.channel = f.uint32
            case 8: n.viaMQTT = f.bool
            case 9: n.hopsAway = f.uint32
            default: break
            }
        }
        return n
    }
}

// MARK: - MyNodeInfo (my_node_num 1, reboot_count 8, min_app_version 11)

struct MeshPBMyNodeInfo: Equatable, Sendable {
    var myNodeNum: UInt32 = 0
    var rebootCount: UInt32 = 0
    var minAppVersion: UInt32 = 0

    func encode() -> Data {
        var w = MeshProtoWriter()
        w.writeUInt32IfNonZero(1, myNodeNum)
        w.writeUInt32IfNonZero(8, rebootCount)
        w.writeUInt32IfNonZero(11, minAppVersion)
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBMyNodeInfo {
        var m = MeshPBMyNodeInfo()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: m.myNodeNum = f.uint32
            case 8: m.rebootCount = f.uint32
            case 11: m.minAppVersion = f.uint32
            default: break
            }
        }
        return m
    }
}

// MARK: - Channel (index 1 int32, settings 2, role 3) and
// ChannelSettings (psk 2 bytes, name 3 string)

struct MeshPBChannel: Equatable, Sendable {
    enum Role: UInt32, Sendable { case disabled = 0, primary = 1, secondary = 2 }

    var index: Int32 = 0
    var name = ""
    var hasPSK = false
    var role: UInt32 = 0

    func encode() -> Data {
        var w = MeshProtoWriter()
        if index != 0 { w.writeInt32(1, index) }
        w.writeMessage(2) { s in
            if hasPSK { s.writeBytes(2, Data([1])) }
            s.writeStringIfNotEmpty(3, name)
        }
        w.writeUInt32IfNonZero(3, role)
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBChannel {
        var c = MeshPBChannel()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: c.index = f.int32
            case 2:
                try MeshProtoReader.forEachField(in: f.bytes) { s in
                    switch s.number {
                    case 2: c.hasPSK = !s.bytes.isEmpty
                    case 3: c.name = s.string
                    default: break
                    }
                }
            case 3: c.role = f.uint32
            default: break
            }
        }
        return c
    }
}

// MARK: - DeviceMetadata (firmware_version 1, role 7, hw_model 9, hasPKC 11)

struct MeshPBDeviceMetadata: Equatable, Sendable {
    var firmwareVersion = ""
    var role: UInt32 = 0
    var hwModel: UInt32 = 0
    var hasPKC = false

    func encode() -> Data {
        var w = MeshProtoWriter()
        w.writeStringIfNotEmpty(1, firmwareVersion)
        w.writeUInt32IfNonZero(7, role)
        w.writeUInt32IfNonZero(9, hwModel)
        w.writeBoolIfTrue(11, hasPKC)
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBDeviceMetadata {
        var m = MeshPBDeviceMetadata()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: m.firmwareVersion = f.string
            case 7: m.role = f.uint32
            case 9: m.hwModel = f.uint32
            case 11: m.hasPKC = f.bool
            default: break
            }
        }
        return m
    }
}

// MARK: - Config.lora (Config field 6) -> LoRaConfig (modem_preset 2,
// region 7, hop_limit 8). Region 0 = UNSET, 1 = US (902-928 MHz).

struct MeshPBLoRaConfig: Equatable, Sendable {
    var modemPreset: UInt32 = 0
    var region: UInt32 = 0
    var hopLimit: UInt32 = 0

    var regionName: String {
        switch region {
        case 0: return "Not set"
        case 1: return "US 915 MHz"
        case 2: return "EU 433 MHz"
        case 3: return "EU 868 MHz"
        case 6: return "ANZ"
        default: return "Region \(region)"
        }
    }

    /// Encodes as a full `Config` message with only the lora variant set.
    func encodeAsConfig() -> Data {
        var w = MeshProtoWriter()
        w.writeMessage(6) { l in
            l.writeUInt32IfNonZero(2, modemPreset)
            l.writeUInt32IfNonZero(7, region)
            l.writeUInt32IfNonZero(8, hopLimit)
        }
        return w.data
    }

    /// Decodes a `Config` message; returns nil when it is not the lora variant.
    static func decode(config data: Data) throws -> MeshPBLoRaConfig? {
        var result: MeshPBLoRaConfig?
        try MeshProtoReader.forEachField(in: data) { f in
            guard f.number == 6 else { return }
            var l = MeshPBLoRaConfig()
            try MeshProtoReader.forEachField(in: f.bytes) { s in
                switch s.number {
                case 2: l.modemPreset = s.uint32
                case 7: l.region = s.uint32
                case 8: l.hopLimit = s.uint32
                default: break
                }
            }
            result = l
        }
        return result
    }
}

// MARK: - Routing (route_request 1, route_reply 2, error_reason 3)

struct MeshPBRouting: Equatable, Sendable {
    /// nil when the message is a route request/reply rather than an ack/nak.
    var errorReason: UInt32?

    func encode() -> Data {
        var w = MeshProtoWriter()
        // error_reason is a oneof member, so even NONE (0) is written.
        if let errorReason { w.writeUInt32(3, errorReason) }
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBRouting {
        var r = MeshPBRouting()
        var sawRoute = false
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1, 2: sawRoute = true
            case 3: r.errorReason = f.uint32
            default: break
            }
        }
        // An empty Routing body carries no error: treat it as an ACK.
        if r.errorReason == nil && !sawRoute { r.errorReason = 0 }
        return r
    }
}

// MARK: - QueueStatus (res 1 int32, free 2, maxlen 3, mesh_packet_id 4)

struct MeshPBQueueStatus: Equatable, Sendable {
    var res: Int32 = 0
    var free: UInt32 = 0
    var maxlen: UInt32 = 0
    var meshPacketID: UInt32 = 0

    func encode() -> Data {
        var w = MeshProtoWriter()
        if res != 0 { w.writeInt32(1, res) }
        w.writeUInt32IfNonZero(2, free)
        w.writeUInt32IfNonZero(3, maxlen)
        w.writeUInt32IfNonZero(4, meshPacketID)
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBQueueStatus {
        var q = MeshPBQueueStatus()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: q.res = f.int32
            case 2: q.free = f.uint32
            case 3: q.maxlen = f.uint32
            case 4: q.meshPacketID = f.uint32
            default: break
            }
        }
        return q
    }
}

// MARK: - FromRadio (id 1, packet 2, my_info 3, node_info 4, config 5,
// log_record 6, config_complete_id 7, rebooted 8, channel 10, queueStatus 11,
// metadata 13, clientNotification 16 -> message 4)

enum MeshPBFromRadioVariant: Equatable, Sendable {
    case packet(MeshPBPacket)
    case myInfo(MeshPBMyNodeInfo)
    case nodeInfo(MeshPBNodeInfo)
    case loraConfig(MeshPBLoRaConfig)
    case otherConfig
    case logRecord(String)
    case configCompleteID(UInt32)
    case rebooted
    case channel(MeshPBChannel)
    case queueStatus(MeshPBQueueStatus)
    case metadata(MeshPBDeviceMetadata)
    case clientNotification(String)
    /// A variant we do not model (module config, file info, and so on).
    case unhandled(field: Int)
}

struct MeshPBFromRadio: Equatable, Sendable {
    var id: UInt32 = 0
    var variant: MeshPBFromRadioVariant?

    func encode() -> Data {
        var w = MeshProtoWriter()
        w.writeUInt32IfNonZero(1, id)
        switch variant {
        case .packet(let p)?: w.writeBytes(2, p.encode())
        case .myInfo(let m)?: w.writeBytes(3, m.encode())
        case .nodeInfo(let n)?: w.writeBytes(4, n.encode())
        case .loraConfig(let l)?: w.writeBytes(5, l.encodeAsConfig())
        case .otherConfig?: w.writeMessage(5) { $0.writeMessage(1) { _ in } }
        case .logRecord(let s)?: w.writeMessage(6) { $0.writeString(1, s) }
        case .configCompleteID(let id)?: w.writeUInt32(7, id)
        case .rebooted?: w.writeBool(8, true)
        case .channel(let c)?: w.writeBytes(10, c.encode())
        case .queueStatus(let q)?: w.writeBytes(11, q.encode())
        case .metadata(let m)?: w.writeBytes(13, m.encode())
        case .clientNotification(let s)?: w.writeMessage(16) { $0.writeString(4, s) }
        case .unhandled(let field)?: w.writeBytes(field, Data())
        case nil: break
        }
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBFromRadio {
        var r = MeshPBFromRadio()
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: r.id = f.uint32
            case 2: r.variant = .packet(try MeshPBPacket.decode(f.bytes))
            case 3: r.variant = .myInfo(try MeshPBMyNodeInfo.decode(f.bytes))
            case 4: r.variant = .nodeInfo(try MeshPBNodeInfo.decode(f.bytes))
            case 5:
                if let lora = try MeshPBLoRaConfig.decode(config: f.bytes) {
                    r.variant = .loraConfig(lora)
                } else {
                    r.variant = .otherConfig
                }
            case 6:
                var msg = ""
                try MeshProtoReader.forEachField(in: f.bytes) { s in if s.number == 1 { msg = s.string } }
                r.variant = .logRecord(msg)
            case 7: r.variant = .configCompleteID(f.uint32)
            case 8: r.variant = .rebooted
            case 10: r.variant = .channel(try MeshPBChannel.decode(f.bytes))
            case 11: r.variant = .queueStatus(try MeshPBQueueStatus.decode(f.bytes))
            case 13: r.variant = .metadata(try MeshPBDeviceMetadata.decode(f.bytes))
            case 16:
                var msg = ""
                try MeshProtoReader.forEachField(in: f.bytes) { s in if s.number == 4 { msg = s.string } }
                r.variant = .clientNotification(msg)
            default:
                r.variant = .unhandled(field: f.number)
            }
        }
        return r
    }
}

// MARK: - ToRadio (packet 1, want_config_id 3, disconnect 4, heartbeat 7)

enum MeshPBToRadio: Equatable, Sendable {
    case packet(MeshPBPacket)
    case wantConfigID(UInt32)
    case disconnect
    case heartbeat(nonce: UInt32)

    func encode() -> Data {
        var w = MeshProtoWriter()
        switch self {
        case .packet(let p): w.writeBytes(1, p.encode())
        case .wantConfigID(let id): w.writeUInt32(3, id)
        case .disconnect: w.writeBool(4, true)
        case .heartbeat(let nonce): w.writeMessage(7) { $0.writeUInt32IfNonZero(1, nonce) }
        }
        return w.data
    }

    static func decode(_ data: Data) throws -> MeshPBToRadio? {
        var result: MeshPBToRadio?
        try MeshProtoReader.forEachField(in: data) { f in
            switch f.number {
            case 1: result = .packet(try MeshPBPacket.decode(f.bytes))
            case 3: result = .wantConfigID(f.uint32)
            case 4: result = .disconnect
            case 7:
                var nonce: UInt32 = 0
                try MeshProtoReader.forEachField(in: f.bytes) { s in if s.number == 1 { nonce = s.uint32 } }
                result = .heartbeat(nonce: nonce)
            default: break
            }
        }
        return result
    }
}
