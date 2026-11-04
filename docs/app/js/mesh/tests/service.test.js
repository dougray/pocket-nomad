// service.test.js — ported from MeshTests/MeshServiceTests.swift
// SPDX-License-Identifier: Apache-2.0
//
// Drives MeshService through a fake radio: handshake, chat, ACKs, persistence.
// Drive-test ping tests are not ported: there is no drive mode on the web.

import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";
import {
  MeshConstants, MeshPort, RoutingError, encodeRouting, encodeUser, encodePosition, makeChannel, makeData,
  makeDeviceMetadata, makeLoRaConfig, makeMyNodeInfo, makeNodeInfo, makePacket, makePosition, makeQueueStatus,
  makeUser, variant,
} from "../meshtastic.js";
import { MeshService, newPacketID } from "../service.js";
import { MAX_MESSAGES, MemoryStore, MeshStore, capped } from "../store.js";
import { channelConv, directConv, makeMessage } from "../models.js";
import { fromUtf8, utf8 } from "../wire.js";
import { FakeMeshTransport, FakeTimers } from "./fakes.js";

const me = 0x11111111;
const alice = 0xaaaa0001;
const bob = 0xbbbb0002;
const NOW = 1_790_000_000_000;
const nowSec = Math.floor(NOW / 1000);
const BCAST = MeshConstants.broadcastNum;

let kv;
beforeEach(() => {
  kv = new MemoryStore();
});

async function makeService(fake, extra = {}) {
  const svc = new MeshService({ transport: fake, store: new MeshStore(kv), timers: new FakeTimers(), now: () => NOW, ...extra });
  await svc.init();
  return svc;
}

/** Connects and completes the full startup handshake. */
async function connected() {
  const fake = new FakeMeshTransport();
  const svc = await makeService(fake);
  svc.start();
  await svc.pickRadio();
  fake.becomeReady();
  const cfg = fake.lastWantConfigID();
  assert.ok(cfg);
  fake.enqueue(variant("myInfo", makeMyNodeInfo({ myNodeNum: me })));
  fake.enqueue(variant("metadata", makeDeviceMetadata({ firmwareVersion: "2.6.0" })));
  fake.enqueue(variant("nodeInfo", makeNodeInfo({ num: me, user: makeUser({ id: "!11111111", longName: "My Radio", shortName: "ME" }) })));
  // Made-up position: 12.34567, -45.67891 (open ocean).
  fake.enqueue(variant("nodeInfo", makeNodeInfo({
    num: alice, user: makeUser({ longName: "Alice", shortName: "AL" }),
    position: makePosition({ latitudeI: 123_456_700, longitudeI: -456_789_100 }),
    snr: 5.5, lastHeard: nowSec - 60, hopsAway: 1,
  })));
  fake.enqueue(variant("nodeInfo", makeNodeInfo({ num: bob, user: makeUser({ longName: "Bob", shortName: "BO" }), lastHeard: nowSec - 3600 })));
  fake.enqueue(variant("channel", makeChannel({ index: 0, name: "LongFast", hasPSK: true, role: 1 })));
  fake.enqueue(variant("channel", makeChannel({ index: 1, name: "", role: 0 })));
  fake.enqueue(variant("loraConfig", makeLoRaConfig({ modemPreset: 0, region: 1, hopLimit: 4 })));
  fake.enqueue(variant("otherConfig"));
  fake.enqueue(variant("unhandled", 9));
  fake.enqueue(variant("configCompleteID", cfg));
  fake.pump();
  return { svc, fake };
}

function incoming(from, to, text, id, channel = 0) {
  return variant("packet", makePacket({
    from, to, channel, decoded: makeData({ portnum: MeshPort.textMessage, payload: utf8(text) }),
    id, rxTime: nowSec, rxSNR: 4.25, hopLimit: 2, hopStart: 3,
  }));
}

function routing(from, requestID, error) {
  return variant("packet", makePacket({
    from, to: me,
    decoded: makeData({ portnum: MeshPort.routing, payload: encodeRouting({ errorReason: error }), requestID }),
    id: newPacketID(),
  }));
}

