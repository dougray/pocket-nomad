// MeshCoreTests.swift
// Pocket Nomad - Mesh module tests
// SPDX-License-Identifier: Apache-2.0
//
// MeshCore companion protocol: frames built byte for byte to the firmware's
// layout (examples/companion_radio/MyMesh.cpp), and a full session against a
// scripted MeshCore radio.

import XCTest
#if canImport(NomadPocket)
@testable import NomadPocket
#endif

/// Builds radio-side frames the way MeshCore firmware writes them.
enum CoreFrames {
    static func le(_ v: UInt32) -> [UInt8] { [UInt8(v & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 24)] }
    static func le(_ v: Int32) -> [UInt8] { le(UInt32(bitPattern: v)) }
    static func padded(_ s: String, _ n: Int) -> [UInt8] {
        let b = Array(s.utf8.prefix(n))
        return b + [UInt8](repeating: 0, count: n - b.count)
    }

    static func key(_ first: UInt8) -> [UInt8] { [first, 0x22, 0x33, 0x44, 0x55, 0x66] + [UInt8](repeating: first, count: 26) }

    static func contact(code: UInt8 = 3, key: [UInt8], name: String, type: UInt8 = 1, pathLen: UInt8 = 2,
                        lat: Int32 = 39_739_200, lon: Int32 = -104_990_300, advert: UInt32 = 1_790_000_000) -> Data {
        Data([code] + key + [type, 0, pathLen] + [UInt8](repeating: 0, count: 64) + padded(name, 32)
             + le(advert) + le(lat) + le(lon) + le(UInt32(1)))
    }

    static func selfInfo(key: [UInt8], name: String) -> Data {
        Data([5, 1, 20, 22] + key + le(Int32(0)) + le(Int32(0)) + [0, 0, 0, 0]
             + le(UInt32(910_525)) + le(UInt32(62_500)) + [7, 5] + Array(name.utf8))
    }

    static func deviceInfo(maxChannels: UInt8) -> Data {
        Data([13, 9, 50, maxChannels] + le(UInt32(123456)) + padded("01 Oct 2026", 12)
             + padded("Heltec V4", 40) + padded("v1.12.0", 20) + [0, 0])
    }

    static func channelInfo(_ index: UInt8, _ name: String) -> Data {
        Data([18, index] + padded(name, 32) + [UInt8](repeating: name.isEmpty ? 0 : 0x8B, count: 16))
    }

    static func channelMessageV3(channel: UInt8, text: String, ts: UInt32, snr: Int8 = 22) -> Data {
        Data([17, UInt8(bitPattern: snr), 0, 0, channel, 1, 0] + le(ts) + Array(text.utf8))
    }

    static func directMessageV3(prefix: [UInt8], text: String, ts: UInt32, signed: Bool = false) -> Data {
        Data([16, 20, 0, 0] + prefix + [0xFF, signed ? 2 : 0] + le(ts) + (signed ? [9, 9, 9, 9] : []) + Array(text.utf8))
    }

    static func sent(ack: UInt32) -> Data { Data([6, 0] + le(ack) + le(UInt32(5000))) }
    static func sendConfirmed(ack: UInt32) -> Data { Data([0x82] + le(ack) + le(UInt32(800))) }

    /// An over-the-air packet: header (route | payload type << 2), path length, path, payload.
    static func packet(route: UInt8, type: UInt8, path: [[UInt8]] = [], payload: [UInt8]) -> [UInt8] {
        let size = UInt8(path.first?.count ?? 1)
        return [route | type << 2] + (route == 0 || route == 3 ? [9, 9, 9, 9] : [])
            + [(size - 1) << 6 | UInt8(path.count)] + path.flatMap { $0 } + payload
    }

    static func rxLog(snr: Float, rssi: Int8 = -100, _ raw: [UInt8]) -> Data {
        Data([0x88, UInt8(bitPattern: Int8(snr * 4)), UInt8(bitPattern: rssi)] + raw)
    }

