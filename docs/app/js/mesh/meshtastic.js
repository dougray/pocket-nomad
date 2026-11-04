// meshtastic.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The handful of Meshtastic client-API messages this app needs, encoded and
// decoded by field number with wire.js. Ported from
// App/Sources/Mesh/MeshProtocol.swift, which was written from Meshtastic's
// public documentation (client API page and the Buf Schema Registry reference
// for buf.build/meshtastic/protobufs) to interoperate at the wire level. No
// Meshtastic source or .proto files are copied or generated from.
//
// Messages are plain objects. Each `make*` fills in the defaults, so a
// decoded message deep-equals the one that was encoded. Absent optional
// fields are null. Bytes are Uint8Array.

import { ProtoReader, ProtoWriter } from "./wire.js";

export const MeshConstants = Object.freeze({
  /** MeshPacket.to for "everyone on this channel". */
  broadcastNum: 0xffffffff,
  /** Practical UTF-8 byte budget for one text message. */
  maxTextBytes: 200,
  defaultHopLimit: 3,
});

/** PortNum values (Data.portnum). */
export const MeshPort = Object.freeze({
  unknown: 0,
  textMessage: 1,
  position: 3,
  nodeInfo: 4,
  routing: 5,
});

/** Routing.Error values we name; anything else is shown by number. */
export const RoutingError = Object.freeze({
  none: 0,
  noRoute: 1,
  gotNak: 2,
  timeout: 3,
  noInterface: 4,
  maxRetransmit: 5,
  noChannel: 6,
  tooLarge: 7,
  noResponse: 8,
  dutyCycleLimit: 9,
  badRequest: 32,
  notAuthorized: 33,
  pkiFailed: 34,
  pkiUnknownPubkey: 35,
  rateLimitExceeded: 38,
});

const ROUTING_TEXT = {
  0: "OK",
  1: "No route to that radio",
  2: "Rejected by the mesh",
  3: "Timed out",
  4: "Radio has no way to send",
  5: "Their radio didn't answer",
  6: "Channel mismatch",
  7: "Message too long",
  8: "No response",
  9: "Radio is over its legal airtime limit, try later",
  32: "Bad request",
  33: "Not authorized",
  34: "Encryption failed",
  35: "Their radio's key is unknown yet",
  38: "Sending too fast, slow down",
};

export function describeRoutingError(raw) {
  return ROUTING_TEXT[raw] ?? `Error ${raw}`;
}

const EMPTY = () => new Uint8Array(0);

// MARK: Data (portnum 1, payload 2, want_response 3, dest 4, source 5,
// request_id 6, reply_id 7, emoji 8, bitfield 9)

export function makeData(p = {}) {
  return {
    portnum: 0, payload: EMPTY(), wantResponse: false, dest: 0, source: 0,
    requestID: 0, replyID: 0, emoji: 0, bitfield: null, ...p,
  };
}

export function encodeData(d) {
  const w = new ProtoWriter();
  w.writeUInt32IfNonZero(1, d.portnum);
  w.writeBytesIfNotEmpty(2, d.payload);
  w.writeBoolIfTrue(3, d.wantResponse);
  w.writeFixed32IfNonZero(4, d.dest);
  w.writeFixed32IfNonZero(5, d.source);
  w.writeFixed32IfNonZero(6, d.requestID);
  w.writeFixed32IfNonZero(7, d.replyID);
  w.writeFixed32IfNonZero(8, d.emoji);
  if (d.bitfield != null) w.writeUInt32(9, d.bitfield);
  return w.data;
}

export function decodeData(data) {
  const d = makeData();
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: d.portnum = f.uint32; break;
      case 2: d.payload = f.bytes; break;
      case 3: d.wantResponse = f.bool; break;
      case 4: d.dest = f.uint32; break;
      case 5: d.source = f.uint32; break;
      case 6: d.requestID = f.uint32; break;
      case 7: d.replyID = f.uint32; break;
      case 8: d.emoji = f.uint32; break;
      case 9: d.bitfield = f.uint32; break;
    }
  });
  return d;
}

// MARK: MeshPacket (from 1 fixed32, to 2 fixed32, channel 3, decoded 4,
// encrypted 5, id 6 fixed32, rx_time 7 fixed32, rx_snr 8 float, hop_limit 9,
// want_ack 10, priority 11, rx_rssi 12 int32, via_mqtt 14, hop_start 15)

export function makePacket(p = {}) {
  return {
    from: 0, to: 0, channel: 0, decoded: null, encrypted: null, id: 0, rxTime: 0,
    rxSNR: 0, hopLimit: 0, wantAck: false, priority: 0, rxRSSI: 0, viaMQTT: false,
    hopStart: 0, ...p,
  };
}