function deliver(fake, ...variants) {
  for (const v of variants) fake.enqueue(v);
  fake.emit({ type: "fromNumChanged" });
  fake.pump();
}

// MARK: Handshake

test("handshake collects nodes, channels and settings", async () => {
  const { svc } = await connected();
  assert.deepEqual(svc.connection, { state: "connected", name: "Test Radio" });
  assert.equal(svc.myNodeNum, me);
  assert.equal(svc.radio.firmwareVersion, "2.6.0");
  assert.equal(svc.radio.primaryChannelName, "LongFast");
  assert.equal(svc.radio.channelNames[1], undefined);
  assert.equal(svc.radio.region, 1);
  assert.equal(svc.radio.hopLimit, 4);
  assert.equal(svc.myNode.longName, "My Radio");
  // Own node excluded, most recently heard first.
  assert.deepEqual(svc.sortedNodes.map((n) => n.num), [alice, bob]);
  const a = svc.nodes.get(alice);
  assert.equal(a.shortName, "AL");
  assert.equal(a.hopsAway, 1);
  assert.equal(a.snr, 5.5);
  assert.ok(Math.abs(a.latitude - 12.34567) < 1e-6);
  assert.equal(a.firmware, "meshtastic");
  assert.equal(svc.notice, null);
});

test("handshake ignores mismatched config_complete_id", async () => {
  const fake = new FakeMeshTransport();
  const svc = await makeService(fake);
  svc.start();
  await svc.pickRadio();
  fake.becomeReady();
  assert.deepEqual(svc.connection, { state: "syncing", name: "Test Radio" });
  const cfg = fake.lastWantConfigID();
  fake.enqueue(variant("myInfo", makeMyNodeInfo({ myNodeNum: me })));
  fake.enqueue(variant("configCompleteID", (cfg + 1) >>> 0)); // stale reply to someone else's request
  fake.pump();
  assert.equal(svc.connection.state, "syncing");
  deliver(fake, variant("configCompleteID", cfg));
  assert.equal(svc.connection.state, "connected");
});

test("handshake retries once, then gives up politely", async () => {
  const fake = new FakeMeshTransport();
  const timers = new FakeTimers();
  const svc = await makeService(fake, { timers });
  svc.start();
  await svc.pickRadio();
  fake.becomeReady();
  const first = fake.lastWantConfigID();
  fake.pump();
  timers.advance(30_000);
  assert.notEqual(fake.lastWantConfigID(), first, "second want_config");
  assert.equal(svc.connection.state, "syncing");
  timers.advance(30_000);
  assert.equal(svc.connection.state, "connected");
  assert.match(svc.notice, /did not finish sending its settings/);
});

test("region unset shows notice", async () => {
  const fake = new FakeMeshTransport();
  const svc = await makeService(fake);
  svc.start();
  await svc.pickRadio();
  fake.becomeReady();
  const cfg = fake.lastWantConfigID();
  fake.enqueue(variant("loraConfig", makeLoRaConfig({ region: 0 })));
  fake.enqueue(variant("configCompleteID", cfg));
  fake.pump();
  assert.ok(svc.notice?.includes("US 915"));
});

test("reboot triggers new handshake", async () => {
  const { svc, fake } = await connected();
  const first = fake.lastWantConfigID();
  deliver(fake, variant("rebooted"));
  assert.equal(svc.connection.state, "syncing");
  assert.notEqual(fake.lastWantConfigID(), first);
});

test("corrupt packet is skipped and reading continues", async () => {
  const { svc, fake } = await connected();
  fake.queue.push(Uint8Array.of(0x12, 0x7f, 0x00)); // length past end
  deliver(fake, incoming(alice, BCAST, "after junk", 50));
  assert.deepEqual(svc.messagesIn(channelConv(0)).map((m) => m.text), ["after junk"]);
});

// MARK: Incoming messages

