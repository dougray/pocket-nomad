// ui.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The Mesh tab: status, radios, channel chat, direct messages, node list,
// blocking and SOS. Ported from MeshView, MeshChatView, MeshNodeListView,
// MeshDevicesSheet, MeshInfoView and MeshComponents (SwiftUI), keeping their
// wording. Plain DOM, no framework.

import { linkUp } from "./service.js";
import {
  badge, byteCount, channelConv, convKey, directConv, displayName, firmwareInfo, formatDegrees, hexID,
  isBroadcast, trimText,
} from "./models.js";

// MARK: DOM helpers

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "text") el.textContent = v;
      else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
      else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
      else if (k === "html") el.innerHTML = v;
      else el.setAttribute(k, v === true ? "" : v);
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

const ICONS = {
  antenna: '<path d="M12 12v9M8.5 8.5a5 5 0 0 1 7 0M5.6 5.6a9 9 0 0 1 12.8 0"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/>',
  antennaOff: '<path d="M12 12v9M8.5 8.5a5 5 0 0 1 7 0M5.6 5.6a9 9 0 0 1 12.8 0M4 4l16 16"/>',
  waves: '<circle cx="12" cy="12" r="2" fill="currentColor"/><path d="M8 8a5.7 5.7 0 0 0 0 8M16 8a5.7 5.7 0 0 1 0 8M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14"/>',
  warning: '<path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17h.01"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  back: '<path d="m15 5-7 7 7 7"/>',
  chat: '<path d="M4 5h16v11H9l-5 4V5z"/>',
  people: '<circle cx="9" cy="8" r="3"/><circle cx="17" cy="9" r="2.4"/><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M15 14.5c3 0 6 1.8 6 5"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5h.01"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  send: '<path d="M3 11 21 3l-8 18-2-8-8-2z"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 5 5"/>',
  refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.6M20 4v5h-5"/>',
  more: '<circle cx="5" cy="12" r="1.7" fill="currentColor"/><circle cx="12" cy="12" r="1.7" fill="currentColor"/><circle cx="19" cy="12" r="1.7" fill="currentColor"/>',
  question: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6M12 17h.01"/>',
  check: '<path d="m5 12 5 5 9-10"/>',
  checkCircle: '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
  location: '<path d="M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z"/><circle cx="12" cy="10" r="2.5"/>',
  hand: '<path d="M8 13V5.5a1.5 1.5 0 0 1 3 0V11M11 10V4.5a1.5 1.5 0 0 1 3 0V11M14 10V5.5a1.5 1.5 0 0 1 3 0V14c0 4-2.7 7-6.5 7S5 18.5 4 15l-1-3a1.5 1.5 0 0 1 2.7-1.2L8 13"/>',
  trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
};

function icon(name, cls = "mesh-icon") {
  const span = document.createElement("span");
  span.className = cls;
  span.setAttribute("aria-hidden", "true");
  span.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] ?? ""}</svg>`;
  return span;
}

function relative(ms, now = Date.now()) {
  const diff = (ms - now) / 1000;
  if (Math.abs(diff) < 60) return "just now";
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  const units = [["year", 31536000], ["month", 2592000], ["week", 604800], ["day", 86400], ["hour", 3600], ["minute", 60]];
  for (const [unit, s] of units) {
    if (Math.abs(diff) >= s) return rtf.format(Math.round(diff / s), unit);
  }
  return "just now";
}

const timeOf = (ms) => new Date(ms).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });

/** A stable colour per node so people are easy to tell apart. */
const nodeColor = (num) => `hsl(${num % 360} 84% 69%)`;

function nodeBadge(node, size = 48) {
  return h("span", {
    class: "mesh-badge",
    "aria-hidden": "true",
    style: { background: nodeColor(node.num), width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.3)}px` },
    text: badge(node),
  });
}

function infoCard({ icon: ic, title, message, tone = "accent", actions = [] }) {
  return h("div", { class: `mesh-card mesh-info mesh-tone-${tone}`, role: "note" },
    h("div", { class: "mesh-info-title" }, icon(ic), h("strong", { text: title })),
    h("p", { text: message }),
    actions);
}

