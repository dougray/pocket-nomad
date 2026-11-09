// HeardCreditTests.swift
// Pocket Nomad - Mesh module tests
// SPDX-License-Identifier: Apache-2.0
//
// Which radio a coverage sample is credited to: a MeshCore last-hop hash goes
// to the nearest known radio in range, or stays an unidentified repeater; node 0
// is coverage only; old recordings are re-derived; own radios never count.
// All places are made up, around Denver.

import XCTest
#if canImport(NomadPocket)
@testable import NomadPocket
#endif

final class HeardCreditTests: XCTestCase {
    /// Union Station, where the phone was.
    let here = (lat: 39.7527, lon: -105.0001)

    func node(_ key: [UInt8], _ type: MeshCoreContactType = .repeater, lat: Double?, lon: Double?, name: String = "") -> MeshNode {
        var n = MeshNode(num: MeshCoreContact.nodeNum(for: Data(key)))
        n.publicKeyPrefix = Data(key)
        n.meshCoreType = type
        n.network = .meshcore
        n.latitude = lat
        n.longitude = lon
        n.longName = name
        return n
    }

    func sample(_ node: UInt32, hops: UInt32? = 3, firmware: MeshFirmware = .meshcore,
                lat: Double? = nil, lon: Double? = nil) -> CoverageSample {
        CoverageSample(date: Date(timeIntervalSince1970: 1_800_000_000), latitude: lat ?? here.lat, longitude: lon ?? here.lon,
                       accuracy: 5, firmware: firmware, node: node, snr: 4, hops: hops)
    }

    func dict(_ nodes: [MeshNode]) -> [UInt32: MeshNode] { Dictionary(uniqueKeysWithValues: nodes.map { ($0.num, $0) }) }

    let noOwn: (UInt32) -> Bool = { _ in false }
    var standIn3F: UInt32 { MeshCoreContact.nodeNum(forHash: Data([0x3F])) }

    // Three radios whose keys all start with 3F: Golden (~19 km), Lyons (~53 km, out), Pueblo (~165 km).
    lazy var golden = node([0x3F, 0x01, 0x02, 0x03, 0x04, 0x05], lat: 39.7555, lon: -105.2211, name: "Golden")
    lazy var lyons = node([0x3F, 0x11, 0x12, 0x13, 0x14, 0x15], lat: 40.2247, lon: -105.2714, name: "Lyons")
    lazy var pueblo = node([0x3F, 0x21, 0x22, 0x23, 0x24, 0x25], lat: 38.2544, lon: -104.6091, name: "Pueblo")
    /// A chat radio with a 3F key a block away: a companion, which doesn't repeat.
    lazy var phone = node([0x3F, 0x31, 0x32, 0x33, 0x34, 0x35], .chat, lat: 39.7530, lon: -105.0010, name: "Someone's phone")

    func testLastHopGoesToTheNearestRepeaterInRange() {
        let nodes = dict([pueblo, lyons, golden, phone])
        XCTAssertEqual(HeardResolver.credit(sample(standIn3F), nodes: nodes, isOwn: noOwn), .radio(golden.num),
                       "nearest repeater in range, over a closer radio that doesn't repeat")
        // Without Golden, Lyons is 53 km off and Pueblo much further; the phone is in range but never repeats.
        XCTAssertEqual(HeardResolver.credit(sample(standIn3F), nodes: dict([pueblo, lyons, phone]), isOwn: noOwn),
                       .unidentified(hash: Data([0x3F])))
    }

    func testOutOfRangeOrUnplacedIsUnidentified() {
        let unplaced = node([0x3F, 0x41, 0x42, 0x43, 0x44, 0x45], lat: nil, lon: nil)
        let credit = HeardResolver.credit(sample(standIn3F), nodes: dict([pueblo, lyons, unplaced]), isOwn: noOwn)
        XCTAssertEqual(credit, .unidentified(hash: Data([0x3F])))
        XCTAssertEqual(HeardResolver.label(forHash: Data([0x3F])), "Repeater 3f, not identified")
        // A hash nobody's key starts with.
        XCTAssertEqual(HeardResolver.credit(sample(MeshCoreContact.nodeNum(forHash: Data([0x9C]))), nodes: dict([golden]), isOwn: noOwn),
                       .unidentified(hash: Data([0x9C])))
    }

    func testNodeZeroAndUnknownHopsAreCoverageOnly() {
        XCTAssertEqual(HeardResolver.credit(sample(golden.num, hops: nil), nodes: dict([golden]), isOwn: noOwn), .coverageOnly)
        XCTAssertEqual(HeardResolver.credit(sample(0), nodes: dict([golden]), isOwn: noOwn), .coverageOnly)
        XCTAssertEqual(HeardResolver.credit(sample(0, hops: 0), nodes: dict([golden]), isOwn: noOwn), .coverageOnly)
    }

