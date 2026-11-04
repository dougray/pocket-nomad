// ui.js — Pocket Nomad web app: small DOM, storage and location helpers.
// SPDX-License-Identifier: Apache-2.0

/** Escapes text for HTML. */
export function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/** Saved settings and records. Kept only in this browser; never sent anywhere. */
export const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem(`pn.${key}`); return v == null ? fallback : JSON.parse(v); } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(`pn.${key}`, JSON.stringify(value)); } catch { /* private mode or full: keep going */ }
  },
};

let cache;
/** The guide index (categories, guides, checklists), loaded once. */
export async function guideIndex() {
  if (!cache) {
    const [index, checklists] = await Promise.all([
      fetch("guides/index.json").then((r) => r.json()),
      fetch("guides/checklists.json").then((r) => r.json()),
    ]);
    cache = { categories: index.categories, checklists };
  }
  return cache;
}

/** One shared GPS watch, started by whichever screen needs it first. */
const listeners = new Set();
let watchId = null;
export let lastFix = null;
export function watchLocation(callback) {
  listeners.add(callback);
  if (lastFix) callback(lastFix);
  if (watchId == null && "geolocation" in navigator) {
    watchId = navigator.geolocation.watchPosition(
      (p) => { lastFix = p; listeners.forEach((l) => l(p)); },
      (e) => listeners.forEach((l) => l(null, e)),
      { enableHighAccuracy: true, maximumAge: 5000, timeout: 30000 });
  }
  return () => {
    listeners.delete(callback);
    if (!listeners.size && watchId != null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
  };
}

export function locationError(e) {
  if (!("geolocation" in navigator)) return "This browser has no location access.";
  if (e && e.code === 1) return "Location is blocked. Allow it for this site in your browser settings.";
  return "Waiting for GPS. Go outside or near a window.";
}

/** Keeps the screen on while a tool needs it (where the browser allows). */
export async function keepAwake() {
  try { return await navigator.wakeLock?.request("screen"); } catch { return null; }
}
