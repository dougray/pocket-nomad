// tools.js — Pocket Nomad web app: compass, signal light, Morse, water, Medical ID, daylight.
// SPDX-License-Identifier: Apache-2.0
//
// Same behaviour and wording as the iOS tools; the maths lives in logic.js.

import { esc, store, watchLocation, locationError, keepAwake } from "./ui.js";
import * as L from "./logic.js";

export const TOOLS = [
  { id: "compass", title: "Compass & Position", icon: "🧭" },
  { id: "sos", title: "SOS Light", icon: "🔦" },
  { id: "morse", title: "Morse Code", icon: "📶" },
  { id: "water", title: "Water Purifier", icon: "💧" },
  { id: "medical", title: "Medical ID", icon: "🩺" },
  { id: "sun", title: "Daylight", icon: "🌅" },
];

/** Opens a tool; returns an object with unmount() to stop sensors and timers. */
export async function open(view, id, setTitle) {
  const tool = TOOLS.find((t) => t.id === id);
  setTitle(tool ? tool.title : "Tool");
  switch (id) {
    case "compass": return compass(view);
    case "sos": return signal(view, "SOS");
    case "morse": return morse(view);
    case "water": return water(view);
    case "medical": return medical(view);
    case "sun": return daylight(view);
    default: view.innerHTML = `<p class="muted">Tool not found.</p>`; return null;
  }
}

// MARK: Compass and position

function compass(view) {
  view.innerHTML = `
    <div class="dial" aria-hidden="true"><div class="rose" id="rose">
      <b style="top:6px;color:var(--danger)">N</b><b style="bottom:6px">S</b>
      <b style="left:10px;top:50%;transform:translateY(-50%)">W</b><b style="right:10px;left:auto;top:50%;transform:translateY(-50%)">E</b></div>
      <div class="needle"></div></div>
    <p class="big" id="heading" style="text-align:center">—</p>
    <div class="row-btns" style="justify-content:center"><button class="btn" id="enable" hidden>Turn on compass</button></div>
    <div class="card" id="pos"><p class="muted">Waiting for GPS…</p></div>
    <p class="small muted">Phone compasses can be off near cars, metal and magnets. Hold the phone flat, away from them. The web version shows magnetic north where true north isn't available.</p>`;
  const rose = view.querySelector("#rose"), headingEl = view.querySelector("#heading"), enable = view.querySelector("#enable");
  const onOrient = (e) => {
    const h = e.webkitCompassHeading ?? (e.absolute && e.alpha != null ? (360 - e.alpha) % 360 : null);
    if (h == null) return;
    rose.style.transform = `rotate(${-h}deg)`;
    headingEl.textContent = `${Math.round(h)}° ${L.cardinal(h)}`;
  };
  const start = () => {
    window.addEventListener("deviceorientationabsolute", onOrient);
    window.addEventListener("deviceorientation", onOrient);
  };
  if (typeof DeviceOrientationEvent !== "undefined" && typeof DeviceOrientationEvent.requestPermission === "function") {
    // iPhone and iPad ask for permission, and only after a tap.
    enable.hidden = false;
    enable.addEventListener("click", async () => {
      if (await DeviceOrientationEvent.requestPermission().catch(() => "denied") === "granted") { enable.hidden = true; start(); }
      else headingEl.textContent = "Compass blocked";
    });
  } else if ("ondeviceorientationabsolute" in window || "ondeviceorientation" in window) {
    start();
  } else {
    headingEl.textContent = "No compass";
  }
  const pos = view.querySelector("#pos");
  const stop = watchLocation((p, err) => {
    if (!p) { pos.innerHTML = `<p class="muted">${esc(locationError(err))}</p>`; return; }
    const { latitude: lat, longitude: lon, accuracy, altitude } = p.coords;
    pos.innerHTML = `
      <p><span class="muted small">Decimal</span><br><b>${L.decimal(lat, lon)}</b></p>
      <p><span class="muted small">Degrees, minutes, seconds</span><br><b>${L.dms(lat, lon)}</b></p>
      <p><span class="muted small">MGRS</span><br><b>${esc(L.mgrs(lat, lon) ?? "Not available this far north or south")}</b></p>
      <p class="small muted">Accurate to about ${Math.round(accuracy)} m${altitude != null ? ` · altitude ${Math.round(altitude * 3.28084)} ft` : ""}</p>
      <div class="row-btns"><button class="btn" id="copy">Copy position</button></div>`;
    pos.querySelector("#copy").onclick = () => navigator.clipboard?.writeText(`${L.decimal(lat, lon)} (${L.mgrs(lat, lon) ?? ""})`);
  });
  return { unmount() { stop(); window.removeEventListener("deviceorientationabsolute", onOrient); window.removeEventListener("deviceorientation", onOrient); } };
}

