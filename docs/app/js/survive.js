// survive.js — Pocket Nomad web app: guides, search and checklists.
// SPDX-License-Identifier: Apache-2.0

import { esc, store, guideIndex } from "./ui.js";
import { searchGuides } from "./logic.js";
import { TOOLS } from "./tools.js";

export async function home(view, setTitle) {
  setTitle("Survive");
  const { categories } = await guideIndex();
  const urgent = categories.flatMap((c) => c.guides).filter((g) => g.urgent);
  view.innerHTML = `
    <input class="search" type="search" placeholder="Search guides: bleeding, water, snake…" aria-label="Search guides" id="q">
    <div id="results"></div>
    <div id="home">
      <h2>Emergency</h2>
      <div class="chips">${urgent.map((g) => `<a class="chip" href="#/guide/${esc(g.file)}">${esc(g.title)}</a>`).join("")}</div>
      <h2>Tools</h2>
      <div class="grid">${TOOLS.map((t) => `<a class="tile" href="#/tool/${t.id}"><span aria-hidden="true">${t.icon}</span><b>${esc(t.title)}</b></a>`).join("")}
        <a class="tile" href="#/checklist/"><span aria-hidden="true">☑︎</span><b>Checklists</b></a></div>
      ${categories.map((c) => `
        <h2>${esc(c.title)}</h2>
        <ul class="list">${c.guides.map((g) => `<li><a href="#/guide/${esc(g.file)}"><span>${esc(g.title)}<span class="sub">${esc(g.summary)}</span></span></a></li>`).join("")}</ul>`).join("")}
      <p class="small muted" style="margin-top:24px">These guides follow published guidance and list their sources, but they don't replace training. Call your local emergency number whenever you can.</p>
    </div>`;
  const q = view.querySelector("#q"), results = view.querySelector("#results"), homeEl = view.querySelector("#home");
  q.value = store.get("search", "");
  const run = () => {
    store.set("search", q.value);
    const hits = searchGuides(categories, q.value);
    homeEl.hidden = !!q.value.trim();
    results.innerHTML = !q.value.trim() ? "" : hits.length
      ? `<h2>${hits.length} guide${hits.length === 1 ? "" : "s"}</h2><ul class="list">${hits.map((g) => `<li><a href="#/guide/${esc(g.file)}"><span>${esc(g.title)}<span class="sub">${esc(g.summary)}</span></span></a></li>`).join("")}</ul>`
      : `<p class="muted">No guide matches “${esc(q.value)}”. Try one word, like “burn” or “water”.</p>`;
  };
  q.addEventListener("input", run);
  run();
}

export async function guide(view, file, setTitle) {
  const { categories } = await guideIndex();
  const g = categories.flatMap((c) => c.guides).find((x) => x.file === file);
  if (!g) { setTitle("Guide"); view.innerHTML = `<p class="muted">This guide is missing.</p>`; return; }
  setTitle(g.title);
  const html = await fetch(`guides/${g.file}`).then((r) => r.text());
  // Guides are our own bundled HTML (the same files the iOS app shows).
  view.innerHTML = `<article class="guide"><h1 style="font-size:28px;margin:4px 0 16px">${esc(g.title)}</h1>${html}</article>`;
  // Guide links point at other guide files; keep them inside the app.
  view.querySelectorAll("a[href]").forEach((a) => {
    const href = a.getAttribute("href");
    if (/^[\w-]+\/[\w-]+\.html$/.test(href)) a.setAttribute("href", `#/guide/${href}`);
    else if (/^https?:/.test(href)) { a.target = "_blank"; a.rel = "noopener"; }
  });
}

export async function checklist(view, id, setTitle) {
  const { checklists } = await guideIndex();
  if (!id) {
    setTitle("Checklists");
    view.innerHTML = `<ul class="list">${checklists.map((c) => {
      const ticks = store.get(`check.${c.id}`, []);
      return `<li><a href="#/checklist/${esc(c.id)}"><span>${esc(c.title)}<span class="sub">${ticks.length} of ${c.items.length} done</span></span></a></li>`;
    }).join("")}</ul>`;
    return;
  }
  const list = checklists.find((c) => c.id === id);
  if (!list) { view.innerHTML = `<p class="muted">Checklist not found.</p>`; return; }
  setTitle(list.title);
  const render = () => {
    const ticks = new Set(store.get(`check.${id}`, []));
    view.innerHTML = `
      <p class="muted">${ticks.size} of ${list.items.length} done</p>
      <ul class="list">${list.items.map((item, i) => `
        <li><label class="check ${ticks.has(i) ? "done" : ""}"><input type="checkbox" data-i="${i}" ${ticks.has(i) ? "checked" : ""}><span>${esc(item)}</span></label></li>`).join("")}</ul>
      <div class="row-btns"><button class="btn" id="reset">Uncheck everything</button></div>`;
    view.querySelectorAll("input[type=checkbox]").forEach((box) => box.addEventListener("change", () => {
      const i = Number(box.dataset.i);
      if (box.checked) ticks.add(i); else ticks.delete(i);
      store.set(`check.${id}`, [...ticks]);
      render();
    }));
    view.querySelector("#reset").addEventListener("click", () => { store.set(`check.${id}`, []); render(); });
  };
  render();
}
