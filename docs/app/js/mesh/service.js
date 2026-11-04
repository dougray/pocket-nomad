// service.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Owns the radio link, the handshake (Meshtastic or MeshCore), the node list
// and chat history. Ported from App/Sources/Mesh/MeshService.swift. The views
// subscribe with `service.subscribe(fn)`.
//
// Not ported (iPhone only): coverage recording, drive mode and its ping test.
// A web page stops when it goes to the background, so they can't work here.
//
// Transport contract (see ble.js and tests/fakes.js). The service sets
// `transport.onEvent = fn` and calls:
//   start(), requestDevice() -> Promise<{id, name}>, knows(id) -> Promise<bool>,
//   connect(id), disconnect(), write(bytes), readFromRadio()
// Events are {type, ...}: availability {availability}, connecting {id, name},
//   ready {id, name, firmware}, disconnected {id, reason, willReconnect},
//   connectFailed {id, reason, needsPrompt}, fromRadio {data}, fromNumChanged,
//   frame {data} (MeshCore), pairingProblem {message}, writeFailed {message}.

import {
  ChannelRole, MeshConstants, MeshPort, RoutingError, decodeFromRadio, decodePosition, decodeRouting,
  decodeUser, describeRoutingError, encodeToRadio, hopsAway as packetHopsAway, makeData, makePacket,
  positionHasFix, positionLatitude, positionLongitude, regionName, variant,
} from "./meshtastic.js";
import { CoreCommand, coreNodeNum, decodeCoreFrame } from "./meshcore.js";
import {
  Delivery, byteCount, channelConv, conversationOf, convKey, displayName, firmwareInfo, isBroadcast,
  makeMessage, makeNode, sameConv, sosText, toHex, trimText, truncated,
} from "./models.js";
import { MAX_MESSAGES, MemoryStore, MeshStore, capped } from "./store.js";
import { fromUtf8, utf8 } from "./wire.js";

const defaultTimers = {
  set: (fn, ms) => setTimeout(fn, ms),
  clear: (h) => clearTimeout(h),
};

export function newPacketID() {
  if (globalThis.crypto?.getRandomValues) {
    const a = new Uint32Array(1);
    do globalThis.crypto.getRandomValues(a); while (a[0] === 0 || a[0] === 0xffffffff);
    return a[0];
  }
  return 1 + Math.floor(Math.random() * 0xfffffffd);
}

export const linkUp = (c) => c.state === "syncing" || c.state === "connected";

export class MeshService {
  /**
   * @param {object} o
   * @param {object} o.transport
   * @param {MeshStore} [o.store]
   * @param {{set: Function, clear: Function}} [o.timers]
   * @param {() => number} [o.now] ms since 1970
   * @param {() => Promise<{latitude: number, longitude: number}|null>} [o.location]
   */
  constructor({ transport, store, timers, now, location } = {}) {
    this.transport = transport;
    this.store = store ?? new MeshStore(new MemoryStore());
    this.timers = timers ?? defaultTimers;
    this.now = now ?? (() => Date.now());
    this.location = location ?? (async () => null);

    // Observable state
    this.availability = "unknown";
    this.connection = { state: "idle", name: "" };
    /** @type {Map<number, object>} */
    this.nodes = new Map();
    this.messages = [];
    this.radio = emptyRadio();
    this.lastRead = {};
    /** Radios the user blocked: their messages are dropped and they leave the node list. */
    this.blocked = new Set();
    this.pairingHint = null;
    this.notice = null;
    this.lastDeviceID = null;
    this.lastDeviceName = null;

    // Internals
    this._listeners = new Set();
    this._notifyQueued = false;
    this._started = false;
    this._autoReconnect = false;
    this._configID = 0;
    this._handshakeDone = false;
    this._handshakeAttempts = 0;
    this._isReading = false;
    this._readAgain = false;
    this._currentDeviceName = "";
    this._saveTimer = null;
    this._handshakeTimer = null;
    this._pollTimer = null;
    // MeshCore: one command in flight at a time, each waiting for its reply.
    this._coreQueue = [];
    this._corePending = null;
    this._coreTimer = null;

    this._loaded = null;
    if (this.transport) this.transport.onEvent = (e) => this.handle(e);
  }

