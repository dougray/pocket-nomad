// wire.test.js — ported from MeshTests/MeshProtoWireTests.swift
// SPDX-License-Identifier: Apache-2.0

import test from "node:test";
import assert from "node:assert/strict";
import { ProtoReader, ProtoWriter, WireType, ZigZag, utf8 } from "../wire.js";
import { decodeData } from "../meshtastic.js";

const U32MAX = 0xffffffffn;
const U64MAX = (1n << 64n) - 1n;
const I64MAX = (1n << 63n) - 1n;
const I64MIN = -(1n << 63n);

function varintBytes(v) {
  const w = new ProtoWriter();
  w.writeRawVarint(v);
  return Array.from(w.data);
}

const readVarint = (bytes) => new ProtoReader(Uint8Array.from(bytes)).readRawVarint();

const throwsCode = (fn, code, detail) =>
  assert.throws(fn, (e) => e.code === code && (detail === undefined || e.detail === detail));

test("varint known encodings", () => {
  assert.deepEqual(varintBytes(0), [0x00]);
  assert.deepEqual(varintBytes(1), [0x01]);
  assert.deepEqual(varintBytes(127), [0x7f]);
  assert.deepEqual(varintBytes(128), [0x80, 0x01]);
  assert.deepEqual(varintBytes(300), [0xac, 0x02]);
  assert.deepEqual(varintBytes(16_383), [0xff, 0x7f]);
  assert.deepEqual(varintBytes(16_384), [0x80, 0x80, 0x01]);
  assert.deepEqual(varintBytes(U32MAX), [0xff, 0xff, 0xff, 0xff, 0x0f]);
  assert.deepEqual(varintBytes(0xffffffff), [0xff, 0xff, 0xff, 0xff, 0x0f]);
  assert.deepEqual(varintBytes(U64MAX), [0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01]);
});

test("varint round trip edge values", () => {
  const values = [0n, 1n, 127n, 128n, 255n, 256n, 16_383n, 16_384n, 2_097_151n, 2_097_152n,
    U32MAX - 1n, U32MAX, U32MAX + 1n, I64MAX, U64MAX - 1n, U64MAX];
  for (const v of values) assert.equal(readVarint(varintBytes(v)), v, `value ${v}`);
});

test("varint too long throws", () => {
  // 11 continuation bytes.
  throwsCode(() => readVarint([...new Array(10).fill(0xff), 0x01]), "varintTooLong");
  // 10th byte carries more than one bit: overflows 64 bits.
  throwsCode(() => readVarint([...new Array(9).fill(0xff), 0x02]), "varintTooLong");
});

test("varint truncated throws", () => {
  throwsCode(() => readVarint([0x80]), "truncated");
  throwsCode(() => readVarint([]), "truncated");
});

test("negative int32 uses ten bytes", () => {
  const w = new ProtoWriter();
  w.writeInt32(1, -1);
  assert.equal(w.data.length, 11); // 1 tag + 10 varint bytes
  const f = new ProtoReader(w.data).next();
  assert.equal(f.int32, -1);
  assert.equal(f.int64, -1n);
});

test("zigzag", () => {
  assert.equal(ZigZag.encode32(0), 0);
  assert.equal(ZigZag.encode32(-1), 1);
  assert.equal(ZigZag.encode32(1), 2);
  assert.equal(ZigZag.encode32(-2), 3);
  assert.equal(ZigZag.encode32(2147483647), 0xfffffffe);
  assert.equal(ZigZag.encode32(-2147483648), 0xffffffff);
  for (const v of [0, 1, -1, 63, -64, 1000, -1000, 2147483647, -2147483648]) {
    assert.equal(ZigZag.decode32(ZigZag.encode32(v)), v);
  }
  for (const v of [0n, 1n, -1n, I64MAX, I64MIN, -123_456_789_012n]) {
    assert.equal(ZigZag.decode64(ZigZag.encode64(v)), v);
  }
  const w = new ProtoWriter();
  w.writeSInt32(1, -2);
  assert.deepEqual(Array.from(w.data), [0x08, 0x03]);
});

test("fixed, float and bytes round trip", () => {
  const w = new ProtoWriter();
  w.writeFixed32(1, 0xdeadbeef);
  w.writeSFixed32(2, -302_000_000);
  w.writeFixed64(3, 0x0102030405060708n);
  w.writeFloat(4, -7.25);
  w.writeDouble(5, 3.141592653589793);
  w.writeString(6, "héllo 📡");
  w.writeBytes(7, Uint8Array.of(0, 1, 2));
  w.writeBool(8, true);
  w.writeSInt64(9, -42n);

  // fixed32 is little-endian on the wire
  assert.deepEqual(Array.from(w.data.slice(0, 5)), [0x0d, 0xef, 0xbe, 0xad, 0xde]);

  const got = {};
  ProtoReader.forEachField(w.data, (f) => { got[f.number] = f; });
  assert.equal(got[1].uint32, 0xdeadbeef);
  assert.equal(got[1].wireType, WireType.fixed32);
  assert.equal(got[2].sfixed32, -302_000_000);
  assert.equal(got[3].uint64, 0x0102030405060708n);
  assert.equal(got[4].float, -7.25);
  assert.equal(got[5].double, 3.141592653589793);
  assert.equal(got[6].string, "héllo 📡");
  assert.deepEqual(got[7].bytes, Uint8Array.of(0, 1, 2));
  assert.equal(got[8].bool, true);
  assert.equal(got[9].sint64, -42n);
});

test("unknown fields of every wire type are skipped", () => {
  const w = new ProtoWriter();
  w.writeVarint(100, 12345); // unknown varint
  w.writeUInt32(1, 1); // portnum
  w.writeFixed64(101, U64MAX); // unknown fixed64
  w.writeBytes(102, new Uint8Array(40).fill(0xaa)); // unknown length-delimited
  // unknown legacy group: start(103) { field 1 varint 5; nested group 104 {} } end(103)
  w.writeTag(103, WireType.startGroup);
  w.writeVarint(1, 5);
  w.writeTag(104, WireType.startGroup);
  w.writeTag(104, WireType.endGroup);
  w.writeTag(103, WireType.endGroup);
  w.writeBytes(2, utf8("hi")); // payload
  w.writeFixed32(105, 7); // unknown fixed32
  w.writeFixed32(6, 0xcafe); // request_id

  const d = decodeData(w.data);
  assert.equal(d.portnum, 1);
  assert.deepEqual(d.payload, utf8("hi"));
  assert.equal(d.requestID, 0xcafe);
});

test("malformed input throws", () => {
  const dec = (arr) => () => decodeData(Uint8Array.from(arr));
  throwsCode(dec([0x12, 0x05, 0x01]), "lengthOutOfRange"); // length runs past the end
  throwsCode(dec([0x0f]), "invalidWireType", 7); // wire type 7 does not exist
  throwsCode(dec([0x00, 0x01]), "invalidFieldNumber"); // field number 0 is invalid
  throwsCode(dec([0x25, 0x01, 0x02]), "truncated"); // truncated fixed32
  throwsCode(dec([0x0c]), "unbalancedGroup"); // stray end-group
  throwsCode(dec([0x0b, 0x08, 0x01]), "unbalancedGroup"); // unterminated group
});

test("invalid UTF-8 decodes lossily", () => {
  const w = new ProtoWriter();
  w.writeBytes(2, Uint8Array.of(0x68, 0xff, 0x69));
  assert.equal(new ProtoReader(w.data).next().string, "h�i");
});

test("empty buffer has no fields", () => {
  assert.equal(new ProtoReader(new Uint8Array(0)).next(), null);
});
