// models.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// App-level models and small pure helpers, ported from
// App/Sources/Mesh/MeshModels.swift. Dates are milliseconds since 1970.
//
// Message: {id, packetID, from, to, channel, text, date, isOutgoing,
//   delivery, failReason, rxSNR, hopsAway, senderName}
// delivery: "sent" | "heardByMesh" | "notRepeated" | "delivered" | "failed" | "received"
// Node: {num, userID, longName, shortName, hwModel, lastHeard, snr, hopsAway,
//   latitude, longitude, altitude, batteryLevel, hasPublicKey,
//   publicKeyPrefix (hex string, MeshCore), meshCoreType, firmware}

import { MeshConstants } from "./meshtastic.js";
import { utf8 } from "./wire.js";

export const Firmware = Object.freeze({
  meshtastic: { id: "meshtastic", displayName: "Meshtastic", maxTextBytes: MeshConstants.maxTextBytes },
  // MeshCore's limit is lower and its channel messages also carry the sender's name.
  meshcore: { id: "meshcore", displayName: "MeshCore", maxTextBytes: 133 },
});

export const firmwareInfo = (id) => Firmware[id] ?? Firmware.meshtastic;

export const Delivery = Object.freeze({
  sent: "sent",
  heardByMesh: "heardByMesh",
  notRepeated: "notRepeated",
  delivered: "delivered",
  failed: "failed",
  received: "received",
});

let idCounter = 0;
export function newMessageID() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  idCounter += 1;
  return `m${Date.now().toString(36)}${idCounter}${Math.random().toString(36).slice(2, 8)}`;
}

export function makeMessage(p) {
  return {
    id: newMessageID(), packetID: 0, from: 0, to: 0, channel: 0, text: "", date: Date.now(),
    isOutgoing: false, delivery: Delivery.received, failReason: null, rxSNR: null,
    hopsAway: null, senderName: null, ...p,
  };
}

export const isBroadcast = (m) => m.to === MeshConstants.broadcastNum;

export function makeNode(num, p = {}) {
  return {
    num, userID: "", longName: "", shortName: "", hwModel: 0, lastHeard: null, snr: null,
    hopsAway: null, latitude: null, longitude: null, altitude: null, batteryLevel: null,
    hasPublicKey: false, publicKeyPrefix: null, meshCoreType: null, firmware: null, ...p,
  };
}

/** "!a1b2c3d4", the Meshtastic way of writing a node number. */
export const hexID = (num) => "!" + (num >>> 0).toString(16).padStart(8, "0");

export function displayName(node) {
  if (node.longName) return node.longName;
  if (node.shortName) return node.shortName;
  return hexID(node.num);
}

export function badge(node) {
  if (node.shortName) return node.shortName;
  return (node.num & 0xffff).toString(16).padStart(4, "0");
}

export const hasPosition = (n) => n.latitude != null && n.longitude != null;

/** Conversation: {type: "channel", index} | {type: "direct", num}. */
export const channelConv = (index = 0) => ({ type: "channel", index });
export const directConv = (num) => ({ type: "direct", num });
export const convKey = (c) => (c.type === "channel" ? `ch${c.index}` : `dm${c.num}`);
export const sameConv = (a, b) => !!a && !!b && convKey(a) === convKey(b);

export function conversationOf(m, myNum) {
  if (m.to === MeshConstants.broadcastNum) return channelConv(m.channel);
  if (m.isOutgoing || (myNum !== 0 && m.from === myNum)) return directConv(m.to);
  return directConv(m.from);
}

// MARK: Text

export const byteCount = (s) => utf8(s).length;

const segmenter = typeof Intl !== "undefined" && Intl.Segmenter ? new Intl.Segmenter(undefined, { granularity: "grapheme" }) : null;
const graphemes = (s) => (segmenter ? Array.from(segmenter.segment(s), (x) => x.segment) : Array.from(s));

/** Trims a string to fit `maxBytes` of UTF-8 without splitting a character. */
export function truncated(s, maxBytes) {
  if (byteCount(s) <= maxBytes) return s;
  let out = "";
  let used = 0;
  for (const ch of graphemes(s)) {
    const n = byteCount(ch);
    if (used + n > maxBytes) break;
    out += ch;
    used += n;
  }
  return out;
}

/** Trims spaces and newlines at both ends, like Swift's .whitespacesAndNewlines. */
export const trimText = (s) => s.replace(/^\s+|\s+$/gu, "");

// MARK: SOS

/** Decimal degrees, exactly five places, with a dot separator in any locale. */
export function formatDegrees(deg) {
  const s = deg.toFixed(5);
  // Swift's %.5f keeps the sign of tiny negatives: -0.000004 -> "-0.00000".
  if (deg < 0 && !s.startsWith("-")) return "-" + s;
  return s;
}

/** Builds the emergency text. Kept short to fit the 200-byte limit. */
export function sosText(latitude, longitude, senderName) {
  const parts = ["SOS - I need help."];
  if (latitude != null && longitude != null) {
    parts.push(`Location: ${formatDegrees(latitude)}, ${formatDegrees(longitude)}`);
  } else {
    parts.push("Location unknown.");
  }
  if (senderName) parts.push(`From ${senderName}.`);
  parts.push("Please reply.");
  return truncated(parts.join(" "), MeshConstants.maxTextBytes);
}

// MARK: Hex

export const toHex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
export function fromHex(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}
