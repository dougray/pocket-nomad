// MeshDualRadioTests.swift
// Pocket Nomad - Mesh module tests
// SPDX-License-Identifier: Apache-2.0
//
// Two radios at once through one fake transport: a Meshtastic radio and a
// MeshCore radio, each with its own scripted side. Covers routing per network,
// SOS fan-out, one-per-firmware replacement, migration of the single-radio
// settings, coverage tagging and drive-mode pings on both.

import CoreLocation
import XCTest
#if canImport(NomadPocket)
@testable import NomadPocket
#endif

@MainActor
final class MeshDualRadioTests: XCTestCase {
    let me: UInt32 = 0x1111_1111
    let alice: UInt32 = 0xAAAA_0001
    let tastic = UUID()
    let core = UUID()

    var dir: URL!
    var defaults: UserDefaults!
    var suiteName: String!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("dual-tests-\(UUID().uuidString)")
        suiteName = "dual-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func makeService(_ fake: FakeMeshTransport) -> MeshService {
        MeshService(transport: fake, store: MeshStore(directory: dir), defaults: defaults)
    }

    private func device(_ id: UUID, _ name: String, _ firmware: MeshFirmware) -> MeshDiscoveredDevice {
        MeshDiscoveredDevice(id: id, name: name, rssi: -55, lastSeen: Date(), firmware: firmware)
    }

    /// Runs the Meshtastic handshake for `id` with one other node, Alice.
    private func meshtasticHandshake(_ fake: FakeMeshTransport, _ id: UUID) throws {
        fake.becomeReady(id, name: "Tastic", firmware: .meshtastic)
        let cfg = try XCTUnwrap(try fake.lastWantConfigID(to: id))
        fake.enqueue(.myInfo(MeshPBMyNodeInfo(myNodeNum: me)))
        fake.enqueue(.nodeInfo(MeshPBNodeInfo(num: me, user: MeshPBUser(longName: "My Tastic", shortName: "MT"),
                                              deviceMetrics: MeshPBDeviceMetrics(batteryLevel: 81))))
        fake.enqueue(.nodeInfo(MeshPBNodeInfo(num: alice, user: MeshPBUser(longName: "Alice", shortName: "AL"),
                                              position: MeshPBPosition(latitudeI: 397_392_400, longitudeI: -1_049_902_500),
                                              lastHeard: UInt32(Date().timeIntervalSince1970) - 60)))
        fake.enqueue(.channel(MeshPBChannel(index: 0, name: "LongFast", hasPSK: true, role: 1)))
        fake.enqueue(.loraConfig(MeshPBLoRaConfig(region: 1, hopLimit: 3)))
        fake.enqueue(.configCompleteID(cfg))
        fake.pump()
    }

    /// Both radios connected and synced.
    private func dual() throws -> (MeshService, FakeMeshTransport, FakeMeshCoreRadio) {
        let fake = FakeMeshTransport()
        let svc = makeService(fake)
        svc.start()
        svc.connect(to: device(tastic, "Tastic", .meshtastic))
        try meshtasticHandshake(fake, tastic)
        let radio = FakeMeshCoreRadio(fake, id: core)
        radio.waiting = [CoreFrames.channelMessageV3(channel: 0, text: "Ana: anyone on?", ts: 1_790_000_000)]
        svc.connect(to: device(core, "Core", .meshcore))
        fake.becomeReady(core, name: "Core", firmware: .meshcore)
        radio.run()
        return (svc, fake, radio)
    }

    // MARK: Both at once

    func testConnectsBothAndKeepsTheNetworksApart() throws {
        let (svc, fake, radio) = try dual()
        XCTAssertEqual(svc.link(.meshtastic).connection, .connected(name: "Tastic"))
        XCTAssertEqual(svc.link(.meshcore).connection, .connected(name: "Core"))
        XCTAssertEqual(svc.connection, .connected(name: "Tastic + Core"))
        XCTAssertEqual(svc.connectedLinks.map(\.firmware), [.meshtastic, .meshcore])
        XCTAssertEqual(fake.connectedIDs, [tastic, core], "connecting the second radio keeps the first")
        XCTAssertEqual(svc.link(.meshtastic).batteryLevel, 81)

        // Each radio's own node stays off the list; the others merge, tagged.
        let listed = Dictionary(uniqueKeysWithValues: svc.sortedNodes.map { ($0.displayName, $0.firmware) })
        XCTAssertEqual(listed, ["Alice": .meshtastic, "Ana": .meshcore])
        XCTAssertEqual(svc.link(.meshcore).radio.primaryChannelName, "Public")
        XCTAssertEqual(svc.link(.meshtastic).radio.primaryChannelName, "LongFast")

        // Channel 0 is a different thread on each network.
        fake.enqueue(.packet(MeshPBPacket(from: alice, to: MeshConstants.broadcastNum,
                                          decoded: MeshPBData(portnum: MeshPort.textMessage.rawValue, payload: Data("hi from tastic".utf8)),
                                          id: 77, rxTime: UInt32(Date().timeIntervalSince1970))))
        fake.fromNumChanged(tastic)
        fake.pump()
        XCTAssertEqual(svc.messages(in: .channel(0), network: .meshtastic).map(\.text), ["hi from tastic"])
        XCTAssertEqual(svc.messages(in: .channel(0), network: .meshcore).map(\.text), ["anyone on?"])
        XCTAssertEqual(svc.unreadCount(.channel(0), network: .meshtastic), 1)
        svc.markRead(.channel(0), network: .meshtastic)
        XCTAssertEqual(svc.unreadCount(.channel(0), network: .meshtastic), 0)
        XCTAssertEqual(svc.unreadCount(.channel(0), network: .meshcore), 1, "reading one network's channel leaves the other unread")
        XCTAssertEqual(svc.networksInUse, [.meshtastic, .meshcore])
        _ = radio
    }

    func testRepliesGoOutOnTheConversationsNetwork() throws {
        let (svc, fake, radio) = try dual()
        let coreWrites = fake.writes(to: core).count
        let tasticWrites = fake.writes(to: tastic).count

        XCTAssertTrue(svc.send("on meshcore", to: .channel(0), network: .meshcore))
        radio.run()
        XCTAssertEqual(fake.writes(to: core).count, coreWrites + 1)
        XCTAssertEqual(fake.writes(to: core).last?.first, 3, "a MeshCore channel text")
        XCTAssertEqual(fake.writes(to: tastic).count, tasticWrites, "nothing goes to the other radio")

        XCTAssertTrue(svc.send("to alice", to: .direct(alice), network: .meshtastic))
        let p = try XCTUnwrap(try fake.lastSentPacket(to: tastic))
        XCTAssertEqual(p.to, alice)
        XCTAssertEqual(fake.writes(to: core).count, coreWrites + 1)

        XCTAssertEqual(svc.messages(in: .channel(0), network: .meshcore).last?.text, "on meshcore")
        XCTAssertEqual(svc.messages(in: .direct(alice), network: .meshtastic).map(\.text), ["to alice"])
        XCTAssertTrue(svc.messages(in: .direct(alice), network: .meshcore).isEmpty)
        XCTAssertEqual(Set(svc.directThreads.map(\.network)), [.meshtastic])

        // MeshCore's own limit applies on its network only.
        XCTAssertFalse(svc.send(String(repeating: "x", count: 150), to: .channel(0), network: .meshcore))
        XCTAssertTrue(svc.send(String(repeating: "x", count: 150), to: .channel(0), network: .meshtastic))
    }

    func testIncomingMessagesAreNeverBridged() throws {
        let (svc, fake, radio) = try dual()
        let tasticWrites = fake.writes(to: tastic).count
        radio.waiting = [CoreFrames.channelMessageV3(channel: 0, text: "Ana: core only", ts: UInt32(Date().timeIntervalSince1970))]
        fake.frame(Data([0x83]), from: core)
        radio.run()
        XCTAssertEqual(svc.messages(in: .channel(0), network: .meshcore).last?.text, "core only")
        XCTAssertEqual(fake.writes(to: tastic).count, tasticWrites, "a MeshCore message is not repeated on Meshtastic")
        XCTAssertFalse(svc.messages(in: .channel(0), network: .meshtastic).contains { $0.text == "core only" })
    }

    func testSOSGoesOutOnEveryConnectedRadio() async throws {
        let (svc, fake, radio) = try dual()
        let results = await svc.sendSOSEverywhere(includeLocation: false)
        radio.run()
        XCTAssertEqual(results.map(\.network), [.meshtastic, .meshcore])
        XCTAssertTrue(results.allSatisfy(\.sent))
        XCTAssertEqual(results.map(\.radioName), ["Tastic", "Core"])
        XCTAssertTrue(results[0].text?.contains("My Tastic") == true, "each network names its own radio")
        XCTAssertLessThanOrEqual(results[1].text?.utf8.count ?? 999, 133)
        let p = try XCTUnwrap(try fake.lastSentPacket(to: tastic))
        XCTAssertEqual(p.to, MeshConstants.broadcastNum)
        XCTAssertTrue(svc.messages(in: .channel(0), network: .meshcore).contains { $0.text.hasPrefix("SOS") })

        // With one radio gone, the other still sends and the result says so.
        svc.disconnect(.meshcore)
        let one = await svc.sendSOSEverywhere(includeLocation: false)
        XCTAssertEqual(one.map(\.network), [.meshtastic])
        XCTAssertEqual(one.first?.sent, true)
    }

    func testDisconnectingOneRadioLeavesTheOther() throws {
        let (svc, fake, _) = try dual()
        svc.disconnect(.meshtastic)
        XCTAssertEqual(svc.link(.meshtastic).connection, .idle)
        XCTAssertEqual(svc.link(.meshcore).connection, .connected(name: "Core"))
        XCTAssertEqual(fake.connectedIDs, [core])
        XCTAssertEqual(svc.defaultNetwork, .meshcore, "the only connected radio is the default")
        XCTAssertTrue(svc.canSend)
        XCTAssertFalse(svc.canSend(on: .meshtastic))
        XCTAssertFalse(svc.send("hi", to: .channel(0), network: .meshtastic))
        XCTAssertEqual(svc.notice, "Not connected to the Meshtastic radio.")
        // A link drop on one radio only touches that one.
        fake.onEvent?(.disconnected(id: core, reason: "Link lost", willReconnect: true))
        XCTAssertEqual(svc.link(.meshcore).connection, .reconnecting(name: "Core"))
        XCTAssertNotNil(svc.link(.meshtastic).deviceID, "still remembered")
    }

    // MARK: One per firmware

    func testSecondRadioOfTheSameFirmwareReplacesTheFirst() throws {
        let (svc, fake, _) = try dual()
        let other = UUID()
        let newcomer = device(other, "Tastic Two", .meshtastic)
        XCTAssertEqual(svc.replaced(by: newcomer)?.displayName, "Tastic", "the UI asks before replacing")
        XCTAssertNil(svc.replaced(by: device(tastic, "Tastic", .meshtastic)), "the same radio isn't a replacement")

        svc.connect(to: newcomer)
        XCTAssertFalse(fake.connectedIDs.contains(tastic), "the old Meshtastic radio is disconnected")
        XCTAssertTrue(fake.connectedIDs.contains(other))
        XCTAssertEqual(svc.link(.meshtastic).deviceID, other)
        XCTAssertEqual(svc.link(.meshcore).connection, .connected(name: "Core"), "the MeshCore radio is untouched")
        XCTAssertEqual(svc.rememberedLinks.count, 2)
    }

    func testRadioThatRunsTheOtherFirmwareMovesToItsSlot() throws {
        let fake = FakeMeshTransport()
        let svc = makeService(fake)
        svc.start()
        let id = UUID()
        // Its advert looked like Meshtastic, but it answers as MeshCore.
        svc.connect(to: device(id, "Mystery", .meshtastic))
        XCTAssertEqual(svc.link(.meshtastic).deviceID, id)
        fake.becomeReady(id, name: "Mystery", firmware: .meshcore)
        XCTAssertNil(svc.link(.meshtastic).deviceID)
        XCTAssertEqual(svc.link(.meshcore).deviceID, id)
        XCTAssertEqual(svc.link(.meshcore).connection, .syncing(name: "Mystery"))
    }

    // MARK: Persistence and migration

    func testBothRadiosAreRememberedAndReconnect() throws {
        let (svc, _, _) = try dual()
        svc.saveNow()
        let fake2 = FakeMeshTransport()
        let svc2 = makeService(fake2)
        XCTAssertEqual(svc2.link(.meshtastic).deviceName, "Tastic")
        XCTAssertEqual(svc2.link(.meshcore).deviceName, "Core")
        XCTAssertEqual(svc2.link(.meshtastic).nodes[alice]?.longName, "Alice")
        XCTAssertEqual(svc2.link(.meshcore).nodes.values.map(\.longName).sorted(), ["Ana", "Base Camp"])
        XCTAssertEqual(svc2.messages(in: .channel(0), network: .meshcore).map(\.text), ["anyone on?"])
        svc2.start()
        XCTAssertEqual(fake2.connectedIDs, [tastic, core])
        // The fake names every radio it connects "Test Radio".
        XCTAssertEqual(svc2.link(.meshcore).connection, .connecting(name: "Test Radio"))
        XCTAssertEqual(svc2.link(.meshtastic).connection, .connecting(name: "Test Radio"))
    }

    func testOldSingleRadioSettingsMoveIntoTheirSlot() throws {
        // What a single-radio version left behind: a MeshCore radio.
        let old = UUID()
        defaults.set(old.uuidString, forKey: "mesh.lastDeviceID")
        defaults.set("Old Core", forKey: "mesh.lastDeviceName")
        defaults.set(true, forKey: "mesh.autoReconnect")
        var legacyRadio = MeshRadioInfo()
        legacyRadio.firmware = .meshcore
        legacyRadio.myNodeNum = 0x55
        let legacy = MeshSnapshot(
            messages: [MeshMessage(packetID: 1, from: 0x66, to: MeshConstants.broadcastNum, channel: 0, text: "old",
                                   date: Date(), isOutgoing: false, delivery: .received)],
            nodes: [MeshNode(num: 0x55, longName: "Old Core"), MeshNode(num: 0x66, longName: "Friend")],
            radio: legacyRadio, lastRead: ["ch0": Date.distantFuture])
        try MeshStore(directory: dir).save(legacy)

        let fake = FakeMeshTransport()
        let svc = makeService(fake)
        XCTAssertEqual(svc.link(.meshcore).deviceID, old)
        XCTAssertEqual(svc.link(.meshcore).deviceName, "Old Core")
        XCTAssertNil(svc.link(.meshtastic).deviceID)
        XCTAssertEqual(svc.link(.meshcore).nodes[0x66]?.firmware, .meshcore)
        XCTAssertEqual(svc.messages.first?.network, .meshcore)
        XCTAssertEqual(svc.unreadCount(.channel(0), network: .meshcore), 0, "read markers move with it")
        XCTAssertEqual(svc.sortedNodes.map(\.longName), ["Friend"])
        XCTAssertNil(defaults.string(forKey: "mesh.lastDeviceID"), "the old keys are cleared")
        svc.start()
        XCTAssertEqual(fake.connectedIDs, [old], "it still reconnects by itself")

        // Migration runs once: a later launch keeps the slot as it is.
        svc.forgetDevice(.meshcore)
        XCTAssertNil(makeService(FakeMeshTransport()).link(.meshcore).deviceID)
    }

    func testOldSingleMeshtasticRadioMigratesToo() {
        let old = UUID()
        defaults.set(old.uuidString, forKey: "mesh.lastDeviceID")
        defaults.set("Old Tastic", forKey: "mesh.lastDeviceName")
        let svc = makeService(FakeMeshTransport())
        XCTAssertEqual(svc.link(.meshtastic).deviceID, old)
        XCTAssertFalse(svc.link(.meshtastic).autoReconnect, "it was disconnected, so it stays that way")
        XCTAssertEqual(svc.lastDeviceName, "Old Tastic")
    }

    // MARK: Coverage

    func testHeardEventsCarryTheirNetwork() throws {
        let (svc, fake, radio) = try dual()
        var heard: [MeshHeard] = []
        svc.onHeard = { heard.append($0) }
        fake.enqueue(.packet(MeshPBPacket(from: alice, to: MeshConstants.broadcastNum, decoded: MeshPBData(portnum: 3, payload: Data()),
                                          id: 9, rxTime: UInt32(Date().timeIntervalSince1970), rxSNR: 4)))
        fake.fromNumChanged(tastic)
        fake.pump()
        fake.frame(Data([0x80]) + Data(radio.ana), from: core)
        XCTAssertEqual(heard.map(\.firmware), [.meshtastic, .meshcore])
        XCTAssertEqual(heard.first?.node, alice)
    }

    func testCoverageRecordsBothNetworksAtTheSameSpot() {
        var clock = Date(timeIntervalSince1970: 1_800_000_000)
        let fix = CLLocation(coordinate: .init(latitude: 39.74, longitude: -104.99), altitude: 0, horizontalAccuracy: 8,
                             verticalAccuracy: 10, timestamp: clock)
        let file = dir.appending(path: "coverage.json")
        let store = CoverageStore(defaults: defaults, fileURL: file, location: { fix }, nodeName: { _, _ in "" },
                                  now: { clock }, controlsGPS: false)
        store.recording = true
        // Same node number on both networks, same place, same moment: two samples.
        store.record(MeshHeard(node: 0x42, snr: 3, hops: 0, firmware: .meshtastic))
        store.record(MeshHeard(node: 0x42, snr: nil, hops: 1, firmware: .meshcore))
        store.record(MeshHeard(node: 0x42, snr: 3, hops: 0, firmware: .meshtastic)) // throttled
        XCTAssertEqual(store.samples.map(\.firmware), [.meshtastic, .meshcore])
        clock += 1
        store.recordPing(.reached, at: fix, firmware: .meshcore)
        XCTAssertEqual(store.counts(.meshcore).samples, 1)
        XCTAssertEqual(store.counts(.meshcore).pings, 1)
        XCTAssertEqual(store.counts(.meshtastic).pings, 0)

        // Per-network cells for the map filter; the combined cell lists both.
        let both = CoverageShare.cells(from: store.samples, pings: store.pings, zones: [])
        XCTAssertEqual(both.count, 1)
        XCTAssertEqual(both.first?.firmwares, [.meshtastic, .meshcore])
        store.addZone(name: "Far away", latitude: 0, longitude: 0, radius: 100)
        XCTAssertEqual(store.sharedCells(for: .meshcore).first?.firmwares, [.meshcore])
        XCTAssertEqual(store.sharedCells(for: .meshcore).first?.pingsReached, 1)
        XCTAssertEqual(store.sharedCells(for: .meshtastic).first?.pingsReached, 0)
        XCTAssertEqual(store.sharedCells(for: nil).first?.count, 2)

        // The records stay readable: old files have the same fields.
        store.saveNow()
        let reloaded = CoverageStore(defaults: defaults, fileURL: file, location: { fix }, nodeName: { _, _ in "" },
                                     now: { clock }, controlsGPS: false)
        XCTAssertEqual(reloaded.samples.map(\.firmware), [.meshtastic, .meshcore])
        XCTAssertEqual(reloaded.pings.map(\.firmware), [.meshcore])
    }

    func testOptOutIsCheckedOnTheRightNetwork() {
        let fix = CLLocation(coordinate: .init(latitude: 39.74, longitude: -104.99), altitude: 0, horizontalAccuracy: 8,
                             verticalAccuracy: 10, timestamp: Date())
        // Node 7 is "Hidden ⛔" on MeshCore only.
        let store = CoverageStore(defaults: defaults, fileURL: dir.appending(path: "c.json"), location: { fix },
                                  nodeName: { n, f in n == 7 && f == .meshcore ? "Hidden ⛔" : "Open" }, controlsGPS: false)
        store.recording = true
        store.record(MeshHeard(node: 7, snr: 1, hops: 0, firmware: .meshcore))
        store.record(MeshHeard(node: 7, snr: 1, hops: 0, firmware: .meshtastic))
        XCTAssertEqual(store.samples.map(\.firmware), [.meshtastic])
    }

    // MARK: Drive mode

    func testDriveModePingsEachRadioInTurn() throws {
        let (svc, fake, radio) = try dual()
        var clock = Date()
        var fix = CLLocation(coordinate: .init(latitude: 39.74, longitude: -104.99), altitude: 0, horizontalAccuracy: 8,
                             verticalAccuracy: 10, timestamp: clock)
        let store = CoverageStore(defaults: defaults, fileURL: dir.appending(path: "cov.json"), location: { fix },
                                  nodeName: { _, _ in "" }, now: { clock }, controlsGPS: false)
        let drive = DriveMode(mesh: svc, coverage: store, defaults: defaults, now: { clock })
        let ana = try XCTUnwrap(svc.link(.meshcore).nodes.values.first { $0.longName == "Ana" })
        drive.setTarget(ana.num, for: .meshcore)
        drive.pinging = true

        let tasticBefore = fake.writes(to: tastic).count
        let coreBefore = fake.writes(to: core).count
        drive.tick()
        XCTAssertEqual(fake.writes(to: tastic).count, tasticBefore + 1, "Meshtastic pings first")
        XCTAssertEqual(fake.writes(to: core).count, coreBefore, "only one ping per tick")
        let p = try XCTUnwrap(try fake.lastSentPacket(to: tastic))
        XCTAssertEqual(p.decoded?.portnum, MeshService.pingPort)
        XCTAssertEqual(p.hopLimit, 1, "no Meshtastic target: any neighbour")

        drive.tick()
        radio.run()
        XCTAssertEqual(fake.writes(to: core).count, coreBefore + 1, "MeshCore's turn on the next tick")
        XCTAssertEqual(String(decoding: try XCTUnwrap(fake.writes(to: core).last).suffix(from: 13), as: UTF8.self), MeshService.pingText)
        XCTAssertEqual(drive.sent, 2)
        XCTAssertEqual(drive.waitingOn, [.meshtastic, .meshcore])

        // Answers come back per network and land on the map with their tag.
        fake.enqueue(.packet(MeshPBPacket(from: me, to: me,
                                          decoded: MeshPBData(portnum: MeshPort.routing.rawValue,
                                                              payload: MeshPBRouting(errorReason: 0).encode(), requestID: p.id),
                                          id: 1234)))
        fake.fromNumChanged(tastic)
        fake.pump()
        fake.frame(CoreFrames.sendConfirmed(ack: radio.nextAck - 1), from: core)
        XCTAssertEqual(store.pings.map(\.firmware), [.meshtastic, .meshcore])
        XCTAssertEqual(store.pings.map(\.outcome), [.relayed, .reached])
        XCTAssertEqual(drive.tallies[.meshtastic]?.relayed, 1)
        XCTAssertEqual(drive.tallies[.meshcore]?.reached, 1)

        // Nothing more until each radio has moved far enough and waited long enough.
        let quiet = fake.sent.count
        drive.tick()
        XCTAssertEqual(fake.sent.count, quiet)
        clock += DriveMode.minInterval + 1
        fix = CLLocation(coordinate: .init(latitude: 39.75, longitude: -104.99), altitude: 0, horizontalAccuracy: 8,
                         verticalAccuracy: 10, timestamp: clock)
        drive.tick()
        drive.tick()
        radio.run()
        XCTAssertEqual(drive.sent, 4)
    }

    func testDriveModeSaysWhyARadioIsntPinging() throws {
        let (svc, _, _) = try dual()
        let fix = CLLocation(coordinate: .init(latitude: 39.74, longitude: -104.99), altitude: 0, horizontalAccuracy: 8,
                             verticalAccuracy: 10, timestamp: Date())
        let store = CoverageStore(defaults: defaults, fileURL: dir.appending(path: "cov.json"), location: { fix },
                                  nodeName: { _, _ in "" }, controlsGPS: false)
        let drive = DriveMode(mesh: svc, coverage: store, defaults: defaults)
        drive.pinging = true
        drive.tick()
        XCTAssertEqual(drive.notes[.meshcore], "Waiting to hear a repeater")
        XCTAssertNil(drive.notes[.meshtastic])
        XCTAssertEqual(drive.tallies[.meshtastic]?.sent, 1, "the other radio still pings")
        XCTAssertEqual(drive.pingNote, "Waiting to hear a repeater")
    }

    func testDriveModeSurvivesTheAppBeingClosedAndLogsWhy() throws {
        let (svc, _, _) = try dual()
        let store = CoverageStore(defaults: defaults, fileURL: dir.appending(path: "cov.json"), location: { nil },
                                  nodeName: { _, _ in "" }, controlsGPS: false)
        let log = DriveLog(fileURL: dir.appending(path: "drive-log.txt"))
        let drive = DriveMode(mesh: svc, coverage: store, defaults: defaults, log: log)
        drive.start()
        let started = try XCTUnwrap(drive.startedAt)
        drive.tick()
        // iOS closes the app; a new launch picks the drive back up with the same start time.
        let again = DriveMode(mesh: svc, coverage: store, defaults: defaults, log: log)
        XCTAssertFalse(again.active)
        again.resumeIfNeeded()
        XCTAssertTrue(again.active)
        XCTAssertEqual(again.startedAt, started)
        again.stop()
        let third = DriveMode(mesh: svc, coverage: store, defaults: defaults, log: log)
        third.resumeIfNeeded()
        XCTAssertFalse(third.active, "a drive turned off stays off")
        let text = try String(contentsOf: dir.appending(path: "drive-log.txt"), encoding: .utf8)
        XCTAssertTrue(text.contains("drive mode on"))
        XCTAssertTrue(text.contains("MeshCore radio connected"))
        XCTAssertTrue(text.contains("still running"))
        XCTAssertTrue(text.contains("drive mode resumed after iOS closed the app"))
        XCTAssertTrue(text.contains("drive mode off: turned off"))
    }

    func testDriveModePingsTheRepeaterHeardWithoutAChoice() throws {
        let (svc, fake, radio) = try dual()
        let fix = CLLocation(coordinate: .init(latitude: 39.74, longitude: -104.99), altitude: 0, horizontalAccuracy: 8,
                             verticalAccuracy: 10, timestamp: Date())
        let store = CoverageStore(defaults: defaults, fileURL: dir.appending(path: "cov.json"), location: { fix },
                                  nodeName: { _, _ in "" }, controlsGPS: false)
        let drive = DriveMode(mesh: svc, coverage: store, defaults: defaults)
        drive.pinging = true
        fake.frame(CoreFrames.rxLog(snr: 5, CoreFrames.packet(route: 1, type: 5, path: [[0xC7]], payload: [1])), from: core)
        drive.tick() // Meshtastic pings first (both never pinged; ties go by network)
        drive.tick()
        radio.run()
        XCTAssertNil(drive.notes[.meshcore])
        XCTAssertEqual(drive.tallies[.meshcore]?.sent, 1)
        XCTAssertEqual(fake.writes(to: core).last?.first, 36)
    }

    func testOldDriveTargetMovesToTheNetworkThatKnowsIt() throws {
        let (svc, _, _) = try dual()
        let ana = try XCTUnwrap(svc.link(.meshcore).nodes.values.first { $0.longName == "Ana" })
        defaults.set(Int(ana.num), forKey: "drive.target")
        let store = CoverageStore(defaults: defaults, fileURL: dir.appending(path: "cov.json"), location: { nil },
                                  nodeName: { _, _ in "" }, controlsGPS: false)
        let drive = DriveMode(mesh: svc, coverage: store, defaults: defaults)
        XCTAssertEqual(drive.target(for: .meshcore), ana.num)
        XCTAssertNil(drive.target(for: .meshtastic))
    }

    // MARK: Watch

    func testWatchRepliesUseTheMessagesNetwork() async throws {
        let (svc, fake, radio) = try dual()
        let bridge = WatchBridge(mesh: svc)
        let status = bridge.status()
        XCTAssertEqual(status.networks, ["meshtastic", "meshcore"])
        let incoming = try XCTUnwrap(status.messages.first { $0.text == "anyone on?" })
        XCTAssertEqual(incoming.network, "meshcore")
        XCTAssertEqual(incoming.conversationTitle, "Public · MeshCore")
        XCTAssertEqual(status.maxTextBytes, 133, "the tighter limit while both are up")

        let coreBefore = fake.writes(to: core).count
        let response = await bridge.handle(.reply(text: "OK", conversation: incoming.conversation, network: incoming.network))
        radio.run()
        XCTAssertTrue(response.ok)
        XCTAssertEqual(fake.writes(to: core).count, coreBefore + 1)
        XCTAssertEqual(svc.messages(in: .channel(0), network: .meshcore).last?.text, "OK")

        // An older watch app sends no network: the phone's default radio takes it.
        let old = try WatchRelay.encode(WatchRelay.Request.reply(text: "On my way", conversation: .channel(0)))
        let json = String(decoding: try XCTUnwrap(old[WatchRelay.payloadKey] as? Data), as: UTF8.self)
        XCTAssertFalse(json.contains("network"))
        let decoded = try WatchRelay.decode(WatchRelay.Request.self, from: old)
        _ = await bridge.handle(decoded)
        XCTAssertEqual(svc.messages(in: .channel(0), network: .meshtastic).last?.text, "On my way")

        let sos = await bridge.handle(.sos(latitude: 39.7, longitude: -104.9))
        radio.run()
        XCTAssertTrue(sos.ok)
        XCTAssertTrue(sos.detail.contains("Meshtastic: sent"))
        XCTAssertTrue(sos.detail.contains("MeshCore: sent"))
    }
}
