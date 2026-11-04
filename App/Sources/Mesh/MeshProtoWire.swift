// MeshProtoWire.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// A small, hand-written Protocol Buffers wire-format encoder and decoder.
// It knows nothing about any schema: callers write and read fields by number.
// Supports varint, zigzag (sint32/sint64), fixed32/fixed64 (and their signed
// and float/double forms), and length-delimited fields. Unknown fields,
// including deprecated groups, can be skipped.

import Foundation

/// Protobuf wire types.
public enum MeshProtoWireType: UInt8, Sendable {
    case varint = 0
    case fixed64 = 1
    case lengthDelimited = 2
    case startGroup = 3
    case endGroup = 4
    case fixed32 = 5
}

public enum MeshProtoError: Error, Equatable, Sendable {
    case truncated
    case varintTooLong
    case invalidWireType(UInt8)
    case invalidFieldNumber
    case lengthOutOfRange
    case unbalancedGroup
}

// MARK: - Writer

/// Builds a protobuf message by appending fields.
public struct MeshProtoWriter: Sendable {
    public private(set) var data = Data()

    public init() {}

    // Raw primitives

    public mutating func writeRawVarint(_ value: UInt64) {
        var v = value
        while v >= 0x80 {
            data.append(UInt8(truncatingIfNeeded: v) | 0x80)
            v >>= 7
        }
        data.append(UInt8(v))
    }

    public mutating func writeTag(_ field: Int, _ type: MeshProtoWireType) {
        writeRawVarint(UInt64(field) << 3 | UInt64(type.rawValue))
    }

    public mutating func writeRawFixed32(_ value: UInt32) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }

    public mutating func writeRawFixed64(_ value: UInt64) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }

    // Field writers. Proto3 semantics: callers decide whether to skip defaults;
    // the `*IfNonZero` helpers do that for the common case.

    public mutating func writeVarint(_ field: Int, _ value: UInt64) {
        writeTag(field, .varint)
        writeRawVarint(value)
    }

    public mutating func writeUInt32(_ field: Int, _ value: UInt32) {
        writeVarint(field, UInt64(value))
    }

    /// int32 negatives are sign-extended to 10 bytes, as the spec requires.
    public mutating func writeInt32(_ field: Int, _ value: Int32) {
        writeVarint(field, UInt64(bitPattern: Int64(value)))
    }

    public mutating func writeInt64(_ field: Int, _ value: Int64) {
        writeVarint(field, UInt64(bitPattern: value))
    }

    public mutating func writeSInt32(_ field: Int, _ value: Int32) {
        writeVarint(field, UInt64(MeshProtoZigZag.encode32(value)))
    }

    public mutating func writeSInt64(_ field: Int, _ value: Int64) {
        writeVarint(field, MeshProtoZigZag.encode64(value))
    }

    public mutating func writeBool(_ field: Int, _ value: Bool) {
        writeVarint(field, value ? 1 : 0)
    }

    public mutating func writeFixed32(_ field: Int, _ value: UInt32) {
        writeTag(field, .fixed32)
        writeRawFixed32(value)
    }

    public mutating func writeSFixed32(_ field: Int, _ value: Int32) {
        writeFixed32(field, UInt32(bitPattern: value))
    }

    public mutating func writeFixed64(_ field: Int, _ value: UInt64) {
        writeTag(field, .fixed64)
        writeRawFixed64(value)
    }

    public mutating func writeFloat(_ field: Int, _ value: Float) {
        writeFixed32(field, value.bitPattern)
    }

    public mutating func writeDouble(_ field: Int, _ value: Double) {
        writeFixed64(field, value.bitPattern)
    }

    public mutating func writeBytes(_ field: Int, _ value: Data) {
        writeTag(field, .lengthDelimited)
        writeRawVarint(UInt64(value.count))
        data.append(value)
    }

    public mutating func writeString(_ field: Int, _ value: String) {
        writeBytes(field, Data(value.utf8))
    }

    public mutating func writeMessage(_ field: Int, _ build: (inout MeshProtoWriter) -> Void) {
        var sub = MeshProtoWriter()
        build(&sub)
        writeBytes(field, sub.data)
    }

    // Default-skipping helpers

    public mutating func writeUInt32IfNonZero(_ field: Int, _ value: UInt32) {
        if value != 0 { writeUInt32(field, value) }
    }

    public mutating func writeFixed32IfNonZero(_ field: Int, _ value: UInt32) {
        if value != 0 { writeFixed32(field, value) }
    }

    public mutating func writeBoolIfTrue(_ field: Int, _ value: Bool) {
        if value { writeBool(field, true) }
    }

    public mutating func writeStringIfNotEmpty(_ field: Int, _ value: String) {
        if !value.isEmpty { writeString(field, value) }
    }

    public mutating func writeBytesIfNotEmpty(_ field: Int, _ value: Data) {
        if !value.isEmpty { writeBytes(field, value) }
    }
}

// MARK: - Zigzag

public enum MeshProtoZigZag {
    public static func encode32(_ n: Int32) -> UInt32 {
        UInt32(bitPattern: (n << 1) ^ (n >> 31))
    }
    public static func decode32(_ n: UInt32) -> Int32 {
        Int32(bitPattern: (n >> 1) ^ (0 &- (n & 1)))
    }
    public static func encode64(_ n: Int64) -> UInt64 {
        UInt64(bitPattern: (n << 1) ^ (n >> 63))
    }
    public static func decode64(_ n: UInt64) -> Int64 {
        Int64(bitPattern: (n >> 1) ^ (0 &- (n & 1)))
    }
}

