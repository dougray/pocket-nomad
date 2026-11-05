// MeshCoreProtocol.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Encoder and decoder for MeshCore's companion radio protocol (the BLE link
// between a phone and a MeshCore radio), written from MeshCore's MIT-licensed
// docs/companion_protocol.md and examples/companion_radio/MyMesh.cpp.
//
// Each BLE write is one command frame and each notification is one response
// frame: [code: 1 byte][payload]. Integers are little-endian, text is UTF-8.

import Foundation

enum MeshCoreCommand {
    /// First byte of each command frame.
    enum Code {
        static let appStart: UInt8 = 1
        static let sendText: UInt8 = 2
        static let sendChannelText: UInt8 = 3
        static let getContacts: UInt8 = 4
        static let setDeviceTime: UInt8 = 6
        static let sendSelfAdvert: UInt8 = 7
        static let syncNextMessage: UInt8 = 10
        /// CMD_SET_RADIO_PARAMS, CMD_SET_RADIO_TX_POWER and CMD_REBOOT
        /// (examples/companion_radio/MyMesh.cpp, tag companion-v1.18.0).
        static let setRadioParams: UInt8 = 11
        static let setRadioTxPower: UInt8 = 12
        static let reboot: UInt8 = 19
        static let deviceQuery: UInt8 = 22
        static let getChannel: UInt8 = 31
        /// CMD_SEND_TRACE_PATH (MyMesh.cpp, companion-v1.18.0).
        static let sendTracePath: UInt8 = 36
    }

    /// The protocol version this app understands; asks for the V3 message formats.
    static let appProtocolVersion: UInt8 = 3

    static func appStart(appName: String) -> Data {
        Data([Code.appStart, 0, 0, 0, 0, 0, 0, 0]) + Data(appName.utf8)
    }

    static func deviceQuery() -> Data { Data([Code.deviceQuery, appProtocolVersion]) }

    static func setDeviceTime(_ date: Date) -> Data {
        var d = Data([Code.setDeviceTime])
        d.appendLE(UInt32(clamping: Int(date.timeIntervalSince1970)))
        return d
    }

    static func getContacts() -> Data { Data([Code.getContacts]) }

    /// CMD_SET_RADIO_PARAMS: [11][freq u32 kHz][bw u32 Hz][sf u8][cr u8], little-endian.
    /// The firmware stores freq/1000 as MHz and bw/1000 as kHz, checks
    /// 150000 <= freq <= 2500000, 7000 <= bw <= 500000, sf 5-12, cr 5-8, saves
    /// its prefs, retunes the radio and answers OK (0), else ERR (1) with
    /// ERR_CODE_ILLEGAL_ARG (6). An optional 11th byte (firmware v9+) turns on
    /// client repeat mode; it is left out, which the firmware reads as off.
    /// Source: MyMesh.cpp `cmd_frame[0] == CMD_SET_RADIO_PARAMS`, companion-v1.18.0.
    static func setRadioParams(_ p: MeshCoreRadioParams) -> Data {
        var d = Data([Code.setRadioParams])
        d.appendLE(p.frequencyKHz)
        d.appendLE(p.bandwidthHz)
        d.append(p.spreadingFactor)
        d.append(p.codingRate)
        return d
    }

    /// CMD_SET_RADIO_TX_POWER: [12][dBm as int8]. The firmware accepts
    /// -9 ... MAX_LORA_TX_POWER (SELF_INFO byte 3), saves, applies, answers OK.
    static func setRadioTxPower(_ dBm: Int8) -> Data {
        Data([Code.setRadioTxPower, UInt8(bitPattern: dBm)])
    }

    /// CMD_REBOOT: [19]"reboot". The firmware saves pending contacts and
    /// restarts at once without answering; the Bluetooth link drops.
    static func reboot() -> Data { Data([Code.reboot]) + Data("reboot".utf8) }

    static func getChannel(_ index: UInt8) -> Data { Data([Code.getChannel, index]) }

    static func syncNextMessage() -> Data { Data([Code.syncNextMessage]) }

    /// Flood-advertises this radio so others add it as a contact.
    static func sendSelfAdvert(flood: Bool = true) -> Data { Data([Code.sendSelfAdvert, flood ? 1 : 0]) }

