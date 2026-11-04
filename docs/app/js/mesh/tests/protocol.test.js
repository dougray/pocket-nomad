// protocol.test.js — ported from MeshTests/MeshProtocolTests.swift
// SPDX-License-Identifier: Apache-2.0

import test from "node:test";
import assert from "node:assert/strict";
import {
  MeshConstants, decodeFromRadio, decodeNodeInfo, decodePacket, decodeRouting, decodeToRadio,
  describeRoutingError, encodeFromRadio, encodeNodeInfo, encodePacket, encodeRouting, encodeToRadio,
  hopsAway, makeChannel, makeData, makeDeviceMetadata, makeDeviceMetrics, makeLoRaConfig, makeMyNodeInfo,
  makeNodeInfo, makePacket, makePosition, makeQueueStatus, makeUser, positionHasFix, positionLatitude,
  positionLongitude, variant,
} from "../meshtastic.js";
import { ProtoWriter, utf8 } from "../wire.js";
import { byteCount, formatDegrees, sosText, truncated } from "../models.js";

const f32 = Math.fround;

test("want_config wire bytes", () => {
  // ToRadio field 3 (want_config_id), varint: tag 0x18
  assert.deepEqual(Array.from(encodeToRadio(variant("wantConfigID", 1))), [0x18, 0x01]);
  assert.deepEqual(Array.from(encodeToRadio(variant("wantConfigID", 300))), [0x18, 0xac, 0x02]);
});

test("broadcast text packet wire bytes", () => {
  const p = makePacket({
    to: MeshConstants.broadcastNum,
    decoded: makeData({ portnum: 1, payload: utf8("hi") }),
    id: 0x01020304,
    hopLimit: 3,
    wantAck: true,
  });
  const bytes = Array.from(encodeToRadio(variant("packet", p)));
  const expectedPacket = [
    0x15, 0xff, 0xff, 0xff, 0xff, // to: fixed32 field 2
    0x22, 0x06, 0x08, 0x01, 0x12, 0x02, 0x68, 0x69, // decoded: Data{portnum 1, payload "hi"}
    0x35, 0x04, 0x03, 0x02, 0x01, // id: fixed32 field 6, little-endian
    0x48, 0x03, // hop_limit field 9
    0x50, 0x01, // want_ack field 10
  ];
  assert.deepEqual(bytes, [0x0a, expectedPacket.length, ...expectedPacket]);
});

test("ToRadio round trip", () => {
  const cases = [
    variant("wantConfigID", 0xffffffff),
    variant("disconnect"),
    variant("heartbeat", 9),
    variant("packet", makePacket({
      from: 1, to: 2, channel: 1,
      decoded: makeData({ portnum: 1, payload: utf8("x"), replyID: 5, emoji: 1 }),
      id: 77, hopLimit: 3, wantAck: true, priority: 70,
    })),
  ];
  for (const c of cases) assert.deepEqual(decodeToRadio(encodeToRadio(c)), c);
});

test("packet round trip with all fields", () => {
  const p = makePacket({
    from: 0xa1b2c3d4, to: 0xffffffff, channel: 2,
    decoded: makeData({ portnum: 3, payload: Uint8Array.of(1, 2, 3), wantResponse: true, dest: 9, source: 8,
      requestID: 7, replyID: 6, emoji: 1, bitfield: 1 }),
    id: 0xfffffffe, rxTime: 1_760_000_000, rxSNR: -12.75, hopLimit: 2, wantAck: true,
    priority: 64, rxRSSI: -118, viaMQTT: true, hopStart: 5,
  });
  const back = decodePacket(encodePacket(p));
  assert.deepEqual(back, p);
  assert.equal(hopsAway(back), 3);
});

test("encrypted packet keeps bytes", () => {
  const p = makePacket({ from: 1, to: 2, encrypted: Uint8Array.of(9, 9, 9), id: 3 });
  const back = decodePacket(encodePacket(p));
  assert.equal(back.decoded, null);
  assert.deepEqual(back.encrypted, Uint8Array.of(9, 9, 9));
});

