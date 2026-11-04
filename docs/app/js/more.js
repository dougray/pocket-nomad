// more.js — Pocket Nomad web app: install help, offline status, privacy and about.
// SPDX-License-Identifier: Apache-2.0

let installEvent = null;
window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); installEvent = e; });

const installed = () => window.matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;

function platform() {
  const ua = navigator.userAgent;
  if (/iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1)) return "ios";
  if (/Android/.test(ua)) return "android";
  return "desktop";
}

export async function show(view, page, setTitle) {
  if (page === "install") return install(view, setTitle);
  if (page === "privacy") return privacy(view, setTitle);
  setTitle("More");
  view.innerHTML = `
    <div class="card" id="offline"><b>Offline</b><p class="muted small" id="offline-text">Checking…</p></div>
    <h2>This app</h2>
    <ul class="list">
      <li><a href="#/more/install"><span>${installed() ? "Installed on this device" : "Install Pocket Nomad"}<span class="sub">Add it to your home screen so it opens like an app and works with no signal</span></span></a></li>
      <li><a href="#/more/privacy"><span>Privacy<span class="sub">What this web app keeps, and where</span></span></a></li>
      <li><a href="../pwa/"><span>Web, iPhone and Android compared<span class="sub">What each version can do</span></span></a></li>
    </ul>
    <h2>Get the full app</h2>
    <ul class="list">
      <li><a href="../ios/"><span>iPhone, iPad and Apple Watch<span class="sub">Adds the offline library, on-device AI, drive mode and more</span></span></a></li>
      <li><a href="../android/"><span>Android<span class="sub">Beta APK, then Google Play</span></span></a></li>
    </ul>
    <h2>About</h2>
    <div class="card small">
      <p>Pocket Nomad is free and open source under the Apache License 2.0. Map data © OpenStreetMap contributors, tiles by OpenFreeMap, drawn with MapLibre (BSD-3-Clause).</p>
      <p>The guides follow published guidance (Red Cross, AHA, CDC, EPA, WHO and others) and list their sources, but they don't replace training. <a href="../MEDICAL-REVIEW.html">How the guides were checked</a>.</p>
      <p><a href="https://github.com/dougray/pocket-nomad">Source code</a> · <a href="https://github.com/dougray/pocket-nomad/issues">Support</a> · <a href="../">pocketnomad.us</a></p>
    </div>`;
  const text = view.querySelector("#offline-text");
  if (!("serviceWorker" in navigator)) { text.textContent = "This browser can't keep the app for offline use."; return; }
  const reg = await navigator.serviceWorker.getRegistration();
  const keys = await caches.keys().catch(() => []);
  const ready = keys.some((k) => k.startsWith("pn-app-"));
  let usage = "";
  try { const e = await navigator.storage.estimate(); usage = ` Using ${(e.usage / 1048576).toFixed(1)} MB on this device.`; } catch { /* not supported */ }
  text.textContent = (reg && ready
    ? "Guides, checklists and tools are saved on this device and work with no signal. Map areas you've looked at are kept too."
    : "Saving the app for offline use… keep this page open for a moment.") + usage;
  if (navigator.storage?.persist && !(await navigator.storage.persisted?.())) {
    view.querySelector("#offline").insertAdjacentHTML("beforeend",
      `<div class="row-btns"><button class="btn" id="persist">Ask the browser to keep it</button></div>`);
    view.querySelector("#persist").onclick = async () => {
      const ok = await navigator.storage.persist();
      view.querySelector("#persist").textContent = ok ? "The browser will keep it" : "The browser decides; installing helps";
    };
  }
}

function install(view, setTitle) {
  setTitle("Install");
  const p = platform();
  const steps = {
    ios: `<ol>
      <li>Open <b>pocketnomad.us/app</b> in <b>Safari</b>.</li>
      <li>Tap the <b>Share</b> button (the square with an arrow).</li>
      <li>Scroll down and tap <b>Add to Home Screen</b>, then <b>Add</b>.</li>
      <li>Open Pocket Nomad from your home screen once while you have signal, so it saves itself for offline use.</li></ol>
      <p class="small muted">On iPhone the web app can't use Bluetooth radios. For mesh messaging, drive mode, the offline library and the on-device AI, use the <a href="../ios/">iPhone app</a>.</p>`,
    android: `<ol>
      <li>Open <b>pocketnomad.us/app</b> in <b>Chrome</b>.</li>
      <li>Tap <b>Install</b> below, or the <b>⋮</b> menu › <b>Add to home screen</b> › <b>Install</b>.</li>
      <li>Open Pocket Nomad from your home screen once while you have signal, so it saves itself for offline use.</li></ol>
      <p class="small muted">The web app can text over a mesh radio in Chrome while the screen is on. For drive mode with the screen off, use the <a href="../android/">Android app</a>.</p>`,
    desktop: `<ol>
      <li>Open <b>pocketnomad.us/app</b> in <b>Chrome</b> or <b>Edge</b>.</li>
      <li>Click <b>Install</b> below, or the install icon at the right of the address bar.</li>
      <li>Open it once while online so it saves itself for offline use.</li></ol>
      <p class="small muted">On a computer, Chrome and Edge can connect to a mesh radio over Bluetooth.</p>`,
  };
  view.innerHTML = `
    ${installed() ? `<div class="notice">Pocket Nomad is installed on this device.</div>` : ""}
    <div class="card guide">${steps[p]}
      ${installEvent && !installed() ? `<div class="row-btns"><button class="btn primary" id="install">Install</button></div>` : ""}</div>
    <h2>Other devices</h2>
    <div class="card small">${Object.entries(steps).filter(([k]) => k !== p)
      .map(([k, v]) => `<details><summary><b>${{ ios: "iPhone and iPad", android: "Android", desktop: "Computer" }[k]}</b></summary>${v}</details>`).join("")}</div>`;
  view.querySelector("#install")?.addEventListener("click", async () => {
    installEvent.prompt();
    await installEvent.userChoice;
    installEvent = null;
    install(view, setTitle);
  });
}

function privacy(view, setTitle) {
  setTitle("Privacy");
  view.innerHTML = `<div class="card guide">
    <ul>
      <li><b>No accounts, analytics, ads or tracking.</b> Nothing you do here is sent to us; there are no servers of ours.</li>
      <li><b>Kept in this browser only:</b> your Medical ID, checklist ticks, saved places, mesh messages and settings. Clearing this site's data in your browser deletes them.</li>
      <li><b>Location</b> is used on this device for the compass, map, daylight times and an SOS you choose to send. It isn't sent anywhere by the app.</li>
      <li><b>Network:</b> the app files come from pocketnomad.us, and map tiles from OpenFreeMap (tiles.openfreemap.org), which sees your IP address and the map area you look at. Nothing else.</li>
      <li><b>Mesh radios:</b> what you send over a radio is broadcast to other radios by design. The default public channels use a well-known key, so anyone nearby with a radio can read them.</li>
    </ul>
    <p><a href="../PRIVACY.html">Full privacy policy</a></p></div>`;
}
