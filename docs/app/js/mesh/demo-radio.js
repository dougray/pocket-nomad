// demo-radio.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// A pretend Meshtastic radio that lives in memory, for demo.html. It speaks
// the same ToRadio/FromRadio protobufs as a real radio (through
// meshtastic.js), so the whole service and UI run without Bluetooth.
// Names and positions are made up (open Pacific Ocean).

import {
  MeshConstants, MeshPort, decodeToRadio, encodeFromRadio, encodePosition, encodeRouting, makeChannel, makeData,
  makeDeviceMetadata, makeDeviceMetrics, makeLoRaConfig, makeMyNodeInfo, makeNodeInfo, makePacket, makePosition,
  makeUser, variant,
} from "./meshtastic.js";
import { fromUtf8, utf8 } from "./wire.js";

const ME = 0x0d0e0001;
const BASE_LAT = 10.0;
const BASE_LON = -140.0;
const deg = (d) => Math.round(d * 1e7);

const PEOPLE = [
  { num: 0x0a110001, long: "Ridge Repeater", short: "RDG", dLat: 0.012, dLon: 0.004, battery: 101, hops: 0, snr: 7.5 },
  { num: 0x0a110002, long: "Ana", short: "ANA", dLat: 0.02, dLon: 0.015, battery: 76, hops: 1, snr: 2.25 },
  { num: 0x0a110003, long: "Base Camp", short: "BASE", dLat: -0.006, dLon: -0.012, battery: 54, hops: 0, snr: 9 },
  { num: 0x0a110004, long: "Creek Crew", short: "CRK", dLat: -0.03, dLon: 0.04, battery: 33, hops: 2, snr: -6.5 },
  { num: 0x0a110005, long: "Far Ridge", short: "FAR", dLat: 0.07, dLon: -0.05, battery: null, hops: 3, snr: -14 },
];

const CHATTER = [
  "Anyone on this channel?",
  "Water source at the second bend is running.",
  "Heading back to camp in 20.",
  "Copy that.",
  "Trail is washed out past the bridge, go around.",
  "Radio check, how do you read?",
];

const REPLIES = ["Copy.", "Got it, thanks.", "Heard you loud and clear.", "On my way.", "👍"];

export class DemoRadio {
  constructor() {
    this.supported = true;
    this.onEvent = null;
    this.queue = [];
    this.timers = new Set();
    this.connected = false;
    this.device = { id: "demo-radio", name: "Demo Radio" };
    this.nextMsg = 0;
  }

  _emit(e) {
    this.onEvent?.(e);
  }

  _later(ms, fn) {
    const t = setTimeout(() => {
      this.timers.delete(t);
      fn();
    }, ms);
    this.timers.add(t);
  }

  _clearTimers() {
    for (const t of this.timers) clearTimeout(t);
    this.timers.clear();
    clearInterval(this.chatter);
  }

  start() {
    this._emit({ type: "availability", availability: "poweredOn" });
  }

  async requestDevice() {
    await new Promise((r) => setTimeout(r, 300));
    return this.device;
  }

  async knows(id) {
    return id === this.device.id;
  }

  connect(id) {
    this._emit({ type: "connecting", id, name: this.device.name });
    this._later(600, () => {
      this.connected = true;
      this._emit({ type: "ready", id, name: this.device.name, firmware: "meshtastic" });
    });
  }

  disconnect() {
    this._clearTimers();
    this.queue = [];
    if (this.connected) this._emit({ type: "disconnected", id: this.device.id, reason: null, willReconnect: false });
    this.connected = false;
  }

  forget() {}

  readFromRadio() {
    this._later(15, () => this._emit({ type: "fromRadio", data: this.queue.length ? this.queue.shift() : new Uint8Array(0) }));
  }

  _push(v) {
    this.queue.push(encodeFromRadio({ id: this.queue.length + 1, variant: v }));
  }

  _notify() {
    this._emit({ type: "fromNumChanged" });
  }

  write(bytes) {
    if (!this.connected) {
      this._emit({ type: "writeFailed", message: "Not connected to a radio." });
      return;
    }
    const msg = decodeToRadio(bytes);
    if (!msg) return;
    if (msg.kind === "wantConfigID") this._sendConfig(msg.value);
    else if (msg.kind === "packet") this._onPacket(msg.value);
  }