    static func traceData(tag: UInt32, hashes: [UInt8]) -> Data {
        Data([0x89, 0, UInt8(hashes.count), 0] + le(tag) + le(UInt32(0)) + hashes
             + [UInt8](repeating: 24, count: hashes.count) + [UInt8(bitPattern: Int8(-12))])
    }
}

final class MeshCoreCodecTests: XCTestCase {
    func testRawPacketNamesWhoWeHeard() throws {
        // Flood text that passed two repeaters: the last one is in range of us.
        let relayed = try XCTUnwrap(MeshCoreRawPacket(Data(CoreFrames.packet(route: 1, type: 5, path: [[0x3A], [0xC7]], payload: [1, 2, 3]))))
        XCTAssertEqual(relayed.heardFrom, .repeater(hash: Data([0xC7])))
        XCTAssertEqual(relayed.payloadType, 5)
        XCTAssertEqual(relayed.payload, Data([1, 2, 3]))
        // Transport codes are skipped; two-byte hashes are read whole.
        let transport = try XCTUnwrap(MeshCoreRawPacket(Data(CoreFrames.packet(route: 0, type: 2, path: [[0x01, 0x02], [0xAB, 0xCD]], payload: [7]))))
        XCTAssertEqual(transport.heardFrom, .repeater(hash: Data([0xAB, 0xCD])))
        // An advert straight from its sender names it in full.
        let key = CoreFrames.key(0x5E)
        let advert = try XCTUnwrap(MeshCoreRawPacket(Data(CoreFrames.packet(route: 1, type: 4, payload: key + [0, 0, 0, 0]))))
        XCTAssertEqual(advert.heardFrom, .node(publicKey: Data(key)))
        // Direct routes drop hashes as they go, so the sender is unknown.
        let direct = try XCTUnwrap(MeshCoreRawPacket(Data(CoreFrames.packet(route: 2, type: 2, path: [[0x44]], payload: [1]))))
        XCTAssertNil(direct.heardFrom)
        // Too short for its path.
        XCTAssertNil(MeshCoreRawPacket(Data([0x05, 0x03, 0x01])))
    }

    func testReceiveLogAndTraceFramesDecode() {
        guard case .rxLog(let log)? = MeshCoreFrame.decode(CoreFrames.rxLog(snr: 6.25, rssi: -97, CoreFrames.packet(route: 1, type: 5, path: [[0xC7]], payload: [1]))) else {
            return XCTFail("expected a receive log")
        }
        XCTAssertEqual(log.snr, 6.25)
        XCTAssertEqual(log.rssi, -97)
        XCTAssertEqual(log.packet?.heardFrom, .repeater(hash: Data([0xC7])))
        guard case .traceData(let tag, let snr)? = MeshCoreFrame.decode(CoreFrames.traceData(tag: 0x1234_5678, hashes: [0xC7])) else {
            return XCTFail("expected trace data")
        }
        XCTAssertEqual(tag, 0x1234_5678)
        XCTAssertEqual(snr, -3)
        XCTAssertEqual([UInt8](MeshCoreCommand.sendTrace(tag: 0x0102_0304, path: Data([0xC7]))),
                       [36, 4, 3, 2, 1, 0, 0, 0, 0, 0, 0xC7])
        XCTAssertEqual(MeshCoreCommand.sendTrace(tag: 1, path: Data([0xAB, 0xCD]))[9], 1, "flags carry the hash size minus one")
    }

