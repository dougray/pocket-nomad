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
}

final class MeshCoreCodecTests: XCTestCase {
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
        XCTAssertEqual(MeshCoreFrame.decode(Data([0x88, 1, 2, 3])), .other(0x88))
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

    init(_ fake: FakeMeshTransport) { self.fake = fake }

    /// Replies to every write not yet answered, until the app goes quiet.
    func run() {
        var guardCount = 0
        while answered < fake.writes.count, guardCount < 200 {
            guardCount += 1
            let cmd = fake.writes[answered]
            answered += 1
            for reply in replies(to: cmd) { fake.onEvent?(.frame(reply)) }
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
        fake.onEvent?(.frame(CoreFrames.sendConfirmed(ack: sent.packetID)))
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
        fake.onEvent?(.frame(Data([0x83])))
        radio.run()
        let ana = svc.sortedNodes.first!
        XCTAssertEqual(svc.messages(in: .direct(ana.num)).map(\.text), ["you ok?"])
        // The same message replayed is not shown twice.
        radio.waiting = [CoreFrames.directMessageV3(prefix: Array(radio.ana.prefix(6)), text: "you ok?", ts: 1_790_000_500)]
        fake.onEvent?(.frame(Data([0x83])))
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
        fake.onEvent?(.frame(CoreFrames.sendConfirmed(ack: ack)))
        XCTAssertEqual(outcome, .reached)
    }
}