    /// Direct text to a contact, addressed by the first 6 bytes of its public key.
    static func sendText(_ text: String, toKeyPrefix prefix: Data, date: Date, attempt: UInt8 = 0) -> Data {
        var d = Data([Code.sendText, MeshCoreTextType.plain, attempt])
        d.appendLE(UInt32(clamping: Int(date.timeIntervalSince1970)))
        d.append(prefix.prefix(6))
        d.append(Data(text.utf8))
        return d
    }

    /// CMD_SEND_TRACE_PATH: [36][tag u32][auth u32][flags][path hashes]. Flags'
    /// low two bits are the hash size minus one. The radio answers SENT (6) with
    /// the tag in place of an ack, and PUSH_CODE_TRACE_DATA (0x89) with the same
    /// tag when the trace comes back. Repeaters answer traces without a login,
    /// which makes a trace to a repeater in range the MeshCore drive-test ping.
    static func sendTrace(tag: UInt32, path: Data) -> Data {
        var d = Data([Code.sendTracePath])
        d.appendLE(tag)
        d.appendLE(0)
        d.append(UInt8(max(1, path.count) - 1) & 0x03)
        d.append(path)
        return d
    }

    static func sendChannelText(_ text: String, channel: UInt8, date: Date) -> Data {
        var d = Data([Code.sendChannelText, MeshCoreTextType.plain, channel])
        d.appendLE(UInt32(clamping: Int(date.timeIntervalSince1970)))
        d.append(Data(text.utf8))
        return d
    }
}

enum MeshCoreTextType {
    static let plain: UInt8 = 0
    static let signedPlain: UInt8 = 2
}

/// Contact kinds from the radio's adverts.
enum MeshCoreContactType: UInt8, Codable, Sendable {
    case none = 0, chat = 1, repeater = 2, room = 3, sensor = 4
}

struct MeshCoreSelfInfo: Equatable, Sendable {
    /// SELF_INFO bytes 2 and 3: the radio's TX power and its maximum, in dBm.
    var txPowerDBm: Int8 = 0
    var maxTxPowerDBm: Int8 = 0
    var publicKey: Data
    var latitude: Double?
    var longitude: Double?
    var frequencyMHz: Double
    var bandwidthKHz: Double
    var spreadingFactor: UInt8
    var codingRate: UInt8
    var name: String

    /// The radio settings, as SELF_INFO reports them (freq u32 = MHz x 1000,
    /// bw u32 = kHz x 1000; docs/companion_protocol.md, PACKET_SELF_INFO).
    var radioParams: MeshCoreRadioParams {
        MeshCoreRadioParams(frequencyKHz: UInt32((frequencyMHz * 1000).rounded()),
                            bandwidthHz: UInt32((bandwidthKHz * 1000).rounded()),
                            spreadingFactor: spreadingFactor, codingRate: codingRate)
    }
}

struct MeshCoreDeviceInfo: Equatable, Sendable {
    var firmwareVersionCode: UInt8
    var maxContacts: Int
    var maxChannels: Int
    var firmwareBuild: String
    var model: String
    var version: String
}

struct MeshCoreContact: Equatable, Sendable {
    var publicKey: Data
    var type: MeshCoreContactType
    var name: String
    var lastAdvert: Date?
    var latitude: Double?
    var longitude: Double?
    /// Hops on the stored route, nil when the radio would flood.
    var pathLength: UInt8?
}

struct MeshCoreChannelInfo: Equatable, Sendable {
    var index: UInt8
    var name: String
    var secret: Data
    /// Unused slots have no name and an all-zero secret.
    var isEmpty: Bool { name.isEmpty && secret.allSatisfy { $0 == 0 } }
}

struct MeshCoreDirectMessage: Equatable, Sendable {
    var senderKeyPrefix: Data
    var pathLength: UInt8
    var timestamp: UInt32
    var text: String
    var snr: Float?
}

struct MeshCoreChannelMessage: Equatable, Sendable {
    var channel: UInt8
    var pathLength: UInt8
    var timestamp: UInt32
    /// The sender's name, which MeshCore puts in front of the text ("Ana: hi").
    var senderName: String?
    var text: String
    var snr: Float?
}