  /** Loads saved history and settings. Call (and await) once before start(). */
  init() {
    if (!this._loaded) {
      this._loaded = (async () => {
        const [snap, prefs] = await Promise.all([this.store.load(), this.store.loadPrefs()]);
        // Anything that arrived before loading finished is kept.
        const live = this.messages;
        this.messages = capped([...(snap.messages ?? []), ...live]);
        for (const n of snap.nodes ?? []) if (!this.nodes.has(n.num)) this.nodes.set(n.num, makeNode(n.num, n));
        this.radio = { ...emptyRadio(), ...(snap.radio ?? {}), ...pickSet(this.radio) };
        this.lastRead = { ...(snap.lastRead ?? {}), ...this.lastRead };
        this.lastDeviceID = prefs.lastDeviceID;
        this.lastDeviceName = prefs.lastDeviceName;
        this._autoReconnect = !!prefs.autoReconnect;
        this.blocked = new Set([...(prefs.blocked ?? []), ...this.blocked]);
        this._changed();
      })();
    }
    return this._loaded;
  }

  // MARK: - Subscriptions

  subscribe(fn) {
    this._listeners.add(fn);
    return () => this._listeners.delete(fn);
  }

  _changed() {
    if (this._notifyQueued) return;
    this._notifyQueued = true;
    queueMicrotask(() => {
      this._notifyQueued = false;
      for (const fn of [...this._listeners]) {
        try {
          fn(this);
        } catch (e) {
          console.error(e);
        }
      }
    });
  }

  // MARK: - Public API

  get supported() {
    return this.transport?.supported !== false;
  }

  /** Brings Bluetooth up and reconnects to the last radio if the user had one connected. */
  start() {
    if (this._started) return;
    this._started = true;
    this.transport.start();
    if (this._autoReconnect && this.lastDeviceID) {
      this._setConnection("reconnecting", this.lastDeviceName ?? "radio");
      this._currentDeviceName = this.lastDeviceName ?? "radio";
      this.transport.connect(this.lastDeviceID);
    }
  }

  /** Opens the browser's radio chooser. Must run from a tap or click. */
  async pickRadio() {
    this.start();
    let device;
    try {
      device = await this.transport.requestDevice();
    } catch (e) {
      if (e?.name !== "NotFoundError" && e?.name !== "AbortError") {
        this.notice = e?.message || "Could not open the radio list.";
        this._changed();
      }
      return false;
    }
    if (!device) return false;
    this._connect(device.id, device.name);
    return true;
  }

  async reconnectLast() {
    if (!this.lastDeviceID) return this.pickRadio();
    const known = this.transport.knows ? await this.transport.knows(this.lastDeviceID) : true;
    if (!known) return this.pickRadio();
    this._connect(this.lastDeviceID, this.lastDeviceName ?? "radio");
    return true;
  }

  disconnect() {
    this._autoReconnect = false;
    this._savePrefs();
    this._clearHandshakeTimer();
    this._coreReset();
    this.transport.disconnect();
    this._setConnection("idle");
  }

  /** Forgets the remembered radio entirely. */
  forgetDevice() {
    this.disconnect();
    this.transport.forget?.(this.lastDeviceID);
    this.lastDeviceID = null;
    this.lastDeviceName = null;
    this._savePrefs();
    this._changed();
  }

  get myNodeNum() {
    return this.radio.myNodeNum;
  }

  get myNode() {
    return this.nodes.get(this.radio.myNodeNum) ?? null;
  }

  /** Other radios, most recently heard first. */
  get sortedNodes() {
    return [...this.nodes.values()]
      .filter((n) => n.num !== this.radio.myNodeNum && !this.blocked.has(n.num))
      .sort((a, b) => (b.lastHeard ?? -Infinity) - (a.lastHeard ?? -Infinity));
  }

  /** Blocked radios, for the list where they can be unblocked. */
  get blockedNodes() {
    return [...this.blocked].map((n) => this.node(n)).sort((a, b) => displayName(a).localeCompare(displayName(b)));
  }

  isBlocked(num) {
    return this.blocked.has(num);
  }

  /** Hides a radio on this device: deletes its messages, drops new ones and takes it
   * off the node list. Nothing is sent; the other radio can't tell. */
  block(num) {
    if (!num || num === this.radio.myNodeNum) return;
    this.blocked.add(num);
    this.messages = this.messages.filter((m) => !(m.from === num || (m.isOutgoing && m.to === num)));
    this._savePrefs();
    this._scheduleSave();
    this._changed();
  }

  unblock(num) {
    this.blocked.delete(num);
    this._savePrefs();
    this._changed();
  }

  node(num) {
    return this.nodes.get(num) ?? makeNode(num);
  }

