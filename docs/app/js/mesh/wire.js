// wire.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// A small, hand-written Protocol Buffers wire-format encoder and decoder,
// ported from App/Sources/Mesh/MeshProtoWire.swift. It knows nothing about any
// schema: callers write and read fields by number. Supports varint, zigzag
// (sint32/sint64), fixed32/fixed64 (and their signed and float/double forms),
// and length-delimited fields. Unknown fields, including deprecated groups,
// can be skipped.
//
// 64-bit values are BigInt. 32-bit values are plain numbers.

export const WireType = Object.freeze({
  varint: 0,
  fixed64: 1,
  lengthDelimited: 2,
  startGroup: 3,
  endGroup: 4,
  fixed32: 5,
});

const VALID_WIRE = new Set([0, 1, 2, 3, 4, 5]);
const U64 = (1n << 64n) - 1n;

export class ProtoError extends Error {
  /** @param {"truncated"|"varintTooLong"|"invalidWireType"|"invalidFieldNumber"|"lengthOutOfRange"|"unbalancedGroup"} code */
  constructor(code, detail) {
    super(detail === undefined ? code : `${code}(${detail})`);
    this.name = "ProtoError";
    this.code = code;
    this.detail = detail;
  }
}

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: false });

export const utf8 = (s) => encoder.encode(s);
/** Lossy UTF-8 decode: invalid sequences become U+FFFD rather than failing. */
export const fromUtf8 = (b) => decoder.decode(b);