export function encodePacket(p) {
  const w = new ProtoWriter();
  w.writeFixed32IfNonZero(1, p.from);
  w.writeFixed32IfNonZero(2, p.to);
  w.writeUInt32IfNonZero(3, p.channel);
  if (p.decoded) w.writeBytes(4, encodeData(p.decoded));
  if (p.encrypted) w.writeBytes(5, p.encrypted);
  w.writeFixed32IfNonZero(6, p.id);
  w.writeFixed32IfNonZero(7, p.rxTime);
  if (p.rxSNR) w.writeFloat(8, p.rxSNR);
  w.writeUInt32IfNonZero(9, p.hopLimit);
  w.writeBoolIfTrue(10, p.wantAck);
  w.writeUInt32IfNonZero(11, p.priority);
  if (p.rxRSSI) w.writeInt32(12, p.rxRSSI);
  w.writeBoolIfTrue(14, p.viaMQTT);
  w.writeUInt32IfNonZero(15, p.hopStart);
  return w.data;
}

export function decodePacket(data) {
  const p = makePacket();
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: p.from = f.uint32; break;
      case 2: p.to = f.uint32; break;
      case 3: p.channel = f.uint32; break;
      case 4: p.decoded = decodeData(f.bytes); break;
      case 5: p.encrypted = f.bytes; break;
      case 6: p.id = f.uint32; break;
      case 7: p.rxTime = f.uint32; break;
      case 8: p.rxSNR = f.float; break;
      case 9: p.hopLimit = f.uint32; break;
      case 10: p.wantAck = f.bool; break;
      case 11: p.priority = f.uint32; break;
      case 12: p.rxRSSI = f.int32; break;
      case 14: p.viaMQTT = f.bool; break;
      case 15: p.hopStart = f.uint32; break;
    }
  });
  return p;
}

/** Hops the packet took to reach us, when the firmware reports hop_start. */
export function hopsAway(p) {
  if (!(p.hopStart > 0) || p.hopStart < p.hopLimit) return null;
  return p.hopStart - p.hopLimit;
}

// MARK: User (id 1, long_name 2, short_name 3, hw_model 5, is_licensed 6,
// role 7, public_key 8)

export function makeUser(p = {}) {
  return { id: "", longName: "", shortName: "", hwModel: 0, isLicensed: false, role: 0, publicKey: EMPTY(), ...p };
}

export function encodeUser(u) {
  const w = new ProtoWriter();
  w.writeStringIfNotEmpty(1, u.id);
  w.writeStringIfNotEmpty(2, u.longName);
  w.writeStringIfNotEmpty(3, u.shortName);
  w.writeUInt32IfNonZero(5, u.hwModel);
  w.writeBoolIfTrue(6, u.isLicensed);
  w.writeUInt32IfNonZero(7, u.role);
  w.writeBytesIfNotEmpty(8, u.publicKey);
  return w.data;
}

export function decodeUser(data) {
  const u = makeUser();
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: u.id = f.string; break;
      case 2: u.longName = f.string; break;
      case 3: u.shortName = f.string; break;
      case 5: u.hwModel = f.uint32; break;
      case 6: u.isLicensed = f.bool; break;
      case 7: u.role = f.uint32; break;
      case 8: u.publicKey = f.bytes; break;
    }
  });
  return u;
}

// MARK: Position (latitude_i 1 sfixed32, longitude_i 2 sfixed32,
// altitude 3 int32, time 4 fixed32). Degrees are stored as value * 1e-7.

export function makePosition(p = {}) {
  return { latitudeI: null, longitudeI: null, altitude: null, time: 0, ...p };
}

export const positionLatitude = (p) => (p.latitudeI == null ? null : p.latitudeI * 1e-7);
export const positionLongitude = (p) => (p.longitudeI == null ? null : p.longitudeI * 1e-7);

/** A usable fix: both coordinates present and not the 0,0 "no fix" placeholder. */
export function positionHasFix(p) {
  if (!p || p.latitudeI == null || p.longitudeI == null) return false;
  return !(p.latitudeI === 0 && p.longitudeI === 0);
}

export function encodePosition(p) {
  const w = new ProtoWriter();
  if (p.latitudeI != null) w.writeSFixed32(1, p.latitudeI);
  if (p.longitudeI != null) w.writeSFixed32(2, p.longitudeI);
  if (p.altitude != null) w.writeInt32(3, p.altitude);
  w.writeFixed32IfNonZero(4, p.time);
  return w.data;
}