  _sendConfig(id) {
    const now = Math.floor(Date.now() / 1000);
    this._push(variant("myInfo", makeMyNodeInfo({ myNodeNum: ME })));
    this._push(variant("metadata", makeDeviceMetadata({ firmwareVersion: "2.6.0.demo" })));
    this._push(variant("nodeInfo", makeNodeInfo({
      num: ME, user: makeUser({ id: "!0d0e0001", longName: "My Demo Radio", shortName: "ME" }),
      position: makePosition({ latitudeI: deg(BASE_LAT), longitudeI: deg(BASE_LON) }),
    })));
    PEOPLE.forEach((p, i) => {
      this._push(variant("nodeInfo", makeNodeInfo({
        num: p.num,
        user: makeUser({ id: "!" + p.num.toString(16).padStart(8, "0"), longName: p.long, shortName: p.short }),
        position: makePosition({ latitudeI: deg(BASE_LAT + p.dLat), longitudeI: deg(BASE_LON + p.dLon) }),
        snr: p.snr,
        lastHeard: now - i * 420 - 30,
        hopsAway: p.hops,
        deviceMetrics: p.battery == null ? null : makeDeviceMetrics({ batteryLevel: p.battery }),
      })));
    });
    this._push(variant("channel", makeChannel({ index: 0, name: "LongFast", hasPSK: true, role: 1 })));
    this._push(variant("channel", makeChannel({ index: 1, name: "", role: 0 })));
    this._push(variant("loraConfig", makeLoRaConfig({ region: 1, hopLimit: 3 })));
    this._push(variant("configCompleteID", id));
    this._notify();

    // Something already waiting, then occasional chatter.
    this._later(1500, () => this._incoming(PEOPLE[1].num, MeshConstants.broadcastNum, "Morning all, Ana here."));
    this._later(3000, () => this._incoming(PEOPLE[2].num, ME, "Are you still at the trailhead?"));
    clearInterval(this.chatter);
    this.chatter = setInterval(() => {
      if (!this.connected) return;
      const p = PEOPLE[Math.floor(Math.random() * 4)];
      if (Math.random() < 0.5) this._incoming(p.num, MeshConstants.broadcastNum, CHATTER[this.nextMsg++ % CHATTER.length]);
      else this._move(p);
    }, 15_000);
  }

  _incoming(from, to, text) {
    const p = PEOPLE.find((x) => x.num === from);
    this._push(variant("packet", makePacket({
      from, to, channel: 0,
      decoded: makeData({ portnum: MeshPort.textMessage, payload: utf8(text) }),
      id: (Math.random() * 0xfffffffe) >>> 0 || 1,
      rxTime: Math.floor(Date.now() / 1000), rxSNR: p?.snr ?? 5, hopLimit: 3 - (p?.hops ?? 0), hopStart: 3,
    })));
    this._notify();
  }

  _move(p) {
    p.dLat += (Math.random() - 0.5) * 0.004;
    p.dLon += (Math.random() - 0.5) * 0.004;
    this._push(variant("packet", makePacket({
      from: p.num, to: MeshConstants.broadcastNum,
      decoded: makeData({ portnum: MeshPort.position, payload: encodePosition(makePosition({ latitudeI: deg(BASE_LAT + p.dLat), longitudeI: deg(BASE_LON + p.dLon) })) }),
      id: (Math.random() * 0xfffffffe) >>> 0 || 1, rxTime: Math.floor(Date.now() / 1000), rxSNR: p.snr,
    })));
    this._notify();
  }

  _routing(from, requestID, error) {
    this._push(variant("packet", makePacket({
      from, to: ME,
      decoded: makeData({ portnum: MeshPort.routing, payload: encodeRouting({ errorReason: error }), requestID }),
      id: (Math.random() * 0xfffffffe) >>> 0 || 1,
    })));
    this._notify();
  }

  _onPacket(p) {
    if (p.decoded?.portnum !== MeshPort.textMessage) return;
    const text = fromUtf8(p.decoded.payload);
    if (p.to === MeshConstants.broadcastNum) {
      // A neighbour repeats it (implicit ACK), and sometimes someone answers.
      this._later(1200, () => this._routing(ME, p.id, 0));
      if (!text.startsWith("SOS")) {
        this._later(3500, () => this._incoming(PEOPLE[1].num, MeshConstants.broadcastNum, REPLIES[Math.floor(Math.random() * REPLIES.length)]));
      } else {
        this._later(4000, () => this._incoming(PEOPLE[2].num, MeshConstants.broadcastNum, "Got your SOS. Coming to you now."));
      }
      return;
    }
    const target = PEOPLE.find((x) => x.num === p.to);
    if (target?.short === "FAR") {
      // Too far away: nobody repeats it and the radio gives up after its retries.
      this._later(4000, () => this._routing(ME, p.id, 5));
    } else {
      this._later(900, () => this._routing(ME, p.id, 0));
      this._later(2600, () => this._routing(p.to, p.id, 0));
      this._later(5000, () => this._incoming(p.to, ME, REPLIES[Math.floor(Math.random() * REPLIES.length)]));
    }
  }
}