test("incoming broadcast and duplicate", async () => {
  const { svc, fake } = await connected();
  deliver(fake, incoming(alice, BCAST, "hello mesh", 100), incoming(alice, BCAST, "hello mesh", 100));
  const thread = svc.messagesIn(channelConv(0));
  assert.equal(thread.length, 1);
  assert.equal(thread[0].text, "hello mesh");
  assert.equal(thread[0].delivery, "received");
  assert.equal(thread[0].hopsAway, 1);
  assert.equal(svc.unreadCount(channelConv(0)), 1);
  svc.markRead(channelConv(0));
  assert.equal(svc.unreadCount(channelConv(0)), 0);
});

test("incoming direct message, others' traffic ignored", async () => {
  const { svc, fake } = await connected();
  deliver(fake,
    incoming(bob, me, "just you", 200),
    incoming(bob, alice, "not for me", 201),
    variant("packet", makePacket({ from: bob, to: me, encrypted: Uint8Array.of(1, 2), id: 202 })));
  assert.deepEqual(svc.messagesIn(directConv(bob)).map((m) => m.text), ["just you"]);
  assert.deepEqual(svc.directThreads.map((t) => t.node.num), [bob]);
  assert.equal(svc.messagesIn(channelConv(0)).length, 0);
  assert.equal(svc.messages.length, 1);
});

test("position and nodeinfo packets update nodes", async () => {
  const { svc, fake } = await connected();
  const carol = 0xcccc0003;
  deliver(fake,
    variant("packet", makePacket({
      from: carol, to: BCAST,
      decoded: makeData({ portnum: MeshPort.nodeInfo, payload: encodeUser(makeUser({ longName: "Carol", shortName: "CA" })) }),
      id: 300, rxSNR: -3,
    })),
    variant("packet", makePacket({
      from: carol, to: BCAST,
      decoded: makeData({ portnum: MeshPort.position, payload: encodePosition(makePosition({ latitudeI: -123_456_700, longitudeI: 98_765_400 })) }),
      id: 301,
    })));
  const c = svc.nodes.get(carol);
  assert.equal(c.longName, "Carol");
  assert.equal(c.snr, -3);
  assert.ok(Math.abs(c.latitude - -12.34567) < 1e-6);
  assert.equal(svc.sortedNodes[0].num, carol);
});

// MARK: Sending and delivery

test("send direct message, then delivered ack", async () => {
  const { svc, fake } = await connected();
  assert.equal(svc.send("  meet at the trailhead  ", directConv(alice)), true);
  const p = fake.lastSentPacket();
  assert.equal(p.to, alice);
  assert.equal(p.channel, 0);
  assert.equal(p.wantAck, true);
  assert.equal(p.hopLimit, 4); // from the radio's LoRa config
  assert.notEqual(p.id, 0);
  assert.equal(p.decoded.portnum, 1);
  assert.equal(fromUtf8(p.decoded.payload), "meet at the trailhead");

  assert.equal(svc.messagesIn(directConv(alice)).at(-1).delivery, "sent");

  // Implicit ACK from our own radio first, then the real one from Alice.
  deliver(fake, routing(me, p.id, 0));
  assert.equal(svc.messagesIn(directConv(alice)).at(-1).delivery, "heardByMesh");
  deliver(fake, routing(alice, p.id, 0));
  assert.equal(svc.messagesIn(directConv(alice)).at(-1).delivery, "delivered");
  // A late NAK does not undo a confirmed delivery.
  deliver(fake, routing(me, p.id, 5));
  assert.equal(svc.messagesIn(directConv(alice)).at(-1).delivery, "delivered");
});

test("broadcast ack and nak", async () => {
  const { svc, fake } = await connected();
  assert.equal(svc.send("one", channelConv(0)), true);
  const first = fake.lastSentPacket();
  assert.equal(first.to, BCAST);
  assert.equal(svc.send("two", channelConv(0)), true);
  const second = fake.lastSentPacket();

  deliver(fake, routing(me, first.id, 0), routing(me, second.id, RoutingError.maxRetransmit));
  // A broadcast nobody repeated was still sent; it isn't a failure.
  assert.deepEqual(svc.messagesIn(channelConv(0)).map((m) => m.delivery), ["heardByMesh", "notRepeated"]);

  // A direct message that runs out of retries really did fail.
  assert.equal(svc.send("you there?", directConv(alice)), true);
  const dm = fake.lastSentPacket();
  deliver(fake, routing(me, dm.id, RoutingError.maxRetransmit));
  const last = svc.messagesIn(directConv(alice)).at(-1);
  assert.equal(last.delivery, "failed");
  assert.equal(last.failReason, "Their radio didn't answer");
});