export function decodePosition(data) {
  const p = makePosition();
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: p.latitudeI = f.sfixed32; break;
      case 2: p.longitudeI = f.sfixed32; break;
      case 3: p.altitude = f.int32; break;
      case 4: p.time = f.uint32; break;
    }
  });
  return p;
}

// MARK: DeviceMetrics (battery_level 1, voltage 2 float)

export function makeDeviceMetrics(p = {}) {
  return { batteryLevel: null, voltage: null, ...p };
}

export function encodeDeviceMetrics(m) {
  const w = new ProtoWriter();
  if (m.batteryLevel != null) w.writeUInt32(1, m.batteryLevel);
  if (m.voltage != null) w.writeFloat(2, m.voltage);
  return w.data;
}

export function decodeDeviceMetrics(data) {
  const m = makeDeviceMetrics();
  ProtoReader.forEachField(data, (f) => {
    if (f.number === 1) m.batteryLevel = f.uint32;
    else if (f.number === 2) m.voltage = f.float;
  });
  return m;
}

// MARK: NodeInfo (num 1, user 2, position 3, snr 4 float, last_heard 5
// fixed32, device_metrics 6, channel 7, via_mqtt 8, hops_away 9)

export function makeNodeInfo(p = {}) {
  return {
    num: 0, user: null, position: null, snr: 0, lastHeard: 0, deviceMetrics: null,
    channel: 0, viaMQTT: false, hopsAway: null, ...p,
  };
}

export function encodeNodeInfo(n) {
  const w = new ProtoWriter();
  w.writeUInt32IfNonZero(1, n.num);
  if (n.user) w.writeBytes(2, encodeUser(n.user));
  if (n.position) w.writeBytes(3, encodePosition(n.position));
  if (n.snr) w.writeFloat(4, n.snr);
  w.writeFixed32IfNonZero(5, n.lastHeard);
  if (n.deviceMetrics) w.writeBytes(6, encodeDeviceMetrics(n.deviceMetrics));
  w.writeUInt32IfNonZero(7, n.channel);
  w.writeBoolIfTrue(8, n.viaMQTT);
  if (n.hopsAway != null) w.writeUInt32(9, n.hopsAway);
  return w.data;
}

export function decodeNodeInfo(data) {
  const n = makeNodeInfo();
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: n.num = f.uint32; break;
      case 2: n.user = decodeUser(f.bytes); break;
      case 3: n.position = decodePosition(f.bytes); break;
      case 4: n.snr = f.float; break;
      case 5: n.lastHeard = f.uint32; break;
      case 6: n.deviceMetrics = decodeDeviceMetrics(f.bytes); break;
      case 7: n.channel = f.uint32; break;
      case 8: n.viaMQTT = f.bool; break;
      case 9: n.hopsAway = f.uint32; break;
    }
  });
  return n;
}

// MARK: MyNodeInfo (my_node_num 1, reboot_count 8, min_app_version 11)

export function makeMyNodeInfo(p = {}) {
  return { myNodeNum: 0, rebootCount: 0, minAppVersion: 0, ...p };
}

export function encodeMyNodeInfo(m) {
  const w = new ProtoWriter();
  w.writeUInt32IfNonZero(1, m.myNodeNum);
  w.writeUInt32IfNonZero(8, m.rebootCount);
  w.writeUInt32IfNonZero(11, m.minAppVersion);
  return w.data;
}

export function decodeMyNodeInfo(data) {
  const m = makeMyNodeInfo();
  ProtoReader.forEachField(data, (f) => {
    if (f.number === 1) m.myNodeNum = f.uint32;
    else if (f.number === 8) m.rebootCount = f.uint32;
    else if (f.number === 11) m.minAppVersion = f.uint32;
  });
  return m;
}

// MARK: Channel (index 1 int32, settings 2, role 3) and
// ChannelSettings (psk 2 bytes, name 3 string)

export const ChannelRole = Object.freeze({ disabled: 0, primary: 1, secondary: 2 });

export function makeChannel(p = {}) {
  return { index: 0, name: "", hasPSK: false, role: 0, ...p };
}

export function encodeChannel(c) {
  const w = new ProtoWriter();
  if (c.index) w.writeInt32(1, c.index);
  w.writeMessage(2, (s) => {
    if (c.hasPSK) s.writeBytes(2, Uint8Array.of(1));
    s.writeStringIfNotEmpty(3, c.name);
  });
  w.writeUInt32IfNonZero(3, c.role);
  return w.data;
}

