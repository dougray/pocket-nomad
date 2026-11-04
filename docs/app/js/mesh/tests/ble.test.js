// ble.test.js — Web Bluetooth transport against a mock GATT server
// SPDX-License-Identifier: Apache-2.0
//
// No radio hardware: this checks the transport's use of the Web Bluetooth API
// (UUIDs, chooser filters, one GATT operation at a time, notifications,
// reconnect), not real radio behaviour.

import test from "node:test";
import assert from "node:assert/strict";
import { MeshUUID, WebBluetoothTransport, pairingMessage, webBluetoothProblem } from "../ble.js";

class MockTarget {
  constructor() { this.listeners = {}; }
  addEventListener(t, f) { (this.listeners[t] ??= new Set()).add(f); }
  removeEventListener(t, f) { this.listeners[t]?.delete(f); }
  fire(t, extra = {}) { for (const f of this.listeners[t] ?? []) f({ target: this, ...extra }); }
}

class MockChar extends MockTarget {
  constructor(uuid, log, props = { write: true, notify: true, read: true }) {
    super();
    this.uuid = uuid;
    this.log = log;
    this.properties = props;
    this.reads = [];
    this.value = null;
    this.busy = false;
  }
  async _op(name, fn) {
    // Web Bluetooth rejects overlapping GATT operations; so does the mock.
    if (this.log.busy) throw new Error("GATT operation already in progress.");
    this.log.busy = true;
    this.log.ops.push(`${name}:${this.uuid.slice(0, 8)}`);
    await new Promise((r) => setTimeout(r, 1));
    this.log.busy = false;
    return fn();
  }
  startNotifications() { return this._op("notify", () => this); }
  readValue() {
    return this._op("read", () => {
      const next = this.reads.shift() ?? new Uint8Array(0);
      return new DataView(next.buffer, next.byteOffset, next.byteLength);
    });
  }
  writeValueWithResponse(v) { return this._op("write", () => { this.log.written.push(Uint8Array.from(v)); }); }
  writeValueWithoutResponse(v) { return this._op("writeNR", () => { this.log.written.push(Uint8Array.from(v)); }); }
  notify(bytes) {
    this.value = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.fire("characteristicvaluechanged");
  }
}

function mockRadio(kind) {
  const log = { ops: [], written: [], busy: false };
  const chars = {};
  const service = kind === "meshtastic" ? MeshUUID.service : MeshUUID.meshCoreService;
  const uuids = kind === "meshtastic"
    ? [MeshUUID.toRadio, MeshUUID.fromRadio, MeshUUID.fromNum]
    : [MeshUUID.meshCoreRX, MeshUUID.meshCoreTX];
  for (const u of uuids) chars[u] = new MockChar(u, log);
  const device = new MockTarget();
  Object.assign(device, {
    id: `${kind}-id`,
    name: kind === "meshtastic" ? "Mesh A1" : "MeshCore-B2",
    connects: 0,
    gatt: {
      connected: false,
      async connect() {
        device.connects += 1;
        if (device.failNext) { device.failNext = false; throw new Error("Connection failed for unknown reason."); }
        this.connected = true;
        return {
          async getPrimaryService(uuid) {
            if (uuid !== service) throw new Error("NotFoundError: No Services matching UUID found in Device.");
            return { async getCharacteristic(c) { if (!chars[c]) throw new Error("not found"); return chars[c]; } };
          },
        };
      },
      disconnect() { this.connected = false; },
    },
  });
  const bluetooth = {
    requested: null,
    async requestDevice(opts) { this.requested = opts; return device; },
    async getAvailability() { return true; },
    addEventListener() {},
  };
  return { log, chars, device, bluetooth };
}

const tick = (ms = 20) => new Promise((r) => setTimeout(r, ms));

test("feature detection", () => {
  assert.equal(webBluetoothProblem({}, true), "unsupported");
  assert.equal(webBluetoothProblem({}, false), "insecure");
  assert.equal(webBluetoothProblem({ bluetooth: { requestDevice() {} } }, true), null);
  assert.equal(new WebBluetoothTransport(undefined).supported, false);
});

test("pairing errors are recognised", () => {
  assert.match(pairingMessage({ name: "SecurityError", message: "x" }), /PIN/);
  assert.match(pairingMessage({ name: "NetworkError", message: "Authentication failed." }), /PIN/);
  assert.equal(pairingMessage({ name: "NetworkError", message: "GATT Server is disconnected." }), null);
});

