// fakes.js — test doubles for the Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Ported from FakeMeshTransport (MeshServiceTests.swift) and FakeMeshCoreRadio
// (MeshCoreTests.swift). No Bluetooth involved.

import { decodeToRadio, encodeFromRadio } from "../meshtastic.js";
import { concatBytes, utf8 } from "../wire.js";

/** Timers that only run when the test says so. */
export class FakeTimers {
  constructor() {
    this.nowMs = 0;
    this.pending = new Map();
    this.nextID = 1;
    this.set = (fn, ms) => {
      const id = this.nextID++;
      this.pending.set(id, { fn, at: this.nowMs + ms });
      return id;
    };
    this.clear = (id) => {
      this.pending.delete(id);
    };
  }
  advance(ms) {
    const until = this.nowMs + ms;
    for (;;) {
      let next = null;
      for (const [id, t] of this.pending) if (t.at <= until && (!next || t.at < next[1].at)) next = [id, t];
      if (!next) break;
      this.pending.delete(next[0]);
      this.nowMs = next[1].at;
      next[1].fn();
    }
    this.nowMs = until;
  }
}

/** Plays the radio side of the Meshtastic BLE contract. */
export class FakeMeshTransport {
  constructor() {
    this.supported = true;
    this.onEvent = null;
    this.writes = [];
    /** FromRadio packets waiting to be read. */
    this.queue = [];
    this.pendingReads = 0;
    this.connectedID = null;
    this.nextDevice = { id: "dev-1", name: "Test Radio" };
    this.known = new Set();
  }

  emit(e) {
    this.onEvent?.(e);
  }

  start() {
    this.emit({ type: "availability", availability: "poweredOn" });
  }
  async requestDevice() {
    if (!this.nextDevice) {
      const e = new Error("User cancelled the requestDevice() chooser.");
      e.name = "NotFoundError";
      throw e;
    }
    this.known.add(this.nextDevice.id);
    return this.nextDevice;
  }
  async knows(id) {
    return this.known.has(id);
  }
  connect(id) {
    this.connectedID = id;
    this.emit({ type: "connecting", id, name: "Test Radio" });
  }
  disconnect() {
    if (this.connectedID) this.emit({ type: "disconnected", id: this.connectedID, reason: null, willReconnect: false });
    this.connectedID = null;
  }
  write(bytes) {
    this.writes.push(bytes);
  }
  readFromRadio() {
    this.pendingReads += 1;
  }

  // Test helpers

  becomeReady(firmware = "meshtastic", name = "Test Radio") {
    this.emit({ type: "ready", id: this.connectedID ?? "dev-x", name, firmware });
  }

  enqueue(v) {
    this.queue.push(encodeFromRadio({ id: this.queue.length + 1, variant: v }));
  }

  /** Answers reads until the service stops asking (like reading until empty). */
  pump() {
    let guard = 0;
    while (this.pendingReads > 0 && guard < 1000) {
      this.pendingReads -= 1;
      guard += 1;
      this.emit({ type: "fromRadio", data: this.queue.length ? this.queue.shift() : new Uint8Array(0) });
    }
  }

  decodedWrites() {
    return this.writes.map((w) => decodeToRadio(w)).filter(Boolean);
  }

  lastWantConfigID() {
    const w = this.decodedWrites().reverse().find((x) => x.kind === "wantConfigID");
    return w ? w.value : null;
  }

  lastSentPacket() {
    const w = this.decodedWrites().reverse().find((x) => x.kind === "packet");
    return w ? w.value : null;
  }
}

// MARK: MeshCore frames, built the way MeshCore firmware writes them.

const le = (v) => {
  const n = v >>> 0;
  return [n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, (n >>> 24) & 0xff];
};
const padded = (s, n) => {
  const b = Array.from(utf8(s)).slice(0, n);
  return b.concat(new Array(n - b.length).fill(0));
};
const bytes = (arr) => Uint8Array.from(arr);

// Fixture coordinates are made up (open ocean), in MeshCore's 1e-6 degrees.
export const FIX_LAT = 12_345_600;
export const FIX_LON = -45_678_900;

export const CoreFrames = {
  le,
  padded,
  key(first) {
    return [first, 0x22, 0x33, 0x44, 0x55, 0x66, ...new Array(26).fill(first)];
  },
  contact({ code = 3, key, name, type = 1, pathLen = 2, lat = FIX_LAT, lon = FIX_LON, advert = 1_790_000_000 }) {
    return bytes([code, ...key, type, 0, pathLen, ...new Array(64).fill(0), ...padded(name, 32),
      ...le(advert), ...le(lat), ...le(lon), ...le(1)]);
  },
  selfInfo(key, name) {
    return bytes([5, 1, 20, 22, ...key, ...le(0), ...le(0), 0, 0, 0, 0, ...le(910_525), ...le(62_500), 7, 5, ...utf8(name)]);
  },
  deviceInfo(maxChannels) {
    return bytes([13, 9, 50, maxChannels, ...le(123456), ...padded("01 Oct 2026", 12),
      ...padded("Heltec V4", 40), ...padded("v1.12.0", 20), 0, 0]);
  },
  channelInfo(index, name) {
    return bytes([18, index, ...padded(name, 32), ...new Array(16).fill(name ? 0x8b : 0)]);
  },
  channelMessageV3(channel, text, ts, snr = 22) {
    return bytes([17, snr & 0xff, 0, 0, channel, 1, 0, ...le(ts), ...utf8(text)]);
  },
  directMessageV3(prefix, text, ts, signed = false) {
    return bytes([16, 20, 0, 0, ...prefix, 0xff, signed ? 2 : 0, ...le(ts), ...(signed ? [9, 9, 9, 9] : []), ...utf8(text)]);
  },
  sent(ack) {
    return bytes([6, 0, ...le(ack), ...le(5000)]);
  },
  sendConfirmed(ack) {
    return bytes([0x82, ...le(ack), ...le(800)]);
  },
};

/** Answers the app's MeshCore commands like a radio would. */
export class FakeMeshCoreRadio {
  constructor(fake) {
    this.fake = fake;
    this.answered = 0;
    this.waiting = [];
    this.me = CoreFrames.key(0x11);
    this.ana = CoreFrames.key(0xaa);
    this.nextAck = 0xa0a00001;
  }

  /** Replies to every write not yet answered, until the app goes quiet. */
  run() {
    let guard = 0;
    while (this.answered < this.fake.writes.length && guard < 200) {
      guard += 1;
      const cmd = this.fake.writes[this.answered];
      this.answered += 1;
      for (const reply of this.replies(cmd)) this.fake.emit({ type: "frame", data: reply });
    }
  }

  replies(cmd) {
    switch (cmd[0]) {
      case 1: return [CoreFrames.selfInfo(this.me, "Base Camp")];
      case 22: return [CoreFrames.deviceInfo(2)];
      case 31: return [cmd[1] === 0 ? CoreFrames.channelInfo(0, "Public") : CoreFrames.channelInfo(cmd[1], "")];
      case 6: return [bytes([0])];
      case 4: return [bytes([2, ...le(1)]), CoreFrames.contact({ key: this.ana, name: "Ana" }), bytes([4, 0, 0, 0, 0])];
      case 10: return [this.waiting.length ? this.waiting.shift() : bytes([10])];
      case 2:
      case 3: {
        const ack = cmd[0] === 2 ? this.nextAck : 0;
        this.nextAck += 1;
        return [CoreFrames.sent(ack)];
      }
      default: return [bytes([1, 1])];
    }
  }
}

export { concatBytes };
