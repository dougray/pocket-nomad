import CoreLocation
import XCTest
@testable import NomadPocket

/// The coverage backup file, restore merging, the street-map download maths
/// and the own-radio guard. Demo places are around Denver.
@MainActor
final class BackupAndStreetMapTests: XCTestCase {
    var suite: String!
    var defaults: UserDefaults!
    var dir: URL!
    var fix: CLLocation?
    var clock = Date(timeIntervalSince1970: 1_800_000_000)
    var own: Set<UInt32> = []

    override func setUp() async throws {
        suite = "backup-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        dir = FileManager.default.temporaryDirectory.appending(path: suite)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fix = nil
        own = []
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: dir)
    }

    private func store(_ name: String = "coverage") -> CoverageStore {
        CoverageStore(defaults: defaults, fileURL: dir.appending(path: "\(name).json"), location: { [unowned self] in self.fix },
                      nodeName: { _, _ in "" }, isOwnRadio: { [unowned self] num, _ in self.own.contains(num) },
                      now: { [unowned self] in self.clock }, controlsGPS: false)
    }

    private func sample(_ minutes: Double, _ lat: Double, _ lon: Double, _ fw: MeshFirmware = .meshtastic, node: UInt32 = 0xA1) -> CoverageSample {
        CoverageSample(date: clock.addingTimeInterval(-minutes * 60), latitude: lat, longitude: lon, accuracy: 5,
                       firmware: fw, node: node, snr: 4.25, hops: 1)
    }

    private func ping(_ minutes: Double, _ lat: Double, _ outcome: MeshPingOutcome) -> CoveragePing {
        CoveragePing(date: clock.addingTimeInterval(-minutes * 60), latitude: lat, longitude: -104.99, accuracy: 6,
                     firmware: .meshcore, outcome: outcome)
    }

    private func backup(samples: [CoverageSample], pings: [CoveragePing] = [], zones: [CoverageZone] = [],
                        retention: Int? = 30) -> CoverageBackup {
        CoverageBackup(version: CoverageBackup.currentVersion, app: "Pocket Nomad iOS test", createdAt: clock,
                       samples: samples, pings: pings, zones: zones,
                       settings: .init(recording: true, sharing: true, retentionDays: retention),
                       drive: .init(pinging: false, keepScreenOn: false, targets: ["meshcore": 0xBEEF]))
    }

    // MARK: Backup format

    func testBackupRoundTripKeepsEverythingAndTheSharedKeys() throws {
        let zone = CoverageZone(name: "Demo zone", latitude: 39.74, longitude: -104.99, radius: 500)
        let original = backup(samples: [sample(5, 39.75, -104.98), sample(3, 39.76, -104.97, .meshcore)],
                              pings: [ping(4, 39.75, .reached)], zones: [zone])
        let data = try original.encoded()

        let top = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(top.keys), ["version", "app", "createdAt", "samples", "pings", "zones", "settings", "drive"],
                       "the Android app reads these exact keys")
        XCTAssertEqual(top["version"] as? Int, 1)
        let firstSample = try XCTUnwrap((top["samples"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(firstSample.keys), ["date", "latitude", "longitude", "accuracy", "firmware", "node", "snr", "hops"],
                       "same keys as coverage.json")
        XCTAssertEqual(firstSample["date"] as? Int64, Int64((clock.timeIntervalSince1970 - 300) * 1000), "Unix milliseconds")
        XCTAssertEqual(top["createdAt"] as? String, "2027-01-15T08:00:00.000Z", "ISO 8601, UTC")
        XCTAssertEqual(Set(try XCTUnwrap(top["settings"] as? [String: Any]).keys), ["recording", "sharing", "retentionDays"])
        XCTAssertEqual(Set(try XCTUnwrap((top["pings"] as? [[String: Any]])?.first).keys),
                       ["date", "latitude", "longitude", "accuracy", "firmware", "outcome"], "same keys as coverage.pings.json")

        let read = try CoverageBackup.decode(data)
        XCTAssertEqual(read.samples, original.samples)
        XCTAssertEqual(read.pings, original.pings)
        XCTAssertEqual(read.zones, original.zones)
        XCTAssertEqual(read.createdAt, clock)
        XCTAssertEqual(read.settings, original.settings)
        XCTAssertEqual(read.drive.targets, ["meshcore": 0xBEEF])
    }

    func testRefusesNewerVersionsAndOtherFiles() throws {
        var newer = try XCTUnwrap(JSONSerialization.jsonObject(with: backup(samples: []).encoded()) as? [String: Any])
        newer["version"] = 99
        XCTAssertThrowsError(try CoverageBackup.decode(JSONSerialization.data(withJSONObject: newer))) { error in
            guard case CoverageBackup.ReadError.tooNew(99)? = error as? CoverageBackup.ReadError else {
                return XCTFail("expected tooNew, got \(error)")
            }
        }
        XCTAssertThrowsError(try CoverageBackup.decode(Data(#"{"type":"FeatureCollection"}"#.utf8)))
        XCTAssertThrowsError(try CoverageBackup.decode(Data("not json".utf8)))
    }

    func testReadsDatesWrittenOtherWays() throws {
        let json = """
        {"version":1,"app":"Pocket Nomad Android 0.4","createdAt":"2027-01-15T12:00:00Z",
         "samples":[{"date":1800000000000,"latitude":39.7,"longitude":-105,"accuracy":5,"firmware":"meshtastic","node":7},
                    {"date":1800000000,"latitude":39.8,"longitude":-105,"accuracy":5,"firmware":"meshcore","node":8},
                    {"date":821692800,"latitude":39.9,"longitude":-105,"accuracy":5,"firmware":"meshcore","node":9},
                    {"date":"garbage","latitude":39.9,"longitude":-105},
                    {"date":"2027-01-15T12:00:00.5Z","latitude":40.0,"longitude":-105,"accuracy":5,"firmware":"meshcore","node":10}],
         "pings":[{"date":1800000000000}],"zones":[{"id":"zone-1","name":"Demo","latitude":39.7,"longitude":-105,"radius":300}],
         "settings":{},"drive":{"pinging":true,"targets":{"meshtastic":null,"meshcore":48879}}}
        """
        let b = try CoverageBackup.decode(Data(json.utf8))
        XCTAssertEqual(b.samples[0].date, Date(timeIntervalSince1970: 1_800_000_000), "milliseconds")
        XCTAssertEqual(b.samples[1].date, Date(timeIntervalSince1970: 1_800_000_000), "seconds")
        XCTAssertEqual(b.samples[2].date, Date(timeIntervalSinceReferenceDate: 821_692_800), "coverage.json's reference-date seconds")
        XCTAssertEqual(b.samples.count, 4, "the unreadable record is skipped, the rest kept")
        XCTAssertEqual(b.samples[3].date, Date(timeIntervalSince1970: 1_800_014_400.5), "ISO 8601")
        XCTAssertTrue(b.pings.isEmpty)
        XCTAssertEqual(b.createdAt, Date(timeIntervalSince1970: 1_800_014_400))
        XCTAssertEqual(b.drive.targets, ["meshcore": 48879], "a null target is no target")
        XCTAssertEqual(b.zones.first?.name, "Demo", "a zone id that isn't a UUID still reads")
        XCTAssertNil(b.settings.retentionDays)
    }

    // MARK: Restore

    func testRestoreMergesAndSkipsDuplicates() throws {
        let s = store()
        let a = sample(10, 39.75, -104.98), b = sample(8, 39.76, -104.97, .meshcore)
        s.merge(samples: [a], pings: [ping(9, 39.75, .relayed)], zones: [])
        // The same point after a trip through the file (millisecond dates), plus a new one.
        let file = try CoverageBackup.decode(backup(samples: [a, b], pings: [ping(9, 39.75, .relayed), ping(7, 39.76, .none)],
                                                    zones: [CoverageZone(name: "Demo zone", latitude: 39.74, longitude: -104.99, radius: 500)]).encoded())
        let added = s.merge(samples: file.samples, pings: file.pings, zones: file.zones)
        XCTAssertEqual(added.samples, 1)
        XCTAssertEqual(added.pings, 1)
        XCTAssertEqual(added.zones, 1)
        XCTAssertEqual(s.samples.count, 2)
        XCTAssertEqual(s.pings.count, 2)
        XCTAssertEqual(s.samples.map(\.date), s.samples.map(\.date).sorted(), "kept in date order")

        // Restoring the same file again changes nothing.
        let again = s.merge(samples: file.samples, pings: file.pings, zones: file.zones)
        XCTAssertEqual(again.samples + again.pings + again.zones, 0)
        XCTAssertEqual(s.zones.count, 1)
        XCTAssertEqual(store().samples.count, 2, "saved to disk")
    }

    func testRestoreNeverDropsDataAndKeepsOldPoints() throws {
        let s = store()
        s.retentionDays = 7
        let here = [sample(1, 39.70, -105.0), sample(2, 39.71, -105.0)]
        s.merge(samples: here, pings: [], zones: [CoverageZone(name: "Mine", latitude: 39.6, longitude: -105, radius: 300)])
        // A backup with only older points, a shorter retention and no zones.
        let old = sample(60 * 24 * 40, 39.80, -105.1) // 40 days ago
        let svc = CoverageBackupService(defaults: defaults, coverage: s,
                                        drive: { [unowned self] in DriveMode(coverage: s, defaults: self.defaults) },
                                        now: { [unowned self] in self.clock }, appContainer: "/nowhere")
        let message = svc.restore(backup(samples: [old], retention: 7))
        XCTAssertTrue(message.contains("Restored 1 points"), message)
        XCTAssertEqual(s.samples.count, 3, "nothing here was dropped and the old point stayed")
        XCTAssertTrue(here.allSatisfy { h in s.samples.contains(h) })
        XCTAssertEqual(s.retentionDays, 90, "retention raised so the restored point survives the next tidy-up")
        XCTAssertEqual(s.zones.map(\.name), ["Mine"], "zones here kept")
        XCTAssertFalse(s.sharing, "a restore never turns sharing on")
        XCTAssertFalse(s.recording, "or recording")
        XCTAssertTrue(message.contains("Recording and sharing were on in the backup"), message)
    }

    func testRestoreFillsOnlyMissingPingTargets() {
        let drive = DriveMode(coverage: store(), defaults: defaults)
        drive.setTarget(0x1234, for: .meshtastic)
        drive.restore(.init(pinging: false, keepScreenOn: nil, targets: ["meshtastic": 0x9999, "meshcore": 0xBEEF, "other": 1]),
                      switches: false)
        XCTAssertEqual(drive.target(for: .meshtastic), 0x1234, "a choice made here wins")
        XCTAssertEqual(drive.target(for: .meshcore), 0xBEEF)
        XCTAssertTrue(drive.pinging, "switches stay as they are on a phone that already has data")
        drive.restore(.init(pinging: false), switches: true)
        XCTAssertFalse(drive.pinging, "but come back on a fresh phone")
        XCTAssertEqual(drive.backupSettings.targets, ["meshtastic": 0x1234, "meshcore": 0xBEEF])
    }

    func testBackupFolderWriteReadAndChangeTracking() async throws {
        let s = store()
        s.merge(samples: [sample(3, 39.75, -104.98)], pings: [], zones: [])
        let svc = CoverageBackupService(defaults: defaults, coverage: s,
                                        drive: { [unowned self] in DriveMode(coverage: s, defaults: self.defaults) },
                                        now: { [unowned self] in self.clock }, appContainer: "/nowhere")
        XCTAssertFalse(svc.isOn)
        // An empty phone never writes a backup.
        let emptyFolder = dir.appending(path: "Empty")
        try FileManager.default.createDirectory(at: emptyFolder, withIntermediateDirectories: true)
        let emptySvc = CoverageBackupService(defaults: UserDefaults(suiteName: suite + "-empty")!, coverage: store("empty"),
                                             drive: { DriveMode(coverage: s, defaults: self.defaults) }, appContainer: "/nowhere")
        await emptySvc.chooseFolder(emptyFolder)
        XCTAssertFalse(FileManager.default.fileExists(atPath: emptyFolder.appending(path: CoverageBackup.fileName).path))
        UserDefaults().removePersistentDomain(forName: suite + "-empty")
        let folder = dir.appending(path: "Backups")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        await svc.chooseFolder(folder)
        XCTAssertEqual(svc.folderName, "Backups")
        XCTAssertEqual(svc.lastBackup, clock)
        XCTAssertFalse(svc.hasChanges)
        let written = try CoverageBackup.decode(Data(contentsOf: folder.appending(path: CoverageBackup.fileName)))
        XCTAssertEqual(written.samples, try CoverageBackup.decode(backup(samples: s.samples).encoded()).samples)

        s.merge(samples: [sample(1, 39.76, -104.97)], pings: [], zones: [])
        XCTAssertTrue(svc.hasChanges, "a new point means a backup is due")
        // A fresh copy of the service finds the folder again from its bookmark.
        let relaunched = CoverageBackupService(defaults: defaults, coverage: s, drive: { DriveMode(coverage: s, defaults: self.defaults) }, appContainer: "/nowhere")
        XCTAssertEqual(relaunched.folderName, "Backups")
        await svc.backUpNow()
        svc.turnOff()
        XCTAssertFalse(svc.isOn)

        // A phone choosing a folder that already holds a fuller backup merges it in before writing.
        let other = store("other")
        other.merge(samples: [sample(2, 39.80, -104.90)], pings: [], zones: [])
        let otherSvc = CoverageBackupService(defaults: UserDefaults(suiteName: suite + "-other")!, coverage: other,
                                             drive: { DriveMode(coverage: other, defaults: self.defaults) }, appContainer: "/nowhere")
        await otherSvc.chooseFolder(folder)
        XCTAssertEqual(other.samples.count, 3, "the folder's backup was merged in")
        XCTAssertNotNil(otherSvc.folderRestoreMessage)
        let merged = try CoverageBackup.decode(Data(contentsOf: folder.appending(path: CoverageBackup.fileName)))
        XCTAssertEqual(merged.samples.count, 3, "and the file now holds both phones' points")
        UserDefaults().removePersistentDomain(forName: suite + "-other")
    }

    func testBackupFoldersMustBeOnThisPhoneAndOutsideTheApp() async {
        let app = "/private/var/mobile/Containers/Data/Application/0000-APP"
        func check(_ path: String, ubiquitous: Bool = false) -> CoverageBackupService.FolderCheck {
            CoverageBackupService.check(path: path, isUbiquitous: ubiquitous, appContainer: app)
        }
        XCTAssertEqual(check("/private/var/mobile/Containers/Shared/AppGroup/1111/File Provider Storage/Pocket Nomad Backups"), .local,
                       "On My iPhone")
        XCTAssertEqual(check("/private/var/mobile/Library/Mobile Documents/com~apple~CloudDocs/Backups"), .cloud)
        XCTAssertEqual(check("/var/mobile/Library/Mobile Documents/iCloud~org~example/Documents"), .cloud)
        XCTAssertEqual(check("/private/var/mobile/Containers/Shared/AppGroup/2222/File Provider Storage/Sync", ubiquitous: true), .cloud,
                       "another cloud provider's folder")
        XCTAssertEqual(check("/var/mobile/Containers/Data/Application/0000-APP/Documents"), .insideApp, "with or without /private")
        XCTAssertEqual(check(app), .insideApp)
        XCTAssertEqual(check("/var/mobile/Containers/Data/Application/0000-APPX/Documents"), .local, "a different container")

        // A refused folder is never remembered.
        let s = store()
        s.merge(samples: [sample(3, 39.75, -104.98)], pings: [], zones: [])
        let svc = CoverageBackupService(defaults: defaults, coverage: s, drive: { DriveMode(coverage: s, defaults: self.defaults) },
                                        appContainer: dir.path)
        await svc.chooseFolder(dir.appending(path: "Inside"))
        XCTAssertFalse(svc.isOn)
        XCTAssertNotNil(svc.lastError)
    }

    // MARK: Own radios

    func testOwnRadiosAreNeverRecorded() {
        let s = store()
        s.recording = true
        fix = CLLocation(coordinate: .init(latitude: 39.74, longitude: -104.99), altitude: 0, horizontalAccuracy: 8,
                         verticalAccuracy: 10, timestamp: clock)
        own = [0xB001]
        s.record(MeshHeard(node: 0xB001, snr: 9, hops: 0, firmware: .meshtastic))
        XCTAssertTrue(s.samples.isEmpty)
        s.record(MeshHeard(node: 0xB002, snr: 9, hops: 0, firmware: .meshcore))
        XCTAssertEqual(s.samples.map(\.node), [0xB002])
    }

    func testOwnRadioRuleCoversBothNetworksAndMeshCoreHashes() {
        let key = Data([0x3A, 0x7C, 0x11, 0x90, 0x55, 0x66])
        let coreNum = MeshCoreContact.nodeNum(for: key)
        let nums: [UInt32] = [0xB001, coreNum]
        XCTAssertTrue(MeshService.isOwnRadio(0xB001, on: .meshtastic, ownNums: nums, ownKeyPrefix: key))
        XCTAssertTrue(MeshService.isOwnRadio(coreNum, on: .meshcore, ownNums: nums, ownKeyPrefix: key))
        XCTAssertTrue(MeshService.isOwnRadio(0xB001, on: .meshcore, ownNums: nums, ownKeyPrefix: key), "either network's own radio")
        // Repeaters known only by a 1-3 byte path hash that matches the start of our key.
        for n in 1 ... 3 {
            XCTAssertTrue(MeshService.isOwnRadio(MeshCoreContact.nodeNum(forHash: key.prefix(n)), on: .meshcore, ownNums: nums, ownKeyPrefix: key))
        }
        XCTAssertFalse(MeshService.isOwnRadio(MeshCoreContact.nodeNum(forHash: Data([0x3B])), on: .meshcore, ownNums: nums, ownKeyPrefix: key))
        XCTAssertFalse(MeshService.isOwnRadio(0xA1, on: .meshtastic, ownNums: nums, ownKeyPrefix: key))
        XCTAssertFalse(MeshService.isOwnRadio(0, on: .meshtastic, ownNums: [0, 0], ownKeyPrefix: nil), "unknown radios are no one's")
        XCTAssertFalse(MeshService.isOwnRadio(0x3A, on: .meshtastic, ownNums: nums, ownKeyPrefix: key), "hashes are MeshCore only")
    }

    // MARK: Street maps

    func testTileMaths() {
        XCTAssertEqual(StreetTiles.x(-104.9903, 14), 3413)
        XCTAssertEqual(StreetTiles.y(39.7392, 14), 6218)
        XCTAssertEqual(StreetTiles.x(-104.9903, 10), 213)
        XCTAssertEqual(StreetTiles.y(39.7392, 10), 388)
        let world = StreetTiles.Bounds(south: -85.0511, west: -180, north: 85.0511, east: 180)
        for z in 0 ... 4 { XCTAssertEqual(StreetTiles.count(world, zoom: z), 1 << (2 * z)) }
        XCTAssertEqual(StreetTiles.count(world, zooms: 0 ... 2), 1 + 4 + 16)

        let b = StreetTiles.bounds(latitude: 39.7392, longitude: -104.9903, radiusKm: 50)
        XCTAssertEqual(b.north - 39.7392, 50 / 111.32, accuracy: 1e-9)
        XCTAssertGreaterThan(b.east - (-104.9903), b.north - 39.7392, "longitude degrees are shorter away from the equator")
        XCTAssertEqual(StreetTiles.count(b, zoom: 11), 64)
        XCTAssertEqual(StreetTiles.count(b, zoom: 14), 2916)

        let small = StreetTiles.estimate(latitude: 39.7392, longitude: -104.9903, wideKm: 100, detailKm: 25)
        let big = StreetTiles.estimate(latitude: 39.7392, longitude: -104.9903, wideKm: 400, detailKm: 100)
        XCTAssertLessThan(small.bytes, big.bytes)
        XCTAssertGreaterThan(big.detailTiles, small.detailTiles * 10, "doubling the radius twice is about 16x the street tiles")
        XCTAssertGreaterThan(small.bytes, StreetTiles.styleBytes)
        // Bounds never leave the Web Mercator world.
        let edge = StreetTiles.bounds(latitude: 84.9, longitude: 179.9, radiusKm: 400)
        XCTAssertLessThanOrEqual(edge.north, 85.0511)
        XCTAssertLessThanOrEqual(edge.east, 180)
    }

    func testPackContextRoundTrip() {
        let c = StreetPackContext(downloadID: UUID(), kind: .detail, name: "Streets around 39.74, -104.99",
                                  latitude: 39.7392, longitude: -104.9903, wideRadiusKm: 400, detailRadiusKm: 50,
                                  created: clock, tiles: "https://tiles.openfreemap.org/planet/20261101_080013_pt/{z}/{x}/{y}.pbf")
        XCTAssertEqual(StreetPackContext.decode(c.encoded()), c)
        XCTAssertNil(StreetPackContext.decode(Data("someone else's pack".utf8)), "packs this app didn't make are ignored")
    }

    func testBaseMapChoiceAndPinnedStyle() {
        XCTAssertEqual(BaseMap.choose(hasRegions: true, streetsOn: true, hasPacks: true, online: true), .protomaps, "region files win")
        XCTAssertEqual(BaseMap.choose(hasRegions: false, streetsOn: false, hasPacks: true, online: true), .protomaps)
        XCTAssertEqual(BaseMap.choose(hasRegions: false, streetsOn: true, hasPacks: true, online: false), .streetsOffline)
        XCTAssertEqual(BaseMap.choose(hasRegions: false, streetsOn: true, hasPacks: false, online: true), .streetsOnline)
        XCTAssertEqual(BaseMap.choose(hasRegions: false, streetsOn: true, hasPacks: false, online: false), .protomaps)

        let style: [String: Any] = ["version": 8, "layers": [["id": "bg"]],
                                    "sources": ["openmaptiles": ["type": "vector", "url": "https://tiles.openfreemap.org/planet"],
                                                "ne2_shaded": ["type": "raster"]]]
        let tiles = "https://tiles.openfreemap.org/planet/20261101_080013_pt/{z}/{x}/{y}.pbf"
        let pinned = StreetMapStore.pin(style, tiles: tiles)
        let sources = pinned["sources"] as? [String: Any]
        let omt = sources?["openmaptiles"] as? [String: Any]
        XCTAssertEqual(omt?["tiles"] as? [String], [tiles])
        XCTAssertNil(omt?["url"], "no TileJSON lookup, so offline tiles match")
        XCTAssertEqual(omt?["maxzoom"] as? Int, 14)
        XCTAssertNotNil(sources?["ne2_shaded"], "other sources untouched")
    }
}
