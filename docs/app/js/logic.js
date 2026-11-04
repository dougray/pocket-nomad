// logic.js — Pocket Nomad web app
// SPDX-License-Identifier: Apache-2.0
//
// Pure functions ported from the iOS app (Shared/CoordinateFormat.swift,
// Shared/Morse.swift, Tools/DaylightView.swift, Tools/WaterCalculatorView.swift),
// so every platform gives the same numbers. No DOM here: tested with node --test.

// MARK: Coordinates

export function decimal(lat, lon) {
  return `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
}

export function dms(lat, lon) {
  const part = (v, pos, neg) => {
    const a = Math.abs(v);
    const d = Math.trunc(a), m = Math.trunc((a - d) * 60), s = (a - d - m / 60) * 3600;
    return `${d}°${String(m).padStart(2, "0")}′${s.toFixed(1).padStart(4, "0")}″${v >= 0 ? pos : neg}`;
  };
  return `${part(lat, "N", "S")} ${part(lon, "E", "W")}`;
}

/** MGRS (the grid search-and-rescue teams use), 1 m precision. Valid 80°S–84°N. */
export function mgrs(lat, lon) {
  if (lat < -80 || lat > 84) return null;
  let zone = Math.trunc((lon + 180) / 6) + 1;
  if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32;
  if (lat >= 72 && lat < 84) {
    if (lon >= 0 && lon < 9) zone = 31; else if (lon >= 9 && lon < 21) zone = 33;
    else if (lon >= 21 && lon < 33) zone = 35; else if (lon >= 33 && lon < 42) zone = 37;
  }
  const [e, n] = utm(lat, lon, zone);
  const bands = "CDEFGHJKLMNPQRSTUVWXX";
  const band = bands[Math.min(20, Math.max(0, Math.trunc((lat + 80) / 8)))];
  const colSets = ["ABCDEFGH", "JKLMNPQR", "STUVWXYZ"];
  const rowLetters = "ABCDEFGHJKLMNPQRSTUV";
  const col = colSets[(zone - 1) % 3][(Math.trunc(e / 100000) - 1) % 8];
  const rowOffset = zone % 2 === 0 ? 5 : 0;
  const row = rowLetters[(Math.trunc(n / 100000) % 20 + rowOffset) % 20];
  const pad = (v) => String(Math.trunc(v) % 100000).padStart(5, "0");
  return `${zone}${band} ${col}${row} ${pad(e)} ${pad(n)}`;
}

function utm(lat, lon, zone) {
  const a = 6378137.0, f = 1 / 298.257223563, k0 = 0.9996;
  const e2 = f * (2 - f), ep2 = e2 / (1 - e2);
  const phi = lat * Math.PI / 180;
  const lon0 = ((zone - 1) * 6 - 180 + 3) * Math.PI / 180;
  const N = a / Math.sqrt(1 - e2 * Math.sin(phi) ** 2);
  const T = Math.tan(phi) ** 2, C = ep2 * Math.cos(phi) ** 2;
  const A = Math.cos(phi) * (lon * Math.PI / 180 - lon0);
  const M = a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi
    - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * Math.sin(2 * phi)
    + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * Math.sin(4 * phi)
    - (35 * e2 ** 3 / 3072) * Math.sin(6 * phi));
  const easting = k0 * N * (A + (1 - T + C) * A ** 3 / 6 + (5 - 18 * T + T * T + 72 * C - 58 * ep2) * A ** 5 / 120) + 500000;
  let northing = k0 * (M + N * Math.tan(phi) * (A * A / 2 + (5 - T + 9 * C + 4 * C * C) * A ** 4 / 24
    + (61 - 58 * T + T * T + 600 * C - 330 * ep2) * A ** 6 / 720));
  if (lat < 0) northing += 10000000;
  return [easting, northing];
}

export function cardinal(degrees) {
  const names = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
  return names[Math.round(degrees / 22.5) % 16];
}

// MARK: Morse

export const MORSE = {
  A: ".-", B: "-...", C: "-.-.", D: "-..", E: ".", F: "..-.", G: "--.", H: "....",
  I: "..", J: ".---", K: "-.-", L: ".-..", M: "--", N: "-.", O: "---", P: ".--.",
  Q: "--.-", R: ".-.", S: "...", T: "-", U: "..-", V: "...-", W: ".--", X: "-..-",
  Y: "-.--", Z: "--..", 0: "-----", 1: ".----", 2: "..---", 3: "...--", 4: "....-",
  5: ".....", 6: "-....", 7: "--...", 8: "---..", 9: "----.", ".": ".-.-.-", ",": "--..--",
  "?": "..--..", "/": "-..-.", "-": "-....-", "@": ".--.-.", "=": "-...-",
};

export function morseEncode(text) {
  return text.toUpperCase().split(" ").filter(Boolean)
    .map((w) => [...w].map((c) => MORSE[c]).filter(Boolean).join(" ")).join(" / ");
}

/** On/off durations in Morse units: dot 1, dash 3, gaps 1 / 3 / 7. SOS is one run. */
export function morseTimeline(text) {
  const out = [];
  const upper = text.toUpperCase();
  const words = upper === "SOS" ? [["...---..."]]
    : upper.split(" ").filter(Boolean).map((w) => [...w].map((c) => MORSE[c]).filter(Boolean));
  words.forEach((letters, w) => {
    if (w > 0) out.push({ on: false, units: 7 });
    letters.forEach((code, l) => {
      if (l > 0) out.push({ on: false, units: 3 });
      [...code].forEach((symbol, s) => {
        if (s > 0) out.push({ on: false, units: 1 });
        out.push({ on: true, units: symbol === "-" ? 3 : 1 });
      });
    });
  });
  out.push({ on: false, units: 7 });
  return out;
}

// MARK: Sun and moon (NOAA solar equations)

/** Sunrise, sunset and civil twilight for the local calendar day of `date`. */
export function solarDay(date, lat, lon) {
  return {
    sunrise: solarEvent(date, lat, lon, 90.833, true),
    sunset: solarEvent(date, lat, lon, 90.833, false),
    civilDawn: solarEvent(date, lat, lon, 96, true),
    civilDusk: solarEvent(date, lat, lon, 96, false),
  };
}

function solarEvent(date, latDeg, lonDeg, zenith, rising) {
  const rad = Math.PI / 180;
  const y = date.getFullYear(), mo = date.getMonth(), d = date.getDate();
  const noonUTC = Date.UTC(y, mo, d, 12);
  const jd = noonUTC / 86400000 + 2440587.5 - lonDeg / 360;
  const t = (jd - 2451545) / 36525;
  const l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360;
  const m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
  const e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
  const center = Math.sin(m * rad) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * m * rad) * (0.019993 - 0.000101 * t) + Math.sin(3 * m * rad) * 0.000289;
  const omega = 125.04 - 1934.136 * t;
  const lambda = l0 + center - 0.00569 - 0.00478 * Math.sin(omega * rad);
  const eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
  const eps = eps0 + 0.00256 * Math.cos(omega * rad);
  const decl = Math.asin(Math.sin(eps * rad) * Math.sin(lambda * rad));
  const yy = Math.tan(eps * rad / 2) ** 2;
  const eqTime = 4 / rad * (yy * Math.sin(2 * l0 * rad) - 2 * e * Math.sin(m * rad) + 4 * e * yy * Math.sin(m * rad) * Math.cos(2 * l0 * rad)
    - 0.5 * yy * yy * Math.sin(4 * l0 * rad) - 1.25 * e * e * Math.sin(2 * m * rad));
  const lat = latDeg * rad;
  const cosH = (Math.cos(zenith * rad) - Math.sin(lat) * Math.sin(decl)) / (Math.cos(lat) * Math.cos(decl));
  if (cosH < -1 || cosH > 1) return null; // polar day or night
  const h = Math.acos(cosH) / rad;
  const minutes = 720 - 4 * lonDeg - eqTime + (rising ? -4 * h : 4 * h);
  return new Date(Date.UTC(y, mo, d) + minutes * 60000);
}

/** 0 = new moon, 0.5 = full. */
export function moonPhase(date) {
  const synodic = 29.530588853;
  const days = (date.getTime() / 1000 - 947182440) / 86400;
  const p = (days % synodic) / synodic;
  return p < 0 ? p + 1 : p;
}

export function moonName(p) {
  if (p < 0.03 || p >= 0.97) return "New moon";
  if (p < 0.22) return "Waxing crescent";
  if (p < 0.28) return "First quarter";
  if (p < 0.47) return "Waxing gibbous";
  if (p < 0.53) return "Full moon";
  if (p < 0.72) return "Waning gibbous";
  if (p < 0.78) return "Last quarter";
  return "Waning crescent";
}

// MARK: Water (EPA/CDC emergency disinfection)

/** Drops of plain bleach: 8 per gallon at 6%, 6 at 8.25%; double for cloudy, colored or cold water. */
export function bleachDrops(gallons, strength, cloudy) {
  const perGallon = strength === "8.25%" ? 6 : 8;
  return Math.ceil(gallons * perGallon * (cloudy ? 2 : 1) - 1e-9);
}

/** 0.125 -> "1/8 teaspoon", 1.5 -> "1 1/2 teaspoons", 7 -> "2 1/3 tablespoons". */
export function kitchenMeasure(tsp) {
  const useTbsp = tsp >= 3;
  const amount = useTbsp ? tsp / 3 : tsp;
  const fractions = [[0, ""], [0.125, "1/8"], [0.25, "1/4"], [1 / 3, "1/3"], [0.5, "1/2"], [2 / 3, "2/3"], [0.75, "3/4"], [1, ""]];
  let whole = Math.trunc(amount);
  const rest = amount - whole;
  const nearest = fractions.reduce((a, b) => (Math.abs(b[0] - rest) < Math.abs(a[0] - rest) ? b : a));
  if (nearest[0] === 1) whole += 1;
  const fraction = nearest[0] === 1 ? "" : nearest[1];
  const number = [whole > 0 ? String(whole) : "", fraction].filter(Boolean).join(" ");
  return `${number} ${useTbsp ? "tablespoon" : "teaspoon"}${amount > 1.01 ? "s" : ""}`;
}

/** Boiling time: 3 minutes above 5,000 ft (1,524 m, EPA), else 1. */
export function boilMinutes(altitudeMeters) {
  return altitudeMeters != null && altitudeMeters > 1524 ? 3 : 1;
}

// MARK: Search

/** Guides matching every word of the query in title, summary or keywords; urgent first. */
export function searchGuides(categories, query) {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  const out = [];
  for (const c of categories) {
    for (const g of c.guides) {
      const hay = [g.title, g.summary, ...(g.keywords || [])].join(" ").toLowerCase();
      if (words.every((w) => hay.includes(w))) {
        const inTitle = words.filter((w) => g.title.toLowerCase().includes(w)).length;
        out.push({ ...g, category: c.id, score: (g.urgent ? 10 : 0) + inTitle });
      }
    }
  }
  return out.sort((a, b) => b.score - a.score || a.title.localeCompare(b.title));
}
