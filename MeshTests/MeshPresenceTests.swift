// MeshPresenceTests.swift
// Pocket Nomad - Mesh module tests
// SPDX-License-Identifier: Apache-2.0
//
// Whether a radio is here: brief drops at the edge of Bluetooth range that
// shouldn't show, a radio left behind that becomes "not nearby" (and still
// comes back by itself), "not with me today", and how drive mode and its log
// treat all three. Also the map side of a drive: pings binned into hexagons
// and the "Radios heard" layer. A movable clock stands in for time.

import CoreBluetooth
import CoreLocation
import XCTest
#if canImport(NomadPocket)
@testable import NomadPocket
#endif

@MainActor
final class MeshPresenceTests: XCTestCase {
    let me: UInt32 = 0x1111_1111
    let alice: UInt32 = 0xAAAA_0001
    let radioID = UUID()

    var dir: URL!
    var defaults: UserDefaults!
    var suiteName: String!
    var clock = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("presence-tests-\(UUID().uuidString)")
        suiteName = "presence-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func makeService(_ fake: FakeMeshTransport) -> MeshService {
        MeshService(transport: fake, store: MeshStore(directory: dir), defaults: defaults, now: { [unowned self] in self.clock })
    }

    /// Runs the Meshtastic handshake with one other node, Alice (who reports a position in Denver).
    private func handshake(_ fake: FakeMeshTransport) throws {
        fake.becomeReady(radioID, name: "Tastic", firmware: .meshtastic)
        let cfg = try XCTUnwrap(try fake.lastWantConfigID(to: radioID))
        fake.enqueue(.myInfo(MeshPBMyNodeInfo(myNodeNum: me)))
        fake.enqueue(.nodeInfo(MeshPBNodeInfo(num: me, user: MeshPBUser(longName: "My Tastic", shortName: "MT"))))
        fake.enqueue(.nodeInfo(MeshPBNodeInfo(num: alice, user: MeshPBUser(longName: "Alice", shortName: "AL"),
                                              position: MeshPBPosition(latitudeI: 397_392_400, longitudeI: -1_049_902_500))))
        fake.enqueue(.channel(MeshPBChannel(index: 0, name: "LongFast", hasPSK: true, role: 1)))
        fake.enqueue(.loraConfig(MeshPBLoRaConfig(region: 1, hopLimit: 3)))
        fake.enqueue(.configCompleteID(cfg))
        fake.pump()
    }

    private func connected() throws -> (MeshService, FakeMeshTransport) {
        let fake = FakeMeshTransport()
        let svc = makeService(fake)
        svc.start()
        svc.connect(to: MeshDiscoveredDevice(id: radioID, name: "Tastic", rssi: -50, lastSeen: clock, firmware: .meshtastic))
        try handshake(fake)
        XCTAssertTrue(svc.link(.meshtastic).connection.isConnected)
        return (svc, fake)
    }

    /// The radio drops off Bluetooth; iOS keeps a pending connect open.
    private func drop(_ fake: FakeMeshTransport, reason: String? = "out of range (the link timed out)") {
        fake.onEvent?(.disconnected(id: radioID, reason: reason, willReconnect: true))
    }

    // MARK: Flicker

    func testDropUnderTwentySecondsDoesNotChangeWhatIsShown() throws {
        let (svc, fake) = try connected()
        let l = svc.link(.meshtastic)
        drop(fake)
        XCTAssertEqual(l.connection, .reconnecting(name: "Tastic"), "the real state is down: nothing is written to it")
        XCTAssertTrue(l.displayedConnection.isConnected)
        XCTAssertTrue(svc.connection.isConnected)
        clock += 10
        svc.updateLinks()
        XCTAssertTrue(l.displayedConnection.isConnected)
        fake.becomeReady(radioID, name: "Tastic", firmware: .meshtastic)
        XCTAssertEqual(l.connection, .syncing(name: "Tastic"))
        XCTAssertTrue(l.displayedConnection.isConnected, "still shown as connected while it syncs again")
        try handshake(fake) // back within the grace
        clock += 5
        svc.updateLinks()
        XCTAssertTrue(l.connection.isConnected)
        XCTAssertTrue(l.displayedConnection.isConnected)
        XCTAssertEqual(l.briefDrops, 1)

        // A drop that lasts past the grace does show.
        drop(fake)
        clock += MeshLink.dropGrace + 1
        svc.updateLinks()
        XCTAssertEqual(l.displayedConnection, .reconnecting(name: "Tastic"))
        XCTAssertEqual(l.lastDropReason, "out of range (the link timed out)")
        try handshake(fake)
        XCTAssertTrue(l.displayedConnection.isConnected)
        XCTAssertEqual(l.briefDrops, 1, "a long drop isn't counted as brief")
    }

    // MARK: Not nearby

    func testRadioUnseenForTwoMinutesIsNotNearbyThenComesBackByItself() throws {
        let (svc, fake) = try connected()
        let l = svc.link(.meshtastic)
        drop(fake)
        clock += 60
        svc.updateLinks()
        XCTAssertEqual(l.connection, .reconnecting(name: "Tastic"))
        clock += 61
        svc.updateLinks()
        XCTAssertEqual(l.connection, .notNearby(name: "Tastic"))
        XCTAssertEqual(l.displayedConnection, .notNearby(name: "Tastic"))
        XCTAssertTrue(fake.connectedIDs.contains(radioID), "the pending connect is kept")
        XCTAssertTrue(l.autoReconnect)

        // Quiet while away: no notices from failed retries, no flipping back to "connecting".
        svc.notice = nil
        fake.onEvent?(.connectFailed(id: radioID, reason: "Could not connect."))
        fake.onEvent?(.connecting(id: radioID, name: "Tastic"))
        XCTAssertNil(svc.notice)
        XCTAssertEqual(l.connection, .notNearby(name: "Tastic"))

        // Back from the store: iOS completes the pending connect.
        try handshake(fake)
        XCTAssertTrue(l.connection.isConnected)
        XCTAssertTrue(l.displayedConnection.isConnected)
        clock += 300
        svc.updateLinks()
        XCTAssertTrue(l.connection.isConnected)
    }

    func testRadioLeftAtHomeIsNotNearbyAfterLaunch() throws {
        _ = try connected()
        // Next launch, the radio isn't there.
        let fake = FakeMeshTransport()
        let svc = makeService(fake)
        svc.start()
        let l = svc.link(.meshtastic)
        XCTAssertTrue(l.connection.isWaiting)
        clock += MeshService.notNearbyAfter
        svc.updateLinks()
        XCTAssertEqual(l.connection, .notNearby(name: l.displayName))
        // Tapping Connect gives it two more minutes.
        svc.reconnect(.meshtastic)
        XCTAssertTrue(l.connection.isWaiting)
        clock += 60
        svc.updateLinks()
        XCTAssertTrue(l.connection.isWaiting)
        clock += 61
        svc.updateLinks()
        XCTAssertEqual(l.connection, .notNearby(name: l.displayName))
    }

    // MARK: Not with me today

    func testNotWithMeTodayStopsReconnectingButKeepsAutoReconnect() throws {
        let (svc, fake) = try connected()
        let l = svc.link(.meshtastic)
        drop(fake)
        svc.notWithMeToday(.meshtastic)
        XCTAssertEqual(l.connection, .notWithMeToday(name: "Tastic"))
        XCTAssertEqual(l.displayedConnection, .notWithMeToday(name: "Tastic"))
        XCTAssertFalse(fake.connectedIDs.contains(radioID), "the pending connect is cancelled")
        XCTAssertTrue(l.autoReconnect)
        XCTAssertTrue(defaults.bool(forKey: "mesh.meshtastic.autoReconnect"), "still reconnects at the next launch")
        clock += 600
        svc.updateLinks()
        XCTAssertEqual(l.connection, .notWithMeToday(name: "Tastic"))

        // Connect clears it.
        svc.reconnect(.meshtastic)
        XCTAssertTrue(fake.connectedIDs.contains(radioID))
        try handshake(fake)
        XCTAssertTrue(l.connection.isConnected)

        // So does the next launch.
        svc.notWithMeToday(.meshtastic)
        let fake2 = FakeMeshTransport()
        let next = makeService(fake2)
        next.start()
        XCTAssertTrue(fake2.connectedIDs.contains(radioID))
        XCTAssertTrue(next.link(.meshtastic).connection.isWaiting)
    }

    // MARK: Drive mode and its log

    func testDriveModeTreatsAwayRadiosAsAbsentAndLogsWhy() throws {
        let (svc, fake) = try connected()
        let l = svc.link(.meshtastic)
        let fix = CLLocation(coordinate: .init(latitude: 39.74, longitude: -104.99), altitude: 0, horizontalAccuracy: 8,
                             verticalAccuracy: 10, timestamp: clock)
        let store = CoverageStore(defaults: defaults, fileURL: dir.appending(path: "cov.json"), location: { fix },
                                  nodeName: { _, _ in "" }, now: { [unowned self] in self.clock }, controlsGPS: false)
        let logURL = dir.appending(path: "drive-log.txt")
        let drive = DriveMode(mesh: svc, coverage: store, defaults: defaults, now: { [unowned self] in self.clock },
                              log: DriveLog(fileURL: logURL))
        drive.pinging = true
        drive.start()
        drive.tick()
        XCTAssertEqual(drive.sent, 1)

        // A brief drop: no ping (the real link is down) but no "waiting" either.
        drop(fake)
        clock += 5
        drive.tick()
        XCTAssertNil(drive.pingNote)
        XCTAssertEqual(drive.sent, 1)
        try handshake(fake)
        clock += 61
        drive.tick() // heartbeat

        // A long one shows, with its reason, then becomes "not nearby".
        drop(fake)
        clock += MeshLink.dropGrace + 1
        drive.tick()
        XCTAssertEqual(drive.pingNote, "Waiting for the radio")
        clock += MeshService.notNearbyAfter
        drive.tick()
        XCTAssertEqual(l.connection, .notNearby(name: "Tastic"))
        XCTAssertNil(drive.pingNote, "a radio that isn't here isn't waited for")
        XCTAssertTrue(drive.notes.isEmpty)
        let sent = fake.sent.count
        clock += 600
        drive.tick()
        XCTAssertEqual(fake.sent.count, sent, "no pings")
        svc.notWithMeToday(.meshtastic)
        drive.tick()
        XCTAssertNil(drive.pingNote)
        drive.stop()

        let text = try String(contentsOf: logURL, encoding: .utf8)
        XCTAssertTrue(text.contains("brief drop ×1"), text)
        XCTAssertTrue(text.contains("Meshtastic radio dropped, reconnecting (out of range (the link timed out))"), text)
        XCTAssertTrue(text.contains("Meshtastic radio not nearby"), text)
        XCTAssertTrue(text.contains("Meshtastic radio not with me today"), text)
        XCTAssertEqual(text.components(separatedBy: "pings waiting: Waiting for the radio").count - 1, 1,
                       "logged once, for the long drop only")
    }

    func testDropReasonsInWords() {
        XCTAssertEqual(MeshBLETransport.dropReason(CBError(.connectionTimeout)), "out of range (the link timed out)")
        XCTAssertEqual(MeshBLETransport.dropReason(CBError(.peripheralDisconnected)),
                       "the radio closed the link (restarted or switched off)")
        XCTAssertNil(MeshBLETransport.dropReason(nil))
    }

    // MARK: Map: pings as hexagons, radios heard

    private func ping(_ outcome: MeshPingOutcome, _ lat: Double, _ lon: Double, _ f: MeshFirmware) -> CoveragePing {
        CoveragePing(date: clock, latitude: lat, longitude: lon, accuracy: 5, firmware: f, outcome: outcome)
    }

    func testPingsBinIntoHexagonsWithTheBestOutcomePerNetwork() {
        // Points close around the centre of the downtown Denver hexagon, so they share it.
        let downtown = CoverageShare.hex(latitude: 39.7480, longitude: -104.9970)
        let c = CoverageShare.center(q: downtown.q, r: downtown.r)
        let pings = [
            ping(.none, c.latitude, c.longitude, .meshtastic),
            ping(.reached, c.latitude + 0.0004, c.longitude - 0.0004, .meshtastic),
            ping(.relayed, c.latitude - 0.0004, c.longitude + 0.0004, .meshtastic),
            ping(.none, c.latitude, c.longitude, .meshcore),
            ping(.relayed, 39.7700, -104.9500, .meshtastic),
        ]
        let cells = CoverageShare.pingCells(from: pings)
        XCTAssertEqual(cells.count, 3, "one per hexagon and network, never one per ping")
        let tasticHere = cells.first { $0.q == downtown.q && $0.r == downtown.r && $0.firmware == .meshtastic }
        XCTAssertEqual(tasticHere?.best, .reached)
        XCTAssertEqual(tasticHere?.count, 3)
        XCTAssertEqual(cells.first { $0.q == downtown.q && $0.r == downtown.r && $0.firmware == .meshcore }?.best, MeshPingOutcome.none)
        XCTAssertEqual(cells.filter { $0.best == .relayed }.count, 1)

        // A private zone drops the pings near it.
        let zone = CoverageZone(name: "Zone", latitude: 39.7700, longitude: -104.9500, radius: 300)
        XCTAssertEqual(CoverageShare.pingCells(from: pings, zones: [zone]).count, 2)
    }

    func testExportedFileHasOnlyHexagonsNeverPingPoints() throws {
        let s = CoverageStore(defaults: defaults, fileURL: dir.appending(path: "cov.json"), location: { nil },
                              nodeName: { _, _ in "" }, now: { [unowned self] in self.clock }, controlsGPS: false)
        let spots = [(39.7480, -104.9970), (39.7530, -104.9900), (39.7600, -104.9800)]
        for (lat, lon) in spots {
            s.recordPing(.reached, at: CLLocation(latitude: lat, longitude: lon), firmware: .meshtastic)
        }
        s.addZone(name: "Zone", latitude: 39.6000, longitude: -105.2000, radius: 300)
        s.sharing = true
        let url = try s.exportFile()
        let doc = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let features = try XCTUnwrap(doc["features"] as? [[String: Any]])
        XCTAssertEqual(features.count, 3)
        for f in features {
            let geometry = try XCTUnwrap(f["geometry"] as? [String: Any])
            XCTAssertEqual(geometry["type"] as? String, "Polygon")
            let ring = try XCTUnwrap((geometry["coordinates"] as? [[[Double]]])?.first)
            for corner in ring {
                XCTAssertFalse(spots.contains { abs($0.0 - corner[1]) < 1e-6 && abs($0.1 - corner[0]) < 1e-6 },
                               "no ping position is written out")
            }
        }
        // On the phone the map gets cells too.
        XCTAssertEqual(s.pingCells(for: nil).count, 3)
        XCTAssertEqual(s.pings.count, 3, "the raw pings stay stored")
    }

    func testRadiosHeardLeaveOutOwnRadiosAndNodesWithoutAPosition() throws {
        var repeater = MeshNode(num: 0xC0FFEE)
        repeater.longName = "Ridge Repeater"
        repeater.latitude = 39.7480
        repeater.longitude = -104.9970
        repeater.meshCoreType = .repeater
        repeater.network = .meshcore
        var person = MeshNode(num: 0xA1)
        person.latitude = 39.7560
        person.longitude = -104.9870
        person.network = .meshtastic
        var noPosition = MeshNode(num: 0xA2)
        noPosition.network = .meshtastic
        var own = MeshNode(num: me)
        own.latitude = 39.7392
        own.longitude = -104.9849
        own.network = .meshtastic
        var blocked = person
        blocked.num = 0xA3

        let shown = HeardRadios.mapped([repeater, person, noPosition, own, blocked],
                                       isOwn: { num, _ in num == self.me }, blocked: [0xA3])
        XCTAssertEqual(Set(shown.map(\.num)), [0xC0FFEE, 0xA1])
        XCTAssertEqual(HeardRadios.summary(shown), "1 Meshtastic radio and 1 MeshCore repeater heard")
        XCTAssertEqual(HeardRadios.summary([]), "No radios with a known position heard yet")

        // Through MeshService: our own radio is left out even with a position.
        let (svc, _) = try connected()
        svc.link(.meshtastic).update(me) { n in n.latitude = 39.7392; n.longitude = -104.9849 }
        let mapped = HeardRadios.mapped(in: svc)
        XCTAssertEqual(mapped.map(\.num), [alice])
        XCTAssertFalse(mapped.contains { $0.num == me })
    }

    func testMeshtasticRoutersCountAsRepeaters() {
        var n = MeshNode(num: 1)
        XCTAssertFalse(n.isRepeater)
        n.role = 2
        XCTAssertTrue(n.isRepeater)
        n.role = 0
        XCTAssertFalse(n.isRepeater)
        n.meshCoreType = .room
        XCTAssertTrue(n.isRepeater)
    }
}