// MARK: - Reader

/// One decoded field. The value is kept raw; use the accessors to interpret it.
public struct MeshProtoField: Sendable {
    public let number: Int
    public let wireType: MeshProtoWireType
    /// For varint / fixed32 / fixed64.
    public let scalar: UInt64
    /// For length-delimited.
    public let bytes: Data

    public var uint32: UInt32 { UInt32(truncatingIfNeeded: scalar) }
    public var uint64: UInt64 { scalar }
    public var int32: Int32 { Int32(truncatingIfNeeded: Int64(bitPattern: scalar)) }
    public var int64: Int64 { Int64(bitPattern: scalar) }
    public var sint32: Int32 { MeshProtoZigZag.decode32(UInt32(truncatingIfNeeded: scalar)) }
    public var sint64: Int64 { MeshProtoZigZag.decode64(scalar) }
    public var bool: Bool { scalar != 0 }
    public var sfixed32: Int32 { Int32(bitPattern: uint32) }
    public var float: Float { Float(bitPattern: uint32) }
    public var double: Double { Double(bitPattern: scalar) }
    /// Lossy UTF-8 decode: invalid sequences become U+FFFD rather than failing.
    public var string: String { String(decoding: bytes, as: UTF8.self) }
}

/// Iterates the fields of a protobuf message. Groups (wire types 3/4) are
/// skipped as a unit; every other unknown field is returned and the caller
/// simply ignores numbers it does not care about.
public struct MeshProtoReader: Sendable {
    private let bytes: [UInt8]
    private var index = 0

    public init(_ data: Data) {
        self.bytes = [UInt8](data)
    }

    public var isAtEnd: Bool { index >= bytes.count }

    public mutating func readRawVarint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        for i in 0..<10 {
            guard index < bytes.count else { throw MeshProtoError.truncated }
            let b = bytes[index]
            index += 1
            if i == 9 && b > 0x01 { throw MeshProtoError.varintTooLong }
            result |= UInt64(b & 0x7F) << shift
            if b & 0x80 == 0 { return result }
            shift += 7
        }
        throw MeshProtoError.varintTooLong
    }

    private mutating func readRawFixed32() throws -> UInt32 {
        guard bytes.count - index >= 4 else { throw MeshProtoError.truncated }
        var v: UInt32 = 0
        for i in 0..<4 { v |= UInt32(bytes[index + i]) << (8 * UInt32(i)) }
        index += 4
        return v
    }

    private mutating func readRawFixed64() throws -> UInt64 {
        guard bytes.count - index >= 8 else { throw MeshProtoError.truncated }
        var v: UInt64 = 0
        for i in 0..<8 { v |= UInt64(bytes[index + i]) << (8 * UInt64(i)) }
        index += 8
        return v
    }

    private mutating func readTag() throws -> (Int, MeshProtoWireType) {
        let key = try readRawVarint()
        let wireRaw = UInt8(key & 0x7)
        guard let wire = MeshProtoWireType(rawValue: wireRaw) else {
            throw MeshProtoError.invalidWireType(wireRaw)
        }
        let number = key >> 3
        guard number >= 1, number <= 536_870_911 else { throw MeshProtoError.invalidFieldNumber }
        return (Int(number), wire)
    }

    /// Skips a group body up to and including the matching end-group tag.
    private mutating func skipGroup(_ startNumber: Int) throws {
        while true {
            guard !isAtEnd else { throw MeshProtoError.unbalancedGroup }
            let (number, wire) = try readTag()
            switch wire {
            case .varint: _ = try readRawVarint()
            case .fixed64: _ = try readRawFixed64()
            case .fixed32: _ = try readRawFixed32()
            case .lengthDelimited: _ = try readLengthDelimited()
            case .startGroup: try skipGroup(number)
            case .endGroup:
                guard number == startNumber else { throw MeshProtoError.unbalancedGroup }
                return
            }
        }
    }

    private mutating func readLengthDelimited() throws -> Data {
        let len = try readRawVarint()
        guard len <= UInt64(bytes.count - index) else { throw MeshProtoError.lengthOutOfRange }
        let n = Int(len)
        let slice = Data(bytes[index..<(index + n)])
        index += n
        return slice
    }

    /// Returns the next field, or nil at the end of the buffer.
    public mutating func next() throws -> MeshProtoField? {
        while !isAtEnd {
            let (number, wire) = try readTag()
            switch wire {
            case .varint:
                return MeshProtoField(number: number, wireType: wire, scalar: try readRawVarint(), bytes: Data())
            case .fixed64:
                return MeshProtoField(number: number, wireType: wire, scalar: try readRawFixed64(), bytes: Data())
            case .fixed32:
                return MeshProtoField(number: number, wireType: wire, scalar: UInt64(try readRawFixed32()), bytes: Data())
            case .lengthDelimited:
                return MeshProtoField(number: number, wireType: wire, scalar: 0, bytes: try readLengthDelimited())
            case .startGroup:
                try skipGroup(number) // legacy groups: never used by us, skip entirely
            case .endGroup:
                throw MeshProtoError.unbalancedGroup
            }
        }
        return nil
    }

    /// Convenience: visits every field in `data`.
    public static func forEachField(in data: Data, _ body: (MeshProtoField) throws -> Void) throws {
        var reader = MeshProtoReader(data)
        while let field = try reader.next() {
            try body(field)
        }
    }
}
