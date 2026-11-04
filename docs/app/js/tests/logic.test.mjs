// Same vectors as AppTests/ToolMathTests.swift. Run: node --test docs/app/js/tests/
import test from "node:test";
import assert from "node:assert/strict";
import * as L from "../logic.js";

test("MGRS Washington Monument", () => {
  const m = L.mgrs(38.88952, -77.03524);
  assert.ok(m.startsWith("18S UJ 234"), m);
  assert.ok(m.includes(" 064"), m);
});
test("MGRS southern hemisphere", () => assert.ok(L.mgrs(-33.85678, 151.2153).startsWith("56H LH")));
test("MGRS out of range", () => assert.equal(L.mgrs(86, 0), null));
test("DMS", () => assert.equal(L.dms(30.5, -97.25), "30°30′00.0″N 97°15′00.0″W"));
test("cardinal", () => {
  assert.equal(L.cardinal(0), "N"); assert.equal(L.cardinal(359), "N"); assert.equal(L.cardinal(225), "SW");
});
test("Morse", () => {
  assert.equal(L.morseEncode("SOS"), "... --- ...");
  assert.equal(L.morseEncode("hi you"), ".... .. / -.-- --- ..-");
  const t = L.morseTimeline("SOS");
  assert.deepEqual(t.filter((x) => x.on).map((x) => x.units), [1, 1, 1, 3, 3, 3, 1, 1, 1]);
  assert.ok(!t.slice(0, -1).some((x) => !x.on && x.units > 1), "SOS is one run");
});
test("sunrise and sunset, Denver midsummer", () => {
  process.env.TZ = "America/Denver";
  const day = L.solarDay(new Date(2026, 5, 21, 12), 39.7392, -104.9903);
  const local = (d) => new Intl.DateTimeFormat("en-US", { timeZone: "America/Denver", hour: "numeric", minute: "numeric", hourCycle: "h23" })
    .formatToParts(d).reduce((o, p) => ({ ...o, [p.type]: Number(p.value) }), {});
  const r = local(day.sunrise), s = local(day.sunset);
  assert.equal(r.hour, 5); assert.ok(Math.abs(r.minute - 32) <= 3);
  assert.equal(s.hour, 20); assert.ok(Math.abs(s.minute - 31) <= 3);
});
test("polar night has no sunrise", () => assert.equal(L.solarDay(new Date(2026, 11, 21, 12), 78.2, 15.6).sunrise, null));
test("kitchen measure", () => {
  assert.equal(L.kitchenMeasure(0.125), "1/8 teaspoon");
  assert.equal(L.kitchenMeasure(0.5), "1/2 teaspoon");
  assert.equal(L.kitchenMeasure(1.5), "1 1/2 teaspoons");
  assert.equal(L.kitchenMeasure(0.98), "1 teaspoon");
  assert.equal(L.kitchenMeasure(7), "2 1/3 tablespoons");
});
test("bleach drops and boiling", () => {
  assert.equal(L.bleachDrops(1, "6%", false), 8);
  assert.equal(L.bleachDrops(1, "8.25%", true), 12);
  assert.equal(L.bleachDrops(0.25, "6%", false), 2);
  assert.equal(L.boilMinutes(1600), 3);
  assert.equal(L.boilMinutes(1400), 1);
  assert.equal(L.boilMinutes(null), 1);
});
test("guide search ranks urgent first", () => {
  const cats = [{ id: "a", guides: [
    { id: "x", title: "Nosebleeds", summary: "bleeding nose", keywords: [], urgent: false },
    { id: "y", title: "Severe bleeding", summary: "stop it", keywords: ["tourniquet"], urgent: true }] }];
  assert.deepEqual(L.searchGuides(cats, "bleeding").map((g) => g.id), ["y", "x"]);
  assert.deepEqual(L.searchGuides(cats, "tourniquet").map((g) => g.id), ["y"]);
  assert.deepEqual(L.searchGuides(cats, " "), []);
});