  messagesIn(conversation) {
    const my = this.radio.myNodeNum;
    return this.messages.filter((m) => sameConv(conversationOf(m, my), conversation));
  }

  /** Direct-message threads, newest first: [{node, last}]. */
  get directThreads() {
    const latest = new Map();
    const my = this.radio.myNodeNum;
    for (const m of this.messages) {
      const c = conversationOf(m, my);
      if (c.type !== "direct") continue;
      const cur = latest.get(c.num);
      if (cur && cur.date > m.date) continue;
      latest.set(c.num, m);
    }
    return [...latest.entries()]
      .map(([num, last]) => ({ node: this.node(num), last }))
      .sort((a, b) => b.last.date - a.last.date);
  }

  unreadCount(conversation) {
    const since = this.lastRead[convKey(conversation)] ?? -Infinity;
    return this.messagesIn(conversation).filter((m) => !m.isOutgoing && m.date > since).length;
  }

  get totalUnread() {
    const keys = [channelConv(0), ...this.directThreads.map((t) => ({ type: "direct", num: t.node.num }))];
    return keys.reduce((s, c) => s + this.unreadCount(c), 0);
  }

  markRead(conversation) {
    const key = convKey(conversation);
    const had = this.unreadCount(conversation);
    this.lastRead[key] = this.now();
    this._scheduleSave();
    if (had) this._changed();
  }

  get canSend() {
    return this.connection.state === "connected";
  }

  /** Which firmware the current (or last) radio runs. */
  get firmware() {
    return this.radio.firmware ?? "meshtastic";
  }

  get maxTextBytes() {
    return firmwareInfo(this.firmware).maxTextBytes;
  }

  /** Sends a text. Returns false when not connected or the text is too long. */
  send(rawText, conversation) {
    const text = trimText(rawText);
    if (!text || byteCount(text) > this.maxTextBytes) return false;
    if (!linkUp(this.connection)) {
      this.notice = "Not connected to a radio.";
      this._changed();
      return false;
    }
    if (this.firmware === "meshcore") return this._coreSend(text, conversation);
    const [to, channel] = conversation.type === "channel"
      ? [MeshConstants.broadcastNum, conversation.index]
      : [conversation.num, 0];
    const id = newPacketID();
    const packet = makePacket({
      to,
      channel,
      decoded: makeData({ portnum: MeshPort.textMessage, payload: utf8(text) }),
      id,
      hopLimit: this.radio.hopLimit > 0 ? this.radio.hopLimit : MeshConstants.defaultHopLimit,
      wantAck: true,
    });
    this.transport.write(encodeToRadio(variant("packet", packet)));
    this.messages.push(makeMessage({
      packetID: id, from: this.radio.myNodeNum, to, channel, text, date: this.now(),
      isOutgoing: true, delivery: Delivery.sent,
    }));
    this._trimHistory();
    this._scheduleSave();
    this._startReading();
    this._changed();
    return true;
  }

  /** Text that the SOS button will send (without location). */
  get sosPreview() {
    return sosText(null, null, this.myNode?.longName);
  }

  /** Sends the SOS to everyone on the primary channel, with a GPS fix if
   * allowed. Returns the text sent, or null if it could not be sent. */
  async sendSOS(includeLocation) {
    let lat = null;
    let lon = null;
    if (includeLocation) {
      try {
        const loc = await this.location();
        if (loc) ({ latitude: lat, longitude: lon } = loc);
      } catch {
        // No fix: send without coordinates.
      }
    }
    const text = truncated(sosText(lat, lon, this.myNode?.longName), this.maxTextBytes);
    return this.send(text, channelConv(0)) ? text : null;
  }

  clearPairingHint() {
    this.pairingHint = null;
    this._changed();
  }

  clearNotice() {
    this.notice = null;
    this._changed();
  }

  /** Deletes every stored message and node from this device. The radio keeps
   * its own node list; reconnecting downloads that again. */
  async deleteHistory() {
    this.messages = [];
    const me = this.radio.myNodeNum;
    for (const k of [...this.nodes.keys()]) if (!(k === me && k !== 0)) this.nodes.delete(k);
    this.lastRead = {};
    this._changed();
    await this.saveNow();
  }

  // MARK: - Transport events