function bigButton(label, onclick, { ic, tone = "accent", filled = true, disabled = false, extraClass = "" } = {}) {
  return h("button", {
    type: "button",
    class: `mesh-big mesh-tone-${tone} ${filled ? "mesh-filled" : "mesh-outline"} ${extraClass}`,
    onclick,
    disabled,
  }, ic ? icon(ic) : null, h("span", { text: label }));
}

// MARK: Status wording (MeshStatusCard)

function statusParts(conn, radio) {
  const name = conn.name;
  switch (conn.state) {
    case "idle": return { ic: "antennaOff", tone: "muted", title: "No radio connected", subtitle: "Tap to find your Meshtastic or MeshCore radio.", busy: false };
    case "connecting": return { ic: "waves", tone: "warn", title: `Connecting to ${name}`, subtitle: "If you're asked for a PIN, read it off the radio's screen.", busy: true };
    case "syncing": return { ic: "waves", tone: "warn", title: `Syncing with ${name}`, subtitle: "Downloading the node list and settings.", busy: true };
    case "connected": {
      const bits = [];
      if (radio.firmware) bits.push(firmwareInfo(radio.firmware).displayName);
      if (radio.regionName) bits.push(radio.regionName);
      bits.push(radio.primaryChannelName || "Primary channel");
      return { ic: "antenna", tone: "ok", title: `Connected: ${name}`, subtitle: bits.join(" · "), busy: false };
    }
    case "reconnecting": return { ic: "waves", tone: "warn", title: `Waiting for ${name}`, subtitle: "Will reconnect by itself when the radio is in range. Keep this page open.", busy: true };
    case "failed": return { ic: "warning", tone: "danger", title: "Connection problem", subtitle: conn.reason || "Could not connect.", busy: false };
    default: return { ic: "antennaOff", tone: "muted", title: "No radio connected", subtitle: "", busy: false };
  }
}

const INFO_SECTIONS = [
  ["What is it?", "antenna",
    "A mesh radio is a small, cheap LoRa radio that runs free mesh firmware: Meshtastic or MeshCore. Your phone or computer talks to it over Bluetooth, and it sends your text over the air to other radios. Radios pass messages along, so texts can hop across several radios to reach people far away. No cell towers, Wi-Fi or internet are involved, and messages are encrypted. Pocket Nomad works with both kinds, but a Meshtastic radio only talks to other Meshtastic radios and a MeshCore radio only to MeshCore ones, so use the same kind as the people you want to reach."],
  ["Getting a radio", "search",
    "Look for a LoRa board for the 915 MHz band if you are in the US. Common ones are made by Heltec, RAK Wireless (WisBlock), LilyGO (T-Beam, T-Echo) and Seeed (SenseCAP T1000-E). Most cost about $20 to $80. Get one with a battery or battery holder, and an antenna that is always attached. Never power a radio on without its antenna, which can damage it. Everyone you want to reach needs a radio too."],
  ["Set it up first", "lock",
    "Before using it here, set the radio up once. Meshtastic: install the official Meshtastic app, connect, and set the LoRa region to US (915 MHz); a radio with no region will not transmit. MeshCore: flash the Companion (Bluetooth) firmware with the MeshCore web flasher and pick your area's radio settings in the MeshCore app. Keep the default public channel to reach people nearby, or make a private one and share it with your group. Then come back here and tap Find a radio."],
  ["How far does it reach?", "waves",
    "Expect about 1 to 3 miles between handheld radios in towns or woods, and 5 to 10 miles or more with a clear line of sight, such as from a hilltop. Hills, buildings and bodies block the signal: hold the radio high, away from your body, or put it on a pack strap. Each extra radio in between extends the range."],
  ["What to expect", "chat",
    "Messages are short (about 200 letters) and can take seconds to a minute to arrive. \"Heard by mesh\" means another radio repeated your message. \"Delivered\" means the person's radio confirmed it. Keep this page open and on screen to keep receiving messages: browsers pause pages in the background."],
  ["SOS", "warning",
    "The SOS button sends a short help message, with your GPS position if you allow it, to every radio on the primary channel. It does not reach emergency services unless someone on the mesh passes it on."],
  ["In the browser", "info",
    "Mesh radios need Chrome or Edge on Android, Windows, Mac, Linux or ChromeOS. On iPhone, use the Pocket Nomad app. The coverage scanner and drive mode are only in the iPhone app, because a web page stops when it goes to the background. Messages and the node list are stored only in this browser."],
];

