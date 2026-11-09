import CoreLocation
import XCTest
@testable import NomadPocket

@MainActor
final class DriveAndKnownRadioTests: XCTestCase {
    var suite: String!
    var defaults: UserDefaults!
    var file: URL!
    var fix: CLLocation?
    let clock = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUp() async throws {
        suite = "drive-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        file = FileManager.default.temporaryDirectory.appending(path: "\(suite!).json")
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: file)
        try? FileManager.default.removeItem(at: file.deletingPathExtension().appendingPathExtension("pings.json"))
    }

    private func store(names: [UInt32: String] = [:]) -> CoverageStore {
        CoverageStore(defaults: defaults, fileURL: file, location: { [unowned self] in self.fix },
                      nodeName: { n, _ in names[n] ?? "" }, now: { [unowned self] in self.clock }, controlsGPS: false)
    }

    private func at(_ lat: Double, _ lon: Double) -> CLLocation {
        CLLocation(coordinate: .init(latitude: lat, longitude: lon), altitude: 0, horizontalAccuracy: 8,
                   verticalAccuracy: 10, timestamp: clock.addingTimeInterval(-2))
    }

    func testOptOutEmojiMatchesWithOrWithoutVariationSelector() {
        XCTAssertTrue(CoverageStore.optsOut("Base ⛔"))
        XCTAssertTrue(CoverageStore.optsOut("Base \u{26D4}\u{FE0F}"))
        XCTAssertTrue(CoverageStore.optsOut("🛑 Hilltop"))
        XCTAssertTrue(CoverageStore.optsOut("Car🚫"))
        XCTAssertFalse(CoverageStore.optsOut("Denver Repeater 📡"))
    }

    func testOptedOutRadiosAreNeverRecordedOrShared() {
        let s = store(names: [1: "Home ⛔", 2: "Hilltop"])
        s.recording = true
        fix = at(39.74, -104.99)
        s.record(MeshHeard(node: 1, snr: 5, hops: 0, firmware: .meshtastic))
        s.record(MeshHeard(node: 2, snr: 5, hops: 0, firmware: .meshtastic))
        XCTAssertEqual(s.samples.map(\.node), [2])
    }

    func testPingsAreSavedZonedAndExported() throws {
        let s = store()
        s.recordPing(.reached, at: at(39.74, -104.99), firmware: .meshtastic)
        s.recordPing(.none, at: at(39.74, -104.99), firmware: .meshtastic)
        s.recordPing(.relayed, at: at(39.77, -104.95), firmware: .meshcore)
        s.saveNow()
        XCTAssertEqual(store().pings.count, 3, "pings persist")

        s.addZone(name: "Home", latitude: 39.77, longitude: -104.95, radius: 300)
        let cells = s.sharedCells
        XCTAssertEqual(cells.count, 1, "the ping inside the private zone is dropped")
        XCTAssertEqual(cells[0].count, 0)
        XCTAssertEqual([cells[0].pingsReached, cells[0].pingsRelayed, cells[0].pingsNone], [1, 0, 1])

        let doc = try XCTUnwrap(JSONSerialization.jsonObject(with: CoverageShare.geoJSON(cells)) as? [String: Any])
        let props = try XCTUnwrap(((doc["features"] as? [[String: Any]])?.first?["properties"]) as? [String: Any])
        XCTAssertEqual(props["pings_reached"] as? Int, 1)
        XCTAssertEqual(props["pings_none"] as? Int, 1)
        XCTAssertEqual(props["count"] as? Int, 0)
        XCTAssertNil(props["node"])
    }

    func testCellsWithoutPingsLeaveThePingFieldsOut() throws {
        let sample = CoverageSample(date: clock, latitude: 39.74, longitude: -104.99, accuracy: 5,
                                    firmware: .meshtastic, node: 9, snr: 3, hops: 0)
        let cells = CoverageShare.cells(from: [sample], zones: [])
        let doc = try XCTUnwrap(JSONSerialization.jsonObject(with: CoverageShare.geoJSON(cells)) as? [String: Any])
        let props = try XCTUnwrap(((doc["features"] as? [[String: Any]])?.first?["properties"]) as? [String: Any])
        XCTAssertNil(props["pings_reached"])
        XCTAssertEqual(props["count"] as? Int, 1)
    }

    func testParsesMeshmapNodes() throws {
        let json = """
        {"1002094074": {"longName": " Hill Router ", "shortName": "HR", "role": "ROUTER", "latitude": 397392000, "longitude": -1049903000,
                        "seenBy": {"msh/US/a": 1793801450, "msh/US/b": 1793803896}},
         "55": {"longName": "", "shortName": "CL", "role": "CLIENT_MUTE", "latitude": 397420000, "longitude": -1049872000, "seenBy": {}},
         "77": {"longName": "No position", "role": "CLIENT"}}
        """
        let radios = try KnownRadioStore.parseMeshtastic(Data(json.utf8)).sorted { $0.id < $1.id }
        XCTAssertEqual(radios.count, 2)
        let router = try XCTUnwrap(radios.first { $0.id == "t1002094074" })
        XCTAssertEqual(router.name, "Hill Router")
        XCTAssertEqual(router.role, .repeater)
        XCTAssertEqual(router.firmware, .meshtastic)
        XCTAssertEqual(router.latitude, 39.7392, accuracy: 1e-6)
        XCTAssertEqual(router.longitude, -104.9903, accuracy: 1e-6)
        XCTAssertEqual(router.lastSeen, Date(timeIntervalSince1970: 1793803896))
        XCTAssertEqual(radios.first { $0.id == "t55" }?.name, "CL", "falls back to the short name")
    }

    func testParsesMeshCoreNodes() throws {
        let json = """
        [{"public_key": "0100000ae030862bebf148f4bbb9", "type": 2, "adv_name": "Lookout Mountain RPT",
          "last_advert": "2024-01-01T00:00:00.000Z", "updated_date": "2026-10-17T23:28:51.000Z", "adv_lat": 39.7400, "adv_lon": -105.2100, "params": {"freq": 910.525}},
         {"public_key": "ff", "type": 1, "adv_name": "Zero", "adv_lat": 0, "adv_lon": 0},
         {"public_key": "aa", "type": 3, "adv_name": "Room", "adv_lat": 39.6, "adv_lon": -104.85}]
        """
        let radios = try KnownRadioStore.parseMeshCore(Data(json.utf8))
        XCTAssertEqual(radios.count, 2, "radios without a position are skipped")
        XCTAssertEqual(radios[0].role, .repeater)
        XCTAssertEqual(radios[0].roleLabel, "Repeater")
        XCTAssertEqual(radios[0].firmware, .meshcore)
        XCTAssertEqual(radios[0].lastSeen?.timeIntervalSince1970 ?? 0, 1_792_279_731, accuracy: 1, "server time, not the radio's clock")
        XCTAssertEqual(radios[1].role, .room)
    }

    func testNearbyKeepsTheRadiusAndDropsOptOuts() {
        let denver = CLLocationCoordinate2D(latitude: 39.7392, longitude: -104.9903)
        let radios = [
            KnownRadio(id: "a", firmware: .meshcore, name: "Close", role: .repeater, latitude: 39.77, longitude: -105.00),
            KnownRadio(id: "b", firmware: .meshtastic, name: "Pueblo", role: .client, latitude: 38.25, longitude: -104.61),
            KnownRadio(id: "c", firmware: .meshtastic, name: "Private 🚫", role: .client, latitude: 39.74, longitude: -104.99),
        ]
        XCTAssertEqual(KnownRadioStore.nearby(radios, center: denver, radiusKm: 100).map(\.id), ["a"])
        XCTAssertEqual(KnownRadioStore.nearby(radios, center: denver, radiusKm: 400).map(\.id), ["a", "b"])
    }
}
