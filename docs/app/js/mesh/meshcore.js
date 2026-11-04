// meshcore.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Encoder and decoder for MeshCore's companion radio protocol (the BLE link
// between a phone and a MeshCore radio). Ported from
// App/Sources/Mesh/MeshCoreProtocol.swift, which was written from MeshCore's
// MIT-licensed docs/companion_protocol.md and examples/companion_radio/MyMesh.cpp.
//
// Each BLE write is one command frame and each notification is one response
// frame: [code: 1 byte][payload]. Integers are little-endian, text is UTF-8.

import { concatBytes, fromUtf8, utf8 } from "./wire.js";

export const CoreCode = Object.freeze({
  appStart: 1,
  sendText: 2,
  sendChannelText: 3,
  getContacts: 4,
  setDeviceTime: 6,
  sendSelfAdvert: 7,
  syncNextMessage: 10,
  deviceQuery: 22,
  getChannel: 31,
});

export const CoreTextType = Object.freeze({ plain: 0, signedPlain: 2 });

/** Contact kinds from the radio's adverts. */
export const CoreContactType = Object.freeze({ none: 0, chat: 1, repeater: 2, room: 3, sensor: 4 });
const CONTACT_TYPE_NAMES = ["none", "chat", "repeater", "room", "sensor"];

/** The protocol version this app understands; asks for the V3 message formats. */
export const appProtocolVersion = 3;

function le32(v) {
  const n = Math.max(0, Math.min(0xffffffff, Math.floor(v))) >>> 0;
  return Uint8Array.of(n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, (n >>> 24) & 0xff);
}

const seconds = (dateMs) => Math.floor(dateMs / 1000);

export const CoreCommand = {
  appStart(appName) {
    return concatBytes(Uint8Array.of(CoreCode.appStart, 0, 0, 0, 0, 0, 0, 0), utf8(appName));
  },
  deviceQuery() {
    return Uint8Array.of(CoreCode.deviceQuery, appProtocolVersion);
  },
  /** @param {number} dateMs */
  setDeviceTime(dateMs) {
    return concatBytes(Uint8Array.of(CoreCode.setDeviceTime), le32(seconds(dateMs)));
  },
  getContacts() {
    return Uint8Array.of(CoreCode.getContacts);
  },
  getChannel(index) {
    return Uint8Array.of(CoreCode.getChannel, index & 0xff);
  },
  syncNextMessage() {
    return Uint8Array.of(CoreCode.syncNextMessage);
  },
  /** Flood-advertises this radio so others add it as a contact. */
  sendSelfAdvert(flood = true) {
    return Uint8Array.of(CoreCode.sendSelfAdvert, flood ? 1 : 0);
  },
  /** Direct text to a contact, addressed by the first 6 bytes of its public key. */
  sendText(text, keyPrefix, dateMs, attempt = 0) {
    return concatBytes(
      Uint8Array.of(CoreCode.sendText, CoreTextType.plain, attempt),
      le32(seconds(dateMs)),
      keyPrefix.slice(0, 6),
      utf8(text),
    );
  },
  sendChannelText(text, channel, dateMs) {
    return concatBytes(
      Uint8Array.of(CoreCode.sendChannelText, CoreTextType.plain, Math.max(0, Math.min(255, channel))),
      le32(seconds(dateMs)),
      utf8(text),
    );
  },
};