  handle(event) {
    switch (event.type) {
      case "availability":
        this.availability = event.availability;
        if (event.availability !== "poweredOn") {
          this._isReading = false;
          if (linkUp(this.connection)) this._setConnection("reconnecting", this._currentDeviceName);
        }
        break;

      case "connecting":
        this._currentDeviceName = event.name;
        this._setConnection("connecting", event.name);
        break;

      case "ready":
        this._currentDeviceName = event.name;
        this._rememberDevice(event.id, event.name);
        this.pairingHint = null;
        this.radio.firmware = event.firmware ?? "meshtastic";
        if (this.radio.firmware === "meshcore") this._coreBeginHandshake();
        else this._beginHandshake();
        break;

      case "frame":
        this._coreHandle(event.data);
        break;

      case "disconnected":
        this._isReading = false;
        this._clearHandshakeTimer();
        this._coreReset();
        if (event.willReconnect) {
          this._setConnection("reconnecting", this._currentDeviceName);
        } else {
          this._setConnection("idle");
          if (event.reason) this.notice = event.reason;
        }
        break;

      case "connectFailed":
        if (event.needsPrompt) {
          // The browser forgot its permission for this radio: offer "Reconnect" instead.
          this._setConnection("idle");
          break;
        }
        this.notice = event.reason;
        if (this._autoReconnect && event.willRetry !== false) this._setConnection("reconnecting", this._currentDeviceName);
        else this._setConnection("failed", "", event.reason);
        break;

      case "fromRadio": {
        const data = event.data;
        if (!data || data.length === 0) {
          this._isReading = false;
          if (this._readAgain) {
            this._readAgain = false;
            this._startReading();
          } else if (!this._handshakeDone && linkUp(this.connection)) {
            // Radio may still be preparing its config; poll briefly.
            if (this._pollTimer) this.timers.clear(this._pollTimer);
            this._pollTimer = this.timers.set(() => {
              this._pollTimer = null;
              this._startReading();
            }, 300);
          }
          return;
        }
        try {
          this._handleFromRadio(decodeFromRadio(data));
        } catch {
          // Corrupt or partial packet: skip it and keep draining.
        }
        this.transport.readFromRadio();
        break;
      }

      case "fromNumChanged":
        this._startReading();
        return;

      case "pairingProblem":
        this.pairingHint = event.message;
        break;

      case "writeFailed": {
        this.notice = `Could not send to the radio: ${event.message}`;
        for (let i = this.messages.length - 1; i >= 0; i--) {
          const m = this.messages[i];
          if (m.isOutgoing && m.delivery === Delivery.sent) {
            m.delivery = Delivery.failed;
            m.failReason = "Not sent";
            break;
          }
        }
        break;
      }
      default:
        return;
    }
    this._changed();
  }

  // MARK: - Handshake and reading

  _setConnection(state, name = "", reason = "") {
    this.connection = state === "failed" ? { state, name, reason } : { state, name };
    this._changed();
  }

  _connect(id, name) {
    this.start();
    this._autoReconnect = true;
    this._rememberDevice(id, name);
    this._currentDeviceName = name;
    this._setConnection("connecting", name);
    this.transport.connect(id);
  }

  _rememberDevice(id, name) {
    this.lastDeviceID = id;
    this.lastDeviceName = name;
    this._savePrefs();
  }

  _savePrefs() {
    this.store.savePrefs({
      lastDeviceID: this.lastDeviceID,
      lastDeviceName: this.lastDeviceName,
      autoReconnect: this._autoReconnect,
      blocked: [...this.blocked],
    });
  }

  _clearHandshakeTimer() {
    if (this._handshakeTimer) this.timers.clear(this._handshakeTimer);
    this._handshakeTimer = null;
  }

  _beginHandshake() {
    this._handshakeDone = false;
    this._handshakeAttempts += 1;
    this._setConnection("syncing", this._currentDeviceName);
    this._configID = newPacketID();
    this.transport.write(encodeToRadio(variant("wantConfigID", this._configID)));
    this._startReading();

    this._clearHandshakeTimer();
    const attempt = this._handshakeAttempts;
    this._handshakeTimer = this.timers.set(() => {
      this._handshakeTimer = null;
      if (this._handshakeDone || attempt !== this._handshakeAttempts) return;
      if (attempt < 2) {
        this._beginHandshake();
      } else {
        // Messages may still flow; let the user try.
        this._handshakeDone = true;
        this.notice = "The radio did not finish sending its settings. Messages may still work.";
        this._setConnection("connected", this._currentDeviceName);
      }
    }, 30_000);
  }

  _startReading() {
    if (!linkUp(this.connection)) return;
    if (this._isReading) {
      this._readAgain = true;
      return;
    }
    this._isReading = true;
    this.transport.readFromRadio();
  }

