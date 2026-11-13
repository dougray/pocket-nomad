import CoreLocation
import XCTest
@testable import NomadPocket

/// The user's own phones and repeaters, hidden from the map, and the drive trail turned off.
@MainActor
final class HiddenRadiosTests: XCTestCase {
    var suite: String!
    var defaults: UserDefaults!
    var file: URL!
    let clock = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUp() async throws {
        suite = "hidden-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        file = FileManager.default.temporaryDirectory.appending(path: "\(suite!).json")
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: file)
        try? FileManager.default.removeItem(at: file.deletingPathExtension().appendingPathExtension("pings.json"))
    }

    private func node(_ num: UInt32, _ fw: MeshFirmware, key: Data? = nil, lat: Double = 39.74, lon: Double = -104.99) -> MeshNode {
        var n = MeshNode(num: num)
        n.network = fw
        n.longName = "Radio \(num)"
        n.latitude = lat
        n.longitude = lon
        n.publicKeyPrefix = key
        return n
    }

    func testHiddenRadiosCountAsOwnOnTheirOwnNetworkOnly() {
        let store = HiddenRadioStore(defaults: defaults)
        store.hide(HiddenRadio(node(1234, .meshtastic)))
        XCTAssertTrue(store.hides(1234, on: .meshtastic))
        XCTAssertFalse(store.hides(1234, on: .meshcore))
        XCTAssertFalse(store.hides(99, on: .meshtastic))
        XCTAssertFalse(store.hides(0, on: .meshtastic))
        XCTAssertTrue(HiddenRadioStore(defaults: defaults).hides(1234, on: .meshtastic), "kept across launches")
    }

    func testHiddenMeshCoreRepeaterIsCaughtByItsPathHash() {
        let store = HiddenRadioStore(defaults: defaults)
        let key = Data([0xAB, 0xCD, 0xEF, 0x12, 0x34])
        store.hide(HiddenRadio(node(MeshCoreContact.nodeNum(for: key), .meshcore, key: key)))
        XCTAssertTrue(store.hides(MeshCoreContact.nodeNum(for: key), on: .meshcore))
        XCTAssertTrue(store.hides(MeshCoreContact.nodeNum(forHash: Data([0xAB])), on: .meshcore), "1-byte hash stand-in")
        XCTAssertTrue(store.hides(MeshCoreContact.nodeNum(forHash: Data([0xAB, 0xCD])), on: .meshcore), "2-byte hash stand-in")
        XCTAssertFalse(store.hides(MeshCoreContact.nodeNum(forHash: Data([0xAC])), on: .meshcore))
    }

    func testMeshCoreRadioWithoutKeyUsesItsNodeNumberAsTheKey() {
        let num = MeshCoreContact.nodeNum(for: Data([0x11, 0x22, 0x33, 0x44]))
        let r = HiddenRadio(node(num, .meshcore))
        XCTAssertEqual(r.keyBytes, Data([0x11, 0x22, 0x33, 0x44]))
        XCTAssertTrue(r.matches(MeshCoreContact.nodeNum(forHash: Data([0x11])), on: .meshcore))
    }

    func testUnhideAndListID() {
        let store = HiddenRadioStore(defaults: defaults)
        let n = node(7, .meshtastic)
        store.hide(HiddenRadio(n))
        store.hide(HiddenRadio(n))
        XCTAssertEqual(store.radios.count, 1, "no duplicates")
        XCTAssertTrue(store.isHidden(n.listID))
        store.unhide(n.listID)
        XCTAssertFalse(store.hides(7, on: .meshtastic))
    }

    func testCommunityMapIDsAreMatchedAndCanBeHidden() throws {
        let tastic = KnownRadio(id: "t3928809792", firmware: .meshtastic, name: "Mine", role: .client, latitude: 39.7, longitude: -105)
        let core = KnownRadio(id: "cABCDEF12aa55", firmware: .meshcore, name: "Home repeater", role: .repeater, latitude: 39.7, longitude: -105)
        let other = KnownRadio(id: "c00112233", firmware: .meshcore, name: "Stranger", role: .repeater, latitude: 39.7, longitude: -105)

        let fromTastic = try XCTUnwrap(HiddenRadio(tastic))
        XCTAssertEqual(fromTastic.num, 3_928_809_792)
        let fromCore = try XCTUnwrap(HiddenRadio(core))
        XCTAssertEqual(fromCore.keyBytes, Data([0xAB, 0xCD, 0xEF, 0x12]))
        XCTAssertNil(HiddenRadio(KnownRadio(id: "cXYZ", firmware: .meshcore, name: "", role: .client, latitude: 0, longitude: 0)))

        let store = HiddenRadioStore(defaults: defaults)
        store.hide(fromTastic)
        store.hide(fromCore)
        XCTAssertTrue(store.hides(known: tastic))
        XCTAssertTrue(store.hides(known: core))
        XCTAssertFalse(store.hides(known: other))
        // Hiding from the community map also hides the same radio when heard over the air.
        XCTAssertTrue(store.hides(MeshCoreContact.nodeNum(forHash: Data([0xAB])), on: .meshcore))
        XCTAssertTrue(store.hides(3_928_809_792, on: .meshtastic))
    }

    func testHiddenRadiosAreOffBothMapLayers() {
        let store = HiddenRadioStore(defaults: defaults)
        let mine = node(1, .meshtastic)
        var theirs = node(2, .meshtastic)
        theirs.hopsAway = 0
        var far = node(3, .meshtastic)
        far.hopsAway = 4
        var mineFar = node(4, .meshtastic)
        mineFar.hopsAway = 4
        store.hide(HiddenRadio(mine))
        store.hide(HiddenRadio(mineFar))
        let layers = HeardRadios.layers(nodes: [.meshtastic: [1: mine, 2: theirs, 3: far, 4: mineFar]], samples: [],
                                        isOwn: { store.hides($0, on: $1) })
        XCTAssertEqual(layers.direct.map(\.num), [2])
        XCTAssertEqual(layers.mesh.map(\.num), [3])
    }

    func testHiddenRadiosAreNeverRecordedAndLeaveTheCells() {
        let hidden = HiddenRadioStore(defaults: defaults)
        var fix: CLLocation? = CLLocation(coordinate: .init(latitude: 39.74, longitude: -104.99), altitude: 0,
                                          horizontalAccuracy: 8, verticalAccuracy: 10, timestamp: clock.addingTimeInterval(-2))
        let coverage = CoverageStore(defaults: defaults, fileURL: file, location: { fix }, nodeName: { _, _ in "" },
                                     isOwnRadio: { hidden.hides($0, on: $1) }, now: { [clock] in clock }, controlsGPS: false)
        coverage.recording = true
        coverage.record(MeshHeard(node: 5, snr: 4, hops: 0, firmware: .meshtastic))
        XCTAssertEqual(coverage.privateCells(for: nil).count, 1)

        // Marked as mine afterwards: already-recorded points drop out of the cells.
        hidden.hide(HiddenRadio(node(5, .meshtastic)))
        XCTAssertTrue(coverage.privateCells(for: nil).isEmpty)
        XCTAssertTrue(coverage.sharedCells.isEmpty)

        fix = CLLocation(coordinate: .init(latitude: 39.80, longitude: -104.90), altitude: 0,
                         horizontalAccuracy: 8, verticalAccuracy: 10, timestamp: clock.addingTimeInterval(-1))
        coverage.record(MeshHeard(node: 5, snr: 4, hops: 0, firmware: .meshtastic))
        XCTAssertEqual(coverage.samples.count, 1, "new packets from a hidden radio are not recorded")
    }

    func testDriveTrailIsTurnedOffOnceThenTheChoiceStands() {
        defaults.set(CoverageLayer.recorded.rawValue, forKey: CoverageLayer.key)
        XCTAssertEqual(CoverageLayer.saved(defaults), .off, "turned off once after the update")
        defaults.set(CoverageLayer.recorded.rawValue, forKey: CoverageLayer.key)
        XCTAssertEqual(CoverageLayer.saved(defaults), .recorded, "the user's later choice is kept")
        XCTAssertEqual(CoverageLayer.recorded.label, "Where I drove (private)")
    }
}