export const UNSUPPORTED_TEXT = "Mesh radios need Chrome or Edge on Android, Windows, Mac, Linux or ChromeOS. On iPhone, use the Pocket Nomad app.";
export const WEB_LIMIT_TEXT = "No coverage recording or drive mode on the web: the page stops when it's in the background.";

// MARK: View

export class MeshUI {
  /**
   * @param {HTMLElement} root
   * @param {import("./service.js").MeshService} svc
   * @param {{problem?: string|null, persistent?: boolean}} opts
   */
  constructor(root, svc, opts = {}) {
    this.root = root;
    this.svc = svc;
    this.problem = opts.problem ?? null;
    this.persistent = opts.persistent ?? true;
    this.route = { name: "home" };
    this.drafts = {};
    this.sheet = null;
    this.sosBusy = false;
    this._raf = 0;
    this._lastThreadLen = -1;

    root.classList.add("mesh-root");
    this.headerEl = h("div", { class: "mesh-header" });
    this.bodyEl = h("div", { class: "mesh-body" });
    this.composerEl = null;
    this.liveEl = h("div", { class: "mesh-sr", "aria-live": "polite" });
    this.sheetEl = h("div", { class: "mesh-sheet-host" });
    root.replaceChildren(this.headerEl, this.bodyEl, this.liveEl, this.sheetEl);

    this._onKey = (e) => {
      if (e.key !== "Escape") return;
      if (this.sheet) this.closeSheet();
      else if (this.route.name !== "home") this.go({ name: this.route.from ?? "home" });
    };
    document.addEventListener("keydown", this._onKey);
    this._unsub = svc.subscribe(() => this.schedule());
    this._tick = setInterval(() => { if (!this.sheet) this.schedule(); }, 30_000);
    this.render();
  }

  destroy() {
    this._unsub?.();
    clearInterval(this._tick);
    cancelAnimationFrame(this._raf);
    document.removeEventListener("keydown", this._onKey);
    this.root.replaceChildren();
    this.root.classList.remove("mesh-root");
  }

  schedule() {
    if (this._raf) return;
    this._raf = requestAnimationFrame(() => {
      this._raf = 0;
      this.render();
    });
  }

  go(route) {
    this.route = route;
    this._lastThreadLen = -1;
    this.composerEl?.remove();
    this.composerEl = null;
    this.composer = null;
    this.render();
    this.root.scrollIntoView?.({ block: "start" });
    window.scrollTo?.({ top: 0 });
  }

  // MARK: Rendering

  render() {
    const r = this.route;
    if (this.problem) return this.renderUnsupported();
    if (r.name === "chat") return this.renderChat(r.conv);
    switch (r.name) {
      case "nodes": this.setHeader("Nodes"); this.bodyEl.replaceChildren(...this.nodesView()); break;
      case "info": this.setHeader("What is a mesh radio?"); this.bodyEl.replaceChildren(...this.infoView()); break;
      case "radios": this.setHeader("Radios"); this.bodyEl.replaceChildren(...this.radiosView()); break;
      default: this.setHeader(null); this.bodyEl.replaceChildren(...this.homeView());
    }
  }

  setHeader(title, extra = null) {
    if (!title) {
      this.headerEl.replaceChildren(h("h2", { class: "mesh-title", text: "Mesh" }));
      if (!this.problem) {
        this.headerEl.append(h("button", {
          type: "button", class: "mesh-icon-btn", "aria-label": "Radios", onclick: () => this.go({ name: "radios" }),
        }, icon("antenna")));
      }
      return;
    }
    this.headerEl.replaceChildren(
      h("button", {
        type: "button", class: "mesh-icon-btn", "aria-label": "Back", onclick: () => this.go({ name: this.route.from ?? "home" }),
      }, icon("back")),
      h("h2", { class: "mesh-title mesh-title-sub", text: title }),
      ...(extra ? [extra] : []));
  }

  renderUnsupported() {
    this.setHeader(this.route.name === "info" ? "What is a mesh radio?" : null);
    if (this.route.name === "info") {
      this.bodyEl.replaceChildren(...this.infoView());
      return;
    }
    const secureHint = this.problem === "insecure" ? "This page also has to be opened over https." : null;
    this.bodyEl.replaceChildren(
      infoCard({ icon: "antennaOff", title: "This browser can't reach a mesh radio", message: UNSUPPORTED_TEXT, tone: "warn",
        actions: secureHint ? h("p", { class: "mesh-muted", text: secureHint }) : null }),
      h("p", { class: "mesh-muted mesh-small", text: WEB_LIMIT_TEXT }),
      bigButton("What is a mesh radio?", () => this.go({ name: "info" }), { ic: "question", filled: false }));
  }

