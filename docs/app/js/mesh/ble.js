// ble.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Web Bluetooth transport for Meshtastic and MeshCore radios, ported from
// App/Sources/Mesh/MeshBLETransport.swift. Meshtastic UUIDs are from its
// public client API documentation; MeshCore uses the Nordic UART service
// (MeshCore docs/companion_protocol.md).
//
// Meshtastic: write one ToRadio per write to toRadio; read FromRadio until it
// comes back empty; fromNum notifies when there is more to read.
// MeshCore: write one command frame per write to RX; every TX notification is
// one response or push frame.
//
// Web Bluetooth allows one GATT operation at a time, so every read, write and
// subscribe goes through a single queue.

export const MeshUUID = Object.freeze({
  service: "6ba1b218-15a8-461f-9fa8-5dcae273eafd",
  /** write: one ToRadio protobuf per write. */
  toRadio: "f75c76d2-129e-4dad-a1dd-7866124401e7",
  /** read: one FromRadio protobuf per read, empty when drained. */
  fromRadio: "2c55e69e-4993-11ed-b878-0242ac120002",
  /** read/notify/write: packet counter, notifies when FromRadio has data. */
  fromNum: "ed9da18c-a800-4f66-a670-aa7547e34453",
  /** notify: LogRecord protobufs (debug logs). Not used. */
  logRecord: "5a3d6e49-06e6-4423-9944-e9de8cdf9547",

  meshCoreService: "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
  /** write: one command frame per write. */
  meshCoreRX: "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
  /** notify: one response or push frame per notification. */
  meshCoreTX: "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
});

const PAIRING_HINT =
  "Pairing did not finish. Pair the radio in this device's Bluetooth settings using the PIN on the radio's screen (radios with no screen often use 123456), then connect again.";

/** Recognises Bluetooth security errors so the UI can explain pairing. */
export function pairingMessage(error) {
  if (!error) return null;
  const text = `${error.name ?? ""} ${error.message ?? ""}`;
  if (error.name === "SecurityError" || /auth|encrypt|pair|insufficient|security/i.test(text)) return PAIRING_HINT;
  return null;
}

/** Why Web Bluetooth can't be used here, or null when it can. */
export function webBluetoothProblem(nav = globalThis.navigator, secure = globalThis.isSecureContext) {
  if (!nav || !nav.bluetooth || typeof nav.bluetooth.requestDevice !== "function") {
    return secure === false ? "insecure" : "unsupported";
  }
  return null;
}

const copyBytes = (dv) => new Uint8Array(dv.buffer.slice(dv.byteOffset, dv.byteOffset + dv.byteLength));

function withTimeout(promise, ms, message) {
  let t;
  return Promise.race([
    promise.finally(() => clearTimeout(t)),
    new Promise((_, reject) => { t = setTimeout(() => reject(new Error(message)), ms); }),
  ]);
}

export class WebBluetoothTransport {
  constructor(bluetooth = globalThis.navigator?.bluetooth) {
    this.bt = bluetooth;
    this.supported = !!bluetooth && typeof bluetooth.requestDevice === "function";
    this.onEvent = null;
    /** @type {Map<string, BluetoothDevice>} */
    this.devices = new Map();
    this.device = null;
    this.wantedID = null;
    this.firmware = null;
    this.chars = {};
    this._queue = Promise.resolve();
    this._retryTimer = null;
    this._retryDelay = 2000;
    this._pollTimer = null;
    this._started = false;
    this._generation = 0;
    this._onDisconnected = (e) => this._handleDisconnected(e.target);
    this._onFromNum = () => this._emit({ type: "fromNumChanged" });
    this._onFrame = (e) => {
      const v = e.target.value;
      if (v && v.byteLength) this._emit({ type: "frame", data: copyBytes(v) });
    };
  }

  _emit(e) {
    this.onEvent?.(e);
  }

  start() {
    if (this._started || !this.supported) return;
    this._started = true;
    const report = (on) => this._emit({ type: "availability", availability: on ? "poweredOn" : "poweredOff" });
    if (typeof this.bt.getAvailability === "function") {
      this.bt.getAvailability().then(report, () => {});
      this.bt.addEventListener?.("availabilitychanged", (e) => report(!!e.value));
    } else {
      report(true);
    }
  }

