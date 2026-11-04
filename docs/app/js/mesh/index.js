// index.js — Pocket Nomad web app, Mesh module entry point
// SPDX-License-Identifier: Apache-2.0
//
// Contract with the app shell:
//   mount(el)      render the Mesh tab into `el`
//   unmount()      stop the tab's timers and listeners; the radio stays connected
//   onNodes(cb)    radios with positions for the map (not yours, not blocked):
//                  [{num, name, firmware, latitude, longitude, lastHeard}]; returns unsubscribe
// Also: configure({transport, store, location}) before first use, for the
// demo page and tests (a fake radio instead of Web Bluetooth).
//
// The shell links mesh.css.

import { WebBluetoothTransport, webBluetoothProblem } from "./ble.js";
import { MeshService } from "./service.js";
import { MeshStore, openStore } from "./store.js";
import { MeshUI } from "./ui.js";
import { displayName } from "./models.js";

let options = {};
let servicePromise = null;
let ui = null;
let problem = null;

/** Optional: swap in a transport, key-value store or location source. Call before mount/onNodes. */
export function configure(opts = {}) {
  if (servicePromise) throw new Error("mesh: configure() must be called before mount() or onNodes()");
  options = { ...opts };
}

/** One GPS fix for the SOS, or null (no permission, no fix within 15 s). */
function browserLocation() {
  return new Promise((resolve) => {
    if (!navigator.geolocation) return resolve(null);
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude }),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 60_000 },
    );
  });
}

function getService() {
  if (!servicePromise) {
    servicePromise = (async () => {
      const transport = options.transport ?? new WebBluetoothTransport();
      problem = options.transport ? null : webBluetoothProblem();
      const kv = options.store ?? (await openStore());
      const svc = new MeshService({ transport, store: new MeshStore(kv), location: options.location ?? browserLocation });
      await svc.init();
      // Brings Bluetooth up and reconnects to the last radio, if any.
      if (!problem) svc.start();
      return svc;
    })();
  }
  return servicePromise;
}

/** For the shell or debugging: the live service once loaded. */
export function service() {
  return getService();
}

let mountToken = 0;

export async function mount(el) {
  const token = ++mountToken;
  const svc = await getService();
  if (token !== mountToken) return; // unmounted (or remounted) while loading
  ui?.destroy();
  ui = new MeshUI(el, svc, { problem, persistent: svc.store.persistent });
}

export function unmount() {
  mountToken += 1;
  ui?.destroy();
  ui = null;
}

function mapNodes(svc) {
  return svc.sortedNodes
    .filter((n) => n.latitude != null && n.longitude != null)
    .map((n) => ({
      num: n.num,
      name: displayName(n),
      firmware: n.firmware ?? svc.firmware,
      latitude: n.latitude,
      longitude: n.longitude,
      lastHeard: n.lastHeard ?? null,
    }));
}

export function onNodes(callback) {
  let active = true;
  let off = () => {};
  let last = null;
  const push = (svc) => {
    const list = mapNodes(svc);
    const key = JSON.stringify(list);
    if (key === last) return;
    last = key;
    try {
      callback(list);
    } catch (e) {
      console.error(e);
    }
  };
  getService().then((svc) => {
    if (!active) return;
    off = svc.subscribe(push);
    push(svc);
  }, (e) => console.error("mesh: could not start", e));
  return () => {
    active = false;
    off();
  };
}