// MARK: Signal light (screen, sound and, where the browser allows, the torch)

function signal(view, text) {
  view.innerHTML = `
    <div class="card">
      <p>Flashes <b>${esc(text)}</b> in Morse code: <span class="muted">${esc(L.morseEncode(text))}</span></p>
      <label class="check"><input type="checkbox" id="sound" ${store.get("signal.sound", false) ? "checked" : ""}><span>Beep too</span></label>
      <label class="check"><input type="checkbox" id="torch" ${store.get("signal.torch", true) ? "checked" : ""}><span>Use the flashlight if this browser allows it (Android Chrome)</span></label>
      <label class="field"><span>Speed</span><select id="speed"><option value="240">Slow</option><option value="160" selected>Normal</option><option value="100">Fast</option></select></label>
      <div class="row-btns"><button class="btn primary" id="go">Start signalling</button></div>
    </div>
    <p class="small muted">Point the screen toward rescuers. At night a phone screen can be seen from far away; in daylight a mirror or bright cloth works better. Tap anywhere to stop.</p>`;
  let stopFn = null;
  view.querySelector("#go").addEventListener("click", async () => {
    const sound = view.querySelector("#sound").checked, torch = view.querySelector("#torch").checked;
    store.set("signal.sound", sound); store.set("signal.torch", torch);
    stopFn = await play(text, Number(view.querySelector("#speed").value), { sound, torch });
  });
  return { unmount() { stopFn?.(); } };
}

/** Plays a Morse timeline on a full-screen flash, optional beep and torch. Returns stop(). */
async function play(text, unitMs, { sound, torch }) {
  const overlay = document.createElement("div");
  overlay.className = "flash";
  overlay.innerHTML = `<button class="btn" style="opacity:.8">Tap to stop</button>`;
  document.body.append(overlay);
  const lock = await keepAwake();
  let audio, osc, gain, track;
  if (sound) {
    audio = new (window.AudioContext || window.webkitAudioContext)();
    osc = audio.createOscillator(); gain = audio.createGain();
    osc.frequency.value = 760; gain.gain.value = 0; osc.connect(gain).connect(audio.destination); osc.start();
  }
  if (torch && navigator.mediaDevices?.getUserMedia) {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
      track = stream.getVideoTracks()[0];
      if (!track.getCapabilities?.().torch) { track.stop(); track = null; }
    } catch { track = null; }
  }
  const set = (on) => {
    overlay.classList.toggle("on", on);
    if (gain) gain.gain.value = on ? 0.25 : 0;
    track?.applyConstraints({ advanced: [{ torch: on }] }).catch(() => {});
  };
  let stopped = false, timer;
  const timeline = L.morseTimeline(text);
  let i = 0;
  const step = () => {
    if (stopped) return;
    const s = timeline[i];
    set(s.on);
    timer = setTimeout(step, s.units * unitMs);
    i = (i + 1) % timeline.length;
  };
  step();
  const stop = () => {
    if (stopped) return;
    stopped = true; clearTimeout(timer); set(false);
    osc?.stop(); audio?.close(); track?.stop(); lock?.release?.(); overlay.remove();
  };
  overlay.addEventListener("click", stop);
  return stop;
}

function morse(view) {
  view.innerHTML = `
    <label class="field"><span>Message</span><input id="msg" maxlength="40" value="${esc(store.get("morse.text", "SOS"))}" autocapitalize="characters"></label>
    <div class="card"><p class="big" style="font-size:28px;word-break:break-all" id="code"></p></div>
    <div class="row-btns"><button class="btn primary" id="go">Flash it</button></div>
    <h2>Alphabet</h2>
    <div class="card" style="columns:3;font-variant-numeric:tabular-nums">${Object.entries(L.MORSE).map(([k, v]) => `<div><b>${esc(k)}</b> <span class="muted">${v}</span></div>`).join("")}</div>`;
  const msg = view.querySelector("#msg"), code = view.querySelector("#code");
  const update = () => { code.textContent = L.morseEncode(msg.value) || "—"; store.set("morse.text", msg.value); };
  msg.addEventListener("input", update);
  update();
  let stopFn = null;
  view.querySelector("#go").addEventListener("click", async () => {
    if (msg.value.trim()) stopFn = await play(msg.value.trim(), 160, { sound: store.get("signal.sound", false), torch: store.get("signal.torch", true) });
  });
  return { unmount() { stopFn?.(); } };
}