/** Bounds-checked little-endian reader. Every read returns null past the end. */
export class CoreReader {
  constructor(data) {
    this.bytes = data instanceof Uint8Array ? data : Uint8Array.from(data);
    this.pos = 0;
  }
  u8() {
    if (this.pos >= this.bytes.length) return null;
    return this.bytes[this.pos++];
  }
  i8() {
    const v = this.u8();
    return v == null ? null : (v << 24) >> 24;
  }
  u32() {
    const b = this.bytes_(4);
    if (!b) return null;
    return (b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24)) >>> 0;
  }
  i32() {
    const v = this.u32();
    return v == null ? null : v | 0;
  }
  bytes_(n) {
    if (n < 0 || this.pos + n > this.bytes.length) return null;
    const out = this.bytes.slice(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }
  /** A NUL-padded fixed-width string field. */
  fixedString(n) {
    const raw = this.bytes_(n);
    if (!raw) return null;
    let end = raw.indexOf(0);
    if (end < 0) end = raw.length;
    return fromUtf8(raw.subarray(0, end)).replace(/^[ \t]+|[ \t]+$/g, "");
  }
  /** Everything left, as text (trailing NULs dropped). */
  restString() {
    const rest = this.pos < this.bytes.length ? this.bytes.subarray(this.pos) : new Uint8Array(0);
    this.pos = this.bytes.length;
    let end = rest.indexOf(0);
    if (end < 0) end = rest.length;
    return fromUtf8(rest.subarray(0, end));
  }
}

/** Node number used across the app: the key's first four bytes, little-endian. */
export function coreNodeNum(key) {
  if (!key || key.length < 4) return 0;
  return (key[0] | (key[1] << 8) | (key[2] << 16) | (key[3] << 24)) >>> 0;
}

/** "Ana: meet at the bridge" → ["Ana", "meet at the bridge"]. */
export function splitSender(raw) {
  const at = raw.indexOf(": ");
  // Distance counted in characters, like Swift's String.distance.
  if (at < 0 || [...raw.slice(0, at)].length > 32) return [null, raw];
  const name = raw.slice(0, at);
  if (!name || name.includes("\n")) return [null, raw];
  return [name, raw.slice(at + 2)];
}

function decodeContact(r) {
  const key = r.bytes_(32);
  const type = r.u8();
  const flags = r.u8();
  const outPathLen = r.u8();
  const path = r.bytes_(64);
  const name = r.fixedString(32);
  const advert = r.u32();
  const lat = r.i32();
  const lon = r.i32();
  if (key == null || type == null || flags == null || outPathLen == null || path == null ||
      name == null || advert == null || lat == null || lon == null) return null;
  const hasPosition = lat !== 0 || lon !== 0;
  return {
    publicKey: key,
    type: CONTACT_TYPE_NAMES[type] ?? "none",
    name,
    lastAdvert: advert === 0 ? null : advert * 1000,
    latitude: hasPosition ? lat / 1e6 : null,
    longitude: hasPosition ? lon / 1e6 : null,
    // 0xFF means "no known route, flood".
    pathLength: outPathLen === 0xff ? null : outPathLen & 0x3f,
  };
}

function decodeSelfInfo(r) {
  // advert type, tx power, max tx power
  if (r.u8() == null || r.u8() == null || r.u8() == null) return null;
  const key = r.bytes_(32);
  const lat = r.i32();
  const lon = r.i32();
  const flags = r.bytes_(4); // multi-acks, location policy, telemetry, manual add
  const freq = r.u32();
  const bw = r.u32();
  const sf = r.u8();
  const cr = r.u8();
  if (key == null || lat == null || lon == null || flags == null || freq == null ||
      bw == null || sf == null || cr == null) return null;
  const hasPosition = lat !== 0 || lon !== 0;
  return {
    publicKey: key,
    latitude: hasPosition ? lat / 1e6 : null,
    longitude: hasPosition ? lon / 1e6 : null,
    frequencyMHz: freq / 1000,
    bandwidthKHz: bw / 1000,
    spreadingFactor: sf,
    codingRate: cr,
    name: r.restString(),
  };
}