  statusCard(clickable) {
    const s = statusParts(this.svc.connection, this.svc.radio);
    const inner = [
      h("span", { class: `mesh-status-icon mesh-text-${s.tone} ${s.busy ? "mesh-pulse" : ""}` }, icon(s.ic, "mesh-icon mesh-icon-lg")),
      h("span", { class: "mesh-status-text" },
        h("strong", { class: "mesh-status-title", text: s.title }),
        h("span", { class: "mesh-muted", text: s.subtitle })),
      clickable ? icon("chevron") : null,
    ];
    const attrs = { class: `mesh-card mesh-status mesh-border-${s.tone}` };
    const el = clickable
      ? h("button", { ...attrs, type: "button", onclick: () => this.go({ name: "radios" }), "aria-label": `${s.title}. ${s.subtitle}. Opens the radio list` }, inner)
      : h("div", { ...attrs, role: "status" }, inner);
    this.liveEl.textContent = s.title;
    return el;
  }

  commonCards() {
    const svc = this.svc;
    const out = [];
    if (svc.availability === "poweredOff") {
      out.push(infoCard({ icon: "warning", title: "Bluetooth is off", tone: "warn",
        message: "Turn Bluetooth on in this device's settings, then come back here." }));
    }
    if (svc.pairingHint) {
      out.push(infoCard({ icon: "lock", title: "Pairing", message: svc.pairingHint, tone: "warn",
        actions: bigButton("Dismiss", () => svc.clearPairingHint(), { tone: "warn", filled: false }) }));
    }
    if (svc.notice) {
      out.push(infoCard({ icon: "info", title: "From the radio", message: svc.notice, tone: "warn",
        actions: bigButton("Dismiss", () => svc.clearNotice(), { tone: "warn", filled: false }) }));
    }
    return out;
  }

  connectButtons() {
    const svc = this.svc;
    const out = [];
    if (svc.lastDeviceID && svc.lastDeviceName) {
      out.push(bigButton(`Reconnect to ${svc.lastDeviceName}`, () => svc.reconnectLast(), { ic: "refresh" }));
    }
    out.push(bigButton("Find a radio", () => svc.pickRadio(), { ic: "search", filled: !svc.lastDeviceID }));
    return h("div", { class: "mesh-stack" }, out);
  }

  homeView() {
    const svc = this.svc;
    const conn = svc.connection;
    const out = [this.statusCard(true), ...this.commonCards()];
    if (conn.state === "idle" || conn.state === "failed") out.push(this.connectButtons());

    const primary = svc.radio.primaryChannelName;
    const lastCh = svc.messagesIn(channelConv(0)).at(-1);
    out.push(this.tile({
      ic: "chat",
      title: primary ? `Channel: ${primary}` : "Channel chat",
      subtitle: lastCh?.text ?? "Talk to everyone on the primary channel",
      badge: svc.unreadCount(channelConv(0)),
      onclick: () => this.go({ name: "chat", conv: channelConv(0) }),
    }));
    out.push(this.tile({
      ic: "people",
      title: `Nodes (${svc.sortedNodes.length})`,
      subtitle: "Radios your radio has heard",
      onclick: () => this.go({ name: "nodes" }),
    }));

    const threads = svc.directThreads.filter((t) => !svc.isBlocked(t.node.num));
    if (threads.length) {
      out.push(h("div", { class: "mesh-stack" },
        h("h3", { class: "mesh-section", text: "Direct messages" }),
        threads.map((t) => h("button", {
          type: "button", class: "mesh-card mesh-row", onclick: () => this.go({ name: "chat", conv: directConv(t.node.num) }),
        },
        nodeBadge(t.node, 44),
        h("span", { class: "mesh-row-text" },
          h("strong", { text: displayName(t.node) }),
          h("span", { class: "mesh-muted mesh-ellipsis", text: t.last.text })),
        this.unread(svc.unreadCount(directConv(t.node.num)))))));
    }

    out.push(h("button", {
      type: "button",
      class: "mesh-sos",
      disabled: !svc.canSend || this.sosBusy,
      onclick: () => this.confirmSOS(),
      "aria-describedby": "mesh-sos-hint",
    }, h("span", { text: this.sosBusy ? "Getting location..." : "SOS" })));
    out.push(h("p", { id: "mesh-sos-hint", class: "mesh-muted mesh-small mesh-center",
      text: svc.canSend ? "Asks before sending an emergency message to everyone." : "Connect a radio first." }));

    out.push(bigButton("What is a mesh radio?", () => this.go({ name: "info" }), { ic: "question", filled: false }));
    out.push(h("p", { class: "mesh-muted mesh-small", text: WEB_LIMIT_TEXT }));
    if (!this.persistent) {
      out.push(h("p", { class: "mesh-muted mesh-small", text: "This browser isn't letting the page save, so messages are kept only until you close it." }));
    }
    return out;
  }