// MARK: Water purifier

function water(view) {
  const s = { amount: 1, unit: "gallons", strength: "6%", cloudy: false, alt: null };
  view.innerHTML = `
    <div class="card">
      <label class="field"><span>Water</span><input id="amount" type="number" min="0.25" max="100" step="0.25" value="1" inputmode="decimal"></label>
      <div class="seg" id="unit"><button aria-pressed="true" data-v="gallons">Gallons</button><button aria-pressed="false" data-v="liters">Liters</button></div>
      <label class="check" style="padding-left:0"><input type="checkbox" id="cloudy"><span>Cloudy, colored or very cold</span></label>
      <p class="muted small" style="margin:8px 0 4px">Plain, unscented bleach strength on the label</p>
      <div class="seg" id="strength"><button aria-pressed="true" data-v="6%">6%</button><button aria-pressed="false" data-v="8.25%">8.25%</button></div>
    </div>
    <div class="card"><p class="muted" style="margin:0">Add</p><p class="big" id="drops"></p><p class="muted" id="tsp"></p>
      <p>⏱ Stir and wait 30 minutes.</p>
      <p>👃 It should smell slightly of chlorine. If not, add the same dose again and wait 15 more minutes.</p>
      <p>🧺 If it is cloudy, let it settle and pour it through a clean cloth or coffee filter first.</p></div>
    <div class="card"><p><b>Or boil it</b></p><p id="boil"></p>
      <p class="small muted">Boiling is the surest way to kill germs. Let it cool, and pour it between clean containers to improve the taste.</p></div>
    <p class="small muted">Never use scented, color-safe or "splash-less" bleach, or bleach with added cleaners. Bleach and boiling don't remove chemicals, fuel or salt; find another source if you suspect them.</p>
    <p><a href="#/guide/water-sanitation/making-water-safe.html">Read the full water guide</a></p>`;
  const render = () => {
    const gallons = s.unit === "gallons" ? s.amount : s.amount / 3.785;
    const drops = L.bleachDrops(gallons, s.strength, s.cloudy);
    view.querySelector("#drops").textContent = `${drops} drops`;
    const tsp = drops / 64;
    view.querySelector("#tsp").textContent = tsp >= 0.125 ? `About ${L.kitchenMeasure(tsp)} (${Math.max(1, Math.round(tsp * 4.93))} mL)` : "Use a clean dropper or the tip of a spoon.";
    view.querySelector("#boil").textContent = s.alt != null
      ? `Bring to a rolling boil for ${L.boilMinutes(s.alt) === 3 ? "3 minutes" : "1 minute"}. You are at about ${Math.round(s.alt * 3.28084)} ft${L.boilMinutes(s.alt) === 3 ? ", which needs the longer boil" : ""}.`
      : "Bring to a rolling boil for 1 minute, or 3 minutes above 5,000 ft (1,524 m).";
  };
  view.querySelector("#amount").addEventListener("input", (e) => { s.amount = Math.max(0, Number(e.target.value) || 0); render(); });
  view.querySelector("#cloudy").addEventListener("change", (e) => { s.cloudy = e.target.checked; render(); });
  for (const [id, key] of [["unit", "unit"], ["strength", "strength"]]) {
    view.querySelectorAll(`#${id} button`).forEach((b) => b.addEventListener("click", () => {
      s[key] = b.dataset.v;
      view.querySelectorAll(`#${id} button`).forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
      render();
    }));
  }
  render();
  const stop = watchLocation((p) => { if (p?.coords.altitude != null) { s.alt = p.coords.altitude; render(); } });
  return { unmount: stop };
}

// MARK: Medical ID

const FIELDS = [["name", "Name"], ["birthDate", "Date of birth"], ["bloodType", "Blood type"], ["allergies", "Allergies"],
  ["medications", "Medications"], ["conditions", "Medical conditions"], ["notes", "Notes for responders"]];