    func testCommandsMatchTheDocumentedBytes() {
        XCTAssertEqual([UInt8](MeshCoreCommand.appStart(appName: "mccli")),
                       [0x01, 0, 0, 0, 0, 0, 0, 0, 0x6d, 0x63, 0x63, 0x6c, 0x69])
        XCTAssertEqual([UInt8](MeshCoreCommand.deviceQuery()), [0x16, 0x03])
        XCTAssertEqual([UInt8](MeshCoreCommand.getChannel(1)), [0x1F, 0x01])
        // "Hello" to channel 1 at 1234567890, from the protocol document.
        XCTAssertEqual([UInt8](MeshCoreCommand.sendChannelText("Hello", channel: 1, date: Date(timeIntervalSince1970: 1_234_567_890))),
                       [0x03, 0x00, 0x01, 0xD2, 0x02, 0x96, 0x49, 0x48, 0x65, 0x6C, 0x6C, 0x6F])
        let dm = [UInt8](MeshCoreCommand.sendText("hi", toKeyPrefix: Data([1, 2, 3, 4, 5, 6, 7, 8]),
                                                  date: Date(timeIntervalSince1970: 1_234_567_890)))
        XCTAssertEqual(dm, [0x02, 0, 0, 0xD2, 0x02, 0x96, 0x49, 1, 2, 3, 4, 5, 6, 0x68, 0x69])
        XCTAssertEqual([UInt8](MeshCoreCommand.sendSelfAdvert()), [0x07, 0x01])
    }

    func testDecodesContact() throws {
        let frame = CoreFrames.contact(key: CoreFrames.key(0xAA), name: "Ana", type: 1, pathLen: 2)
        XCTAssertEqual(frame.count, 148)
        guard case .contact(let c)? = MeshCoreFrame.decode(frame) else { return XCTFail("not a contact") }
        XCTAssertEqual(c.name, "Ana")
        XCTAssertEqual(c.type, .chat)
        XCTAssertEqual(c.pathLength, 2)
        XCTAssertEqual(c.latitude ?? 0, 39.7392, accuracy: 1e-6)
        XCTAssertEqual(c.longitude ?? 0, -104.9903, accuracy: 1e-6)
        XCTAssertEqual(c.nodeNum, 0x4433_22AA)

        guard case .contact(let flood)? = MeshCoreFrame.decode(CoreFrames.contact(key: CoreFrames.key(1), name: "R", type: 2, pathLen: 0xFF, lat: 0, lon: 0))
        else { return XCTFail() }
        XCTAssertNil(flood.pathLength)
        XCTAssertNil(flood.latitude)
        XCTAssertEqual(flood.type, .repeater)
    }

    func testDecodesSelfAndDeviceInfo() throws {
        guard case .selfInfo(let me)? = MeshCoreFrame.decode(CoreFrames.selfInfo(key: CoreFrames.key(0x11), name: "Base Camp"))
        else { return XCTFail() }
        XCTAssertEqual(me.name, "Base Camp")
        XCTAssertEqual(me.frequencyMHz, 910.525, accuracy: 1e-9)
        XCTAssertEqual(me.bandwidthKHz, 62.5, accuracy: 1e-9)
        XCTAssertEqual(me.spreadingFactor, 7)

        guard case .deviceInfo(let info)? = MeshCoreFrame.decode(CoreFrames.deviceInfo(maxChannels: 8)) else { return XCTFail() }
        XCTAssertEqual(info.maxChannels, 8)
        XCTAssertEqual(info.maxContacts, 100)
        XCTAssertEqual(info.model, "Heltec V4")
        XCTAssertEqual(info.version, "v1.12.0")
    }