  /** Shows the browser's chooser. Must be called from a user gesture. */
  async requestDevice() {
    const device = await this.bt.requestDevice({
      filters: [
        { services: [MeshUUID.service] },
        { services: [MeshUUID.meshCoreService] },
        // Some MeshCore radios don't put the UART service in their advert.
        { namePrefix: "MeshCore" },
      ],
      optionalServices: [MeshUUID.service, MeshUUID.meshCoreService],
    });
    this.devices.set(device.id, device);
    return { id: device.id, name: device.name || "Mesh radio" };
  }

  async _find(id) {
    if (this.devices.has(id)) return this.devices.get(id);
    if (typeof this.bt.getDevices === "function") {
      try {
        for (const d of await this.bt.getDevices()) this.devices.set(d.id, d);
      } catch {
        // getDevices may be unavailable; fall through.
      }
    }
    return this.devices.get(id) ?? null;
  }

  /** True when this page can reconnect to `id` without showing the chooser. */
  async knows(id) {
    return !!(await this._find(id));
  }

  connect(id) {
    this.start();
    this.wantedID = id;
    this._clearRetry();
    (async () => {
      const device = await this._find(id);
      if (!device) {
        if (this.wantedID === id) this.wantedID = null;
        this._emit({ type: "connectFailed", id, reason: "Tap Reconnect to choose the radio again.", needsPrompt: true });
        return;
      }
      if (this.device && this.device !== device) this._drop(this.device);
      this.device = device;
      device.removeEventListener("gattserverdisconnected", this._onDisconnected);
      device.addEventListener("gattserverdisconnected", this._onDisconnected);
      this._emit({ type: "connecting", id, name: device.name || "Mesh radio" });
      await this._open(device, true);
    })();
  }

  async _open(device, first) {
    const gen = ++this._generation;
    this._clearLink();
    try {
      const server = await withTimeout(device.gatt.connect(), 20_000, "The radio did not answer. Move closer and try again.");
      if (gen !== this._generation || this.wantedID !== device.id) return;
      let service = null;
      let firmware = null;
      try {
        service = await server.getPrimaryService(MeshUUID.service);
        firmware = "meshtastic";
      } catch {
        try {
          service = await server.getPrimaryService(MeshUUID.meshCoreService);
          firmware = "meshcore";
        } catch {
          throw Object.assign(new Error("This device is not a Meshtastic or MeshCore radio."), { fatal: true });
        }
      }
      this.firmware = firmware;
      if (firmware === "meshtastic") await this._setUpMeshtastic(service);
      else await this._setUpMeshCore(service);
      if (gen !== this._generation) return;
      this._retryDelay = 2000;
      this._emit({ type: "ready", id: device.id, name: device.name || (firmware === "meshcore" ? "MeshCore radio" : "Meshtastic radio"), firmware });
    } catch (error) {
      if (gen !== this._generation) return;
      const pairing = pairingMessage(error);
      if (pairing) this._emit({ type: "pairingProblem", message: pairing });
      const fatal = !!error.fatal;
      if (fatal) this.wantedID = null;
      try { device.gatt.disconnect(); } catch { /* already down */ }
      // Retries after a lost link stay quiet: the UI already says "Waiting for ...".
      if (first || fatal) {
        this._emit({
          type: "connectFailed", id: device.id, reason: pairing ?? error.message ?? "Could not connect.",
          willRetry: !fatal,
        });
      }
      if (!fatal && this.wantedID === device.id) this._scheduleRetry(device);
    }
  }

  async _setUpMeshtastic(service) {
    let toRadio;
    let fromRadio;
    try {
      toRadio = await service.getCharacteristic(MeshUUID.toRadio);
      fromRadio = await service.getCharacteristic(MeshUUID.fromRadio);
    } catch {
      throw Object.assign(new Error("This radio's firmware is too old or not Meshtastic."), { fatal: true });
    }
    this.chars = { toRadio, fromRadio };
    try {
      const fromNum = await service.getCharacteristic(MeshUUID.fromNum);
      fromNum.addEventListener("characteristicvaluechanged", this._onFromNum);
      // The characteristic is encrypted, so this is usually what triggers the
      // system's pairing (PIN) prompt.
      await fromNum.startNotifications();
      this.chars.fromNum = fromNum;
    } catch (error) {
      const pairing = pairingMessage(error);
      if (pairing) throw error;
      // Without notifications we can still poll; carry on.
      this._pollTimer = setInterval(() => this._emit({ type: "fromNumChanged" }), 5000);
    }
  }