test("Meshtastic: chooser filters, ready, serialized reads and writes, fromNum", async () => {
  const { log, chars, device, bluetooth } = mockRadio("meshtastic");
  const t = new WebBluetoothTransport(bluetooth);
  const events = [];
  t.onEvent = (e) => events.push(e);
  t.start();
  const picked = await t.requestDevice();
  assert.deepEqual(picked, { id: "meshtastic-id", name: "Mesh A1" });
  assert.deepEqual(bluetooth.requested.optionalServices, [MeshUUID.service, MeshUUID.meshCoreService]);
  assert.deepEqual(bluetooth.requested.filters.slice(0, 2), [{ services: [MeshUUID.service] }, { services: [MeshUUID.meshCoreService] }]);
  assert.equal(await t.knows("meshtastic-id"), true);
  assert.equal(await t.knows("other"), false);

  t.connect(device.id);
  await tick();
  const ready = events.find((e) => e.type === "ready");
  assert.deepEqual(ready, { type: "ready", id: "meshtastic-id", name: "Mesh A1", firmware: "meshtastic" });

  chars[MeshUUID.fromRadio].reads.push(Uint8Array.of(1, 2, 3));
  t.write(Uint8Array.of(0x18, 0x01));
  t.readFromRadio();
  t.readFromRadio();
  await tick(40);
  assert.deepEqual(log.written, [Uint8Array.of(0x18, 0x01)]);
  const reads = events.filter((e) => e.type === "fromRadio").map((e) => Array.from(e.data));
  assert.deepEqual(reads, [[1, 2, 3], []]);
  assert.ok(!events.some((e) => e.type === "writeFailed"), "no overlapping GATT operations");

  chars[MeshUUID.fromNum].notify(Uint8Array.of(9, 0, 0, 0));
  assert.equal(events.at(-1).type, "fromNumChanged");
  t.disconnect();
});

test("MeshCore: notifications become frames, writes go to RX", async () => {
  const { log, chars, device, bluetooth } = mockRadio("meshcore");
  const t = new WebBluetoothTransport(bluetooth);
  const events = [];
  t.onEvent = (e) => events.push(e);
  await t.requestDevice();
  t.connect(device.id);
  await tick();
  assert.equal(events.find((e) => e.type === "ready").firmware, "meshcore");
  t.write(Uint8Array.of(0x16, 0x03));
  await tick();
  assert.deepEqual(log.written, [Uint8Array.of(0x16, 0x03)]);
  chars[MeshUUID.meshCoreTX].notify(Uint8Array.of(0x83));
  assert.deepEqual(events.at(-1), { type: "frame", data: Uint8Array.of(0x83) });
  t.disconnect();
});

test("lost link reconnects by itself; user disconnect does not", async () => {
  const { device, bluetooth } = mockRadio("meshtastic");
  const t = new WebBluetoothTransport(bluetooth);
  const events = [];
  t.onEvent = (e) => events.push(e);
  await t.requestDevice();
  t.connect(device.id);
  await tick();
  assert.equal(device.connects, 1);
  t._retryDelay = 5; // first retry is normally after 2 s

  device.gatt.connected = false;
  device.fire("gattserverdisconnected");
  assert.deepEqual(events.at(-1), { type: "disconnected", id: device.id, reason: null, willReconnect: true });
  await tick(40);
  assert.equal(device.connects, 2);
  assert.equal(events.filter((e) => e.type === "ready").length, 2);

  t.disconnect();
  assert.deepEqual(events.at(-1), { type: "disconnected", id: device.id, reason: null, willReconnect: false });
  device.fire("gattserverdisconnected");
  await tick(40);
  assert.equal(device.connects, 2, "no reconnect after the user disconnects");
});

test("unknown radio after a reload asks for the chooser", async () => {
  const { bluetooth } = mockRadio("meshtastic");
  const t = new WebBluetoothTransport(bluetooth);
  const events = [];
  t.onEvent = (e) => events.push(e);
  t.connect("forgotten-id");
  await tick();
  assert.equal(events.at(-1).type, "connectFailed");
  assert.equal(events.at(-1).needsPrompt, true);
});

test("a device that is not a mesh radio fails without retrying", async () => {
  const { device, bluetooth } = mockRadio("meshtastic");
  device.gatt.connect = async () => ({ async getPrimaryService() { throw new Error("NotFoundError"); } });
  const t = new WebBluetoothTransport(bluetooth);
  const events = [];
  t.onEvent = (e) => events.push(e);
  await t.requestDevice();
  t.connect(device.id);
  await tick();
  const failed = events.find((e) => e.type === "connectFailed");
  assert.equal(failed.reason, "This device is not a Meshtastic or MeshCore radio.");
  assert.equal(failed.willRetry, false);
  assert.equal(t.wantedID, null);
});
