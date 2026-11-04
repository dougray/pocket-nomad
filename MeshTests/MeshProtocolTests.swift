// MeshProtocolTests.swift
// Pocket Nomad - Mesh module tests
// SPDX-License-Identifier: Apache-2.0

import XCTest
#if canImport(NomadPocket)
@testable import NomadPocket
#endif

final class MeshProtocolTests: XCTestCase {

    func testWantConfigWireBytes() {
        // ToRadio field 3 (want_config_id), varint: tag 0x18
        XCTAssertEqual([UInt8](MeshPBToRadio.wantConfigID(1).encode()), [0x18, 0x01])
        XCTAssertEqual([UInt8](MeshPBToRadio.wantConfigID(300).encode()), [0x18, 0xAC, 0x02])
    }

    func testBroadcastTextPacketWireBytes() {
        let p = MeshPBPacket(
            to: MeshConstants.broadcastNum,
            decoded: MeshPBData(portnum: 1, payload: Data("hi".utf8)),
            id: 0x0102_0304,
            hopLimit: 3,
            wantAck: true
        )
        let bytes = [UInt8](MeshPBToRadio.packet(p).encode())
        let expectedPacket: [UInt8] = [
            0x15, 0xFF, 0xFF, 0xFF, 0xFF,           // to: fixed32 field 2
            0x22, 0x06, 0x08, 0x01, 0x12, 0x02, 0x68, 0x69, // decoded: Data{portnum 1, payload "hi"}
            0x35, 0x04, 0x03, 0x02, 0x01,           // id: fixed32 field 6, little-endian
            0x48, 0x03,                             // hop_limit field 9
            0x50, 0x01,                             // want_ack field 10
        ]
        XCTAssertEqual(bytes, [0x0A, UInt8(expectedPacket.count)] + expectedPacket)
    }

    func testToRadioRoundTrip() throws {
        let cases: [MeshPBToRadio] = [
            .wantConfigID(0xFFFF_FFFF),
            .disconnect,
            .heartbeat(nonce: 9),
            .packet(MeshPBPacket(from: 1, to: 2, channel: 1,
                                 decoded: MeshPBData(portnum: 1, payload: Data("x".utf8), replyID: 5, emoji: 1),
                                 id: 77, hopLimit: 3, wantAck: true, priority: 70)),
        ]
        for c in cases {
            XCTAssertEqual(try MeshPBToRadio.decode(c.encode()), c)
        }
    }

    func testPacketRoundTripWithAllFields() throws {
        let p = MeshPBPacket(from: 0xA1B2_C3D4, to: 0xFFFF_FFFF, channel: 2,
                             decoded: MeshPBData(portnum: 3, payload: Data([1, 2, 3]), wantResponse: true,
                                                 dest: 9, source: 8, requestID: 7, replyID: 6, emoji: 1, bitfield: 1),
                             id: 0xFFFF_FFFE, rxTime: 1_760_000_000, rxSNR: -12.75, hopLimit: 2, wantAck: true,
                             priority: 64, rxRSSI: -118, viaMQTT: true, hopStart: 5)
        let back = try MeshPBPacket.decode(p.encode())
        XCTAssertEqual(back, p)
        XCTAssertEqual(back.hopsAway, 3)
    }

    func testEncryptedPacketKeepsBytes() throws {
        let p = MeshPBPacket(from: 1, to: 2, encrypted: Data([9, 9, 9]), id: 3)
        let back = try MeshPBPacket.decode(p.encode())
        XCTAssertNil(back.decoded)
        XCTAssertEqual(back.encrypted, Data([9, 9, 9]))
    }

    func testNodeInfoRoundTripAndPosition() throws {
        let n = MeshPBNodeInfo(
            num: 0xDEAD_BEEF,
            user: MeshPBUser(id: "!deadbeef", longName: "Base Camp", shortName: "BC", hwModel: 43,
                             isLicensed: false, role: 0, publicKey: Data(repeating: 1, count: 32)),
            position: MeshPBPosition(latitudeI: 397_392_400, longitudeI: -1_049_902_500, altitude: 150, time: 1),
            snr: 6.5, lastHeard: 1_760_000_000,
            deviceMetrics: MeshPBDeviceMetrics(batteryLevel: 101, voltage: 4.2),
            channel: 0, viaMQTT: false, hopsAway: 0
        )
        let back = try MeshPBNodeInfo.decode(n.encode())
        XCTAssertEqual(back, n)
        XCTAssertEqual(try XCTUnwrap(back.position?.latitude), 39.73924, accuracy: 1e-7)
        XCTAssertEqual(try XCTUnwrap(back.position?.longitude), -104.99025, accuracy: 1e-7)
        XCTAssertTrue(back.position?.hasFix == true)
        XCTAssertFalse(MeshPBPosition(latitudeI: 0, longitudeI: 0).hasFix)
        XCTAssertFalse(MeshPBPosition().hasFix)
    }