  _handleFromRadio(msg) {
    const v = msg.variant;
    if (!v) return;
    switch (v.kind) {
      case "myInfo":
        this.radio.myNodeNum = v.value.myNodeNum;
        break;
      case "nodeInfo":
        this._upsert(v.value);
        break;
      case "channel": {
        const ch = v.value;
        if (ch.role === ChannelRole.disabled) {
          delete this.radio.channelNames[ch.index];
        } else {
          this.radio.channelNames[ch.index] = ch.name;
          if (ch.role === ChannelRole.primary) this.radio.primaryChannelName = ch.name;
        }
        break;
      }
      case "metadata":
        this.radio.firmwareVersion = v.value.firmwareVersion;
        break;
      case "loraConfig":
        this.radio.region = v.value.region;
        this.radio.regionName = regionName(v.value.region);
        this.radio.hopLimit = v.value.hopLimit;
        break;
      case "configCompleteID":
        if (v.value !== this._configID) return;
        this._handshakeDone = true;
        this._handshakeAttempts = 0;
        this._clearHandshakeTimer();
        this._setConnection("connected", this._currentDeviceName);
        if (this.radio.region === 0) {
          this.notice = "This radio has no region set. Set it to US 915 MHz in the Meshtastic app, or it will not transmit.";
        }
        this._scheduleSave();
        break;
      case "rebooted":
        if (linkUp(this.connection)) this._beginHandshake();
        break;
      case "queueStatus": {
        const q = v.value;
        if (q.res !== 0 && q.meshPacketID !== 0) {
          const m = findLast(this.messages, (x) => x.isOutgoing && x.packetID === q.meshPacketID);
          if (m) {
            m.delivery = Delivery.failed;
            m.failReason = "Radio is busy, try again";
            this._scheduleSave();
          }
        }
        break;
      }
      case "clientNotification":
        if (v.value) this.notice = v.value;
        break;
      case "packet":
        this._handlePacket(v.value);
        break;
      default:
        return;
    }
    this._changed();
  }

  _nodeFor(num) {
    let n = this.nodes.get(num);
    if (!n) {
      n = makeNode(num, { firmware: this.firmware });
      this.nodes.set(num, n);
    }
    if (!n.firmware) n.firmware = this.firmware;
    return n;
  }

  _upsert(info) {
    if (!info.num) return;
    const n = this._nodeFor(info.num);
    if (info.user) applyUser(info.user, n);
    if (info.position && positionHasFix(info.position)) applyPosition(info.position, n);
    if (info.lastHeard) {
      const heard = info.lastHeard * 1000;
      n.lastHeard = Math.max(heard, n.lastHeard ?? -Infinity);
    }
    if (info.snr) n.snr = info.snr;
    if (info.hopsAway != null) n.hopsAway = info.hopsAway;
    if (info.deviceMetrics?.batteryLevel != null) n.batteryLevel = info.deviceMetrics.batteryLevel;
    this._scheduleSave();
  }

  _handlePacket(p) {
    const hops = packetHopsAway(p);
    // Any packet tells us the sender is alive.
    if (p.from !== 0 && p.from !== this.radio.myNodeNum) {
      const n = this._nodeFor(p.from);
      n.lastHeard = p.rxTime ? p.rxTime * 1000 : this.now();
      if (p.rxSNR) n.snr = p.rxSNR;
      if (hops != null) n.hopsAway = hops;
    }
    const data = p.decoded;
    if (!data) return; // still encrypted: not for us
    switch (data.portnum) {
      case MeshPort.textMessage:
        this._handleText(p, data, hops);
        break;
      case MeshPort.position: {
        try {
          const pos = decodePosition(data.payload);
          if (positionHasFix(pos) && p.from !== 0) applyPosition(pos, this._nodeFor(p.from));
        } catch { /* ignore */ }
        break;
      }
      case MeshPort.nodeInfo: {
        try {
          const user = decodeUser(data.payload);
          if (p.from !== 0) applyUser(user, this._nodeFor(p.from));
        } catch { /* ignore */ }
        break;
      }
      case MeshPort.routing:
        this._handleRouting(p, data);
        break;
    }
    this._scheduleSave();
  }