export function decodeChannel(data) {
  const c = makeChannel();
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: c.index = f.int32; break;
      case 2:
        ProtoReader.forEachField(f.bytes, (s) => {
          if (s.number === 2) c.hasPSK = s.bytes.length > 0;
          else if (s.number === 3) c.name = s.string;
        });
        break;
      case 3: c.role = f.uint32; break;
    }
  });
  return c;
}

// MARK: DeviceMetadata (firmware_version 1, role 7, hw_model 9, hasPKC 11)

export function makeDeviceMetadata(p = {}) {
  return { firmwareVersion: "", role: 0, hwModel: 0, hasPKC: false, ...p };
}

export function encodeDeviceMetadata(m) {
  const w = new ProtoWriter();
  w.writeStringIfNotEmpty(1, m.firmwareVersion);
  w.writeUInt32IfNonZero(7, m.role);
  w.writeUInt32IfNonZero(9, m.hwModel);
  w.writeBoolIfTrue(11, m.hasPKC);
  return w.data;
}

export function decodeDeviceMetadata(data) {
  const m = makeDeviceMetadata();
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: m.firmwareVersion = f.string; break;
      case 7: m.role = f.uint32; break;
      case 9: m.hwModel = f.uint32; break;
      case 11: m.hasPKC = f.bool; break;
    }
  });
  return m;
}

// MARK: Config.lora (Config field 6) -> LoRaConfig (modem_preset 2,
// region 7, hop_limit 8). Region 0 = UNSET, 1 = US (902-928 MHz).

export function makeLoRaConfig(p = {}) {
  return { modemPreset: 0, region: 0, hopLimit: 0, ...p };
}

export function regionName(region) {
  switch (region) {
    case 0: return "Not set";
    case 1: return "US 915 MHz";
    case 2: return "EU 433 MHz";
    case 3: return "EU 868 MHz";
    case 6: return "ANZ";
    default: return `Region ${region}`;
  }
}

/** Encodes as a full `Config` message with only the lora variant set. */
export function encodeLoRaConfigAsConfig(l) {
  const w = new ProtoWriter();
  w.writeMessage(6, (s) => {
    s.writeUInt32IfNonZero(2, l.modemPreset);
    s.writeUInt32IfNonZero(7, l.region);
    s.writeUInt32IfNonZero(8, l.hopLimit);
  });
  return w.data;
}

/** Decodes a `Config` message; returns null when it is not the lora variant. */
export function decodeLoRaConfigFromConfig(data) {
  let result = null;
  ProtoReader.forEachField(data, (f) => {
    if (f.number !== 6) return;
    const l = makeLoRaConfig();
    ProtoReader.forEachField(f.bytes, (s) => {
      if (s.number === 2) l.modemPreset = s.uint32;
      else if (s.number === 7) l.region = s.uint32;
      else if (s.number === 8) l.hopLimit = s.uint32;
    });
    result = l;
  });
  return result;
}

// MARK: Routing (route_request 1, route_reply 2, error_reason 3)

/** errorReason is null when the message is a route request/reply rather than an ack/nak. */
export function encodeRouting(r) {
  const w = new ProtoWriter();
  // error_reason is a oneof member, so even NONE (0) is written.
  if (r.errorReason != null) w.writeUInt32(3, r.errorReason);
  return w.data;
}

export function decodeRouting(data) {
  const r = { errorReason: null };
  let sawRoute = false;
  ProtoReader.forEachField(data, (f) => {
    if (f.number === 1 || f.number === 2) sawRoute = true;
    else if (f.number === 3) r.errorReason = f.uint32;
  });
  // An empty Routing body carries no error: treat it as an ACK.
  if (r.errorReason == null && !sawRoute) r.errorReason = 0;
  return r;
}

// MARK: QueueStatus (res 1 int32, free 2, maxlen 3, mesh_packet_id 4)

export function makeQueueStatus(p = {}) {
  return { res: 0, free: 0, maxlen: 0, meshPacketID: 0, ...p };
}

export function encodeQueueStatus(q) {
  const w = new ProtoWriter();
  if (q.res) w.writeInt32(1, q.res);
  w.writeUInt32IfNonZero(2, q.free);
  w.writeUInt32IfNonZero(3, q.maxlen);
  w.writeUInt32IfNonZero(4, q.meshPacketID);
  return w.data;
}

export function decodeQueueStatus(data) {
  const q = makeQueueStatus();
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: q.res = f.int32; break;
      case 2: q.free = f.uint32; break;
      case 3: q.maxlen = f.uint32; break;
      case 4: q.meshPacketID = f.uint32; break;
    }
  });
  return q;
}

