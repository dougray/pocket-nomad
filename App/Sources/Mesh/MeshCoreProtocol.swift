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
        static let deviceQuery: UInt8 = 22
        static let getChannel: UInt8 = 31
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
    var publicKey: Data
    var latitude: Double?
    var longitude: Double?
    var frequencyMHz: Double
    var bandwidthKHz: Double
    var spreadingFactor: UInt8
    var codingRate: UInt8
    var name: String
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
        guard r.u8() != nil, r.u8() != nil, r.u8() != nil, // advert type, tx power, max tx power
              let key = r.bytes(32), let lat = r.i32(), let lon = r.i32(),
              r.bytes(4) != nil, // multi-acks, location policy, telemetry, manual add
              let freq = r.u32(), let bw = r.u32(), let sf = r.u8(), let cr = r.u8() else { return nil }
        let hasPosition = lat != 0 || lon != 0
        return MeshCoreSelfInfo(
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
}

private extension Data {
    mutating func appendLE(_ v: UInt32) {
        Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) }
    }
}
