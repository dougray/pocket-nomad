import CoreLocation
import XCTest
@testable import NomadPocket

@MainActor
final class CoverageTests: XCTestCase {
    var suite: String!
    var defaults: UserDefaults!
    var file: URL!
    var fix: CLLocation?
    var clock = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUp() async throws {
        suite = "coverage-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        file = FileManager.default.temporaryDirectory.appending(path: "\(suite!).json")
        fix = nil
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: file)
    }

    private func store() -> CoverageStore {
        CoverageStore(defaults: defaults, fileURL: file, location: { [unowned self] in self.fix },
                      now: { [unowned self] in self.clock }, controlsGPS: false)
    }

    private func at(_ lat: Double, _ lon: Double, accuracy: Double = 8, age: TimeInterval = 2) -> CLLocation {
        CLLocation(coordinate: .init(latitude: lat, longitude: lon), altitude: 0, horizontalAccuracy: accuracy,
                   verticalAccuracy: 10, timestamp: clock.addingTimeInterval(-age))
    }

    private let heard = MeshHeard(node: 0xABCD, snr: 6.5, hops: 1, firmware: .meshtastic)

    func testNothingIsRecordedUntilTurnedOn() {
        let s = store()
        XCTAssertFalse(s.recording, "off by default")
        XCTAssertFalse(s.sharing, "sharing off by default")
        fix = at(39.74, -104.99)
        s.record(heard)
        XCTAssertTrue(s.samples.isEmpty)
    }

    func testRecordsFreshAccurateFixesAndThrottles() {
        let s = store()
        s.recording = true
        fix = at(39.74, -104.99, age: 60)          // stale
        s.record(heard)
        fix = at(39.74, -104.99, accuracy: 500)    // inaccurate
        s.record(heard)
        XCTAssertTrue(s.samples.isEmpty)

        fix = at(39.74, -104.99)
        s.record(heard)
        XCTAssertEqual(s.samples.count, 1)
        XCTAssertEqual(s.samples.first?.snr, 6.5)
        clock += 5
        fix = at(39.74, -104.99)
        s.record(heard)                             // same node, same spot, 5 s later
        XCTAssertEqual(s.samples.count, 1)
        fix = at(39.75, -104.99)                    // moved ~1 km
        s.record(heard)
        XCTAssertEqual(s.samples.count, 2)
        s.record(MeshHeard(node: 0x1234, snr: nil, hops: nil, firmware: .meshcore)) // another node
        XCTAssertEqual(s.samples.count, 3)
    }

    func testRetentionAndDeleteAll() {
        let s = store()
        s.recording = true
        fix = at(39.74, -104.99)
        s.record(heard)
        s.saveNow()
        clock += 40 * 86_400
        fix = at(39.75, -104.99)
        s.record(heard)
        s.retentionDays = 30
        XCTAssertEqual(s.samples.count, 1, "the 40-day-old sample is pruned")
        s.deleteAll()
        XCTAssertTrue(s.samples.isEmpty)
        XCTAssertTrue(store().samples.isEmpty, "deletion is saved")
    }

    func testSharingNeedsAPrivateZone() {
        let s = store()
        s.sharing = true
        XCTAssertFalse(s.sharing)
        XCTAssertThrowsError(try s.exportFile())
        s.addZone(name: "Home", latitude: 39.74, longitude: -104.99, radius: 500)
        s.sharing = true
        XCTAssertTrue(s.sharing)
        s.removeZones(at: [0])
        XCTAssertFalse(s.sharing, "removing the last zone turns sharing off")
    }

    func testHexBinningIsDeterministicAndGroupsNearbyPoints() {
        let a = CoverageShare.hex(latitude: 39.7392, longitude: -104.9903)
        XCTAssertTrue(a == CoverageShare.hex(latitude: 39.7392, longitude: -104.9903))
        XCTAssertTrue(a == CoverageShare.hex(latitude: 39.73925, longitude: -104.99035), "a few metres away")
        XCTAssertFalse(a == CoverageShare.hex(latitude: 39.76, longitude: -104.99), "2 km away")
        // the centre of a cell maps back to the same cell
        let c = CoverageShare.center(q: a.q, r: a.r)
        XCTAssertTrue(a == CoverageShare.hex(latitude: c.latitude, longitude: c.longitude))
        // cells are roughly 350 m across at this latitude
        let corners = CoverageShare.corners(q: a.q, r: a.r)
        let width = CoverageShare.distance(corners[0].latitude, corners[0].longitude, corners[3].latitude, corners[3].longitude)
        XCTAssertEqual(width, 2 * 400 * cos(39.74 * .pi / 180), accuracy: 30)
    }

    func testSharedCellsDropPrivateZonesAndKeepOnlySummaries() throws {
        let home = CoverageZone(name: "Home", latitude: 39.74, longitude: -104.99, radius: 500)
        let day = Date(timeIntervalSince1970: 1_800_000_000)
        let samples = [
            CoverageSample(date: day, latitude: 39.7401, longitude: -104.9901, accuracy: 5, firmware: .meshtastic, node: 1, snr: 9, hops: 0),   // at home
            CoverageSample(date: day, latitude: 39.7445, longitude: -104.99, accuracy: 5, firmware: .meshtastic, node: 1, snr: 4, hops: 0),     // ~500 m: cell touches home
            CoverageSample(date: day, latitude: 39.80, longitude: -104.99, accuracy: 5, firmware: .meshtastic, node: 2, snr: -3, hops: 1),     // 6.7 km away
            CoverageSample(date: day.addingTimeInterval(86_400 * 40), latitude: 39.80001, longitude: -104.99001, accuracy: 5,
                           firmware: .meshcore, node: 3, snr: 2, hops: 2),
        ]
        let cells = CoverageShare.cells(from: samples, zones: [home])
        XCTAssertEqual(cells.count, 1, "only the far cell survives")
        let cell = try XCTUnwrap(cells.first)
        XCTAssertEqual(cell.count, 2)
        XCTAssertEqual(cell.bestSNR, 2)
        XCTAssertEqual(cell.firmwares, [.meshtastic, .meshcore])

        let json = try XCTUnwrap(String(data: CoverageShare.geoJSON(cells), encoding: .utf8))
        for secret in ["node", "name", "39.8,", "39.80001", "accuracy", "hops", "date\""] {
            XCTAssertFalse(json.contains(secret), "export must not contain \(secret)")
        }
        XCTAssertTrue(json.contains("\"best_snr\""))
        XCTAssertTrue(json.contains("\"first_month\""))
        let doc = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let feature = try XCTUnwrap((doc["features"] as? [[String: Any]])?.first)
        let ring = try XCTUnwrap(((feature["geometry"] as? [String: Any])?["coordinates"] as? [[[Double]]])?.first)
        XCTAssertEqual(ring.count, 7, "a closed hexagon")
        XCTAssertEqual(Set((feature["properties"] as? [String: Any] ?? [:]).keys),
                       ["count", "best_snr", "firmware", "first_month", "last_month"])
    }

    func testDeleteMeshHistoryKeepsOnlyThisRadio() {
        let fake = FakeMeshTransport()
        let radio = FakeMeshCoreRadio(fake)
        let dir = FileManager.default.temporaryDirectory.appending(path: "del-mesh-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        radio.waiting = [CoreFrames.channelMessageV3(channel: 0, text: "Ana: hello", ts: 1_790_000_000)]
        let svc = MeshService(transport: fake, store: MeshStore(directory: dir), defaults: defaults)
        svc.start()
        svc.connect(to: MeshDiscoveredDevice(id: UUID(), name: "R", rssi: -50, lastSeen: Date(), firmware: .meshcore))
        fake.onEvent?(.ready(id: fake.connectedID ?? UUID(), name: "R", firmware: .meshcore))
        radio.run()
        XCTAssertFalse(svc.messages.isEmpty)
        XCTAssertFalse(svc.sortedNodes.isEmpty)
        svc.deleteHistory()
        XCTAssertTrue(svc.messages.isEmpty)
        XCTAssertTrue(svc.sortedNodes.isEmpty)
        XCTAssertEqual(svc.myNode?.longName, "Base Camp", "this radio's own entry stays")
        let reloaded = MeshService(transport: FakeMeshTransport(), store: MeshStore(directory: dir), defaults: defaults)
        XCTAssertTrue(reloaded.messages.isEmpty, "the deletion is saved")
    }

    func testMeshServiceReportsLiveMeshCoreTrafficOnly() {
        let fake = FakeMeshTransport()
        let radio = FakeMeshCoreRadio(fake)
        let dir = FileManager.default.temporaryDirectory.appending(path: "cov-mesh-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let svc = MeshService(transport: fake, store: MeshStore(directory: dir), defaults: defaults)
        var heard: [MeshHeard] = []
        svc.onHeard = { heard.append($0) }
        // A message the radio stored long ago (replayed on connect) is not "heard here".
        radio.waiting = [CoreFrames.channelMessageV3(channel: 0, text: "Ana: old news", ts: 1_000_000)]
        svc.start()
        svc.connect(to: MeshDiscoveredDevice(id: UUID(), name: "R", rssi: -50, lastSeen: Date(), firmware: .meshcore))
        fake.onEvent?(.ready(id: fake.connectedID ?? UUID(), name: "R", firmware: .meshcore))
        radio.run()
        XCTAssertTrue(heard.isEmpty, "contacts from the handshake and old messages don't count")
        // A live advert does.
        fake.onEvent?(.frame(Data([0x80]) + Data(radio.ana)))
        XCTAssertEqual(heard.count, 1)
        XCTAssertEqual(heard.first?.firmware, .meshcore)
        // So does a fresh message.
        radio.waiting = [CoreFrames.channelMessageV3(channel: 0, text: "Ana: hi", ts: UInt32(Date().timeIntervalSince1970))]
        fake.onEvent?(.frame(Data([0x83])))
        radio.run()
        XCTAssertEqual(heard.count, 2)
        XCTAssertEqual(heard.last?.snr, 5.5)
    }
}
