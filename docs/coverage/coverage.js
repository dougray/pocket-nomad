// Pocket Nomad coverage map. Static: reads data/coverage.geojson (the merged
// contributions) or, when asked, data/demo.geojson (made-up data). No
// analytics, no cookies, no storage.
(function () {
  "use strict";

  var COLORS = { s0: "#6b6b6b", s1: "#d7301f", s2: "#fc8d59", s3: "#fee08b", s4: "#91cf60", s5: "#1a9850",
    p0: "#d7301f", p1: "#f6b92b", p2: "#1a9850" };
  var EMPTY = { type: "FeatureCollection", features: [] };
  var US = [[-125, 24], [-66, 50]];

  var state = { view: "signal", firmware: "all", from: null, to: null, demo: false, data: { real: null, demo: null } };
  var $ = function (id) { return document.getElementById(id); };
  var params = new URLSearchParams(location.search);
  state.demo = params.get("demo") === "1";
  $("demo").checked = state.demo;

  function setBanner(text, demo) {
    $("banner").hidden = !text;
    $("banner").classList.toggle("demo", !!demo);
    $("banner-text").textContent = text || "";
  }

  if (typeof maplibregl === "undefined") {
    setBanner("The map library didn't load. Check your connection; the data is still available as a download below.");
    $("stats").textContent = "";
    return;
  }

  var map = new maplibregl.Map({
    container: "map",
    style: "https://tiles.openfreemap.org/styles/dark",
    bounds: US,
    fitBoundsOptions: { padding: 20 },
    attributionControl: { compact: true },
    cooperativeGestures: window.matchMedia("(pointer: coarse)").matches,
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");

  function load(kind) {
    if (state.data[kind]) return Promise.resolve(state.data[kind]);
    var url = kind === "demo" ? "data/demo.geojson" : "data/coverage.geojson";
    return fetch(url, { credentials: "omit" })
      .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
      .then(function (doc) { state.data[kind] = clean(doc); return state.data[kind]; });
  }

  // Keep only well-formed features with the allowed properties.
  function clean(doc) {
    var months = /^\d{4}-(0[1-9]|1[0-2])$/;
    var out = (doc && Array.isArray(doc.features) ? doc.features : []).filter(function (f) {
      var p = f && f.properties, g = f && f.geometry;
      return p && g && g.type === "Polygon" && typeof p.count === "number" && Array.isArray(p.firmware) &&
        months.test(p.first_month) && months.test(p.last_month);
    }).map(function (f) {
      var p = f.properties;
      return {
        type: "Feature", geometry: f.geometry,
        properties: {
          count: p.count, best_snr: typeof p.best_snr === "number" ? p.best_snr : null,
          firmware: p.firmware.slice().sort(), first_month: p.first_month, last_month: p.last_month,
          fw: p.firmware.slice().sort().join(","),
          pings_reached: whole(p.pings_reached), pings_relayed: whole(p.pings_relayed), pings_none: whole(p.pings_none),
          // Best ping result: 2 answered, 1 repeated, 0 nothing, -1 no pings.
          ping: whole(p.pings_reached) ? 2 : whole(p.pings_relayed) ? 1 : whole(p.pings_none) ? 0 : -1,
        },
      };
    });
    return { type: "FeatureCollection", features: out };
  }

  function whole(v) { return typeof v === "number" && v >= 0 && Math.floor(v) === v ? v : 0; }

  function current() { return state.data[state.demo ? "demo" : "real"] || EMPTY; }

  function monthsOf(fc) {
    var lo = null, hi = null;
    fc.features.forEach(function (f) {
      var p = f.properties;
      if (!lo || p.first_month < lo) lo = p.first_month;
      if (!hi || p.last_month > hi) hi = p.last_month;
    });
    var list = [];
    if (!lo) return list;
    var y = +lo.slice(0, 4), m = +lo.slice(5, 7);
    for (var key = lo; key <= hi && list.length < 600;) {
      list.push(key);
      m += 1; if (m > 12) { m = 1; y += 1; }
      key = y + "-" + (m < 10 ? "0" : "") + m;
    }
    return list;
  }

  function label(month) {
    var d = new Date(Date.UTC(+month.slice(0, 4), +month.slice(5, 7) - 1, 1));
    return d.toLocaleDateString(undefined, { month: "short", year: "numeric", timeZone: "UTC" });
  }

  function fillMonths() {
    var list = monthsOf(current());
    ["from", "to"].forEach(function (id) {
      var sel = $(id);
      sel.textContent = "";
      list.forEach(function (m) {
        var o = document.createElement("option");
        o.value = m; o.textContent = label(m);
        sel.appendChild(o);
      });
      sel.disabled = list.length === 0;
    });
    state.from = list[0] || null;
    state.to = list[list.length - 1] || null;
    if (state.from) { $("from").value = state.from; $("to").value = state.to; }
  }

  function filtered() {
    var fw = state.firmware, from = state.from, to = state.to;
    return {
      type: "FeatureCollection",
      features: current().features.filter(function (f) {
        var p = f.properties, has = p.firmware;
        if (state.view === "pings" ? p.ping < 0 : p.count < 1) return false;
        if (fw === "meshtastic" && has.indexOf("meshtastic") < 0) return false;
        if (fw === "meshcore" && has.indexOf("meshcore") < 0) return false;
        if (fw === "both" && (has.indexOf("meshtastic") < 0 || has.indexOf("meshcore") < 0)) return false;
        if (from && p.last_month < from) return false;
        if (to && p.first_month > to) return false;
        return true;
      }),
    };
  }

  function render(fit) {
    var fc = filtered();
    var src = map.getSource("coverage");
    if (src) src.setData(fc);
    var pings = state.view === "pings";
    var heard = fc.features.reduce(function (n, f) {
      var p = f.properties;
      return n + (pings ? p.pings_reached + p.pings_relayed + p.pings_none : p.count);
    }, 0);
    var total = current().features.length;
    $("stats").innerHTML = "";
    var b1 = document.createElement("b"), b2 = document.createElement("b");
    b1.textContent = fc.features.length.toLocaleString();
    b2.textContent = heard.toLocaleString();
    $("stats").append(b1, " hexagon" + (fc.features.length === 1 ? "" : "s") + " · ", b2,
      (pings ? " ping" + (heard === 1 ? "" : "s") : " time" + (heard === 1 ? "" : "s") + " heard") + (fc.features.length < total ? " (filtered from " + total.toLocaleString() + ")" : ""));

    if (state.demo) setBanner("Demo data: made up to show how the map works. Not real coverage.", true);
    else if (total > 0 && pings && fc.features.length === 0 && state.firmware === "all" && !state.from && !state.to)
      setBanner("No drive-test pings have been contributed yet. Turn on drive mode in the app to add some.");
    else if (total === 0) setBanner("No coverage has been contributed yet. Be the first (see below), or turn on the demo to see how it looks.");
    else if (fc.features.length === 0) setBanner("Nothing matches these filters.");
    else setBanner("");

    if (fit && fc.features.length) {
      var b = new maplibregl.LngLatBounds();
      fc.features.forEach(function (f) { f.geometry.coordinates[0].forEach(function (c) { b.extend(c); }); });
      map.fitBounds(b, { padding: 40, maxZoom: 13, animate: fit !== "jump", duration: 600 });
    } else if (fit) {
      map.fitBounds(US, { padding: 20, animate: fit !== "jump", duration: 600 });
    }
  }

  function show(fit) {
    return load(state.demo ? "demo" : "real")
      .catch(function () {
        setBanner("Couldn't load the coverage data. Try reloading the page.");
        return EMPTY;
      })
      .then(function () { state.loaded = true; fillMonths(); render(fit); });
  }

  map.on("load", function () {
    map.addSource("coverage", { type: "geojson", data: EMPTY });
    var color = ["case", ["==", ["get", "best_snr"], null], COLORS.s0,
      ["step", ["get", "best_snr"], COLORS.s1, -10, COLORS.s2, -5, COLORS.s3, 0, COLORS.s4, 5, COLORS.s5]];
    map.addLayer({ id: "coverage-fill", type: "fill", source: "coverage",
      paint: { "fill-color": state.color = color, "fill-opacity": ["interpolate", ["linear"], ["zoom"], 8, 0.75, 14, 0.5] } });
    map.addLayer({ id: "coverage-line", type: "line", source: "coverage",
      paint: { "line-color": color, "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0, 13, 1] } });

    state.pingColor = ["match", ["get", "ping"], 2, COLORS.p2, 1, COLORS.p1, COLORS.p0];
    if (state.view === "pings") paint();

    map.on("click", "coverage-fill", function (e) {
      var p = e.features[0].properties;
      var fw = String(p.fw || "").split(",").map(function (f) { return f === "meshcore" ? "MeshCore" : f === "meshtastic" ? "Meshtastic" : f; });
      var snr = p.best_snr === null || p.best_snr === undefined || p.best_snr === "null" ? "no reading" : Number(p.best_snr).toFixed(1) + " dB";
      var rows = [["Best signal", snr], ["Times heard", Number(p.count).toLocaleString()], ["Firmware", fw.join(" + ")],
        ["Months", p.first_month === p.last_month ? label(p.first_month) : label(p.first_month) + " – " + label(p.last_month)]];
      var sent = Number(p.pings_reached) + Number(p.pings_relayed) + Number(p.pings_none);
      if (sent > 0) rows.splice(2, 0, ["Pings", p.pings_reached + " answered, " + p.pings_relayed + " repeated, " + p.pings_none + " nothing"]);
      if (Number(p.count) === 0) rows.splice(0, 2);
      var box = document.createElement("div"), dl = document.createElement("dl");
      box.className = "popup";
      rows.forEach(function (r) {
        var dt = document.createElement("dt"), dd = document.createElement("dd");
        dt.textContent = r[0]; dd.textContent = r[1];
        dl.append(dt, dd);
      });
      box.appendChild(dl);
      new maplibregl.Popup({ maxWidth: "280px" }).setLngLat(e.lngLat).setDOMContent(box).addTo(map);
    });
    map.on("mouseenter", "coverage-fill", function () { map.getCanvas().style.cursor = "pointer"; });
    map.on("mouseleave", "coverage-fill", function () { map.getCanvas().style.cursor = ""; });

    if (state.loaded) render("jump");
  });

  // Load the data straight away, so the counts and messages don't wait for map tiles.
  // No animation on first load: background tabs pause animations.
  show("jump");

  function paint() {
    if (!map.getLayer("coverage-fill")) return;
    var c = state.view === "pings" ? state.pingColor : state.color;
    map.setPaintProperty("coverage-fill", "fill-color", c);
    map.setPaintProperty("coverage-line", "line-color", c);
  }

  document.querySelectorAll("#view button").forEach(function (btn) {
    btn.addEventListener("click", function () {
      state.view = btn.getAttribute("data-view");
      document.querySelectorAll("#view button").forEach(function (b) { b.setAttribute("aria-pressed", String(b === btn)); });
      $("legend-pings").hidden = state.view !== "pings";
      $("legend-signal").hidden = state.view === "pings";
      paint();
      render(true);
    });
  });

  document.querySelectorAll("#firmware button").forEach(function (btn) {
    btn.addEventListener("click", function () {
      state.firmware = btn.getAttribute("data-fw");
      document.querySelectorAll("#firmware button").forEach(function (b) { b.setAttribute("aria-pressed", String(b === btn)); });
      render(false);
    });
  });
  $("from").addEventListener("change", function () {
    state.from = this.value;
    if (state.to && state.from > state.to) { state.to = state.from; $("to").value = state.to; }
    render(false);
  });
  $("to").addEventListener("change", function () {
    state.to = this.value;
    if (state.from && state.to < state.from) { state.from = state.to; $("from").value = state.from; }
    render(false);
  });
  $("demo").addEventListener("change", function () {
    state.demo = this.checked;
    show(true);
  });
})();
