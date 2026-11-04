// map.js — Pocket Nomad web app: the map tab.
// SPDX-License-Identifier: Apache-2.0
//
// OpenStreetMap vector tiles from OpenFreeMap, drawn with MapLibre GL JS
// (vendored, BSD-3-Clause). Tiles you look at are kept by the service worker,
// so places you've viewed still show with no signal. Pins stay in this browser.

import { esc, store, watchLocation } from "./ui.js";
import { decimal } from "./logic.js";

const STYLE = "https://tiles.openfreemap.org/styles/dark";
const KINDS = { water: "💧 Water", shelter: "🏠 Shelter", rally: "🚩 Rally point", medical: "✚ Medical", supplies: "📦 Supplies", danger: "⚠️ Danger", other: "📍 Other" };
const COLORS = { meshtastic: "#66d978", meshcore: "#408cfa" };

let map, stopGPS, unsubNodes, pinMarkers = [], nodeMarkers = [];

async function loadMapLibre() {
  if (window.maplibregl) return;
  const css = document.createElement("link");
  css.rel = "stylesheet"; css.href = "vendor/maplibre-gl.css";
  document.head.append(css);
  await new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = "vendor/maplibre-gl.js"; s.onload = resolve; s.onerror = () => reject(new Error("The map library didn't load."));
    document.head.append(s);
  });
}

export async function mount(view) {
  view.innerHTML = `<div id="map" role="region" aria-label="Map"></div>
    <div class="map-tools"><button id="me" title="Show where I am" aria-label="Show where I am">➤</button>
      <button id="pins" title="Saved places" aria-label="Saved places">☰</button></div>
    <div class="map-panel" id="legend"></div>
    <dialog id="dlg"></dialog>`;
  await loadMapLibre();
  const saved = store.get("map.view", { center: [-98, 39], zoom: 3 });
  map = new maplibregl.Map({ container: "map", style: STYLE, center: saved.center, zoom: saved.zoom, attributionControl: { compact: true } });
  map.addControl(new maplibregl.NavigationControl({ showCompass: true }), "top-left");
  map.on("moveend", () => store.set("map.view", { center: map.getCenter().toArray(), zoom: map.getZoom() }));
  map.on("error", () => legend());

  // Where I am.
  let me = null, follow = !store.get("map.view", null);
  const dot = document.createElement("div");
  dot.style.cssText = "width:16px;height:16px;border-radius:50%;background:#3b82f6;border:3px solid #fff;box-shadow:0 0 0 6px rgba(59,130,246,.25)";
  stopGPS = watchLocation((p) => {
    if (!p) return;
    const ll = [p.coords.longitude, p.coords.latitude];
    if (!me) me = new maplibregl.Marker({ element: dot }).setLngLat(ll).addTo(map); else me.setLngLat(ll);
    if (follow) { map.jumpTo({ center: ll, zoom: Math.max(map.getZoom(), 13) }); follow = false; }
  });
  view.querySelector("#me").onclick = () => { if (me) map.flyTo({ center: me.getLngLat(), zoom: Math.max(map.getZoom(), 14) }); else follow = true; };

  // Pins: long-press (or right-click) to add.
  let pressTimer;
  const addAt = (lngLat) => pinDialog(view, lngLat);
  map.on("contextmenu", (e) => addAt(e.lngLat));
  map.on("touchstart", (e) => { if (e.originalEvent.touches.length === 1) pressTimer = setTimeout(() => addAt(e.lngLat), 600); });
  for (const ev of ["touchend", "touchmove", "dragstart", "zoomstart"]) map.on(ev, () => clearTimeout(pressTimer));
  view.querySelector("#pins").onclick = () => pinList(view);
  drawPins();

  // Mesh radios with a position, from the Mesh tab's connection.
  try {
    const mesh = await import("./mesh/index.js");
    unsubNodes = mesh.onNodes?.((nodes) => drawNodes(nodes));
  } catch { /* mesh module unavailable: map still works */ }
  legend();
}

export function unmount() {
  stopGPS?.(); unsubNodes?.();
  pinMarkers = []; nodeMarkers = [];
  map?.remove(); map = null;
}

function legend(nodes = []) {
  const el = document.getElementById("legend");
  if (!el) return;
  el.innerHTML = `${nodes.length ? `<div><b>Mesh radios</b></div>
      <div><span class="dot" style="background:${COLORS.meshtastic}"></span>Meshtastic <span class="dot" style="background:${COLORS.meshcore};margin-left:8px"></span>MeshCore</div>` : ""}
    <div class="muted">Long-press to save a place. ${navigator.onLine ? "Places you view are kept for offline use." : "Offline: showing places you've viewed before."}</div>`;
}