export function concatBytes(...parts) {
  let n = 0;
  for (const p of parts) n += p.length;
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

// MARK: Zigzag

export const ZigZag = {
  encode32(n) {
    n |= 0;
    return ((n << 1) ^ (n >> 31)) >>> 0;
  },
  decode32(n) {
    n >>>= 0;
    return (n >>> 1) ^ -(n & 1);
  },
  encode64(n) {
    n = BigInt.asIntN(64, BigInt(n));
    return BigInt.asUintN(64, (n << 1n) ^ (n >> 63n));
  },
  decode64(n) {
    n = BigInt.asUintN(64, BigInt(n));
    return BigInt.asIntN(64, (n >> 1n) ^ -(n & 1n));
  },
};

// MARK: Writer

/** Builds a protobuf message by appending fields. */
export class ProtoWriter {
  constructor() {
    this._buf = new Uint8Array(64);
    this._len = 0;
  }

  get data() {
    return this._buf.slice(0, this._len);
  }

  _ensure(n) {
    if (this._len + n <= this._buf.length) return;
    let size = this._buf.length * 2;
    while (size < this._len + n) size *= 2;
    const next = new Uint8Array(size);
    next.set(this._buf.subarray(0, this._len));
    this._buf = next;
  }

  _push(byte) {
    this._ensure(1);
    this._buf[this._len++] = byte;
  }

  appendBytes(bytes) {
    this._ensure(bytes.length);
    this._buf.set(bytes, this._len);
    this._len += bytes.length;
  }

  // Raw primitives

  /** @param {number|bigint} value unsigned, up to 64 bits */
  writeRawVarint(value) {
    if (typeof value === "number" && value >= 0 && value <= 0xffffffff && Number.isInteger(value)) {
      let v = value;
      while (v >= 0x80) {
        this._push((v & 0x7f) | 0x80);
        v = Math.floor(v / 128);
      }
      this._push(v);
      return;
    }
    let v = BigInt.asUintN(64, BigInt(value));
    while (v >= 0x80n) {
      this._push(Number(v & 0x7fn) | 0x80);
      v >>= 7n;
    }
    this._push(Number(v));
  }

  writeTag(field, type) {
    this.writeRawVarint(field * 8 + type);
  }

  writeRawFixed32(value) {
    const v = value >>> 0;
    this._ensure(4);
    for (let i = 0; i < 4; i++) this._buf[this._len++] = (v >>> (8 * i)) & 0xff;
  }

  writeRawFixed64(value) {
    let v = BigInt.asUintN(64, BigInt(value));
    this._ensure(8);
    for (let i = 0; i < 8; i++) {
      this._buf[this._len++] = Number(v & 0xffn);
      v >>= 8n;
    }
  }

  // Field writers. Proto3 semantics: callers decide whether to skip defaults;
  // the `*IfNonZero` helpers do that for the common case.

  writeVarint(field, value) {
    this.writeTag(field, WireType.varint);
    this.writeRawVarint(value);
  }

  writeUInt32(field, value) {
    this.writeVarint(field, value >>> 0);
  }

  /** int32 negatives are sign-extended to 10 bytes, as the spec requires. */
  writeInt32(field, value) {
    value |= 0;
    this.writeVarint(field, value >= 0 ? value : BigInt.asUintN(64, BigInt(value)));
  }

  writeInt64(field, value) {
    this.writeVarint(field, BigInt.asUintN(64, BigInt(value)));
  }

  writeSInt32(field, value) {
    this.writeVarint(field, ZigZag.encode32(value));
  }

  writeSInt64(field, value) {
    this.writeVarint(field, ZigZag.encode64(value));
  }

  writeBool(field, value) {
    this.writeVarint(field, value ? 1 : 0);
  }

  writeFixed32(field, value) {
    this.writeTag(field, WireType.fixed32);
    this.writeRawFixed32(value);
  }

  writeSFixed32(field, value) {
    this.writeFixed32(field, value >>> 0);
  }

  writeFixed64(field, value) {
    this.writeTag(field, WireType.fixed64);
    this.writeRawFixed64(value);
  }

  writeFloat(field, value) {
    const dv = new DataView(new ArrayBuffer(4));
    dv.setFloat32(0, value, true);
    this.writeFixed32(field, dv.getUint32(0, true));
  }

  writeDouble(field, value) {
    const dv = new DataView(new ArrayBuffer(8));
    dv.setFloat64(0, value, true);
    this.writeFixed64(field, dv.getBigUint64(0, true));
  }

  writeBytes(field, value) {
    this.writeTag(field, WireType.lengthDelimited);
    this.writeRawVarint(value.length);
    this.appendBytes(value);
  }

  writeString(field, value) {
    this.writeBytes(field, utf8(value));
  }

  /** @param {(w: ProtoWriter) => void} build */
  writeMessage(field, build) {
    const sub = new ProtoWriter();
    build(sub);
    this.writeBytes(field, sub.data);
  }

  // Default-skipping helpers

  writeUInt32IfNonZero(field, value) {
    if (value) this.writeUInt32(field, value);
  }

  writeFixed32IfNonZero(field, value) {
    if (value) this.writeFixed32(field, value);
  }

  writeBoolIfTrue(field, value) {
    if (value) this.writeBool(field, true);
  }

  writeStringIfNotEmpty(field, value) {
    if (value) this.writeString(field, value);
  }

  writeBytesIfNotEmpty(field, value) {
    if (value && value.length) this.writeBytes(field, value);
  }
}

// MARK: Reader

/** One decoded field. The value is kept raw; use the accessors to interpret it. */
export class ProtoField {
  constructor(number, wireType, scalar, bytes) {
    this.number = number;
    this.wireType = wireType;
    /** For varint / fixed32 / fixed64: BigInt. */
    this.scalar = scalar;
    /** For length-delimited. */
    this.bytes = bytes;
  }
  get uint32() { return Number(BigInt.asUintN(32, this.scalar)); }
  get uint64() { return this.scalar; }
  get int32() { return Number(BigInt.asIntN(32, this.scalar)); }
  get int64() { return BigInt.asIntN(64, this.scalar); }
  get sint32() { return ZigZag.decode32(this.uint32); }
  get sint64() { return ZigZag.decode64(this.scalar); }
  get bool() { return this.scalar !== 0n; }
  get sfixed32() { return this.uint32 | 0; }
  get float() {
    const dv = new DataView(new ArrayBuffer(4));
    dv.setUint32(0, this.uint32, true);
    return dv.getFloat32(0, true);
  }
  get double() {
    const dv = new DataView(new ArrayBuffer(8));
    dv.setBigUint64(0, this.scalar, true);
    return dv.getFloat64(0, true);
  }
  get string() { return fromUtf8(this.bytes); }
}

const EMPTY = new Uint8Array(0);

/**
 * Iterates the fields of a protobuf message. Groups (wire types 3/4) are
 * skipped as a unit; every other unknown field is returned and the caller
 * simply ignores numbers it does not care about.
 */
export class ProtoReader {
  /** @param {Uint8Array|ArrayLike<number>} data */
  constructor(data) {
    this.bytes = data instanceof Uint8Array ? data : Uint8Array.from(data);
    this.index = 0;
  }

  get isAtEnd() {
    return this.index >= this.bytes.length;
  }

  /** @returns {bigint} */
  readRawVarint() {
    let result = 0n;
    let shift = 0n;
    const b = this.bytes;
    for (let i = 0; i < 10; i++) {
      if (this.index >= b.length) throw new ProtoError("truncated");
      const byte = b[this.index++];
      if (i === 9 && byte > 0x01) throw new ProtoError("varintTooLong");
      result |= BigInt(byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return result & U64;
      shift += 7n;
    }
    throw new ProtoError("varintTooLong");
  }

  _readRawFixed32() {
    if (this.bytes.length - this.index < 4) throw new ProtoError("truncated");
    let v = 0;
    for (let i = 0; i < 4; i++) v += this.bytes[this.index + i] * 2 ** (8 * i);
    this.index += 4;
    return BigInt(v);
  }

  _readRawFixed64() {
    if (this.bytes.length - this.index < 8) throw new ProtoError("truncated");
    let v = 0n;
    for (let i = 7; i >= 0; i--) v = (v << 8n) | BigInt(this.bytes[this.index + i]);
    this.index += 8;
    return v;
  }

  _readTag() {
    const key = this.readRawVarint();
    const wire = Number(key & 7n);
    if (!VALID_WIRE.has(wire)) throw new ProtoError("invalidWireType", wire);
    const number = key >> 3n;
    if (number < 1n || number > 536870911n) throw new ProtoError("invalidFieldNumber");
    return [Number(number), wire];
  }

  /** Skips a group body up to and including the matching end-group tag. */
  _skipGroup(startNumber) {
    for (;;) {
      if (this.isAtEnd) throw new ProtoError("unbalancedGroup");
      const [number, wire] = this._readTag();
      switch (wire) {
        case WireType.varint: this.readRawVarint(); break;
        case WireType.fixed64: this._readRawFixed64(); break;
        case WireType.fixed32: this._readRawFixed32(); break;
        case WireType.lengthDelimited: this._readLengthDelimited(); break;
        case WireType.startGroup: this._skipGroup(number); break;
        case WireType.endGroup:
          if (number !== startNumber) throw new ProtoError("unbalancedGroup");
          return;
      }
    }
  }

  _readLengthDelimited() {
    const len = this.readRawVarint();
    if (len > BigInt(this.bytes.length - this.index)) throw new ProtoError("lengthOutOfRange");
    const n = Number(len);
    const slice = this.bytes.slice(this.index, this.index + n);
    this.index += n;
    return slice;
  }

  /** Returns the next field, or null at the end of the buffer. */
  next() {
    while (!this.isAtEnd) {
      const [number, wire] = this._readTag();
      switch (wire) {
        case WireType.varint:
          return new ProtoField(number, wire, this.readRawVarint(), EMPTY);
        case WireType.fixed64:
          return new ProtoField(number, wire, this._readRawFixed64(), EMPTY);
        case WireType.fixed32:
          return new ProtoField(number, wire, this._readRawFixed32(), EMPTY);
        case WireType.lengthDelimited:
          return new ProtoField(number, wire, 0n, this._readLengthDelimited());
        case WireType.startGroup:
          this._skipGroup(number); // legacy groups: never used by us, skip entirely
          break;
        case WireType.endGroup:
          throw new ProtoError("unbalancedGroup");
      }
    }
    return null;
  }

  /** Convenience: visits every field in `data`. */
  static forEachField(data, body) {
    const r = new ProtoReader(data);
    let f;
    while ((f = r.next()) !== null) body(f);
  }
}