function decodeDeviceInfo(r) {
  const version = r.u8();
  if (version == null) return null;
  const info = { firmwareVersionCode: version, maxContacts: 0, maxChannels: 0, firmwareBuild: "", model: "", version: "" };
  if (version < 3) return info;
  const contacts = r.u8();
  const channels = r.u8();
  const pin = r.u32(); // BLE PIN, unused
  const build = r.fixedString(12);
  const model = r.fixedString(40);
  const ver = r.fixedString(20);
  if (contacts == null || channels == null || pin == null || build == null || model == null || ver == null) return info;
  info.maxContacts = contacts * 2;
  info.maxChannels = channels;
  info.firmwareBuild = build;
  info.model = model;
  info.version = ver;
  return info;
}

function decodeDirect(r, v3) {
  let snr = null;
  if (v3) {
    const s = r.i8();
    if (s == null || r.bytes_(2) == null) return null;
    snr = s / 4;
  }
  const prefix = r.bytes_(6);
  const path = r.u8();
  const type = r.u8();
  const ts = r.u32();
  if (prefix == null || path == null || type == null || ts == null) return null;
  if (type === CoreTextType.signedPlain && r.bytes_(4) == null) return null;
  return { senderKeyPrefix: prefix, pathLength: path, timestamp: ts, text: r.restString(), snr };
}

function decodeChannelMessage(r, v3) {
  let snr = null;
  if (v3) {
    const s = r.i8();
    if (s == null || r.bytes_(2) == null) return null;
    snr = s / 4;
  }
  const channel = r.u8();
  const path = r.u8();
  const type = r.u8();
  const ts = r.u32();
  if (channel == null || path == null || type == null || ts == null) return null;
  const [senderName, text] = splitSender(r.restString());
  return { channel, pathLength: path, timestamp: ts, senderName, text, snr };
}

/**
 * One frame from the radio, as {kind, ...fields}, or null if truncated.
 * Kinds: ok, error, contactsStart, contact, endOfContacts, selfInfo, sent,
 * directMessage, channelMessage, noMoreMessages, deviceInfo, channelInfo,
 * advert, newAdvert, sendConfirmed, messagesWaiting, other.
 */
export function decodeCoreFrame(data) {
  const r = new CoreReader(data);
  const code = r.u8();
  if (code == null) return null;
  const wrap = (kind, key, v) => (v == null ? null : { kind, [key]: v });
  switch (code) {
    case 0: return { kind: "ok", value: r.u32() };
    case 1: return { kind: "error", code: r.u8() };
    case 2: return { kind: "contactsStart", count: r.u32() ?? 0 };
    case 3: return wrap("contact", "contact", decodeContact(r));
    case 4: return { kind: "endOfContacts" };
    case 5: return wrap("selfInfo", "info", decodeSelfInfo(r));
    case 6: {
      const route = r.u8();
      const ack = r.u32();
      if (route == null || ack == null) return null;
      return { kind: "sent", flood: route === 1, expectedAck: ack, timeoutMS: r.u32() ?? 0 };
    }
    case 7: case 16: return wrap("directMessage", "message", decodeDirect(r, code === 16));
    case 8: case 17: return wrap("channelMessage", "message", decodeChannelMessage(r, code === 17));
    case 10: return { kind: "noMoreMessages" };
    case 13: return wrap("deviceInfo", "info", decodeDeviceInfo(r));
    case 18: {
      const index = r.u8();
      const name = r.fixedString(32);
      const secret = r.bytes_(16);
      if (index == null || name == null || secret == null) return null;
      return { kind: "channelInfo", channel: { index, name, secret, isEmpty: !name && secret.every((b) => b === 0) } };
    }
    case 0x80: {
      const key = r.bytes_(32);
      return key == null ? null : { kind: "advert", publicKey: key };
    }
    case 0x8a: return wrap("newAdvert", "contact", decodeContact(r));
    case 0x82: {
      const ack = r.u32();
      if (ack == null) return null;
      return { kind: "sendConfirmed", ack, roundTripMS: r.u32() ?? 0 };
    }
    case 0x83: return { kind: "messagesWaiting" };
    default: return { kind: "other", code };
  }
}