  _handleText(p, data, hops) {
    const myNum = this.radio.myNodeNum;
    // Drop duplicates (the radio replays its queue after a reconnect).
    if (p.id !== 0 && this.messages.some((m) => m.packetID === p.id && m.from === p.from)) return;
    // Only broadcasts and messages addressed to us (or sent by us), never from a blocked radio.
    if (!(p.to === MeshConstants.broadcastNum || p.to === myNum || p.from === myNum)) return;
    if (this.blocked.has(p.from)) return;
    const now = this.now();
    const date = p.rxTime ? p.rxTime * 1000 : now;
    this.messages.push(makeMessage({
      packetID: p.id, from: p.from, to: p.to, channel: p.channel, text: fromUtf8(data.payload),
      date: Math.min(date, now), isOutgoing: p.from === myNum && myNum !== 0,
      delivery: p.from === myNum ? Delivery.sent : Delivery.received,
      rxSNR: p.rxSNR ? p.rxSNR : null, hopsAway: hops,
    }));
    this._trimHistory();
  }

  _handleRouting(p, data) {
    if (!data.requestID) return;
    const m = findLast(this.messages, (x) => x.isOutgoing && x.packetID === data.requestID);
    if (!m) return;
    let routing;
    try {
      routing = decodeRouting(data.payload);
    } catch {
      return;
    }
    const reason = routing.errorReason;
    if (reason == null) return;
    if (reason === RoutingError.none) {
      if (!isBroadcast(m) && p.from === m.to) {
        m.delivery = Delivery.delivered;
        m.failReason = null;
      } else if (m.delivery !== Delivery.delivered) {
        // Implicit ACK: our radio heard a neighbour repeat it.
        m.delivery = Delivery.heardByMesh;
        m.failReason = null;
      }
    } else if (m.delivery !== Delivery.delivered && m.delivery !== Delivery.heardByMesh) {
      // A broadcast that ran out of retries was still transmitted; the radio
      // just never heard anyone repeat it. Only direct messages truly fail.
      if (isBroadcast(m) && reason === RoutingError.maxRetransmit) {
        m.delivery = Delivery.notRepeated;
      } else {
        m.delivery = Delivery.failed;
        m.failReason = describeRoutingError(reason);
      }
    }
  }

  // MARK: - MeshCore

  /** MeshCore startup: identify, learn limits, set the clock, then fetch
   * contacts, channels and any queued messages. */
  _coreBeginHandshake() {
    this._coreReset();
    this._handshakeDone = false;
    this._handshakeAttempts += 1;
    this._setConnection("syncing", this._currentDeviceName);

    this._coreEnqueue(CoreCommand.appStart("Pocket Nomad"), [5]);
    this._coreEnqueue(CoreCommand.deviceQuery(), [13], 5000, (frame) => {
      if (frame.kind !== "deviceInfo") return;
      const info = frame.info;
      if (info.version) this.radio.firmwareVersion = `MeshCore ${info.version}`;
      // Channel slots vary by board (1, 8 or 40); list the first eight.
      const count = Math.min(Math.max(info.maxChannels, 1), 8);
      for (let i = 0; i < count; i++) this._coreEnqueue(CoreCommand.getChannel(i), [18]);
    });
    this._coreEnqueue(CoreCommand.setDeviceTime(this.now()), [0]);
    this._coreEnqueue(CoreCommand.getContacts(), [4], 20_000, (frame) => {
      if (frame.kind !== "endOfContacts") return;
      this._handshakeDone = true;
      this._handshakeAttempts = 0;
      this._clearHandshakeTimer();
      this._setConnection("connected", this._currentDeviceName);
      this._coreSyncMessages();
      this._scheduleSave();
    });

    this._clearHandshakeTimer();
    const attempt = this._handshakeAttempts;
    this._handshakeTimer = this.timers.set(() => {
      this._handshakeTimer = null;
      if (this._handshakeDone || attempt !== this._handshakeAttempts) return;
      this._handshakeDone = true;
      this.notice = "The radio did not finish sending its contacts. Messages may still work.";
      this._setConnection("connected", this._currentDeviceName);
    }, 45_000);
  }

  _coreEnqueue(frame, finishedBy, timeout = 5000, onReply = null) {
    this._coreQueue.push({ frame, finishedBy: new Set(finishedBy), timeout, onReply });
    this._coreSendNext();
  }

  _coreSendNext() {
    if (this._corePending || this._coreQueue.length === 0 || !linkUp(this.connection)) return;
    const command = this._coreQueue.shift();
    this._corePending = command;
    this.transport.write(command.frame);
    if (this._coreTimer) this.timers.clear(this._coreTimer);
    this._coreTimer = this.timers.set(() => {
      this._coreTimer = null;
      if (this._corePending !== command) return;
      // No answer: give up on this command and move on.
      this._corePending = null;
      this._coreSendNext();
    }, command.timeout);
  }