    func testDecodesMessages() {
        guard case .channelMessage(let ch)? = MeshCoreFrame.decode(CoreFrames.channelMessageV3(channel: 0, text: "Ana: at the bridge", ts: 1_790_000_100))
        else { return XCTFail() }
        XCTAssertEqual(ch.senderName, "Ana")
        XCTAssertEqual(ch.text, "at the bridge")
        XCTAssertEqual(ch.snr, 5.5)
        XCTAssertEqual(ch.timestamp, 1_790_000_100)

        guard case .directMessage(let dm)? = MeshCoreFrame.decode(CoreFrames.directMessageV3(prefix: [1, 2, 3, 4, 5, 6], text: "ok", ts: 7, signed: true))
        else { return XCTFail() }
        XCTAssertEqual(dm.text, "ok")
        XCTAssertEqual([UInt8](dm.senderKeyPrefix), [1, 2, 3, 4, 5, 6])
        XCTAssertEqual(dm.snr, 5)

        // Old (pre-V3) channel format.
        let old = Data([8, 2, 0, 0] + CoreFrames.le(UInt32(9)) + Array("hello".utf8))
        guard case .channelMessage(let o)? = MeshCoreFrame.decode(old) else { return XCTFail() }
        XCTAssertEqual(o.channel, 2)
        XCTAssertNil(o.senderName)
        XCTAssertEqual(o.text, "hello")
    }

    func testDecodesAcksAndPushes() {
        XCTAssertEqual(MeshCoreFrame.decode(CoreFrames.sent(ack: 0xDEAD_BEEF)), .sent(flood: false, expectedAck: 0xDEAD_BEEF, timeoutMS: 5000))
        XCTAssertEqual(MeshCoreFrame.decode(CoreFrames.sendConfirmed(ack: 7)), .sendConfirmed(ack: 7, roundTripMS: 800))
        XCTAssertEqual(MeshCoreFrame.decode(Data([0x83])), .messagesWaiting)
        XCTAssertEqual(MeshCoreFrame.decode(Data([0x0A])), .noMoreMessages)
        XCTAssertEqual(MeshCoreFrame.decode(Data([0x01, 0x02])), .error(2))
        XCTAssertEqual(MeshCoreFrame.decode(Data([0x8F, 1, 2, 3])), .other(0x8F))
    }

    func testTruncatedFramesAreRejected() {
        let contact = CoreFrames.contact(key: CoreFrames.key(1), name: "A")
        XCTAssertNil(MeshCoreFrame.decode(contact.prefix(100)))
        XCTAssertNil(MeshCoreFrame.decode(Data([0x82, 1, 2])))
        XCTAssertNil(MeshCoreFrame.decode(Data()))
    }

    func testSenderSplitIgnoresOrdinaryColons() {
        XCTAssertEqual(MeshCoreFrame.splitSender("Ana: hi").0, "Ana")
        XCTAssertNil(MeshCoreFrame.splitSender("no sender here").0)
        XCTAssertNil(MeshCoreFrame.splitSender("A very long sentence that happens to contain: a colon later").0)
    }
}

/// Answers the app's MeshCore commands like a radio would.
@MainActor
final class FakeMeshCoreRadio {
    let fake: FakeMeshTransport
    var answered = 0
    var waiting: [Data] = []
    let me = CoreFrames.key(0x11)
    let ana = CoreFrames.key(0xAA)
    var nextAck: UInt32 = 0xA0A0_0001

    /// The radio it plays; nil answers every write (one radio connected).
    var id: UUID?

    init(_ fake: FakeMeshTransport, id: UUID? = nil) {
        self.fake = fake
        self.id = id
    }

    /// Replies to every write (to this radio) not yet answered, until the app goes quiet.
    func run() {
        var guardCount = 0
        while guardCount < 200 {
            let mine = fake.sent.filter { id == nil || $0.id == id }
            guard answered < mine.count else { break }
            guardCount += 1
            let cmd = mine[answered]
            answered += 1
            for reply in replies(to: cmd.data) { fake.frame(reply, from: cmd.id) }
        }
    }