test("NodeInfo round trip and position", () => {
  // Made-up coordinates: 12.34567, -45.67891 (open ocean).
  const n = makeNodeInfo({
    num: 0xdeadbeef,
    user: makeUser({ id: "!deadbeef", longName: "Base Camp", shortName: "BC", hwModel: 43,
      isLicensed: false, role: 0, publicKey: new Uint8Array(32).fill(1) }),
    position: makePosition({ latitudeI: 123_456_700, longitudeI: -456_789_100, altitude: 150, time: 1 }),
    snr: 6.5, lastHeard: 1_760_000_000,
    deviceMetrics: makeDeviceMetrics({ batteryLevel: 101, voltage: f32(4.2) }),
    channel: 0, viaMQTT: false, hopsAway: 0,
  });
  const back = decodeNodeInfo(encodeNodeInfo(n));
  assert.deepEqual(back, n);
  assert.ok(Math.abs(positionLatitude(back.position) - 12.34567) < 1e-7);
  assert.ok(Math.abs(positionLongitude(back.position) - -45.67891) < 1e-7);
  assert.equal(positionHasFix(back.position), true);
  assert.equal(positionHasFix(makePosition({ latitudeI: 0, longitudeI: 0 })), false);
  assert.equal(positionHasFix(makePosition()), false);
});

test("FromRadio variants round trip", () => {
  const variants = [
    variant("myInfo", makeMyNodeInfo({ myNodeNum: 42, rebootCount: 3, minAppVersion: 30200 })),
    variant("nodeInfo", makeNodeInfo({ num: 5, user: makeUser({ longName: "A" }) })),
    variant("configCompleteID", 123_456),
    variant("rebooted"),
    variant("channel", makeChannel({ index: 0, name: "LongFast", hasPSK: true, role: 1 })),
    variant("channel", makeChannel({ index: 2, name: "Family", hasPSK: true, role: 2 })),
    variant("queueStatus", makeQueueStatus({ res: -1, free: 0, maxlen: 16, meshPacketID: 99 })),
    variant("metadata", makeDeviceMetadata({ firmwareVersion: "2.6.11.abc", role: 0, hwModel: 43, hasPKC: true })),
    variant("loraConfig", makeLoRaConfig({ modemPreset: 0, region: 1, hopLimit: 3 })),
    variant("clientNotification", "Rate limited"),
    variant("logRecord", "boot"),
    variant("packet", makePacket({ from: 1, to: 2, decoded: makeData({ portnum: 1, payload: utf8("yo") }), id: 3 })),
  ];
  for (const v of variants) {
    const msg = { id: 7, variant: v };
    assert.deepEqual(decodeFromRadio(encodeFromRadio(msg)), msg, v.kind);
  }
});

test("non-lora config and unknown variant", () => {
  assert.deepEqual(decodeFromRadio(encodeFromRadio({ id: 0, variant: variant("otherConfig") })).variant, variant("otherConfig"));
  // moduleConfig (field 9) is not modelled
  const w = new ProtoWriter();
  w.writeUInt32(1, 4);
  w.writeMessage(9, (s) => s.writeVarint(1, 1));
  const r = decodeFromRadio(w.data);
  assert.equal(r.id, 4);
  assert.deepEqual(r.variant, variant("unhandled", 9));
});

test("routing decode", () => {
  assert.equal(decodeRouting(encodeRouting({ errorReason: 0 })).errorReason, 0);
  assert.deepEqual(Array.from(encodeRouting({ errorReason: 0 })), [0x18, 0x00]); // oneof: zero still written
  assert.equal(decodeRouting(encodeRouting({ errorReason: 5 })).errorReason, 5);
  assert.equal(decodeRouting(new Uint8Array(0)).errorReason, 0);
  const w = new ProtoWriter();
  w.writeMessage(1, (s) => s.writeFixed32(1, 1)); // route_request
  assert.equal(decodeRouting(w.data).errorReason, null);
  assert.equal(describeRoutingError(5), "Their radio didn't answer");
  assert.equal(describeRoutingError(200), "Error 200");
});

test("SOS text formatting", () => {
  const t = sosText(12.345671, -45.678911, "Trail Lead");
  assert.ok(t.includes("12.34567, -45.67891"), t);
  assert.ok(t.startsWith("SOS"));
  assert.ok(byteCount(t) <= MeshConstants.maxTextBytes);
  assert.equal(t, "SOS - I need help. Location: 12.34567, -45.67891 From Trail Lead. Please reply.");
  assert.ok(sosText(null, null, null).includes("Location unknown"));
  assert.equal(formatDegrees(-0.000004), "-0.00000");
  assert.equal(formatDegrees(12), "12.00000");
  const long = sosText(1, 2, "N".repeat(400));
  assert.ok(byteCount(long) <= MeshConstants.maxTextBytes);
});

test("truncation respects characters", () => {
  const s = "📡".repeat(60); // 4 bytes each
  assert.equal(byteCount(truncated(s, 200)), 200);
  assert.equal(truncated("é" + "📡", 4), "é");
  assert.equal(byteCount("é"), 2);
});
