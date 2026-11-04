// MeshServiceTests.swift
// Pocket Nomad - Mesh module tests
// SPDX-License-Identifier: Apache-2.0
//
// Drives MeshService through a fake radio: handshake, chat, ACKs, persistence.

import XCTest
#if canImport(NomadPocket)
@testable import NomadPocket
#endif

/// Plays the radio side of the Meshtastic BLE contract.
@MainActor
final class FakeMeshTransport: MeshTransport {
    var onEvent: ((MeshTransportEvent) -> Void)?
    var availability: MeshBluetoothAvailability = .poweredOn
    var writes: [Data] = []
    /// FromRadio packets waiting to be read.
    var queue: [Data] = []
    var pendingReads = 0
    var connectedID: UUID?
    var scanning = false

    func start() { onEvent?(.availability(.poweredOn)) }
    func startScan() { scanning = true }
    func stopScan() { scanning = false }
    func connect(id: UUID) {
        connectedID = id
        onEvent?(.connecting(id: id, name: "Test Radio"))
    }
    func disconnect() {
        if let connectedID { onEvent?(.disconnected(id: connectedID, reason: nil, willReconnect: false)) }
        connectedID = nil
    }
    func write(_ toRadio: Data) { writes.append(toRadio) }
    func readFromRadio() { pendingReads += 1 }

    // Test helpers

    func becomeReady() {
        onEvent?(.ready(id: connectedID ?? UUID(), name: "Test Radio"))
    }

    func enqueue(_ variant: MeshPBFromRadioVariant) {
        queue.append(MeshPBFromRadio(id: UInt32(queue.count + 1), variant: variant).encode())
    }

    /// Answers reads until the service stops asking (like reading until empty).
    func pump() {
        var guardCount = 0
        while pendingReads > 0, guardCount < 1000 {
            pendingReads -= 1
            guardCount += 1
            onEvent?(.fromRadio(queue.isEmpty ? Data() : queue.removeFirst()))
        }
    }

    func decodedWrites() throws -> [MeshPBToRadio] {
        try writes.compactMap { try MeshPBToRadio.decode($0) }
    }

    func lastWantConfigID() throws -> UInt32? {
        for w in try decodedWrites().reversed() {
            if case .wantConfigID(let id) = w { return id }
        }
        return nil
    }

    func lastSentPacket() throws -> MeshPBPacket? {
        for w in try decodedWrites().reversed() {
            if case .packet(let p) = w { return p }
        }
        return nil
    }
}

@MainActor
final class MeshServiceTests: XCTestCase {
    let me: UInt32 = 0x1111_1111
    let alice: UInt32 = 0xAAAA_0001
    let bob: UInt32 = 0xBBBB_0002

