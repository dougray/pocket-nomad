// sw.js — Pocket Nomad web app. GENERATED from sw.template.js by tools/build-pwa.py.
// SPDX-License-Identifier: Apache-2.0
//
// Keeps the whole app (guides, tools, map code) on the device so it works with
// no signal. Map tiles are cached as you look at them, up to a limit; there is
// no background sync and nothing is sent anywhere.

const VERSION = "__VERSION__";
const APP_CACHE = `pn-app-${VERSION}`;
const TILE_CACHE = "pn-tiles-v1";
const MAX_TILES = 4000;
const FILES = __FILES__;

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(APP_CACHE).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key.startsWith("pn-app-") && key !== APP_CACHE) await caches.delete(key);
    }
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin === location.origin) {
    // App files: offline first; navigation falls back to the app shell.
    event.respondWith(caches.match(req, { ignoreSearch: true }).then((hit) =>
      hit || fetch(req).catch(() => req.mode === "navigate" ? caches.match("./") : Response.error())));
    return;
  }
  if (url.hostname === "tiles.openfreemap.org") {
    event.respondWith(tile(req));
  }
});

// Map style, fonts and tiles: use the network when there is one (and keep a
// copy), the saved copy when there isn't.
async function tile(req) {
  const cache = await caches.open(TILE_CACHE);
  try {
    const res = await fetch(req);
    if (res.ok) {
      await cache.put(req, res.clone());
      trim(cache);
    }
    return res;
  } catch {
    return (await cache.match(req)) || Response.error();
  }
}

let trimming = false;
async function trim(cache) {
  if (trimming) return;
  trimming = true;
  const keys = await cache.keys();
  for (const k of keys.slice(0, Math.max(0, keys.length - MAX_TILES))) await cache.delete(k);
  trimming = false;
}