/// One frame from the radio.
enum MeshCoreFrame: Equatable, Sendable {
    case ok(UInt32?)
    case error(UInt8?)
    case contactsStart(count: UInt32)
    case contact(MeshCoreContact)
    case endOfContacts
    case selfInfo(MeshCoreSelfInfo)
    /// A text left this radio. `expectedAck` matches a later `sendConfirmed`
    /// (0 for channel messages, which are never acknowledged).
    case sent(flood: Bool, expectedAck: UInt32, timeoutMS: UInt32)
    case directMessage(MeshCoreDirectMessage)
    case channelMessage(MeshCoreChannelMessage)
    case noMoreMessages
    case deviceInfo(MeshCoreDeviceInfo)
    case channelInfo(MeshCoreChannelInfo)
    /// A known contact advertised again.
    case advert(publicKey: Data)
    /// A new contact was heard (and added, unless the radio is in manual-add mode).
    case newAdvert(MeshCoreContact)
    case sendConfirmed(ack: UInt32, roundTripMS: UInt32)
    case messagesWaiting
    /// PUSH_CODE_LOG_RX_DATA: every packet the radio's LoRa receiver decoded.
    case rxLog(MeshCoreRxLog)
    /// PUSH_CODE_TRACE_DATA: a trace this radio sent came back.
    case traceData(tag: UInt32, finalSNR: Float)
    case other(UInt8)

    static func decode(_ data: Data) -> MeshCoreFrame? {
        let r = MeshCoreReader(data)
        guard let code = r.u8() else { return nil }
        switch code {
        case 0: return .ok(r.u32())
        case 1: return .error(r.u8())
        case 2: return .contactsStart(count: r.u32() ?? 0)
        case 3: return decodeContact(r).map { .contact($0) }
        case 4: return .endOfContacts
        case 5: return decodeSelfInfo(r).map { .selfInfo($0) }
        case 6:
            guard let route = r.u8(), let ack = r.u32() else { return nil }
            return .sent(flood: route == 1, expectedAck: ack, timeoutMS: r.u32() ?? 0)
        case 7, 16: return decodeDirect(r, v3: code == 16).map { .directMessage($0) }
        case 8, 17: return decodeChannel(r, v3: code == 17).map { .channelMessage($0) }
        case 10: return .noMoreMessages
        case 13: return decodeDeviceInfo(r).map { .deviceInfo($0) }
        case 18:
            guard let index = r.u8(), let name = r.fixedString(32), let secret = r.bytes(16) else { return nil }
            return .channelInfo(MeshCoreChannelInfo(index: index, name: name, secret: secret))
        case 0x80:
            guard let key = r.bytes(32) else { return nil }
            return .advert(publicKey: key)
        case 0x8A: return decodeContact(r).map { .newAdvert($0) }
        case 0x82:
            guard let ack = r.u32() else { return nil }
            return .sendConfirmed(ack: ack, roundTripMS: r.u32() ?? 0)
        case 0x83: return .messagesWaiting
        case 0x88:
            // [0x88][snr*4 i8][rssi i8][raw packet] (MyMesh::logRxRaw).
            guard let snr = r.i8(), let rssi = r.i8() else { return nil }
            return .rxLog(MeshCoreRxLog(snr: Float(snr) / 4, rssi: rssi, packet: MeshCoreRawPacket(r.rest())))
        case 0x89:
            // [0x89][reserved][path len][flags][tag u32][auth u32][hashes][snrs][final snr]
            guard r.u8() != nil, let pathLen = r.u8(), let flags = r.u8(), let tag = r.u32(), r.u32() != nil,
                  r.bytes(Int(pathLen)) != nil, r.bytes(Int(pathLen) >> Int(flags & 0x03)) != nil,
                  let final = r.i8() else { return nil }
            return .traceData(tag: tag, finalSNR: Float(final) / 4)
        default: return .other(code)
        }
    }

    private static func decodeContact(_ r: MeshCoreReader) -> MeshCoreContact? {
        guard let key = r.bytes(32), let type = r.u8(), r.u8() != nil /* flags */,
              let outPathLen = r.u8(), r.bytes(64) != nil /* path */,
              let name = r.fixedString(32), let advert = r.u32(),
              let lat = r.i32(), let lon = r.i32() else { return nil }
        let hasPosition = lat != 0 || lon != 0
        return MeshCoreContact(
            publicKey: key,
            type: MeshCoreContactType(rawValue: type) ?? .none,
            name: name,
            lastAdvert: advert == 0 ? nil : Date(timeIntervalSince1970: TimeInterval(advert)),
            latitude: hasPosition ? Double(lat) / 1e6 : nil,
            longitude: hasPosition ? Double(lon) / 1e6 : nil,
            // 0xFF means "no known route, flood".
            pathLength: outPathLen == 0xFF ? nil : outPathLen & 0x3F
        )
    }