function drawPins() {
  pinMarkers.forEach((m) => m.remove());
  pinMarkers = store.get("pins", []).map((p) => {
    const el = document.createElement("div");
    el.textContent = KINDS[p.kind]?.split(" ")[0] || "📍";
    el.style.cssText = "font-size:24px;cursor:pointer;filter:drop-shadow(0 1px 2px #000)";
    return new maplibregl.Marker({ element: el, anchor: "bottom" }).setLngLat([p.lon, p.lat])
      .setPopup(new maplibregl.Popup({ offset: 20 }).setHTML(`<b>${esc(p.name)}</b><br>${esc(KINDS[p.kind] || "")}<br><small>${decimal(p.lat, p.lon)}</small>`))
      .addTo(map);
  });
}

function drawNodes(nodes) {
  if (!map) return;
  nodeMarkers.forEach((m) => m.remove());
  nodeMarkers = nodes.map((n) => {
    const el = document.createElement("div");
    el.style.cssText = `width:22px;height:22px;border-radius:50%;background:${COLORS[n.firmware] || COLORS.meshtastic};border:2px solid #000;display:grid;place-items:center;font-size:12px`;
    el.textContent = "📡";
    const heard = n.lastHeard ? `heard ${new Date(n.lastHeard).toLocaleString()}` : "not heard yet";
    return new maplibregl.Marker({ element: el }).setLngLat([n.longitude, n.latitude])
      .setPopup(new maplibregl.Popup({ offset: 14 }).setHTML(`<b>${esc(n.name)}</b><br>${n.firmware === "meshcore" ? "MeshCore" : "Meshtastic"} · ${esc(heard)}`))
      .addTo(map);
  });
  legend(nodes);
}

function pinDialog(view, lngLat) {
  const dlg = view.querySelector("#dlg");
  dlg.innerHTML = `<form method="dialog">
    <h3 style="margin-top:0">Save this place</h3>
    <p class="small muted">${decimal(lngLat.lat, lngLat.lng)}</p>
    <label class="field"><span>Name</span><input name="name" required autofocus></label>
    <label class="field"><span>Type</span><select name="kind">${Object.entries(KINDS).map(([k, v]) => `<option value="${k}" ${k === "rally" ? "selected" : ""}>${v}</option>`).join("")}</select></label>
    <div class="row-btns"><button class="btn primary" value="save">Save</button><button class="btn" value="cancel" formnovalidate>Cancel</button></div></form>`;
  dlg.onclose = () => {
    if (dlg.returnValue !== "save") return;
    const f = new FormData(dlg.querySelector("form"));
    const pins = store.get("pins", []);
    pins.push({ id: crypto.randomUUID?.() || String(Date.now()), name: String(f.get("name")).trim() || "Place", kind: f.get("kind"), lat: lngLat.lat, lon: lngLat.lng, created: Date.now() });
    store.set("pins", pins);
    drawPins();
  };
  dlg.showModal();
}

function pinList(view) {
  const dlg = view.querySelector("#dlg");
  const pins = store.get("pins", []);
  dlg.innerHTML = `<h3 style="margin-top:0">Saved places</h3>
    ${pins.length ? `<ul class="list">${pins.map((p) => `<li><div class="row"><button class="btn" data-go="${esc(p.id)}" style="flex:1;justify-content:flex-start">${esc(KINDS[p.kind]?.split(" ")[0] || "📍")} ${esc(p.name)}</button>
      <button class="btn danger" data-del="${esc(p.id)}" aria-label="Delete ${esc(p.name)}">✕</button></div></li>`).join("")}</ul>`
      : `<p class="muted">No saved places yet. Long-press the map to add water, shelter or a rally point.</p>`}
    <div class="row-btns"><button class="btn" id="close">Done</button></div>`;
  dlg.querySelector("#close").onclick = () => dlg.close();
  dlg.querySelectorAll("[data-go]").forEach((b) => b.onclick = () => {
    const p = pins.find((x) => x.id === b.dataset.go);
    dlg.close();
    map.flyTo({ center: [p.lon, p.lat], zoom: 15 });
  });
  dlg.querySelectorAll("[data-del]").forEach((b) => b.onclick = () => {
    store.set("pins", pins.filter((x) => x.id !== b.dataset.del));
    drawPins();
    pinList(view);
  });
  if (!dlg.open) dlg.showModal();
}