  tile({ ic, title, subtitle, badge: n = 0, onclick }) {
    return h("button", { type: "button", class: "mesh-card mesh-tile", onclick },
      h("span", { class: "mesh-text-accent" }, icon(ic, "mesh-icon mesh-icon-lg")),
      h("span", { class: "mesh-row-text" },
        h("strong", { class: "mesh-tile-title", text: title }),
        h("span", { class: "mesh-muted mesh-ellipsis", text: subtitle })),
      this.unread(n),
      icon("chevron"));
  }

  unread(n) {
    return n > 0 ? h("span", { class: "mesh-unread", "aria-label": `${n} unread`, text: String(n) }) : null;
  }

  // MARK: Radios (MeshDevicesSheet)

  radiosView() {
    const svc = this.svc;
    const conn = svc.connection;
    const out = [...this.commonCards()];
    if (conn.state !== "idle" && conn.state !== "failed") {
      out.push(this.statusCard(false));
      if (svc.radio.firmwareVersion && conn.state === "connected") {
        out.push(h("p", { class: "mesh-muted mesh-small", text: `Firmware ${svc.radio.firmwareVersion}` }));
      }
      out.push(bigButton(`Disconnect ${conn.name}`, () => svc.disconnect(), { ic: "close", tone: "danger", filled: false }));
      out.push(bigButton("Forget and pick another", async () => {
        svc.forgetDevice();
        await svc.pickRadio();
      }, { ic: "trash", tone: "muted", filled: false }));
    } else {
      out.push(this.statusCard(false));
    }
    if (conn.state !== "connected") {
      out.push(infoCard({ icon: "lock", title: "About the PIN",
        message: "The first time you connect, your phone or computer asks for a 6-digit PIN. Radios with a screen show it there. Radios without a screen usually use 123456 unless someone changed it." }));
    }
    if (!linkUp(conn)) {
      out.push(h("div", { class: "mesh-stack" },
        h("h3", { class: "mesh-section", text: "Nearby radios" }),
        h("p", { class: "mesh-muted", text: svc.availability === "poweredOff"
          ? "Waiting for Bluetooth."
          : "Your browser shows the radios it can see. Make sure the radio is on, Bluetooth is enabled on it, and no other phone is connected to it." }),
        this.connectButtons()));
    }
    return out;
  }

  // MARK: Nodes (MeshNodeListView)

  nodesView() {
    const svc = this.svc;
    const nodes = svc.sortedNodes;
    const out = [];
    if (!nodes.length) {
      out.push(infoCard({ icon: "people", title: "No other radios yet",
        message: svc.connection.state === "connected"
          ? "Your radio has not heard anyone. Radios announce themselves every few hours, so leave it on, get higher up, or ask a friend to send a message."
          : "Connect your radio to see who is around." }));
    }
    for (const node of nodes) out.push(this.nodeRow(node));
    const blocked = svc.blockedNodes;
    if (blocked.length) {
      out.push(h("div", { class: "mesh-stack" },
        h("h3", { class: "mesh-section", text: "Blocked" }),
        blocked.map((n) => h("div", { class: "mesh-card mesh-blocked" },
          h("span", { class: "mesh-muted", text: displayName(n) }),
          h("button", { type: "button", class: "mesh-link-btn", onclick: () => svc.unblock(n.num), text: "Unblock" })))));
    }
    return out;
  }

