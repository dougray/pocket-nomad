// meshcore.test.js — ported from MeshTests/MeshCoreTests.swift
// SPDX-License-Identifier: Apache-2.0
//
// MeshCore companion protocol: frames built byte for byte to the firmware's
// layout, and a full session against a scripted MeshCore radio.

import test, { describe } from "node:test";
import assert from "node:assert/strict";
import { CoreCommand, coreNodeNum, decodeCoreFrame, splitSender } from "../meshcore.js";
import { MeshService } from "../service.js";
import { MemoryStore, MeshStore } from "../store.js";
import { channelConv, directConv, byteCount } from "../models.js";
import { fromUtf8, utf8 } from "../wire.js";
import { CoreFrames, FakeMeshCoreRadio, FakeMeshTransport, FakeTimers, FIX_LAT, FIX_LON } from "./fakes.js";

const T = 1_234_567_890_000; // ms
const near = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) < eps, `${a} ≈ ${b}`);

describe("MeshCore codec", () => {
  test("commands match the documented bytes", () => {
    assert.deepEqual(Array.from(CoreCommand.appStart("mccli")), [0x01, 0, 0, 0, 0, 0, 0, 0, 0x6d, 0x63, 0x63, 0x6c, 0x69]);
    assert.deepEqual(Array.from(CoreCommand.deviceQuery()), [0x16, 0x03]);
    assert.deepEqual(Array.from(CoreCommand.getChannel(1)), [0x1f, 0x01]);
    // "Hello" to channel 1 at 1234567890, from the protocol document.
    assert.deepEqual(Array.from(CoreCommand.sendChannelText("Hello", 1, T)),
      [0x03, 0x00, 0x01, 0xd2, 0x02, 0x96, 0x49, 0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    const dm = Array.from(CoreCommand.sendText("hi", Uint8Array.of(1, 2, 3, 4, 5, 6, 7, 8), T));
    assert.deepEqual(dm, [0x02, 0, 0, 0xd2, 0x02, 0x96, 0x49, 1, 2, 3, 4, 5, 6, 0x68, 0x69]);
    assert.deepEqual(Array.from(CoreCommand.sendSelfAdvert()), [0x07, 0x01]);
  });

  test("decodes contact", () => {
    const frame = CoreFrames.contact({ key: CoreFrames.key(0xaa), name: "Ana", type: 1, pathLen: 2 });
    assert.equal(frame.length, 148);
    const f = decodeCoreFrame(frame);
    assert.equal(f.kind, "contact");
    const c = f.contact;
    assert.equal(c.name, "Ana");
    assert.equal(c.type, "chat");
    assert.equal(c.pathLength, 2);
    near(c.latitude, FIX_LAT / 1e6);
    near(c.longitude, FIX_LON / 1e6);
    assert.equal(coreNodeNum(c.publicKey), 0x443322aa);

    const flood = decodeCoreFrame(CoreFrames.contact({ key: CoreFrames.key(1), name: "R", type: 2, pathLen: 0xff, lat: 0, lon: 0 })).contact;
    assert.equal(flood.pathLength, null);
    assert.equal(flood.latitude, null);
    assert.equal(flood.type, "repeater");
  });

  test("decodes self and device info", () => {
    const me = decodeCoreFrame(CoreFrames.selfInfo(CoreFrames.key(0x11), "Base Camp"));
    assert.equal(me.kind, "selfInfo");
    assert.equal(me.info.name, "Base Camp");
    near(me.info.frequencyMHz, 910.525, 1e-9);
    near(me.info.bandwidthKHz, 62.5, 1e-9);
    assert.equal(me.info.spreadingFactor, 7);

    const info = decodeCoreFrame(CoreFrames.deviceInfo(8));
    assert.equal(info.kind, "deviceInfo");
    assert.equal(info.info.maxChannels, 8);
    assert.equal(info.info.maxContacts, 100);
    assert.equal(info.info.model, "Heltec V4");
    assert.equal(info.info.version, "v1.12.0");
  });

  test("decodes messages", () => {
    const ch = decodeCoreFrame(CoreFrames.channelMessageV3(0, "Ana: at the bridge", 1_790_000_100));
    assert.equal(ch.kind, "channelMessage");
    assert.equal(ch.message.senderName, "Ana");
    assert.equal(ch.message.text, "at the bridge");
    assert.equal(ch.message.snr, 5.5);
    assert.equal(ch.message.timestamp, 1_790_000_100);

    const dm = decodeCoreFrame(CoreFrames.directMessageV3([1, 2, 3, 4, 5, 6], "ok", 7, true));
    assert.equal(dm.kind, "directMessage");
    assert.equal(dm.message.text, "ok");
    assert.deepEqual(Array.from(dm.message.senderKeyPrefix), [1, 2, 3, 4, 5, 6]);
    assert.equal(dm.message.snr, 5);

    // Old (pre-V3) channel format.
    const old = decodeCoreFrame(Uint8Array.from([8, 2, 0, 0, ...CoreFrames.le(9), ...utf8("hello")]));
    assert.equal(old.kind, "channelMessage");
    assert.equal(old.message.channel, 2);
    assert.equal(old.message.senderName, null);
    assert.equal(old.message.text, "hello");
  });

  test("decodes acks and pushes", () => {
    assert.deepEqual(decodeCoreFrame(CoreFrames.sent(0xdeadbeef)), { kind: "sent", flood: false, expectedAck: 0xdeadbeef, timeoutMS: 5000 });
    assert.deepEqual(decodeCoreFrame(CoreFrames.sendConfirmed(7)), { kind: "sendConfirmed", ack: 7, roundTripMS: 800 });
    assert.deepEqual(decodeCoreFrame(Uint8Array.of(0x83)), { kind: "messagesWaiting" });
    assert.deepEqual(decodeCoreFrame(Uint8Array.of(0x0a)), { kind: "noMoreMessages" });
    assert.deepEqual(decodeCoreFrame(Uint8Array.of(0x01, 0x02)), { kind: "error", code: 2 });
    assert.deepEqual(decodeCoreFrame(Uint8Array.of(0x88, 1, 2, 3)), { kind: "other", code: 0x88 });
  });

  test("truncated frames are rejected", () => {
    const contact = CoreFrames.contact({ key: CoreFrames.key(1), name: "A" });
    assert.equal(decodeCoreFrame(contact.slice(0, 100)), null);
    assert.equal(decodeCoreFrame(Uint8Array.of(0x82, 1, 2)), null);
    assert.equal(decodeCoreFrame(new Uint8Array(0)), null);
  });

  test("sender split ignores ordinary colons", () => {
    assert.equal(splitSender("Ana: hi")[0], "Ana");
    assert.equal(splitSender("no sender here")[0], null);
    assert.equal(splitSender("A very long sentence that happens to contain: a colon later")[0], null);
  });
});

describe("MeshCore service", () => {
  // The fixtures use radio timestamps around 1_790_000_000 s; pin the clock near them.
  const NOW = 1_790_000_060_000;

  async function connected() {
    const fake = new FakeMeshTransport();
    const radio = new FakeMeshCoreRadio(fake);
    radio.waiting = [CoreFrames.channelMessageV3(0, "Ana: anyone on?", 1_790_000_000)];
    const timers = new FakeTimers();
    const svc = new MeshService({ transport: fake, store: new MeshStore(new MemoryStore()), timers, now: () => NOW });
    await svc.init();
    svc.start();
    fake.nextDevice = { id: "core-1", name: "MeshCore-Base" };
    await svc.pickRadio();
    fake.becomeReady("meshcore", "MeshCore-Base");
    radio.run();
    return { svc, fake, radio, timers };
  }

  test("handshake loads identity, contacts, channels and queued messages", async () => {
    const { svc, fake } = await connected();
    assert.deepEqual(svc.connection, { state: "connected", name: "MeshCore-Base" });
    assert.equal(svc.firmware, "meshcore");
    assert.equal(svc.maxTextBytes, 133);
    assert.equal(svc.myNode.longName, "Base Camp");
    assert.equal(svc.radio.regionName, "910.525 MHz");
    assert.equal(svc.radio.firmwareVersion, "MeshCore v1.12.0");
    assert.equal(svc.radio.primaryChannelName, "Public");
    assert.deepEqual(svc.sortedNodes.map((n) => n.longName), ["Ana"]);
    assert.equal(svc.sortedNodes[0].hopsAway, 2);
    assert.equal(svc.sortedNodes[0].firmware, "meshcore");
    // First command identifies the app; commands go one at a time.
    assert.equal(fake.writes[0][0], 1);
    const thread = svc.messagesIn(channelConv(0));
    assert.deepEqual(thread.map((m) => m.text), ["anyone on?"]);
    assert.equal(thread[0].senderName, "Ana");
    assert.equal(thread[0].from, svc.sortedNodes[0].num, "sender name matched to the contact");
  });

  test("direct message is addressed by key and confirmed", async () => {
    const { svc, fake, radio } = await connected();
    const ana = svc.sortedNodes[0];
    assert.equal(svc.send("meet at the bridge", directConv(ana.num)), true);
    radio.run();
    const cmd = fake.writes.at(-1);
    assert.equal(cmd[0], 2);
    assert.deepEqual(Array.from(cmd.slice(7, 13)), radio.ana.slice(0, 6));
    assert.equal(fromUtf8(cmd.slice(13)), "meet at the bridge");

    let sent = svc.messagesIn(directConv(ana.num)).at(-1);
    assert.equal(sent.delivery, "sent");
    fake.emit({ type: "frame", data: CoreFrames.sendConfirmed(sent.packetID) });
    sent = svc.messagesIn(directConv(ana.num)).at(-1);
    assert.equal(sent.delivery, "delivered");
  });

  test("channel send and text limit", async () => {
    const { svc, fake, radio } = await connected();
    assert.equal(svc.send("all good here", channelConv(0)), true);
    radio.run();
    assert.equal(fake.writes.at(-1)[0], 3);
    assert.equal(svc.send("x".repeat(134), channelConv(0)), false, "MeshCore's limit is 133 bytes");
  });

  test("messages-waiting push triggers sync", async () => {
    const { svc, fake, radio } = await connected();
    radio.waiting = [CoreFrames.directMessageV3(radio.ana.slice(0, 6), "you ok?", 1_790_000_500)];
    fake.emit({ type: "frame", data: Uint8Array.of(0x83) });
    radio.run();
    const ana = svc.sortedNodes[0];
    assert.deepEqual(svc.messagesIn(directConv(ana.num)).map((m) => m.text), ["you ok?"]);
    // The same message replayed is not shown twice.
    radio.waiting = [CoreFrames.directMessageV3(radio.ana.slice(0, 6), "you ok?", 1_790_000_500)];
    fake.emit({ type: "frame", data: Uint8Array.of(0x83) });
    radio.run();
    assert.equal(svc.messagesIn(directConv(ana.num)).length, 1);
  });

  test("SOS fits MeshCore limit", async () => {
    const { svc } = await connected();
    const text = await svc.sendSOS(false);
    assert.ok(text);
    assert.ok(byteCount(text) <= 133);
    assert.ok(text.startsWith("SOS"));
  });

  test("unanswered command times out and the queue moves on", async () => {
    const fake = new FakeMeshTransport();
    const timers = new FakeTimers();
    const svc = new MeshService({ transport: fake, timers, now: () => NOW });
    await svc.init();
    svc.start();
    await svc.pickRadio();
    fake.becomeReady("meshcore");
    assert.equal(fake.writes.length, 1, "one command in flight");
    timers.advance(5000);
    assert.equal(fake.writes.length, 2, "next command sent after the 5 s timeout");
    assert.equal(fake.writes[1][0], 22);
    timers.advance(45_000);
    assert.equal(svc.connection.state, "connected");
    assert.match(svc.notice, /did not finish sending its contacts/);
  });
});