  async _setUpMeshCore(service) {
    let rx;
    let tx;
    try {
      rx = await service.getCharacteristic(MeshUUID.meshCoreRX);
      tx = await service.getCharacteristic(MeshUUID.meshCoreTX);
    } catch {
      throw Object.assign(new Error("This MeshCore radio is missing its companion service. Flash the Companion (Bluetooth) firmware."), { fatal: true });
    }
    tx.addEventListener("characteristicvaluechanged", this._onFrame);
    // Subscribing is what makes the system ask for the radio's PIN.
    await tx.startNotifications();
    this.chars = { rx, tx };
  }

  _enqueue(op) {
    const run = this._queue.then(op);
    this._queue = run.catch(() => {});
    return run;
  }

  write(bytes) {
    const c = this.chars.toRadio ?? this.chars.rx;
    if (!c) {
      this._emit({ type: "writeFailed", message: "Not connected to a radio." });
      return;
    }
    this._enqueue(async () => {
      if (c === this.chars.rx && !c.properties?.write && c.writeValueWithoutResponse) {
        await c.writeValueWithoutResponse(bytes);
      } else if (c.writeValueWithResponse) {
        await c.writeValueWithResponse(bytes);
      } else {
        await c.writeValue(bytes);
      }
    }).catch((error) => {
      const pairing = pairingMessage(error);
      if (pairing) this._emit({ type: "pairingProblem", message: pairing });
      else this._emit({ type: "writeFailed", message: error?.message || "Bluetooth write failed." });
    });
  }

  readFromRadio() {
    const c = this.chars.fromRadio;
    if (!c) return;
    this._enqueue(async () => {
      const v = await c.readValue();
      this._emit({ type: "fromRadio", data: copyBytes(v) });
    }).catch((error) => {
      const pairing = pairingMessage(error);
      if (pairing) this._emit({ type: "pairingProblem", message: pairing });
      this._emit({ type: "fromRadio", data: new Uint8Array(0) }); // end this read loop; next fromNum retries
    });
  }

  disconnect() {
    const device = this.device;
    this.wantedID = null;
    this._clearRetry();
    this._generation += 1;
    if (device) {
      this._drop(device);
      this._emit({ type: "disconnected", id: device.id, reason: null, willReconnect: false });
    }
    this.device = null;
  }

  /** Revokes this site's permission for the radio, where the browser supports it. */
  forget(id) {
    const device = id ? this.devices.get(id) : null;
    if (device && typeof device.forget === "function") device.forget().catch(() => {});
    if (id) this.devices.delete(id);
  }

  _drop(device) {
    this._clearLink();
    device.removeEventListener("gattserverdisconnected", this._onDisconnected);
    try {
      if (device.gatt.connected) device.gatt.disconnect();
    } catch {
      // Already gone.
    }
  }

  _handleDisconnected(device) {
    if (device !== this.device) return;
    this._clearLink();
    const reconnect = this.wantedID === device.id;
    this._emit({ type: "disconnected", id: device.id, reason: reconnect ? null : "The radio disconnected.", willReconnect: reconnect });
    if (reconnect) this._scheduleRetry(device);
  }

  _scheduleRetry(device) {
    this._clearRetry();
    const delay = this._retryDelay;
    this._retryDelay = Math.min(this._retryDelay * 2, 30_000);
    this._retryTimer = setTimeout(() => {
      this._retryTimer = null;
      if (this.wantedID === device.id) this._open(device, false);
    }, delay);
  }

  _clearRetry() {
    if (this._retryTimer) clearTimeout(this._retryTimer);
    this._retryTimer = null;
  }

  _clearLink() {
    const { fromNum, tx } = this.chars;
    fromNum?.removeEventListener("characteristicvaluechanged", this._onFromNum);
    tx?.removeEventListener("characteristicvaluechanged", this._onFrame);
    if (this._pollTimer) clearInterval(this._pollTimer);
    this._pollTimer = null;
    this.chars = {};
    this._queue = Promise.resolve();
  }
}