  _coreReset() {
    this._coreQueue = [];
    this._corePending = null;
    if (this._coreTimer) this.timers.clear(this._coreTimer);
    this._coreTimer = null;
  }

  /** Pulls every queued message off the radio, one SYNC at a time. */
  _coreSyncMessages() {
    const code = 10;
    if (this._coreQueue.some((c) => c.frame[0] === code) || this._corePending?.frame[0] === code) return;
    this._coreEnqueue(CoreCommand.syncNextMessage(), [7, 8, 10, 16, 17, 27], 5000, (frame) => {
      if (frame.kind === "noMoreMessages") return;
      this._coreSyncMessages();
    });
  }

  _coreHandle(data) {
    if (!data || data.length === 0) return;
    const code = data[0];
    const frame = decodeCoreFrame(data);
    if (!frame) return;
    switch (frame.kind) {
      case "selfInfo": {
        const me = frame.info;
        const num = coreNodeNum(me.publicKey);
        this.radio.myNodeNum = num;
        this.radio.regionName = `${me.frequencyMHz.toFixed(3)} MHz`;
        const n = this._nodeFor(num);
        n.longName = me.name;
        n.shortName = [...me.name].slice(0, 4).join("");
        n.publicKeyPrefix = toHex(me.publicKey.slice(0, 6));
        n.hasPublicKey = true;
        if (me.latitude != null && me.longitude != null) {
          n.latitude = me.latitude;
          n.longitude = me.longitude;
        }
        break;
      }
      case "contact":
      case "newAdvert":
        this._coreUpsert(frame.contact);
        break;
      case "advert": {
        const n = this.nodes.get(coreNodeNum(frame.publicKey));
        if (n) n.lastHeard = this.now();
        break;
      }
      case "channelInfo": {
        const ch = frame.channel;
        if (ch.isEmpty) delete this.radio.channelNames[ch.index];
        else this.radio.channelNames[ch.index] = ch.name;
        if (ch.index === 0) this.radio.primaryChannelName = ch.name;
        break;
      }
      case "directMessage": {
        const m = frame.message;
        this._coreReceive(this._coreNodeForPrefix(m.senderKeyPrefix), this.radio.myNodeNum, 0,
          m.timestamp, m.text, null, m.snr, m.pathLength);
        break;
      }
      case "channelMessage": {
        const m = frame.message;
        let sender = 0;
        if (m.senderName) {
          for (const n of this.nodes.values()) if (n.longName === m.senderName) { sender = n.num; break; }
        }
        this._coreReceive(sender, MeshConstants.broadcastNum, m.channel, m.timestamp, m.text, m.senderName, m.snr, m.pathLength);
        break;
      }
      case "sendConfirmed": {
        const ack = frame.ack;
        const msg = ack !== 0 ? findLast(this.messages, (x) => x.isOutgoing && x.packetID === ack) : null;
        if (msg) {
          msg.delivery = Delivery.delivered;
          msg.failReason = null;
          this._scheduleSave();
        }
        break;
      }
      case "messagesWaiting":
        this._coreSyncMessages();
        break;
    }
    this._changed();
    // Push frames (0x80 and up) arrive at any time; anything else answers the pending command.
    const pending = this._corePending;
    if (code >= 0x80 || !pending) return;
    const finished = pending.finishedBy.has(code) || code === 1;
    if (finished) {
      if (this._coreTimer) this.timers.clear(this._coreTimer);
      this._coreTimer = null;
      this._corePending = null;
    }
    pending.onReply?.(frame);
    if (finished) this._coreSendNext();
  }

  _coreUpsert(c) {
    const num = coreNodeNum(c.publicKey);
    if (!num) return;
    const n = this._nodeFor(num);
    n.longName = c.name;
    n.shortName = [...c.name].slice(0, 4).join("");
    n.userID = toHex(c.publicKey.slice(0, 6));
    n.publicKeyPrefix = n.userID;
    n.hasPublicKey = true;
    n.meshCoreType = c.type;
    if (c.lastAdvert != null) n.lastHeard = Math.max(c.lastAdvert, n.lastHeard ?? -Infinity);
    if (c.latitude != null && c.longitude != null) {
      n.latitude = c.latitude;
      n.longitude = c.longitude;
    }
    if (c.pathLength != null) n.hopsAway = c.pathLength;
    this._scheduleSave();
  }