    private static func decodeSelfInfo(_ r: MeshCoreReader) -> MeshCoreSelfInfo? {
        guard r.u8() != nil, let tx = r.i8(), let maxTx = r.i8(), // advert type, tx power, max tx power
              let key = r.bytes(32), let lat = r.i32(), let lon = r.i32(),
              r.bytes(4) != nil, // multi-acks, location policy, telemetry, manual add
              let freq = r.u32(), let bw = r.u32(), let sf = r.u8(), let cr = r.u8() else { return nil }
        let hasPosition = lat != 0 || lon != 0
        return MeshCoreSelfInfo(
            txPowerDBm: tx, maxTxPowerDBm: maxTx,
            publicKey: key,
            latitude: hasPosition ? Double(lat) / 1e6 : nil,
            longitude: hasPosition ? Double(lon) / 1e6 : nil,
            frequencyMHz: Double(freq) / 1000,
            bandwidthKHz: Double(bw) / 1000,
            spreadingFactor: sf, codingRate: cr,
            name: r.restString()
        )
    }

    private static func decodeDeviceInfo(_ r: MeshCoreReader) -> MeshCoreDeviceInfo? {
        guard let version = r.u8() else { return nil }
        var info = MeshCoreDeviceInfo(firmwareVersionCode: version, maxContacts: 0, maxChannels: 0,
                                      firmwareBuild: "", model: "", version: "")
        guard version >= 3, let contacts = r.u8(), let channels = r.u8(), r.u32() != nil /* BLE PIN */,
              let build = r.fixedString(12), let model = r.fixedString(40), let ver = r.fixedString(20)
        else { return info }
        info.maxContacts = Int(contacts) * 2
        info.maxChannels = Int(channels)
        info.firmwareBuild = build
        info.model = model
        info.version = ver
        return info
    }

    private static func decodeDirect(_ r: MeshCoreReader, v3: Bool) -> MeshCoreDirectMessage? {
        var snr: Float?
        if v3 {
            guard let s = r.i8(), r.bytes(2) != nil else { return nil }
            snr = Float(s) / 4
        }
        guard let prefix = r.bytes(6), let path = r.u8(), let type = r.u8(), let ts = r.u32() else { return nil }
        if type == MeshCoreTextType.signedPlain { guard r.bytes(4) != nil else { return nil } }
        return MeshCoreDirectMessage(senderKeyPrefix: prefix, pathLength: path, timestamp: ts, text: r.restString(), snr: snr)
    }

    private static func decodeChannel(_ r: MeshCoreReader, v3: Bool) -> MeshCoreChannelMessage? {
        var snr: Float?
        if v3 {
            guard let s = r.i8(), r.bytes(2) != nil else { return nil }
            snr = Float(s) / 4
        }
        guard let channel = r.u8(), let path = r.u8(), r.u8() != nil, let ts = r.u32() else { return nil }
        let (sender, text) = splitSender(r.restString())
        return MeshCoreChannelMessage(channel: channel, pathLength: path, timestamp: ts,
                                      senderName: sender, text: text, snr: snr)
    }

    /// "Ana: meet at the bridge" → ("Ana", "meet at the bridge").
    static func splitSender(_ raw: String) -> (String?, String) {
        guard let range = raw.range(of: ": "), raw.distance(from: raw.startIndex, to: range.lowerBound) <= 32 else {
            return (nil, raw)
        }
        let name = String(raw[..<range.lowerBound])
        guard !name.isEmpty, !name.contains("\n") else { return (nil, raw) }
        return (name, String(raw[range.upperBound...]))
    }
}

/// Bounds-checked little-endian reader. Every read returns nil past the end.
final class MeshCoreReader {
    private let bytes: [UInt8]
    private var pos = 0

    init(_ data: Data) { bytes = [UInt8](data) }

    func u8() -> UInt8? {
        guard pos < bytes.count else { return nil }
        defer { pos += 1 }
        return bytes[pos]
    }

    func i8() -> Int8? { u8().map { Int8(bitPattern: $0) } }

    func u32() -> UInt32? {
        guard let b = bytes(4) else { return nil }
        return b.enumerated().reduce(0) { $0 | UInt32($1.element) << (8 * $1.offset) }
    }