function medical(view) {
  const render = (editing) => {
    const info = store.get("medicalID", {});
    const contacts = info.contacts || [];
    const empty = !FIELDS.some(([k]) => info[k]) && !contacts.length;
    if (!editing) {
      view.innerHTML = empty
        ? `<div class="card"><p>Add the details a responder needs if you can't speak: allergies, medicines, conditions and who to call.</p>
           <div class="row-btns"><button class="btn primary" id="edit">Set up Medical ID</button></div></div>`
        : `<div class="card">${FIELDS.filter(([k]) => info[k]).map(([k, label]) => `<p><span class="muted small">${label}</span><br><b style="font-size:20px">${esc(info[k])}</b></p>`).join("")}
           ${contacts.map((c) => `<p><span class="muted small">Emergency contact${c.relation ? ` · ${esc(c.relation)}` : ""}</span><br><b>${esc(c.name)}</b> <a href="tel:${esc(c.phone)}">${esc(c.phone)}</a></p>`).join("")}
           <div class="row-btns"><button class="btn" id="edit">Edit</button></div></div>`;
      view.insertAdjacentHTML("beforeend", `<p class="small muted">Saved only in this browser on this device. Clearing the browser's site data deletes it.</p>`);
      view.querySelector("#edit").onclick = () => render(true);
      return;
    }
    view.innerHTML = `<form class="card" id="f">
      ${FIELDS.map(([k, label]) => `<label class="field"><span>${label}</span>${k === "notes" || k === "medications" || k === "conditions" || k === "allergies"
        ? `<textarea name="${k}" rows="2">${esc(info[k] || "")}</textarea>` : `<input name="${k}" value="${esc(info[k] || "")}">`}</label>`).join("")}
      <h2>Emergency contacts</h2>
      ${[0, 1].map((i) => `<label class="field"><span>Contact ${i + 1}: name</span><input name="c${i}name" value="${esc(contacts[i]?.name || "")}"></label>
        <label class="field"><span>Relation</span><input name="c${i}relation" value="${esc(contacts[i]?.relation || "")}"></label>
        <label class="field"><span>Phone</span><input name="c${i}phone" type="tel" value="${esc(contacts[i]?.phone || "")}"></label>`).join("")}
      <div class="row-btns"><button class="btn primary" type="submit">Save</button><button class="btn" type="button" id="cancel">Cancel</button>
      <button class="btn danger" type="button" id="clear">Delete Medical ID</button></div></form>`;
    const f = view.querySelector("#f");
    f.onsubmit = (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(f));
      const next = Object.fromEntries(FIELDS.map(([k]) => [k, (d[k] || "").trim()]));
      next.contacts = [0, 1].map((i) => ({ name: d[`c${i}name`].trim(), relation: d[`c${i}relation`].trim(), phone: d[`c${i}phone`].trim() })).filter((c) => c.name || c.phone);
      store.set("medicalID", next);
      render(false);
    };
    view.querySelector("#cancel").onclick = () => render(false);
    view.querySelector("#clear").onclick = () => { if (confirm("Delete your Medical ID from this browser?")) { store.set("medicalID", {}); render(false); } };
  };
  render(false);
  return null;
}

// MARK: Daylight

function daylight(view) {
  let fix = null;
  const render = () => {
    if (!fix) { view.innerHTML = `<p class="muted">Waiting for a GPS fix to work out sunrise and sunset where you are.</p>`; return; }
    const now = new Date(), { latitude: lat, longitude: lon } = fix.coords;
    const day = L.solarDay(now, lat, lon);
    const t = (d) => (d ? d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }) : "—");
    const dur = (ms) => { const m = Math.floor(ms / 60000); return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`; };
    let big;
    if (day.sunset && day.sunrise && day.sunset > now && day.sunrise < now) big = ["Daylight left", dur(day.sunset - now)];
    else if (day.civilDawn && day.civilDawn > now) big = ["First light in", dur(day.civilDawn - now)];
    else big = ["After dark", "Plan for night"];
    const p = L.moonPhase(now);
    view.innerHTML = `
      <div class="card"><p class="muted" style="margin:0">${big[0]}</p><p class="big">${big[1]}</p></div>
      <h2>Today</h2><ul class="list">
        ${[["First light", day.civilDawn], ["Sunrise", day.sunrise], ["Sunset", day.sunset], ["Last light", day.civilDusk]]
          .map(([k, v]) => `<li><div class="row"><span>${k}</span><b style="margin-left:auto">${t(v)}</b></div></li>`).join("")}</ul>
      <h2>Moon</h2><div class="card"><b>${L.moonName(p)}</b><p class="small muted">A bright moon (near full) gives enough light to move at night.</p></div>
      <p class="small muted">Quick check: hold your hand at arm's length with fingers flat under the sun. Each finger between the sun and the horizon is about 15 minutes of daylight.</p>`;
  };
  const stop = watchLocation((p) => { if (p) { fix = p; render(); } });
  render();
  const timer = setInterval(render, 30000);
  return { unmount() { stop(); clearInterval(timer); } };
}