test("queue full marks failed", async () => {
  const { svc, fake } = await connected();
  assert.equal(svc.send("busy?", channelConv(0)), true);
  const p = fake.lastSentPacket();
  fake.enqueue(variant("queueStatus", makeQueueStatus({ res: 1, free: 0, maxlen: 16, meshPacketID: p.id })));
  fake.pump();
  const last = svc.messagesIn(channelConv(0)).at(-1);
  assert.equal(last.delivery, "failed");
  assert.equal(last.failReason, "Radio is busy, try again");
});

test("send rejects too long, empty and disconnected", async () => {
  const { svc, fake } = await connected();
  const before = fake.writes.length;
  assert.equal(svc.send("a".repeat(201), channelConv(0)), false);
  assert.equal(svc.send("a".repeat(200), channelConv(0)), true);
  assert.equal(svc.send("   ", channelConv(0)), false);
  assert.equal(svc.send("📡".repeat(51), channelConv(0)), false); // 204 bytes
  assert.equal(fake.writes.length, before + 1);

  svc.disconnect();
  assert.deepEqual(svc.connection, { state: "idle", name: "" });
  assert.equal(svc.send("hi", channelConv(0)), false);
});

test("SOS without location goes to primary channel", async () => {
  const { svc, fake } = await connected();
  const sent = await svc.sendSOS(false);
  assert.ok(sent.startsWith("SOS"));
  assert.ok(sent.includes("My Radio"));
  const p = fake.lastSentPacket();
  assert.equal(p.to, BCAST);
  assert.equal(p.channel, 0);
});

test("SOS with location adds coordinates", async () => {
  const fake = new FakeMeshTransport();
  const svc = await makeService(fake, { location: async () => ({ latitude: 12.345671, longitude: -45.678911 }) });
  svc.start();
  await svc.pickRadio();
  fake.becomeReady();
  deliver(fake, variant("configCompleteID", fake.lastWantConfigID()));
  const sent = await svc.sendSOS(true);
  assert.ok(sent.includes("Location: 12.34567, -45.67891"), sent);
  assert.equal(fromUtf8(fake.lastSentPacket().decoded.payload), sent);
});

// MARK: Blocking

test("block hides messages and the radio, persists, and unblocks", async () => {
  const { svc, fake } = await connected();
  deliver(fake, incoming(bob, BCAST, "spam", 500), incoming(bob, me, "dm spam", 501));
  svc.send("reply", directConv(bob));
  svc.block(bob);
  assert.equal(svc.messages.length, 0);
  assert.deepEqual(svc.sortedNodes.map((n) => n.num), [alice]);
  assert.deepEqual(svc.blockedNodes.map((n) => n.num), [bob]);
  deliver(fake, incoming(bob, BCAST, "more spam", 502));
  assert.equal(svc.messages.length, 0, "new messages from a blocked radio are dropped");
  svc.block(me);
  svc.block(0);
  assert.deepEqual([...svc.blocked], [bob], "can't block yourself or nobody");

  const svc2 = await makeService(new FakeMeshTransport());
  assert.ok(svc2.isBlocked(bob), "blocking is saved");
  svc.unblock(bob);
  assert.deepEqual(svc.sortedNodes.map((n) => n.num), [bob, alice], "back on the list, heard most recently");
  const svc3 = await makeService(new FakeMeshTransport());
  assert.equal(svc3.isBlocked(bob), false);
});

// MARK: Persistence and reconnect