  nodeRow(node) {
    const facts = [];
    facts.push(node.lastHeard ? relative(node.lastHeard, this.svc.now()) : "Not heard");
    if (node.snr != null) facts.push(`${node.snr.toFixed(1)} dB`);
    if (node.hopsAway != null) facts.push(node.hopsAway === 0 ? "Direct" : `${node.hopsAway} hop${node.hopsAway === 1 ? "" : "s"}`);
    if (node.batteryLevel != null) facts.push(node.batteryLevel > 100 ? "Plugged in" : `${node.batteryLevel}%`);
    if (node.meshCoreType && node.meshCoreType !== "chat" && node.meshCoreType !== "none") facts.push(node.meshCoreType === "room" ? "Room server" : node.meshCoreType[0].toUpperCase() + node.meshCoreType.slice(1));
    const open = () => this.go({ name: "chat", conv: directConv(node.num), from: "nodes" });
    return h("div", { class: "mesh-card mesh-node" },
      h("button", { type: "button", class: "mesh-node-main", onclick: open, "aria-label": `${displayName(node)}. Opens a direct message` },
        nodeBadge(node, 52),
        h("span", { class: "mesh-row-text" },
          h("strong", { class: "mesh-tile-title", text: displayName(node) }),
          h("span", { class: "mesh-mono mesh-muted mesh-small", text: node.firmware === "meshcore" && node.userID ? node.userID : hexID(node.num) }),
          h("span", { class: "mesh-muted mesh-small", text: facts.join(" · ") }),
          node.latitude != null && node.longitude != null
            ? h("span", { class: "mesh-mono mesh-muted mesh-small mesh-select" }, icon("location", "mesh-icon mesh-icon-sm"), `${formatDegrees(node.latitude)}, ${formatDegrees(node.longitude)}`)
            : null),
        h("span", { class: "mesh-text-accent" }, icon("chat", "mesh-icon mesh-icon-lg"))),
      h("button", { type: "button", class: "mesh-icon-btn", "aria-label": `More for ${displayName(node)}`, onclick: () => this.nodeActions(node) }, icon("more")));
  }

  nodeActions(node) {
    this.openSheet({
      title: displayName(node),
      actions: [
        { label: "Message", onClick: () => this.go({ name: "chat", conv: directConv(node.num), from: "nodes" }) },
        { label: "Block This Radio", tone: "danger", onClick: () => this.confirmBlock(node) },
      ],
    });
  }

  confirmBlock(node) {
    this.openSheet({
      title: `Block ${displayName(node)}?`,
      message: "Its messages are deleted from this device and new ones are ignored. The other person isn't told. You can unblock it from the Nodes list.",
      actions: [{ label: "Block", tone: "danger", onClick: () => {
        this.svc.block(node.num);
        if (this.route.name === "chat" && this.route.conv.type === "direct" && this.route.conv.num === node.num) this.go({ name: "home" });
      } }],
    });
  }

  // MARK: Info (MeshInfoView)

  infoView() {
    return INFO_SECTIONS.map(([title, ic, text]) => h("section", { class: "mesh-info-section" },
      h("h3", { class: "mesh-text-accent mesh-info-title" }, icon(ic), h("span", { text: title })),
      h("p", { text })));
  }

  // MARK: Chat (MeshChatView)

  chatTitle(conv) {
    if (conv.type === "channel") {
      const name = this.svc.radio.channelNames?.[conv.index] ?? "";
      return name || (conv.index === 0 ? "Primary channel" : `Channel ${conv.index}`);
    }
    return displayName(this.svc.node(conv.num));
  }

  renderChat(conv) {
    const svc = this.svc;
    this.setHeader(this.chatTitle(conv));
    const thread = svc.messagesIn(conv);
    const isChannel = conv.type === "channel";
    const nearBottom = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 120;

    const list = thread.length
      ? h("div", { class: "mesh-thread", role: "log", "aria-label": "Messages" }, thread.map((m) => this.bubble(m, isChannel)))
      : h("div", { class: "mesh-empty" },
        icon(isChannel ? "chat" : "people", "mesh-icon mesh-icon-xl"),
        h("p", { text: isChannel
          ? "No messages yet. Anything you send here reaches every radio in range on this channel."
          : "No messages yet. Only this person's radio can read what you send here." }));
    this.bodyEl.replaceChildren(...this.commonCards(), list);

    if (!this.composerEl) this.buildComposer(conv);
    this.updateComposer(conv);

    if (svc.unreadCount(conv) > 0) svc.markRead(conv);
    if (thread.length !== this._lastThreadLen) {
      const first = this._lastThreadLen < 0;
      this._lastThreadLen = thread.length;
      if (first || nearBottom || thread.at(-1)?.isOutgoing) {
        requestAnimationFrame(() => this.composerEl?.scrollIntoView({ block: "end", behavior: first ? "auto" : "smooth" }));
      }
    }
  }

