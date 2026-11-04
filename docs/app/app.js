// app.js — Pocket Nomad web app: routing, the header and offline support.
// SPDX-License-Identifier: Apache-2.0
//
// Routes (hash based, so it works from any static host and offline):
//   #/survive  #/guide/<file>  #/checklist/<id>  #/tool/<id>  #/map  #/mesh  #/more  #/more/<page>

import * as survive from "./js/survive.js";
import * as tools from "./js/tools.js";
import * as more from "./js/more.js";

const view = document.getElementById("view");
const title = document.getElementById("title");
const back = document.getElementById("back");
let current = null; // the mounted module, for unmount()

back.addEventListener("click", () => (history.length > 1 ? history.back() : (location.hash = "#/survive")));

async function route() {
  const [, top = "survive", ...rest] = (location.hash || "#/survive").slice(1).split("/");
  const arg = decodeURIComponent(rest.join("/"));
  current?.unmount?.();
  current = null;
  view.className = "";
  view.innerHTML = "";
  document.body.classList.remove("map-open");
  back.hidden = !["guide", "checklist", "tool"].includes(top) && !(top === "more" && arg);
  const tab = { guide: "survive", checklist: "survive", tool: "survive" }[top] || top;
  document.querySelectorAll("nav.tabs a").forEach((a) => {
    if (a.dataset.tab === tab) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  });
  const setTitle = (t) => { title.textContent = t; document.title = t === "Pocket Nomad" ? t : `${t} · Pocket Nomad`; };
  try {
    switch (top) {
      case "guide": await survive.guide(view, arg, setTitle); break;
      case "checklist": await survive.checklist(view, arg, setTitle); break;
      case "tool": current = await tools.open(view, arg, setTitle); break;
      case "map": {
        setTitle("Map");
        view.className = "full";
        document.body.classList.add("map-open");
        current = await import("./js/map.js");
        await current.mount(view);
        break;
      }
      case "mesh": {
        setTitle("Mesh");
        try {
          current = await import("./js/mesh/index.js");
          await current.mount(view);
        } catch (e) {
          view.innerHTML = `<div class="notice">The mesh radio part of the web app didn't load. ${e.message ?? ""}</div>`;
        }
        break;
      }
      case "more": await more.show(view, arg, setTitle); break;
      default: await survive.home(view, setTitle);
    }
  } catch (e) {
    view.innerHTML = `<div class="notice">Something went wrong loading this page. ${e.message ?? ""}</div>`;
  }
  if (top !== "map") window.scrollTo(0, 0);
}

window.addEventListener("hashchange", route);
route();

// Offline badge.
const net = document.getElementById("net");
const updateNet = () => { net.hidden = navigator.onLine; };
window.addEventListener("online", updateNet);
window.addEventListener("offline", updateNet);
updateNet();

// Keep everything on the device for use with no signal.
if ("serviceWorker" in navigator && location.protocol !== "file:") {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