  _coreNodeForPrefix(prefix) {
    const hex = toHex(prefix);
    for (const n of this.nodes.values()) if (n.publicKeyPrefix === hex) return n.num;
    return coreNodeNum(prefix);
  }

  _coreReceive(from, to, channel, timestamp, text, senderName, snr, path) {
    // The radio's timestamp plus sender and text identify a message well enough to drop repeats.
    if (this.messages.some((m) => m.packetID === timestamp && m.from === from && m.text === text)) return;
    if (this.blocked.has(from)) return;
    const now = this.now();
    this.messages.push(makeMessage({
      packetID: timestamp, from, to, channel, text,
      date: timestamp === 0 ? now : Math.min(timestamp * 1000, now), isOutgoing: false,
      delivery: Delivery.received, rxSNR: snr, hopsAway: path === 0xff ? null : path & 0x3f, senderName,
    }));
    const n = from !== 0 ? this.nodes.get(from) : null;
    if (n) {
      n.lastHeard = now;
      if (snr != null) n.snr = snr;
    }
    this._trimHistory();
    this._scheduleSave();
  }

  _coreSend(text, conversation) {
    const now = this.now();
    let frame;
    let to;
    let channel;
    if (conversation.type === "channel") {
      frame = CoreCommand.sendChannelText(text, conversation.index, now);
      [to, channel] = [MeshConstants.broadcastNum, conversation.index];
    } else {
      const prefix = this.nodes.get(conversation.num)?.publicKeyPrefix;
      if (!prefix) {
        this.notice = "This contact has no MeshCore key yet. Wait until its radio is heard, then try again.";
        this._changed();
        return false;
      }
      frame = CoreCommand.sendText(text, hexBytes(prefix), now);
      [to, channel] = [conversation.num, 0];
    }
    const message = makeMessage({
      packetID: 0, from: this.radio.myNodeNum, to, channel, text, date: now, isOutgoing: true, delivery: Delivery.sent,
    });
    this.messages.push(message);
    this._coreEnqueue(frame, [6], 5000, (reply) => {
      if (reply.kind === "sent") {
        // Direct messages are acknowledged later with this code.
        message.packetID = reply.expectedAck;
      } else if (reply.kind === "error") {
        message.delivery = Delivery.failed;
        message.failReason = "Radio is busy, try again";
      }
      this._scheduleSave();
      this._changed();
    });
    this._trimHistory();
    this._scheduleSave();
    this._changed();
    return true;
  }

  // MARK: - Helpers

  _trimHistory() {
    if (this.messages.length > MAX_MESSAGES) this.messages = capped(this.messages);
  }

  _scheduleSave() {
    if (this._saveTimer) this.timers.clear(this._saveTimer);
    this._saveTimer = this.timers.set(() => {
      this._saveTimer = null;
      this.saveNow();
    }, 1000);
  }

  async saveNow() {
    if (this._saveTimer) this.timers.clear(this._saveTimer);
    this._saveTimer = null;
    try {
      await this.store.save({
        messages: this.messages,
        nodes: [...this.nodes.values()],
        radio: this.radio,
        lastRead: this.lastRead,
      });
    } catch {
      // Storage full or blocked: history stays in memory.
    }
  }
}

function emptyRadio() {
  return {
    firmware: null, myNodeNum: 0, firmwareVersion: "", region: null, regionName: null, hopLimit: null,
    primaryChannelName: "", channelNames: {},
  };
}

/** Radio fields already learned live (so loading old history doesn't overwrite them). */
function pickSet(radio) {
  const out = {};
  for (const [k, v] of Object.entries(radio)) {
    if (k === "channelNames") { if (Object.keys(v).length) out[k] = v; continue; }
    if (v !== null && v !== 0 && v !== "") out[k] = v;
  }
  return out;
}

function applyUser(u, n) {
  if (u.id) n.userID = u.id;
  if (u.longName) n.longName = u.longName;
  if (u.shortName) n.shortName = u.shortName;
  if (u.hwModel) n.hwModel = u.hwModel;
  if (u.publicKey?.length) n.hasPublicKey = true;
}

function applyPosition(p, n) {
  n.latitude = positionLatitude(p);
  n.longitude = positionLongitude(p);
  n.altitude = p.altitude;
}

function findLast(arr, pred) {
  for (let i = arr.length - 1; i >= 0; i--) if (pred(arr[i])) return arr[i];
  return null;
}

function hexBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}