    func i32() -> Int32? { u32().map { Int32(bitPattern: $0) } }

    func bytes(_ n: Int) -> Data? {
        guard n >= 0, pos + n <= bytes.count else { return nil }
        defer { pos += n }
        return Data(bytes[pos ..< pos + n])
    }

    /// A NUL-padded fixed-width string field.
    func fixedString(_ n: Int) -> String? {
        guard let raw = bytes(n) else { return nil }
        let end = raw.firstIndex(of: 0) ?? raw.endIndex
        return String(decoding: raw[raw.startIndex ..< end], as: UTF8.self).trimmingCharacters(in: .whitespaces)
    }

    /// Everything left, as bytes.
    func rest() -> Data {
        defer { pos = bytes.count }
        return pos < bytes.count ? Data(bytes[pos...]) : Data()
    }

    /// Everything left, as text (trailing NULs dropped).
    func restString() -> String {
        let rest = pos < bytes.count ? Array(bytes[pos...]) : []
        pos = bytes.count
        let end = rest.firstIndex(of: 0) ?? rest.endIndex
        return String(decoding: rest[..<end], as: UTF8.self)
    }
}

extension MeshCoreContact {
    /// Node number used across the app: the key's first four bytes, little-endian.
    var nodeNum: UInt32 { MeshCoreContact.nodeNum(for: publicKey) }

    static func nodeNum(for key: Data) -> UInt32 {
        let b = [UInt8](key.prefix(4))
        guard b.count == 4 else { return 0 }
        return UInt32(b[0]) | UInt32(b[1]) << 8 | UInt32(b[2]) << 16 | UInt32(b[3]) << 24
    }

    /// Stand-in node number for a repeater known only by its path hash (1-3
    /// key bytes): the hash padded with zeros.
    static func nodeNum(forHash hash: Data) -> UInt32 {
        nodeNum(for: hash.prefix(3) + Data(repeating: 0, count: 4 - min(3, hash.count)))
    }
}

/// One packet as the radio heard it off the air.
struct MeshCoreRxLog: Equatable, Sendable {
    var snr: Float
    var rssi: Int8
    /// Nil when the bytes don't parse as a MeshCore packet.
    var packet: MeshCoreRawPacket?
}

/// A raw over-the-air MeshCore packet (src/Packet.cpp `readFrom`):
/// [header][transport codes: 4 bytes, transport routes only][path len][path][payload].
/// The header's low two bits are the route type and the next four the payload
/// type; the path length's low six bits count the hashes and its top two bits
/// are the hash size minus one.
struct MeshCoreRawPacket: Equatable, Sendable {
    static let payloadAdvert: UInt8 = 0x04

    var isFlood: Bool
    var payloadType: UInt8
    /// Repeater hashes, in the order the packet passed them.
    var path: [Data]
    var payload: Data

    init?(_ raw: Data) {
        let b = [UInt8](raw)
        guard let header = b.first else { return nil }
        let route = header & 0x03
        var i = 1
        if route == 0x00 || route == 0x03 { i += 4 } // transport codes
        guard i < b.count else { return nil }
        let pathLen = b[i]; i += 1
        let size = Int(pathLen >> 6) + 1, count = Int(pathLen & 0x3F)
        guard size <= 3, i + size * count < b.count else { return nil }
        isFlood = route == 0x00 || route == 0x01
        payloadType = (header >> 2) & 0x0F
        path = (0 ..< count).map { Data(b[(i + $0 * size) ..< (i + ($0 + 1) * size)]) }
        payload = Data(b[(i + size * count)...])
    }

    /// Who sent the packet we just heard: in flood mode each repeater adds its
    /// hash before passing a packet on, so the last hash is the repeater in
    /// range of us. A packet with no path came straight from its sender, which
    /// only an advert names in full. Direct-routed packets drop hashes as they
    /// go, so their sender is unknown.
    var heardFrom: HeardFrom? {
        if isFlood, let last = path.last { return .repeater(hash: last) }
        if path.isEmpty, payloadType == Self.payloadAdvert, payload.count >= 32 { return .node(publicKey: payload.prefix(32)) }
        return nil
    }

    enum HeardFrom: Equatable, Sendable {
        case repeater(hash: Data)
        case node(publicKey: Data)
    }
}

private extension Data {
    mutating func appendLE(_ v: UInt32) {
        Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) }
    }
}
