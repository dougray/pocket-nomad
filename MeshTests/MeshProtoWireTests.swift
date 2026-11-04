// MeshProtoWireTests.swift
// Pocket Nomad - Mesh module tests
// SPDX-License-Identifier: Apache-2.0

import XCTest
#if canImport(NomadPocket)
@testable import NomadPocket
#endif

final class MeshProtoWireTests: XCTestCase {

    private func varintBytes(_ v: UInt64) -> [UInt8] {
        var w = MeshProtoWriter()
        w.writeRawVarint(v)
        return [UInt8](w.data)
    }

    private func readVarint(_ bytes: [UInt8]) throws -> UInt64 {
        var r = MeshProtoReader(Data(bytes))
        return try r.readRawVarint()
    }

    func testVarintKnownEncodings() {
        XCTAssertEqual(varintBytes(0), [0x00])
        XCTAssertEqual(varintBytes(1), [0x01])
        XCTAssertEqual(varintBytes(127), [0x7F])
        XCTAssertEqual(varintBytes(128), [0x80, 0x01])
        XCTAssertEqual(varintBytes(300), [0xAC, 0x02])
        XCTAssertEqual(varintBytes(16_383), [0xFF, 0x7F])
        XCTAssertEqual(varintBytes(16_384), [0x80, 0x80, 0x01])
        XCTAssertEqual(varintBytes(UInt64(UInt32.max)), [0xFF, 0xFF, 0xFF, 0xFF, 0x0F])
        XCTAssertEqual(varintBytes(UInt64.max), [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01])
    }

    func testVarintRoundTripEdgeValues() throws {
        let values: [UInt64] = [0, 1, 127, 128, 255, 256, 16_383, 16_384, 2_097_151, 2_097_152,
                                UInt64(UInt32.max) - 1, UInt64(UInt32.max), UInt64(UInt32.max) + 1,
                                UInt64(Int64.max), UInt64.max - 1, UInt64.max]
        for v in values {
            XCTAssertEqual(try readVarint(varintBytes(v)), v, "value \(v)")
        }
    }

    func testVarintTooLongThrows() {
        // 11 continuation bytes.
        XCTAssertThrowsError(try readVarint([UInt8](repeating: 0xFF, count: 10) + [0x01])) {
            XCTAssertEqual($0 as? MeshProtoError, .varintTooLong)
        }
        // 10th byte carries more than one bit: overflows 64 bits.
        XCTAssertThrowsError(try readVarint([UInt8](repeating: 0xFF, count: 9) + [0x02])) {
            XCTAssertEqual($0 as? MeshProtoError, .varintTooLong)
        }
    }

    func testVarintTruncatedThrows() {
        XCTAssertThrowsError(try readVarint([0x80])) { XCTAssertEqual($0 as? MeshProtoError, .truncated) }
        XCTAssertThrowsError(try readVarint([])) { XCTAssertEqual($0 as? MeshProtoError, .truncated) }
    }

    func testNegativeInt32UsesTenBytes() throws {
        var w = MeshProtoWriter()
        w.writeInt32(1, -1)
        XCTAssertEqual(w.data.count, 11) // 1 tag + 10 varint bytes
        var r = MeshProtoReader(w.data)
        let f = try XCTUnwrap(try r.next())
        XCTAssertEqual(f.int32, -1)
        XCTAssertEqual(f.int64, -1)
    }

    func testZigZag() {
        XCTAssertEqual(MeshProtoZigZag.encode32(0), 0)
        XCTAssertEqual(MeshProtoZigZag.encode32(-1), 1)
        XCTAssertEqual(MeshProtoZigZag.encode32(1), 2)
        XCTAssertEqual(MeshProtoZigZag.encode32(-2), 3)
        XCTAssertEqual(MeshProtoZigZag.encode32(Int32.max), 0xFFFF_FFFE)
        XCTAssertEqual(MeshProtoZigZag.encode32(Int32.min), 0xFFFF_FFFF)
        for v: Int32 in [0, 1, -1, 63, -64, 1000, -1000, .max, .min] {
            XCTAssertEqual(MeshProtoZigZag.decode32(MeshProtoZigZag.encode32(v)), v)
        }
        for v: Int64 in [0, 1, -1, .max, .min, -123_456_789_012] {
            XCTAssertEqual(MeshProtoZigZag.decode64(MeshProtoZigZag.encode64(v)), v)
        }
        var w = MeshProtoWriter()
        w.writeSInt32(1, -2)
        XCTAssertEqual([UInt8](w.data), [0x08, 0x03])
    }

