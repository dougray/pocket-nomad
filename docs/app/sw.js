// sw.js — Pocket Nomad web app. GENERATED from sw.template.js by tools/build-pwa.py.
// SPDX-License-Identifier: Apache-2.0
//
// Keeps the whole app (guides, tools, map code) on the device so it works with
// no signal. Map tiles are cached as you look at them, up to a limit; there is
// no background sync and nothing is sent anywhere.

const VERSION = "f679b77d0d13";
const APP_CACHE = `pn-app-${VERSION}`;
const TILE_CACHE = "pn-tiles-v1";
const MAX_TILES = 4000;
const FILES = [
 "./",
 "app.css",
 "app.js",
 "guides/checklists.json",
 "guides/first-aid/anaphylaxis.html",
 "guides/first-aid/asthma.html",
 "guides/first-aid/bites-stings.html",
 "guides/first-aid/bleeding.html",
 "guides/first-aid/burns.html",
 "guides/first-aid/choking.html",
 "guides/first-aid/cpr.html",
 "guides/first-aid/dehydration.html",
 "guides/first-aid/diabetic-emergencies.html",
 "guides/first-aid/drowning.html",
 "guides/first-aid/electric-shock.html",
 "guides/first-aid/eye-injuries.html",
 "guides/first-aid/fractures.html",
 "guides/first-aid/frostbite.html",
 "guides/first-aid/head-injury.html",
 "guides/first-aid/heart-attack.html",
 "guides/first-aid/heat-illness.html",
 "guides/first-aid/hypothermia.html",
 "guides/first-aid/nosebleeds.html",
 "guides/first-aid/poisoning.html",
 "guides/first-aid/scene-safety.html",
 "guides/first-aid/seizures.html",
 "guides/first-aid/shock.html",
 "guides/first-aid/snakebite.html",
 "guides/first-aid/spinal-injury.html",
 "guides/first-aid/sprains.html",
 "guides/first-aid/stroke.html",
 "guides/first-aid/triage-start.html",
 "guides/first-aid/wounds.html",
 "guides/index.json",
 "guides/radio-comms/ares-races-skywarn.html",
 "guides/radio-comms/cb-marine-vhf.html",
 "guides/radio-comms/comms-plan.html",
 "guides/radio-comms/frs-gmrs.html",
 "guides/radio-comms/ham-radio-basics.html",
 "guides/radio-comms/key-frequencies.html",
 "guides/radio-comms/meshtastic.html",
 "guides/radio-comms/morse-code.html",
 "guides/radio-comms/noaa-weather-radio.html",
 "guides/radio-comms/phonetic-alphabet.html",
 "guides/radio-comms/radio-emergency-basics.html",
 "guides/radio-comms/radio-procedure.html",
 "guides/radio-comms/winlink.html",
 "guides/survival/earthquake.html",
 "guides/survival/extreme-heat.html",
 "guides/survival/fire.html",
 "guides/survival/flood.html",
 "guides/survival/hurricane.html",
 "guides/survival/navigation.html",
 "guides/survival/power-outage.html",
 "guides/survival/priorities.html",
 "guides/survival/shelter.html",
 "guides/survival/signaling.html",
 "guides/survival/stay-or-go.html",
 "guides/survival/tornado.html",
 "guides/survival/wildfire-smoke.html",
 "guides/survival/winter-storm.html",
 "guides/water-sanitation/cat-holes-latrines.html",
 "guides/water-sanitation/disinfecting-surfaces.html",
 "guides/water-sanitation/emergency-toilets.html",
 "guides/water-sanitation/finding-water.html",
 "guides/water-sanitation/flood-disease.html",
 "guides/water-sanitation/food-safety-power-out.html",
 "guides/water-sanitation/garbage-waste.html",
 "guides/water-sanitation/handwashing.html",
 "guides/water-sanitation/making-water-safe.html",
 "guides/water-sanitation/menstrual-infant-hygiene.html",
 "guides/water-sanitation/mold.html",
 "guides/water-sanitation/personal-hygiene.html",
 "guides/water-sanitation/water-storage.html",
 "icons/apple-touch-icon.png",
 "icons/icon-192.png",
 "icons/icon-512.png",
 "index.html",
 "js/logic.js",
 "js/map.js",
 "js/mesh/ble.js",
 "js/mesh/index.js",
 "js/mesh/mesh.css",
 "js/mesh/meshcore.js",
 "js/mesh/meshtastic.js",
 "js/mesh/models.js",
 "js/mesh/service.js",
 "js/mesh/store.js",
 "js/mesh/ui.js",
 "js/mesh/wire.js",
 "js/more.js",
 "js/survive.js",
 "js/tools.js",
 "js/ui.js",
 "manifest.webmanifest",
 "vendor/MAPLIBRE-LICENSE.txt",
 "vendor/maplibre-gl.css",
 "vendor/maplibre-gl.js"
];

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