  bubble(m, showSender) {
    const svc = this.svc;
    const sender = m.isOutgoing || m.from === 0 ? null : svc.node(m.from);
    const label = !m.isOutgoing && showSender ? (m.senderName ?? (sender ? displayName(sender) : null)) : null;
    const foot = [timeOf(m.date)];
    if (m.rxSNR != null) foot.push(`SNR ${m.rxSNR.toFixed(1)}`);
    if (m.hopsAway > 0) foot.push(`${m.hopsAway} hop${m.hopsAway === 1 ? "" : "s"}`);
    const textEl = h("div", { class: "mesh-bubble-text", text: m.text });
    const canBlock = !m.isOutgoing && m.from !== 0;
    return h("div", { class: `mesh-msg ${m.isOutgoing ? "mesh-out" : "mesh-in"}` },
      sender && showSender ? nodeBadge(sender, 36) : null,
      h("div", { class: "mesh-msg-col" },
        label ? h("span", { class: "mesh-sender", text: label }) : null,
        canBlock
          ? h("button", { type: "button", class: "mesh-bubble mesh-bubble-btn", onclick: () => this.messageActions(m, sender),
            "aria-label": `${label ?? "Message"}: ${m.text}. More options` }, textEl)
          : h("div", { class: "mesh-bubble" }, textEl),
        h("div", { class: "mesh-foot" }, h("span", { text: foot.join(" · ") }), m.isOutgoing ? this.deliveryLabel(m) : null)));
  }

  deliveryLabel(m) {
    switch (m.delivery) {
      case "sent": return h("span", { class: "mesh-deliv" }, icon("check", "mesh-icon mesh-icon-sm"), "Sent");
      case "heardByMesh": return h("span", { class: "mesh-deliv mesh-text-ok" }, icon("waves", "mesh-icon mesh-icon-sm"), isBroadcast(m) ? "Heard by mesh" : "Relayed");
      case "notRepeated": return h("span", { class: "mesh-deliv" }, icon("check", "mesh-icon mesh-icon-sm"), "Sent · no one nearby repeated it");
      case "delivered": return h("span", { class: "mesh-deliv mesh-text-ok" }, icon("checkCircle", "mesh-icon mesh-icon-sm"), "Delivered");
      case "failed": return h("span", { class: "mesh-deliv mesh-text-danger" }, icon("warning", "mesh-icon mesh-icon-sm"), `Failed: ${m.failReason ?? "Not sent"}`);
      default: return null;
    }
  }

  messageActions(m, sender) {
    const actions = [];
    if (navigator.clipboard?.writeText) {
      actions.push({ label: "Copy text", onClick: () => navigator.clipboard.writeText(m.text).catch(() => {}) });
    }
    if (sender && this.route.conv?.type === "channel") {
      actions.push({ label: `Message ${displayName(sender)}`, onClick: () => this.go({ name: "chat", conv: directConv(sender.num) }) });
    }
    actions.push({ label: "Block Sender", tone: "danger", onClick: () => this.confirmBlock(sender ?? this.svc.node(m.from)) });
    this.openSheet({ title: m.senderName ?? (sender ? displayName(sender) : "Message"), actions });
  }