    func testFixedAndFloatAndBytesRoundTrip() throws {
        var w = MeshProtoWriter()
        w.writeFixed32(1, 0xDEAD_BEEF)
        w.writeSFixed32(2, -302_000_000)
        w.writeFixed64(3, 0x0102_0304_0506_0708)
        w.writeFloat(4, -7.25)
        w.writeDouble(5, 3.141592653589793)
        w.writeString(6, "héllo 📡")
        w.writeBytes(7, Data([0, 1, 2]))
        w.writeBool(8, true)
        w.writeSInt64(9, -42)

        // fixed32 is little-endian on the wire
        XCTAssertEqual([UInt8](w.data.prefix(5)), [0x0D, 0xEF, 0xBE, 0xAD, 0xDE])

        var got: [Int: MeshProtoField] = [:]
        try MeshProtoReader.forEachField(in: w.data) { got[$0.number] = $0 }
        XCTAssertEqual(got[1]?.uint32, 0xDEAD_BEEF)
        XCTAssertEqual(got[1]?.wireType, .fixed32)
        XCTAssertEqual(got[2]?.sfixed32, -302_000_000)
        XCTAssertEqual(got[3]?.uint64, 0x0102_0304_0506_0708)
        XCTAssertEqual(got[4]?.float, -7.25)
        XCTAssertEqual(got[5]?.double, 3.141592653589793)
        XCTAssertEqual(got[6]?.string, "héllo 📡")
        XCTAssertEqual(got[7]?.bytes, Data([0, 1, 2]))
        XCTAssertEqual(got[8]?.bool, true)
        XCTAssertEqual(got[9]?.sint64, -42)
    }

    func testUnknownFieldsOfEveryWireTypeAreSkipped() throws {
        var w = MeshProtoWriter()
        w.writeVarint(100, 12345)                     // unknown varint
        w.writeUInt32(1, 1)                           // portnum
        w.writeFixed64(101, .max)                     // unknown fixed64
        w.writeBytes(102, Data(repeating: 0xAA, count: 40)) // unknown length-delimited
        // unknown legacy group: start(103) { field 1 varint 5; nested group 104 {} } end(103)
        w.writeTag(103, .startGroup)
        w.writeVarint(1, 5)
        w.writeTag(104, .startGroup)
        w.writeTag(104, .endGroup)
        w.writeTag(103, .endGroup)
        w.writeBytes(2, Data("hi".utf8))              // payload
        w.writeFixed32(105, 7)                        // unknown fixed32
        w.writeFixed32(6, 0xCAFE)                     // request_id

        let d = try MeshPBData.decode(w.data)
        XCTAssertEqual(d.portnum, 1)
        XCTAssertEqual(d.payload, Data("hi".utf8))
        XCTAssertEqual(d.requestID, 0xCAFE)
    }

    func testMalformedInputThrows() {
        // length runs past the end
        XCTAssertThrowsError(try MeshPBData.decode(Data([0x12, 0x05, 0x01]))) {
            XCTAssertEqual($0 as? MeshProtoError, .lengthOutOfRange)
        }
        // wire type 7 does not exist
        XCTAssertThrowsError(try MeshPBData.decode(Data([0x0F]))) {
            XCTAssertEqual($0 as? MeshProtoError, .invalidWireType(7))
        }
        // field number 0 is invalid
        XCTAssertThrowsError(try MeshPBData.decode(Data([0x00, 0x01]))) {
            XCTAssertEqual($0 as? MeshProtoError, .invalidFieldNumber)
        }
        // truncated fixed32
        XCTAssertThrowsError(try MeshPBData.decode(Data([0x25, 0x01, 0x02]))) {
            XCTAssertEqual($0 as? MeshProtoError, .truncated)
        }
        // stray end-group
        XCTAssertThrowsError(try MeshPBData.decode(Data([0x0C]))) {
            XCTAssertEqual($0 as? MeshProtoError, .unbalancedGroup)
        }
        // unterminated group
        XCTAssertThrowsError(try MeshPBData.decode(Data([0x0B, 0x08, 0x01]))) {
            XCTAssertEqual($0 as? MeshProtoError, .unbalancedGroup)
        }
    }

    func testInvalidUTF8DecodesLossily() throws {
        var w = MeshProtoWriter()
        w.writeBytes(2, Data([0x68, 0xFF, 0x69]))
        var r = MeshProtoReader(w.data)
        let f = try XCTUnwrap(try r.next())
        XCTAssertEqual(f.string, "h\u{FFFD}i")
    }

    func testEmptyBufferHasNoFields() throws {
        var r = MeshProtoReader(Data())
        XCTAssertNil(try r.next())
    }
}