    var dir: URL!
    var defaults: UserDefaults!
    var suiteName: String!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("mesh-tests-\(UUID().uuidString)")
        suiteName = "mesh-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func makeService(_ fake: FakeMeshTransport) -> MeshService {
        MeshService(transport: fake, store: MeshStore(directory: dir), defaults: defaults)
    }

    /// Connects and completes the full startup handshake.
    private func connected() throws -> (MeshService, FakeMeshTransport) {
        let fake = FakeMeshTransport()
        let svc = makeService(fake)
        svc.start()
        svc.connect(to: MeshDiscoveredDevice(id: UUID(), name: "Test Radio", rssi: -60, lastSeen: Date()))
        fake.becomeReady()
        let cfg = try XCTUnwrap(try fake.lastWantConfigID())
        fake.enqueue(.myInfo(MeshPBMyNodeInfo(myNodeNum: me)))
        fake.enqueue(.metadata(MeshPBDeviceMetadata(firmwareVersion: "2.6.0")))
        fake.enqueue(.nodeInfo(MeshPBNodeInfo(num: me, user: MeshPBUser(id: "!11111111", longName: "My Radio", shortName: "ME"))))
        fake.enqueue(.nodeInfo(MeshPBNodeInfo(num: alice, user: MeshPBUser(longName: "Alice", shortName: "AL"),
                                              position: MeshPBPosition(latitudeI: 397_392_400, longitudeI: -1_049_902_500),
                                              snr: 5.5, lastHeard: UInt32(Date().timeIntervalSince1970) - 60, hopsAway: 1)))
        fake.enqueue(.nodeInfo(MeshPBNodeInfo(num: bob, user: MeshPBUser(longName: "Bob", shortName: "BO"),
                                              lastHeard: UInt32(Date().timeIntervalSince1970) - 3600)))
        fake.enqueue(.channel(MeshPBChannel(index: 0, name: "LongFast", hasPSK: true, role: 1)))
        fake.enqueue(.channel(MeshPBChannel(index: 1, name: "", role: 0)))
        fake.enqueue(.loraConfig(MeshPBLoRaConfig(modemPreset: 0, region: 1, hopLimit: 4)))
        fake.enqueue(.otherConfig)
        fake.enqueue(.unhandled(field: 9))
        fake.enqueue(.configCompleteID(cfg))
        fake.pump()
        return (svc, fake)
    }

    private func incoming(from: UInt32, to: UInt32, text: String, id: UInt32, channel: UInt32 = 0) -> MeshPBFromRadioVariant {
        .packet(MeshPBPacket(from: from, to: to, channel: channel,
                             decoded: MeshPBData(portnum: MeshPort.textMessage.rawValue, payload: Data(text.utf8)),
                             id: id, rxTime: UInt32(Date().timeIntervalSince1970), rxSNR: 4.25, hopLimit: 2, hopStart: 3))
    }

    private func routing(from: UInt32, requestID: UInt32, error: UInt32) -> MeshPBFromRadioVariant {
        .packet(MeshPBPacket(from: from, to: me,
                             decoded: MeshPBData(portnum: MeshPort.routing.rawValue,
                                                 payload: MeshPBRouting(errorReason: error).encode(),
                                                 requestID: requestID),
                             id: MeshService.newPacketID()))
    }

    // MARK: Handshake

    func testHandshakeCollectsNodesChannelsAndSettings() throws {
        let (svc, _) = try connected()
        XCTAssertEqual(svc.connection, .connected(name: "Test Radio"))
        XCTAssertEqual(svc.myNodeNum, me)
        XCTAssertEqual(svc.radio.firmwareVersion, "2.6.0")
        XCTAssertEqual(svc.radio.primaryChannelName, "LongFast")
        XCTAssertNil(svc.radio.channelNames[1])
        XCTAssertEqual(svc.radio.region, 1)
        XCTAssertEqual(svc.radio.hopLimit, 4)
        XCTAssertEqual(svc.myNode?.longName, "My Radio")
        // Own node excluded, most recently heard first.
        XCTAssertEqual(svc.sortedNodes.map(\.num), [alice, bob])
        let a = try XCTUnwrap(svc.nodes[alice])
        XCTAssertEqual(a.shortName, "AL")
        XCTAssertEqual(a.hopsAway, 1)
        XCTAssertEqual(a.snr, 5.5)
        XCTAssertEqual(try XCTUnwrap(a.latitude), 39.73924, accuracy: 1e-6)
        XCTAssertNil(svc.notice)
    }

    func testHandshakeIgnoresMismatchedConfigCompleteID() throws {
        let fake = FakeMeshTransport()
        let svc = makeService(fake)
        svc.start()
        svc.connect(to: MeshDiscoveredDevice(id: UUID(), name: "R", rssi: -50, lastSeen: Date()))
        fake.becomeReady()
        XCTAssertEqual(svc.connection, .syncing(name: "Test Radio"))
        let cfg = try XCTUnwrap(try fake.lastWantConfigID())
        fake.enqueue(.myInfo(MeshPBMyNodeInfo(myNodeNum: me)))
        fake.enqueue(.configCompleteID(cfg &+ 1)) // stale reply to someone else's request
        fake.pump()
        XCTAssertEqual(svc.connection, .syncing(name: "Test Radio"))
        fake.enqueue(.configCompleteID(cfg))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(svc.connection, .connected(name: "Test Radio"))
    }

    func testRegionUnsetShowsNotice() throws {
        let fake = FakeMeshTransport()
        let svc = makeService(fake)
        svc.start()
        svc.connect(to: MeshDiscoveredDevice(id: UUID(), name: "R", rssi: -50, lastSeen: Date()))
        fake.becomeReady()
        let cfg = try XCTUnwrap(try fake.lastWantConfigID())
        fake.enqueue(.loraConfig(MeshPBLoRaConfig(region: 0)))
        fake.enqueue(.configCompleteID(cfg))
        fake.pump()
        XCTAssertTrue(svc.notice?.contains("US 915") == true)
    }

    func testRebootTriggersNewHandshake() throws {
        let (svc, fake) = try connected()
        let first = try fake.lastWantConfigID()
        fake.enqueue(.rebooted)
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(svc.connection, .syncing(name: "Test Radio"))
        XCTAssertNotEqual(try fake.lastWantConfigID(), first)
    }

    func testCorruptPacketIsSkippedAndReadingContinues() throws {
        let (svc, fake) = try connected()
        fake.queue.append(Data([0x12, 0x7F, 0x00])) // length past end
        fake.enqueue(incoming(from: alice, to: MeshConstants.broadcastNum, text: "after junk", id: 50))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(svc.messages(in: .channel(0)).map(\.text), ["after junk"])
    }

    // MARK: Incoming messages

    func testIncomingBroadcastAndDuplicate() throws {
        let (svc, fake) = try connected()
        fake.enqueue(incoming(from: alice, to: MeshConstants.broadcastNum, text: "hello mesh", id: 100))
        fake.enqueue(incoming(from: alice, to: MeshConstants.broadcastNum, text: "hello mesh", id: 100))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        let thread = svc.messages(in: .channel(0))
        XCTAssertEqual(thread.count, 1)
        XCTAssertEqual(thread.first?.text, "hello mesh")
        XCTAssertEqual(thread.first?.delivery, .received)
        XCTAssertEqual(thread.first?.hopsAway, 1)
        XCTAssertEqual(svc.unreadCount(.channel(0)), 1)
        svc.markRead(.channel(0))
        XCTAssertEqual(svc.unreadCount(.channel(0)), 0)
    }

    func testIncomingDirectMessageAndOthersTrafficIgnored() throws {
        let (svc, fake) = try connected()
        fake.enqueue(incoming(from: bob, to: me, text: "just you", id: 200))
        fake.enqueue(incoming(from: bob, to: alice, text: "not for me", id: 201))
        fake.enqueue(.packet(MeshPBPacket(from: bob, to: me, encrypted: Data([1, 2]), id: 202)))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(svc.messages(in: .direct(bob)).map(\.text), ["just you"])
        XCTAssertEqual(svc.directThreads.map(\.node.num), [bob])
        XCTAssertTrue(svc.messages(in: .channel(0)).isEmpty)
        XCTAssertEqual(svc.messages.count, 1)
    }

    func testPositionAndNodeInfoPacketsUpdateNodes() throws {
        let (svc, fake) = try connected()
        let carol: UInt32 = 0xCCCC_0003
        fake.enqueue(.packet(MeshPBPacket(from: carol, to: MeshConstants.broadcastNum,
                                          decoded: MeshPBData(portnum: MeshPort.nodeInfo.rawValue,
                                                              payload: MeshPBUser(longName: "Carol", shortName: "CA").encode()),
                                          id: 300, rxSNR: -3)))
        fake.enqueue(.packet(MeshPBPacket(from: carol, to: MeshConstants.broadcastNum,
                                          decoded: MeshPBData(portnum: MeshPort.position.rawValue,
                                                              payload: MeshPBPosition(latitudeI: -338_688_000, longitudeI: 1_512_093_000).encode()),
                                          id: 301)))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        let c = try XCTUnwrap(svc.nodes[carol])
        XCTAssertEqual(c.longName, "Carol")
        XCTAssertEqual(c.snr, -3)
        XCTAssertEqual(try XCTUnwrap(c.latitude), -33.8688, accuracy: 1e-6)
        XCTAssertEqual(svc.sortedNodes.first?.num, carol)
    }

    // MARK: Sending and delivery

    func testSendDirectMessageThenDeliveredAck() throws {
        let (svc, fake) = try connected()
        XCTAssertTrue(svc.send("  meet at the trailhead  ", to: .direct(alice)))
        let p = try XCTUnwrap(try fake.lastSentPacket())
        XCTAssertEqual(p.to, alice)
        XCTAssertEqual(p.channel, 0)
        XCTAssertTrue(p.wantAck)
        XCTAssertEqual(p.hopLimit, 4) // from the radio's LoRa config
        XCTAssertNotEqual(p.id, 0)
        XCTAssertEqual(p.decoded?.portnum, 1)
        XCTAssertEqual(p.decoded.map { String(decoding: $0.payload, as: UTF8.self) }, "meet at the trailhead")

        var m = try XCTUnwrap(svc.messages(in: .direct(alice)).last)
        XCTAssertEqual(m.delivery, .sent)

        // Implicit ACK from our own radio first, then the real one from Alice.
        fake.enqueue(routing(from: me, requestID: p.id, error: 0))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        m = try XCTUnwrap(svc.messages(in: .direct(alice)).last)
        XCTAssertEqual(m.delivery, .heardByMesh)

        fake.enqueue(routing(from: alice, requestID: p.id, error: 0))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        m = try XCTUnwrap(svc.messages(in: .direct(alice)).last)
        XCTAssertEqual(m.delivery, .delivered)

        // A late NAK does not undo a confirmed delivery.
        fake.enqueue(routing(from: me, requestID: p.id, error: 5))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(svc.messages(in: .direct(alice)).last?.delivery, .delivered)
    }

    func testBroadcastAckAndNak() throws {
        let (svc, fake) = try connected()
        XCTAssertTrue(svc.send("one", to: .channel(0)))
        let first = try XCTUnwrap(try fake.lastSentPacket())
        XCTAssertEqual(first.to, MeshConstants.broadcastNum)
        XCTAssertTrue(svc.send("two", to: .channel(0)))
        let second = try XCTUnwrap(try fake.lastSentPacket())

        fake.enqueue(routing(from: me, requestID: first.id, error: 0))
        fake.enqueue(routing(from: me, requestID: second.id, error: MeshRoutingError.maxRetransmit.rawValue))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        let thread = svc.messages(in: .channel(0))
        // A broadcast nobody repeated was still sent; it isn't a failure.
        XCTAssertEqual(thread.map(\.delivery), [.heardByMesh, .notRepeated])

        // A direct message that runs out of retries really did fail.
        XCTAssertTrue(svc.send("you there?", to: .direct(alice)))
        let dm = try XCTUnwrap(try fake.lastSentPacket())
        fake.enqueue(routing(from: me, requestID: dm.id, error: MeshRoutingError.maxRetransmit.rawValue))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(svc.messages(in: .direct(alice)).last?.delivery, .failed("Their radio didn't answer"))
    }

    func testQueueFullMarksFailed() throws {
        let (svc, fake) = try connected()
        XCTAssertTrue(svc.send("busy?", to: .channel(0)))
        let p = try XCTUnwrap(try fake.lastSentPacket())
        fake.enqueue(.queueStatus(MeshPBQueueStatus(res: 1, free: 0, maxlen: 16, meshPacketID: p.id)))
        fake.pump()
        XCTAssertEqual(svc.messages(in: .channel(0)).last?.delivery, .failed("Radio is busy, try again"))
    }

    func testSendRejectsTooLongEmptyAndDisconnected() throws {
        let (svc, fake) = try connected()
        let before = fake.writes.count
        XCTAssertFalse(svc.send(String(repeating: "a", count: 201), to: .channel(0)))
        XCTAssertTrue(svc.send(String(repeating: "a", count: 200), to: .channel(0)))
        XCTAssertFalse(svc.send("   ", to: .channel(0)))
        XCTAssertFalse(svc.send(String(repeating: "📡", count: 51), to: .channel(0))) // 204 bytes
        XCTAssertEqual(fake.writes.count, before + 1)

        svc.disconnect()
        XCTAssertEqual(svc.connection, .idle)
        XCTAssertFalse(svc.send("hi", to: .channel(0)))
    }

    func testSOSWithoutLocationGoesToPrimaryChannel() async throws {
        let (svc, fake) = try connected()
        let sent = await svc.sendSOS(includeLocation: false)
        XCTAssertEqual(sent?.hasPrefix("SOS"), true)
        XCTAssertTrue(sent?.contains("My Radio") == true)
        let p = try XCTUnwrap(try fake.lastSentPacket())
        XCTAssertEqual(p.to, MeshConstants.broadcastNum)
        XCTAssertEqual(p.channel, 0)
    }

    // MARK: Persistence and reconnect

    func testPersistenceRoundTripAndAutoReconnect() throws {
        let (svc, fake) = try connected()
        fake.enqueue(incoming(from: alice, to: MeshConstants.broadcastNum, text: "saved?", id: 400))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        svc.send("reply", to: .direct(alice))
        svc.saveNow()

        let fake2 = FakeMeshTransport()
        let svc2 = makeService(fake2)
        XCTAssertEqual(svc2.messages.map(\.text), ["saved?", "reply"])
        XCTAssertEqual(svc2.nodes[alice]?.longName, "Alice")
        XCTAssertEqual(svc2.radio.myNodeNum, me)
        XCTAssertEqual(svc2.lastDeviceName, "Test Radio")
        // Remembered device reconnects at start.
        svc2.start()
        XCTAssertEqual(fake2.connectedID, svc.lastDeviceID)
    }

    func testDisconnectStopsAutoReconnect() throws {
        let (svc, _) = try connected()
        svc.disconnect()
        let fake2 = FakeMeshTransport()
        let svc2 = makeService(fake2)
        svc2.start()
        XCTAssertNil(fake2.connectedID)
        XCTAssertNotNil(svc2.lastDeviceID) // still offered as "Reconnect to ..."
    }

    func testHistoryIsCapped() {
        let many = (0..<(MeshStore.maxMessages + 50)).map {
            MeshMessage(packetID: UInt32($0 + 1), from: alice, to: MeshConstants.broadcastNum, channel: 0,
                        text: "\($0)", date: Date(), isOutgoing: false, delivery: .received)
        }
        let capped = MeshStore.capped(many)
        XCTAssertEqual(capped.count, MeshStore.maxMessages)
        XCTAssertEqual(capped.last?.text, "\(MeshStore.maxMessages + 49)")
    }

    func testTransportReconnectingState() throws {
        let (svc, fake) = try connected()
        fake.onEvent?(.disconnected(id: UUID(), reason: "Link lost", willReconnect: true))
        XCTAssertEqual(svc.connection, .reconnecting(name: "Test Radio"))
        XCTAssertFalse(svc.canSend)
        fake.onEvent?(.pairingProblem("PIN"))
        XCTAssertEqual(svc.pairingHint, "PIN")
        fake.becomeReady()
        XCTAssertNil(svc.pairingHint)
        XCTAssertEqual(svc.connection, .syncing(name: "Test Radio"))
    }

    // MARK: Drive-test pings

    func testBroadcastPingIsSilentOneHopAndRelayedCounts() throws {
        let (svc, fake) = try connected()
        var outcome: MeshPingOutcome?
        XCTAssertTrue(svc.ping(nil) { outcome = $0 })
        let p = try XCTUnwrap(try fake.lastSentPacket())
        XCTAssertEqual(p.to, MeshConstants.broadcastNum)
        XCTAssertEqual(p.hopLimit, 1, "only neighbours repeat it")
        XCTAssertTrue(p.wantAck)
        XCTAssertEqual(p.decoded?.portnum, MeshService.pingPort, "not a text message, so it never shows as chat")
        XCTAssertTrue(svc.messages(in: .channel(0)).isEmpty)

        fake.enqueue(routing(from: me, requestID: p.id, error: 0)) // implicit ACK: a neighbour repeated it
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(outcome, .relayed)
    }

    func testTargetedPingWaitsForTheTargetAndFallsBackToRelayed() throws {
        let (svc, fake) = try connected()
        var first: MeshPingOutcome?
        XCTAssertTrue(svc.ping(alice) { first = $0 })
        let p1 = try XCTUnwrap(try fake.lastSentPacket())
        XCTAssertEqual(p1.to, alice)
        fake.enqueue(routing(from: me, requestID: p1.id, error: 0))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertNil(first, "a relay alone isn't an answer yet")
        fake.enqueue(routing(from: alice, requestID: p1.id, error: 0))
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(first, .reached)

        var second: MeshPingOutcome?
        XCTAssertTrue(svc.ping(alice) { second = $0 })
        let p2 = try XCTUnwrap(try fake.lastSentPacket())
        fake.enqueue(routing(from: me, requestID: p2.id, error: 0))
        fake.enqueue(routing(from: me, requestID: p2.id, error: 5)) // radio gave up
        fake.onEvent?(.fromNumChanged)
        fake.pump()
        XCTAssertEqual(second, .relayed)
    }

    func testPingNeedsAConnectedRadio() {
        let svc = makeService(FakeMeshTransport())
        XCTAssertFalse(svc.ping(nil) { _ in XCTFail("no result without a radio") })
    }
}