    private func replies(to cmd: Data) -> [Data] {
        switch cmd.first {
        case 1: return [CoreFrames.selfInfo(key: me, name: "Base Camp")]
        case 22: return [CoreFrames.deviceInfo(maxChannels: 2)]
        case 31: return [cmd[1] == 0 ? CoreFrames.channelInfo(0, "Public") : CoreFrames.channelInfo(cmd[1], "")]
        case 6: return [Data([0])]
        case 4: return [Data([2] + CoreFrames.le(UInt32(1))), CoreFrames.contact(key: ana, name: "Ana"), Data([4, 0, 0, 0, 0])]
        case 10: return [waiting.isEmpty ? Data([10]) : waiting.removeFirst()]
        case 36: return [CoreFrames.sent(ack: UInt32(cmd[1]) | UInt32(cmd[2]) << 8 | UInt32(cmd[3]) << 16 | UInt32(cmd[4]) << 24)]
        case 2, 3:
            defer { nextAck += 1 }
            return [CoreFrames.sent(ack: cmd.first == 2 ? nextAck : 0)]
        default: return [Data([1, 1])]
        }
    }
}

@MainActor
final class MeshCoreServiceTests: XCTestCase {
    var dir: URL!
    var defaults: UserDefaults!
    var suiteName: String!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("meshcore-tests-\(UUID().uuidString)")
        suiteName = "meshcore-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func connected() -> (MeshService, FakeMeshTransport, FakeMeshCoreRadio) {
        let fake = FakeMeshTransport()
        let radio = FakeMeshCoreRadio(fake)
        radio.waiting = [CoreFrames.channelMessageV3(channel: 0, text: "Ana: anyone on?", ts: 1_790_000_000)]
        let svc = MeshService(transport: fake, store: MeshStore(directory: dir), defaults: defaults)
        svc.start()
        svc.connect(to: MeshDiscoveredDevice(id: UUID(), name: "MeshCore-Base", rssi: -60, lastSeen: Date(), firmware: .meshcore))
        fake.onEvent?(.ready(id: fake.connectedID ?? UUID(), name: "MeshCore-Base", firmware: .meshcore))
        radio.run()
        return (svc, fake, radio)
    }

    func testHandshakeLoadsIdentityContactsChannelsAndQueuedMessages() {
        let (svc, fake, _) = connected()
        XCTAssertEqual(svc.connection, .connected(name: "MeshCore-Base"))
        XCTAssertEqual(svc.firmware, .meshcore)
        XCTAssertEqual(svc.maxTextBytes, 133)
        XCTAssertEqual(svc.myNode?.longName, "Base Camp")
        XCTAssertEqual(svc.radio.regionName, "910.525 MHz")
        XCTAssertEqual(svc.radio.firmwareVersion, "MeshCore v1.12.0")
        XCTAssertEqual(svc.radio.primaryChannelName, "Public")
        XCTAssertEqual(svc.sortedNodes.map(\.longName), ["Ana"])
        XCTAssertEqual(svc.sortedNodes.first?.hopsAway, 2)
        // First command identifies the app; commands go one at a time.
        XCTAssertEqual(fake.writes.first?.first, 1)
        let thread = svc.messages(in: .channel(0))
        XCTAssertEqual(thread.map(\.text), ["anyone on?"])
        XCTAssertEqual(thread.first?.senderName, "Ana")
        XCTAssertEqual(thread.first?.from, svc.sortedNodes.first?.num, "sender name matched to the contact")
    }

    func testDirectMessageIsAddressedByKeyAndConfirmed() throws {
        let (svc, fake, radio) = connected()
        let ana = try XCTUnwrap(svc.sortedNodes.first)
        XCTAssertTrue(svc.send("meet at the bridge", to: .direct(ana.num)))
        radio.run()
        let cmd = try XCTUnwrap(fake.writes.last)
        XCTAssertEqual(cmd.first, 2)
        XCTAssertEqual([UInt8](cmd[7 ..< 13]), Array(radio.ana.prefix(6)))
        XCTAssertEqual(String(decoding: cmd.suffix(from: 13), as: UTF8.self), "meet at the bridge")

        var sent = try XCTUnwrap(svc.messages(in: .direct(ana.num)).last)
        XCTAssertEqual(sent.delivery, .sent)
        fake.frame(CoreFrames.sendConfirmed(ack: sent.packetID))
        sent = try XCTUnwrap(svc.messages(in: .direct(ana.num)).last)
        XCTAssertEqual(sent.delivery, .delivered)
    }

    func testChannelSendAndTextLimit() throws {
        let (svc, fake, radio) = connected()
        XCTAssertTrue(svc.send("all good here", to: .channel(0)))
        radio.run()
        XCTAssertEqual(fake.writes.last?.first, 3)
        XCTAssertFalse(svc.send(String(repeating: "x", count: 134), to: .channel(0)), "MeshCore's limit is 133 bytes")
    }

    func testMessagesWaitingPushTriggersSync() {
        let (svc, fake, radio) = connected()
        radio.waiting = [CoreFrames.directMessageV3(prefix: Array(radio.ana.prefix(6)), text: "you ok?", ts: 1_790_000_500)]
        fake.frame(Data([0x83]))
        radio.run()
        let ana = svc.sortedNodes.first!
        XCTAssertEqual(svc.messages(in: .direct(ana.num)).map(\.text), ["you ok?"])
        // The same message replayed is not shown twice.
        radio.waiting = [CoreFrames.directMessageV3(prefix: Array(radio.ana.prefix(6)), text: "you ok?", ts: 1_790_000_500)]
        fake.frame(Data([0x83]))
        radio.run()
        XCTAssertEqual(svc.messages(in: .direct(ana.num)).count, 1)
    }

    func testSOSFitsMeshCoreLimit() async throws {
        let (svc, _, _) = connected()
        let sent = await svc.sendSOS(includeLocation: false)
        let text = try XCTUnwrap(sent)
        XCTAssertLessThanOrEqual(text.utf8.count, 133)
        XCTAssertTrue(text.hasPrefix("SOS"))
    }

    func testMeshCorePingNeedsATargetAndIsConfirmed() throws {
        let (svc, fake, radio) = connected()
        XCTAssertFalse(svc.ping(nil) { _ in }, "MeshCore can only ping a chosen radio")
        let ana = try XCTUnwrap(svc.sortedNodes.first)
        var outcome: MeshPingOutcome?
        let ack = radio.nextAck
        XCTAssertTrue(svc.ping(ana.num) { outcome = $0 })
        radio.run()
        let cmd = try XCTUnwrap(fake.writes.last)
        XCTAssertEqual(cmd.first, 2)
        XCTAssertEqual(String(decoding: cmd.suffix(from: 13), as: UTF8.self), MeshService.pingText)
        XCTAssertTrue(svc.messages(in: .direct(ana.num)).isEmpty, "pings are not added to the chat")
        fake.frame(CoreFrames.sendConfirmed(ack: ack))
        XCTAssertEqual(outcome, .reached)
    }

    func testEveryPacketHeardIsACoveragePointAndARepeaterInRange() throws {
        let (svc, fake, _) = connected()
        var heard: [MeshHeard] = []
        svc.onHeard = { heard.append($0) }
        // Someone else's channel text, passed on by repeater C7.
        fake.frame(CoreFrames.rxLog(snr: 7.5, CoreFrames.packet(route: 1, type: 5, path: [[0x3A], [0xC7]], payload: [1, 2, 3])))
        let first = try XCTUnwrap(heard.first)
        XCTAssertEqual(first.firmware, .meshcore)
        XCTAssertEqual(first.snr, 7.5)
        XCTAssertEqual(first.hops, 2)
        let c7 = MeshCoreContact.nodeNum(forHash: Data([0xC7]))
        XCTAssertEqual(first.node, c7)
        XCTAssertEqual(svc.autoPingTarget(on: .meshcore), c7, "the repeater just heard is the ping target")
        // A known contact's hash is recorded as the hash (credit is worked out
        // later from where the sample was taken), and the contact is only
        // marked heard live when the phone is known to be in range of it.
        svc.currentFix = { nil }
        fake.frame(CoreFrames.rxLog(snr: 2, CoreFrames.packet(route: 1, type: 5, path: [[0xAA]], payload: [1])))
        XCTAssertEqual(heard.last?.node, MeshCoreContact.nodeNum(forHash: Data([0xAA])))
        XCTAssertNotEqual(svc.sortedNodes.first { $0.longName == "Ana" }?.snr, 2, "no fix: no guess")
        svc.currentFix = { (39.7400, -104.9900) } // about 100 m from Ana, but Ana is a companion: it never repeats
        fake.frame(CoreFrames.rxLog(snr: 3, CoreFrames.packet(route: 1, type: 5, path: [[0xAA]], payload: [1])))
        let ana = try XCTUnwrap(svc.sortedNodes.first { $0.longName == "Ana" })
        XCTAssertNotEqual(ana.snr, 3)
        XCTAssertNil(svc.link(.meshcore).heardDirect[ana.num])
        // As a repeater in range she is credited; from Fort Collins (~95 km) she isn't.
        svc.link(.meshcore).update(ana.num) { $0.meshCoreType = .repeater }
        fake.frame(CoreFrames.rxLog(snr: 4, CoreFrames.packet(route: 1, type: 5, path: [[0xAA]], payload: [1])))
        XCTAssertEqual(svc.sortedNodes.first { $0.longName == "Ana" }?.snr, 4)
        XCTAssertNotNil(svc.link(.meshcore).heardDirect[ana.num])
        svc.currentFix = { (40.5853, -105.0844) }
        fake.frame(CoreFrames.rxLog(snr: 5, CoreFrames.packet(route: 1, type: 5, path: [[0xAA]], payload: [1])))
        XCTAssertEqual(svc.sortedNodes.first { $0.longName == "Ana" }?.snr, 4)
        // Unparseable bytes are ignored.
        fake.frame(Data([0x88, 0, 0, 0x05]))
        XCTAssertEqual(heard.count, 5)
    }

    func testRepeaterPingIsATraceAnsweredByTraceData() throws {
        let (svc, fake, radio) = connected()
        fake.frame(CoreFrames.rxLog(snr: 5, CoreFrames.packet(route: 1, type: 5, path: [[0xC7]], payload: [1])))
        let c7 = try XCTUnwrap(svc.autoPingTarget(on: .meshcore))
        var outcome: MeshPingOutcome?
        XCTAssertTrue(svc.ping(c7) { outcome = $0 })
        radio.run()
        let cmd = try XCTUnwrap(fake.writes.last)
        XCTAssertEqual(cmd.first, 36)
        XCTAssertEqual(cmd.last, 0xC7)
        let tag = UInt32(cmd[1]) | UInt32(cmd[2]) << 8 | UInt32(cmd[3]) << 16 | UInt32(cmd[4]) << 24
        XCTAssertNil(outcome)
        fake.frame(CoreFrames.traceData(tag: tag &+ 1, hashes: [0xC7]))
        XCTAssertNil(outcome, "someone else's trace")
        fake.frame(CoreFrames.traceData(tag: tag, hashes: [0xC7]))
        XCTAssertEqual(outcome, .reached)
    }

    func testRepeaterContactIsPingedByTraceToo() throws {
        let (svc, fake, radio) = connected()
        fake.frame(CoreFrames.contact(code: 0x8A, key: CoreFrames.key(0x7D), name: "Hilltop", type: 2))
        let hill = try XCTUnwrap(svc.sortedNodes.first { $0.longName == "Hilltop" })
        XCTAssertTrue(svc.ping(hill.num) { _ in })
        radio.run()
        XCTAssertEqual(fake.writes.last?.first, 36)
        XCTAssertEqual(fake.writes.last?.last, 0x7D)
    }
}