    func testFromRadioVariantsRoundTrip() throws {
        let variants: [MeshPBFromRadioVariant] = [
            .myInfo(MeshPBMyNodeInfo(myNodeNum: 42, rebootCount: 3, minAppVersion: 30200)),
            .nodeInfo(MeshPBNodeInfo(num: 5, user: MeshPBUser(longName: "A"))),
            .configCompleteID(123_456),
            .rebooted,
            .channel(MeshPBChannel(index: 0, name: "LongFast", hasPSK: true, role: 1)),
            .channel(MeshPBChannel(index: 2, name: "Family", hasPSK: true, role: 2)),
            .queueStatus(MeshPBQueueStatus(res: -1, free: 0, maxlen: 16, meshPacketID: 99)),
            .metadata(MeshPBDeviceMetadata(firmwareVersion: "2.6.11.abc", role: 0, hwModel: 43, hasPKC: true)),
            .loraConfig(MeshPBLoRaConfig(modemPreset: 0, region: 1, hopLimit: 3)),
            .clientNotification("Rate limited"),
            .logRecord("boot"),
            .packet(MeshPBPacket(from: 1, to: 2, decoded: MeshPBData(portnum: 1, payload: Data("yo".utf8)), id: 3)),
        ]
        for v in variants {
            let msg = MeshPBFromRadio(id: 7, variant: v)
            XCTAssertEqual(try MeshPBFromRadio.decode(msg.encode()), msg, "\(v)")
        }
    }

    func testNonLoraConfigAndUnknownVariant() throws {
        XCTAssertEqual(try MeshPBFromRadio.decode(MeshPBFromRadio(variant: .otherConfig).encode()).variant, .otherConfig)
        // moduleConfig (field 9) is not modelled
        var w = MeshProtoWriter()
        w.writeUInt32(1, 4)
        w.writeMessage(9) { $0.writeVarint(1, 1) }
        let r = try MeshPBFromRadio.decode(w.data)
        XCTAssertEqual(r.id, 4)
        XCTAssertEqual(r.variant, .unhandled(field: 9))
    }

    func testRoutingDecode() throws {
        XCTAssertEqual(try MeshPBRouting.decode(MeshPBRouting(errorReason: 0).encode()).errorReason, 0)
        XCTAssertEqual(MeshPBRouting(errorReason: 0).encode(), Data([0x18, 0x00])) // oneof: zero still written
        XCTAssertEqual(try MeshPBRouting.decode(MeshPBRouting(errorReason: 5).encode()).errorReason, 5)
        XCTAssertEqual(try MeshPBRouting.decode(Data()).errorReason, 0)
        var w = MeshProtoWriter()
        w.writeMessage(1) { $0.writeFixed32(1, 1) } // route_request
        XCTAssertNil(try MeshPBRouting.decode(w.data).errorReason)
        XCTAssertEqual(MeshRoutingError.describe(5), "Their radio didn't answer")
        XCTAssertEqual(MeshRoutingError.describe(200), "Error 200")
    }

    func testSOSTextFormatting() {
        let t = MeshSOS.text(latitude: 39.739236, longitude: -104.990251, senderName: "Trail Lead")
        XCTAssertTrue(t.contains("39.73924, -104.99025"), t)
        XCTAssertTrue(t.hasPrefix("SOS"))
        XCTAssertLessThanOrEqual(t.utf8.count, MeshConstants.maxTextBytes)
        XCTAssertTrue(MeshSOS.text(latitude: nil, longitude: nil, senderName: nil).contains("Location unknown"))
        XCTAssertEqual(MeshSOS.format(-0.000004), "-0.00000")
        XCTAssertEqual(MeshSOS.format(12), "12.00000")
        let long = MeshSOS.text(latitude: 1, longitude: 2, senderName: String(repeating: "N", count: 400))
        XCTAssertLessThanOrEqual(long.utf8.count, MeshConstants.maxTextBytes)
    }

    func testTruncationRespectsCharacters() {
        let s = String(repeating: "📡", count: 60) // 4 bytes each
        let t = MeshText.truncated(s, maxBytes: 200)
        XCTAssertEqual(t.utf8.count, 200)
        XCTAssertEqual(MeshText.truncated("é" + "📡", maxBytes: 4), "é")
        XCTAssertEqual(MeshText.byteCount("é"), 2)
    }
}