  buildComposer(conv) {
    const key = convKey(conv);
    const input = h("textarea", {
      class: "mesh-input", rows: "1", placeholder: "Message", "aria-label": "Message", enterkeyhint: "send",
      oninput: () => { this.drafts[key] = input.value; this.autosize(input); this.updateComposer(conv); },
      onkeydown: (e) => {
        if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
          e.preventDefault();
          this.sendDraft(conv);
        }
      },
    });
    input.value = this.drafts[key] ?? "";
    const send = h("button", { type: "button", class: "mesh-send", "aria-label": "Send", onclick: () => this.sendDraft(conv) }, icon("send", "mesh-icon mesh-icon-lg"));
    this.composer = {
      input, send,
      warn: h("p", { class: "mesh-composer-warn", text: "Connect a radio to send." }),
      hint: h("span", {}),
      count: h("span", { class: "mesh-count" }),
    };
    this.composerEl = h("div", { class: "mesh-composer" },
      this.composer.warn,
      h("div", { class: "mesh-composer-row" }, input, send),
      h("div", { class: "mesh-composer-meta", "aria-live": "polite" }, this.composer.hint, this.composer.count));
    this.root.insertBefore(this.composerEl, this.liveEl);
    this.autosize(input);
  }

  autosize(input) {
    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
  }

  updateComposer() {
    const c = this.composer;
    if (!c) return;
    const svc = this.svc;
    const max = svc.maxTextBytes;
    const bytes = byteCount(trimText(c.input.value));
    const tooLong = bytes > max;
    c.warn.hidden = svc.canSend;
    c.send.disabled = !(svc.canSend && bytes > 0 && !tooLong);
    c.input.classList.toggle("mesh-too-long", tooLong);
    c.hint.textContent = tooLong ? `Too long, trim ${bytes - max} bytes` : "Emoji and accents use extra bytes";
    c.count.textContent = `${bytes}/${max}`;
    this.composerEl.classList.toggle("mesh-text-danger", tooLong);
    this.composerEl.classList.toggle("mesh-near-limit", !tooLong && bytes > max - 20);
  }

  sendDraft(conv) {
    const c = this.composer;
    if (!c || c.send.disabled) return;
    if (this.svc.send(c.input.value, conv)) {
      c.input.value = "";
      this.drafts[convKey(conv)] = "";
      this.autosize(c.input);
      this.updateComposer(conv);
      c.input.focus();
    }
  }

  // MARK: SOS

  confirmSOS() {
    const svc = this.svc;
    this.openSheet({
      title: "Send SOS to everyone on the mesh?",
      message: `This message goes to every radio in range on the primary channel:\n\n"${svc.sosPreview}"\n\nWith location, your GPS position is added (decimal degrees).`,
      actions: [
        { label: "Send SOS with my location", tone: "danger", filled: true, onClick: () => this.sendSOS(true) },
        { label: "Send SOS without location", tone: "danger", onClick: () => this.sendSOS(false) },
      ],
    });
  }

  async sendSOS(withLocation) {
    this.sosBusy = withLocation;
    this.render();
    const sent = await this.svc.sendSOS(withLocation);
    this.sosBusy = false;
    let msg;
    if (sent) {
      msg = `Sent to the mesh:\n${sent}`;
      if (withLocation && !sent.includes("Location:")) msg += "\n\nNo GPS fix was available, so it went without coordinates.";
    } else {
      msg = "Could not send. Check that the radio is connected, then try again.";
    }
    this.render();
    this.openSheet({ title: "SOS", message: msg, actions: [], cancelLabel: "OK" });
  }

  // MARK: Sheets (confirmation dialogs)

  openSheet({ title, message, actions, cancelLabel = "Cancel" }) {
    this.closeSheet(false);
    const before = document.activeElement;
    const close = () => this.closeSheet();
    const buttons = actions.map((a) => bigButton(a.label, () => {
      this.closeSheet();
      a.onClick();
    }, { tone: a.tone ?? "accent", filled: a.filled ?? a.tone !== "danger" }));
    buttons.push(bigButton(cancelLabel, close, { tone: "muted", filled: false }));
    const titleID = `mesh-sheet-${Math.random().toString(36).slice(2, 8)}`;
    const panel = h("div", { class: "mesh-sheet", role: "dialog", "aria-modal": "true", "aria-labelledby": titleID },
      h("h3", { id: titleID, class: "mesh-sheet-title", text: title }),
      message ? h("p", { class: "mesh-sheet-msg", text: message }) : null,
      h("div", { class: "mesh-stack" }, buttons));
    const backdrop = h("div", { class: "mesh-backdrop", onclick: (e) => { if (e.target === backdrop) close(); } }, panel);
    this.sheetEl.replaceChildren(backdrop);
    this.sheet = { before };
    buttons[0]?.focus();
  }

  closeSheet(restore = true) {
    if (!this.sheet) return;
    const { before } = this.sheet;
    this.sheet = null;
    this.sheetEl.replaceChildren();
    if (restore && before?.isConnected) before.focus?.();
  }
}