    func testOldRecordingsAreReDerived() {
        // Old rule: credited to Pueblo, the first contact whose key started with the hash.
        let old = sample(pueblo.num, hops: 6)
        XCTAssertEqual(HeardResolver.credit(old, nodes: dict([pueblo, golden]), isOwn: noOwn), .radio(golden.num))
        XCTAssertEqual(HeardResolver.credit(old, nodes: dict([pueblo]), isOwn: noOwn), .unidentified(hash: Data([0x3F])))
        // A 2-byte stand-in keeps both bytes of its hash.
        let twoByte = MeshCoreContact.nodeNum(forHash: Data([0x3F, 0x01]))
        XCTAssertEqual(HeardResolver.hash(ofStandIn: twoByte), Data([0x3F, 0x01]))
        XCTAssertEqual(HeardResolver.credit(sample(twoByte), nodes: dict([golden, lyons]), isOwn: noOwn), .radio(golden.num))
        XCTAssertEqual(HeardResolver.credit(sample(MeshCoreContact.nodeNum(forHash: Data([0x3F, 0x11]))), nodes: dict([golden, lyons]), isOwn: noOwn),
                       .unidentified(hash: Data([0x3F, 0x11])), "Lyons matches the hash but is out of range")
        // A direct (0-hop) advert from a radio in range counts as itself; one far away doesn't get drawn.
        XCTAssertEqual(HeardResolver.credit(sample(golden.num, hops: 0), nodes: dict([golden]), isOwn: noOwn), .radio(golden.num))
        XCTAssertEqual(HeardResolver.credit(sample(pueblo.num, hops: 0), nodes: dict([pueblo]), isOwn: noOwn), .unidentified(hash: Data([0x3F])))
    }

    func testOwnRadioIsNeverCredited() {
        let mine = node([0x3F, 0x77, 0x77, 0x77, 0x77, 0x77], .chat, lat: here.lat, lon: here.lon, name: "My radio")
        let isOwn: (UInt32) -> Bool = { num in
            MeshService.isOwnRadio(num, on: .meshcore, ownNums: [mine.num], ownKeyPrefix: mine.publicKeyPrefix)
        }
        // Our own hash: own, whatever else matches.
        XCTAssertEqual(HeardResolver.credit(sample(standIn3F), nodes: dict([mine, golden]), isOwn: isOwn), .own)
        XCTAssertEqual(HeardResolver.credit(sample(mine.num, hops: 0), nodes: dict([mine]), isOwn: isOwn), .own)
        // A different hash never resolves to our radio even when it is the nearest match.
        let mine2 = node([0x5A, 0x77, 0x77, 0x77, 0x77, 0x77], .repeater, lat: here.lat, lon: here.lon)
        let isOwn2: (UInt32) -> Bool = { $0 == mine2.num }
        XCTAssertNil(HeardResolver.resolve(hash: Data([0x5A]), latitude: here.lat, longitude: here.lon, among: [mine2], excluding: isOwn2))
    }

    func testMeshtasticOnlyCreditsZeroHopSenders() {
        var alice = MeshNode(num: 0xA11CE)
        alice.network = .meshtastic
        alice.latitude = 39.7392
        alice.longitude = -104.9903
        let nodes = dict([alice])
        XCTAssertEqual(HeardResolver.credit(sample(alice.num, hops: 0, firmware: .meshtastic), nodes: nodes, isOwn: noOwn), .radio(alice.num))
        XCTAssertEqual(HeardResolver.credit(sample(alice.num, hops: 2, firmware: .meshtastic), nodes: nodes, isOwn: noOwn), .coverageOnly)
    }

    func testLayersSplitHeardDirectlyFromKnownOnTheMesh() {
        let mine = node([0x5A, 0x77, 0x77, 0x77, 0x77, 0x77], .chat, lat: here.lat, lon: here.lon)
        let samples = [sample(standIn3F), sample(standIn3F), sample(0), sample(MeshCoreContact.nodeNum(forHash: Data([0x9C]))),
                       sample(mine.num, hops: 0)]
        let layers = HeardRadios.layers(nodes: [.meshcore: dict([golden, lyons, pueblo, mine])], samples: samples,
                                        isOwn: { num, _ in num == mine.num })
        XCTAssertEqual(layers.direct.map(\.num), [golden.num])
        XCTAssertEqual(Set(layers.mesh.map(\.num)), [lyons.num, pueblo.num], "own radio on neither layer")
        XCTAssertEqual(layers.unidentified.map(\.hash), [Data([0x9C])])
        XCTAssertEqual(layers.coverageOnly, 1)
        XCTAssertEqual(HeardRadios.unidentifiedSummary(layers.unidentified), "Also heard 1 repeater not identified: 9c")
        // Live: a radio resolved while the app ran also counts as heard directly.
        let live = HeardRadios.layers(nodes: [.meshcore: dict([golden, lyons])], samples: [], liveDirect: [.meshcore: [lyons.num]],
                                      isOwn: { _, _ in false })
        XCTAssertEqual(live.direct.map(\.num), [lyons.num])
    }
}