// MARK: FromRadio (id 1, packet 2, my_info 3, node_info 4, config 5,
// log_record 6, config_complete_id 7, rebooted 8, channel 10, queueStatus 11,
// metadata 13, clientNotification 16 -> message 4)
//
// variant: {kind, value} or null. Kinds: packet, myInfo, nodeInfo, loraConfig,
// otherConfig, logRecord, configCompleteID, rebooted, channel, queueStatus,
// metadata, clientNotification, unhandled (value = field number).

export const variant = (kind, value = null) => ({ kind, value });

export function encodeFromRadio(msg) {
  const w = new ProtoWriter();
  w.writeUInt32IfNonZero(1, msg.id ?? 0);
  const v = msg.variant;
  if (!v) return w.data;
  switch (v.kind) {
    case "packet": w.writeBytes(2, encodePacket(v.value)); break;
    case "myInfo": w.writeBytes(3, encodeMyNodeInfo(v.value)); break;
    case "nodeInfo": w.writeBytes(4, encodeNodeInfo(v.value)); break;
    case "loraConfig": w.writeBytes(5, encodeLoRaConfigAsConfig(v.value)); break;
    case "otherConfig": w.writeMessage(5, (c) => c.writeMessage(1, () => {})); break;
    case "logRecord": w.writeMessage(6, (s) => s.writeString(1, v.value)); break;
    case "configCompleteID": w.writeUInt32(7, v.value); break;
    case "rebooted": w.writeBool(8, true); break;
    case "channel": w.writeBytes(10, encodeChannel(v.value)); break;
    case "queueStatus": w.writeBytes(11, encodeQueueStatus(v.value)); break;
    case "metadata": w.writeBytes(13, encodeDeviceMetadata(v.value)); break;
    case "clientNotification": w.writeMessage(16, (s) => s.writeString(4, v.value)); break;
    case "unhandled": w.writeBytes(v.value, new Uint8Array(0)); break;
  }
  return w.data;
}

export function decodeFromRadio(data) {
  const r = { id: 0, variant: null };
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: r.id = f.uint32; break;
      case 2: r.variant = variant("packet", decodePacket(f.bytes)); break;
      case 3: r.variant = variant("myInfo", decodeMyNodeInfo(f.bytes)); break;
      case 4: r.variant = variant("nodeInfo", decodeNodeInfo(f.bytes)); break;
      case 5: {
        const lora = decodeLoRaConfigFromConfig(f.bytes);
        r.variant = lora ? variant("loraConfig", lora) : variant("otherConfig");
        break;
      }
      case 6: {
        let msg = "";
        ProtoReader.forEachField(f.bytes, (s) => { if (s.number === 1) msg = s.string; });
        r.variant = variant("logRecord", msg);
        break;
      }
      case 7: r.variant = variant("configCompleteID", f.uint32); break;
      case 8: r.variant = variant("rebooted"); break;
      case 10: r.variant = variant("channel", decodeChannel(f.bytes)); break;
      case 11: r.variant = variant("queueStatus", decodeQueueStatus(f.bytes)); break;
      case 13: r.variant = variant("metadata", decodeDeviceMetadata(f.bytes)); break;
      case 16: {
        let msg = "";
        ProtoReader.forEachField(f.bytes, (s) => { if (s.number === 4) msg = s.string; });
        r.variant = variant("clientNotification", msg);
        break;
      }
      default: r.variant = variant("unhandled", f.number);
    }
  });
  return r;
}

// MARK: ToRadio (packet 1, want_config_id 3, disconnect 4, heartbeat 7)
// {kind: "packet", value: packet} | {kind: "wantConfigID", value: id}
// | {kind: "disconnect", value: null} | {kind: "heartbeat", value: nonce}

export function encodeToRadio(t) {
  const w = new ProtoWriter();
  switch (t.kind) {
    case "packet": w.writeBytes(1, encodePacket(t.value)); break;
    case "wantConfigID": w.writeUInt32(3, t.value); break;
    case "disconnect": w.writeBool(4, true); break;
    case "heartbeat": w.writeMessage(7, (s) => s.writeUInt32IfNonZero(1, t.value)); break;
  }
  return w.data;
}

export function decodeToRadio(data) {
  let result = null;
  ProtoReader.forEachField(data, (f) => {
    switch (f.number) {
      case 1: result = variant("packet", decodePacket(f.bytes)); break;
      case 3: result = variant("wantConfigID", f.uint32); break;
      case 4: result = variant("disconnect"); break;
      case 7: {
        let nonce = 0;
        ProtoReader.forEachField(f.bytes, (s) => { if (s.number === 1) nonce = s.uint32; });
        result = variant("heartbeat", nonce);
        break;
      }
    }
  });
  return result;
}