test("persistence round trip and auto-reconnect", async () => {
  const { svc, fake } = await connected();
  deliver(fake, incoming(alice, BCAST, "saved?", 400));
  svc.send("reply", directConv(alice));
  await svc.saveNow();

  const fake2 = new FakeMeshTransport();
  const svc2 = await makeService(fake2);
  assert.deepEqual(svc2.messages.map((m) => m.text), ["saved?", "reply"]);
  assert.equal(svc2.nodes.get(alice).longName, "Alice");
  assert.equal(svc2.radio.myNodeNum, me);
  assert.equal(svc2.lastDeviceName, "Test Radio");
  // Remembered device reconnects at start.
  svc2.start();
  assert.equal(fake2.connectedID, svc.lastDeviceID);
  assert.equal(svc2.connection.state, "connecting");
});

test("disconnect stops auto-reconnect", async () => {
  const { svc } = await connected();
  svc.disconnect();
  const fake2 = new FakeMeshTransport();
  const svc2 = await makeService(fake2);
  svc2.start();
  assert.equal(fake2.connectedID, null);
  assert.ok(svc2.lastDeviceID, "still offered as Reconnect to ...");
});

test("reconnect falls back to the chooser when the browser forgot the radio", async () => {
  const { svc } = await connected();
  svc.disconnect();
  const fake2 = new FakeMeshTransport();
  fake2.nextDevice = { id: "dev-2", name: "Other Radio" };
  const svc2 = await makeService(fake2);
  await svc2.reconnectLast(); // fake2 doesn't know dev-1
  assert.equal(fake2.connectedID, "dev-2");
  assert.equal(svc2.lastDeviceName, "Other Radio");
});

test("cancelling the chooser is quiet", async () => {
  const fake = new FakeMeshTransport();
  fake.nextDevice = null;
  const svc = await makeService(fake);
  assert.equal(await svc.pickRadio(), false);
  assert.equal(svc.notice, null);
  assert.equal(svc.connection.state, "idle");
});

test("history is capped", () => {
  const many = Array.from({ length: MAX_MESSAGES + 50 }, (_, i) =>
    makeMessage({ packetID: i + 1, from: alice, to: BCAST, channel: 0, text: String(i), date: NOW }));
  const c = capped(many);
  assert.equal(c.length, MAX_MESSAGES);
  assert.equal(c.at(-1).text, String(MAX_MESSAGES + 49));
});

test("delete history keeps own node and blocked list", async () => {
  const { svc, fake } = await connected();
  deliver(fake, incoming(alice, BCAST, "x", 600));
  svc.block(bob);
  await svc.deleteHistory();
  assert.equal(svc.messages.length, 0);
  assert.deepEqual([...svc.nodes.keys()], [me]);
  assert.ok(svc.isBlocked(bob));
  const svc2 = await makeService(new FakeMeshTransport());
  assert.equal(svc2.messages.length, 0);
});

test("transport reconnecting state", async () => {
  const { svc, fake } = await connected();
  fake.emit({ type: "disconnected", id: "x", reason: "Link lost", willReconnect: true });
  assert.deepEqual(svc.connection, { state: "reconnecting", name: "Test Radio" });
  assert.equal(svc.canSend, false);
  fake.emit({ type: "pairingProblem", message: "PIN" });
  assert.equal(svc.pairingHint, "PIN");
  fake.becomeReady();
  assert.equal(svc.pairingHint, null);
  assert.deepEqual(svc.connection, { state: "syncing", name: "Test Radio" });
});

test("write failure marks the last sent message failed", async () => {
  const { svc, fake } = await connected();
  svc.send("lost", channelConv(0));
  fake.emit({ type: "writeFailed", message: "GATT error" });
  const last = svc.messages.at(-1);
  assert.equal(last.delivery, "failed");
  assert.equal(last.failReason, "Not sent");
  assert.match(svc.notice, /GATT error/);
});

test("subscribers are told about changes", async () => {
  const { svc, fake } = await connected();
  let calls = 0;
  const off = svc.subscribe(() => { calls += 1; });
  deliver(fake, incoming(alice, BCAST, "ping", 700));
  await new Promise((r) => setTimeout(r, 0));
  assert.ok(calls >= 1);
  off();
  const before = calls;
  deliver(fake, incoming(alice, BCAST, "pong", 701));
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(calls, before);
});
