#!/usr/bin/env python3
"""Validates contributed coverage files and merges them into the public map.

    python3 tools/coverage-merge.py                  validate docs/coverage/submissions/*.geojson
                                                     and write docs/coverage/data/coverage.geojson
    python3 tools/coverage-merge.py --check FILE...  validate only; write nothing

A submission must be exactly what the app exports (Mesh > Coverage map > share):
a GeoJSON FeatureCollection of closed hexagons on the app's global 400 m
Web Mercator grid, each with only count, best_snr (optional), firmware,
first_month and last_month. Anything else (extra properties, points, names,
IDs, polygons that are not grid hexagons) is rejected, and if any file is
rejected nothing is written. Standard library only.
"""

import argparse
import datetime
import json
import math
import re
import sys
from pathlib import Path

CELL_SIZE = 400.0  # must match CoverageShare.cellSize in App/Sources/Mesh/Coverage.swift
EARTH_RADIUS = 6_378_137.0
APP_GENERATOR = "Pocket Nomad coverage export"
MERGED_GENERATOR = "Pocket Nomad coverage map (merged)"
DEMO_GENERATOR = "Pocket Nomad coverage map DEMO (made-up data, not real coverage)"
FIRMWARES = ("meshcore", "meshtastic")
ALLOWED_PROPS = {"count", "best_snr", "firmware", "first_month", "last_month"}
REQUIRED_PROPS = ALLOWED_PROPS - {"best_snr"}  # the app omits best_snr when no SNR was heard
MAX_FILE_BYTES = 5 * 1024 * 1024
MAX_FEATURES = 20_000
MAX_COUNT = 1_000_000
SNR_RANGE = (-40.0, 40.0)  # LoRa SNR is roughly -20..+15 dB; anything outside this is junk
FIRST_YEAR = 2024
CORNER_TOLERANCE_DEG = 1e-5  # the app rounds to 1e-6; ~1 m slack
MONTH_RE = re.compile(r"^(\d{4})-(\d{2})$")

ROOT = Path(__file__).resolve().parent.parent
SUBMISSIONS = ROOT / "docs" / "coverage" / "submissions"
OUTPUT = ROOT / "docs" / "coverage" / "data" / "coverage.geojson"


class Invalid(ValueError):
    pass


# Geometry: a straight port of CoverageShare so cells line up with the app.

def _mercator(lat, lon):
    lat = max(-85.0, min(85.0, lat))
    return EARTH_RADIUS * math.radians(lon), EARTH_RADIUS * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def _unmercator(x, y):
    return (math.degrees(2 * math.atan(math.exp(y / EARTH_RADIUS)) - math.pi / 2), math.degrees(x / EARTH_RADIUS))


def hex_of(lat, lon):
    x, y = _mercator(lat, lon)
    fq = (math.sqrt(3) / 3 * x - y / 3) / CELL_SIZE
    fr = (2.0 / 3 * y) / CELL_SIZE
    fs = -fq - fr
    q, r, s = round(fq), round(fr), round(fs)
    dq, dr, ds = abs(q - fq), abs(r - fr), abs(s - fs)
    if dq > dr and dq > ds:
        q = -r - s
    elif dr > ds:
        r = -q - s
    return int(q), int(r)


def _center_xy(q, r):
    return CELL_SIZE * math.sqrt(3) * (q + r / 2), CELL_SIZE * 1.5 * r


def corners(q, r):
    """[(lon, lat)] x 6, in the app's order."""
    cx, cy = _center_xy(q, r)
    out = []
    for i in range(6):
        a = math.radians(60 * i - 30)
        lat, lon = _unmercator(cx + CELL_SIZE * math.cos(a), cy + CELL_SIZE * math.sin(a))
        out.append((lon, lat))
    return out


def ring(q, r):
    pts = [[round(lon, 6), round(lat, 6)] for lon, lat in corners(q, r)]
    return pts + [pts[0]]


# Validation

def _is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def _month(v, what, latest):
    if not isinstance(v, str):
        raise Invalid(f"{what} must be a string like 2026-11")
    m = MONTH_RE.match(v)
    if not m:
        raise Invalid(f"{what} must be YYYY-MM, got {v!r}")
    year, month = int(m.group(1)), int(m.group(2))
    if not 1 <= month <= 12:
        raise Invalid(f"{what} has month {month:02d}")
    if v < f"{FIRST_YEAR}-01" or v > latest:
        raise Invalid(f"{what} {v} is outside {FIRST_YEAR}-01..{latest}")
    return v


def _latest_month(today):
    # Allow next month too, for time zones around midnight on the 1st.
    y, m = today.year, today.month + 1
    if m == 13:
        y, m = y + 1, 1
    return f"{y:04d}-{m:02d}"


def _cell_from_geometry(geom, where):
    if not isinstance(geom, dict) or set(geom) != {"type", "coordinates"}:
        raise Invalid(f"{where}: geometry must have exactly type and coordinates")
    if geom["type"] != "Polygon":
        raise Invalid(f"{where}: geometry must be a Polygon hexagon, got {geom['type']!r}")
    rings = geom["coordinates"]
    if not isinstance(rings, list) or len(rings) != 1:
        raise Invalid(f"{where}: a hexagon has exactly one ring")
    pts = rings[0]
    if not isinstance(pts, list) or len(pts) != 7:
        raise Invalid(f"{where}: a closed hexagon has 7 positions")
    for p in pts:
        if not (isinstance(p, list) and len(p) == 2 and all(_is_number(c) for c in p)):
            raise Invalid(f"{where}: positions must be [longitude, latitude]")
        if not (-180 <= p[0] <= 180 and -85 <= p[1] <= 85):
            raise Invalid(f"{where}: position {p} is out of range")
    if pts[0] != pts[6]:
        raise Invalid(f"{where}: ring is not closed")
    six = pts[:6]
    lat = sum(p[1] for p in six) / 6
    lon = sum(p[0] for p in six) / 6
    q, r = hex_of(lat, lon)
    expected = corners(q, r)
    used = set()
    for p in six:
        match = next((i for i, (elon, elat) in enumerate(expected)
                      if i not in used and abs(p[0] - elon) <= CORNER_TOLERANCE_DEG
                      and abs(p[1] - elat) <= CORNER_TOLERANCE_DEG), None)
        if match is None:
            raise Invalid(f"{where}: polygon is not a hexagon on the app's {CELL_SIZE:.0f} m grid")
        used.add(match)
    return q, r


def _props(props, where, latest):
    if not isinstance(props, dict):
        raise Invalid(f"{where}: properties must be an object")
    extra = set(props) - ALLOWED_PROPS
    if extra:
        raise Invalid(f"{where}: properties not allowed: {', '.join(sorted(extra))}")
    missing = REQUIRED_PROPS - set(props)
    if missing:
        raise Invalid(f"{where}: missing properties: {', '.join(sorted(missing))}")
    count = props["count"]
    if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= MAX_COUNT:
        raise Invalid(f"{where}: count must be a whole number from 1 to {MAX_COUNT}")
    snr = props.get("best_snr")
    if snr is not None:
        if not _is_number(snr) or not SNR_RANGE[0] <= snr <= SNR_RANGE[1]:
            raise Invalid(f"{where}: best_snr must be a number from {SNR_RANGE[0]:g} to {SNR_RANGE[1]:g} dB")
        snr = round(float(snr), 1)
    fw = props["firmware"]
    if (not isinstance(fw, list) or not fw or len(set(map(str, fw))) != len(fw)
            or not all(isinstance(f, str) and f in FIRMWARES for f in fw)):
        raise Invalid(f"{where}: firmware must be a list of {' and/or '.join(FIRMWARES)}, no repeats")
    first = _month(props["first_month"], f"{where}: first_month", latest)
    last = _month(props["last_month"], f"{where}: last_month", latest)
    if first > last:
        raise Invalid(f"{where}: first_month is after last_month")
    return {"count": count, "best_snr": snr, "firmware": set(fw), "first_month": first, "last_month": last}


def validate(doc, today=None, allow_demo=False):
    """Returns {(q, r): cell} for a parsed submission, or raises Invalid."""
    latest = _latest_month(today or datetime.date.today())
    if not isinstance(doc, dict):
        raise Invalid("top level must be a GeoJSON FeatureCollection object")
    extra = set(doc) - {"type", "generator", "cell_size_mercator_m", "features"}
    if extra:
        raise Invalid(f"top-level keys not allowed: {', '.join(sorted(extra))}")
    if doc.get("type") != "FeatureCollection":
        raise Invalid("type must be FeatureCollection")
    generators = (APP_GENERATOR, MERGED_GENERATOR) + ((DEMO_GENERATOR,) if allow_demo else ())
    if "generator" in doc and doc["generator"] not in generators:
        raise Invalid("generator is not the Pocket Nomad export")
    if "cell_size_mercator_m" in doc and doc["cell_size_mercator_m"] != CELL_SIZE:
        raise Invalid(f"cell_size_mercator_m must be {CELL_SIZE:g}")
    feats = doc.get("features")
    if not isinstance(feats, list):
        raise Invalid("features must be a list")
    if len(feats) > MAX_FEATURES:
        raise Invalid(f"more than {MAX_FEATURES} hexagons")
    cells = {}
    for i, f in enumerate(feats):
        where = f"feature {i}"
        if not isinstance(f, dict) or set(f) != {"type", "geometry", "properties"} or f["type"] != "Feature":
            raise Invalid(f"{where}: must be a Feature with exactly type, geometry and properties")
        key = _cell_from_geometry(f["geometry"], where)
        if key in cells:
            raise Invalid(f"{where}: the same hexagon appears twice")
        cells[key] = _props(f["properties"], where, latest)
    return cells


def _reject_constant(name):
    raise Invalid(f"{name} is not valid JSON")


def _no_duplicate_keys(pairs):
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise Invalid("an object has the same key twice")
    return dict(pairs)


def load(path, today=None, allow_demo=False):
    path = Path(path)
    if path.stat().st_size > MAX_FILE_BYTES:
        raise Invalid(f"file is larger than {MAX_FILE_BYTES // (1024 * 1024)} MB")
    try:
        doc = json.loads(path.read_bytes().decode("utf-8"),
                         parse_constant=_reject_constant, object_pairs_hook=_no_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise Invalid(f"not valid UTF-8 JSON: {e}") from None
    return validate(doc, today, allow_demo)


# Merging

def merge(cell_maps):
    out = {}
    for cells in cell_maps:
        for key, c in cells.items():
            m = out.get(key)
            if m is None:
                out[key] = {**c, "firmware": set(c["firmware"])}
                continue
            m["count"] = min(MAX_COUNT, m["count"] + c["count"])
            snrs = [s for s in (m["best_snr"], c["best_snr"]) if s is not None]
            m["best_snr"] = max(snrs) if snrs else None
            m["firmware"] |= c["firmware"]
            m["first_month"] = min(m["first_month"], c["first_month"])
            m["last_month"] = max(m["last_month"], c["last_month"])
    return out


def to_geojson(cells, generator=MERGED_GENERATOR):
    features = []
    for (q, r) in sorted(cells):
        c = cells[(q, r)]
        props = {"count": c["count"], "firmware": sorted(c["firmware"]),
                 "first_month": c["first_month"], "last_month": c["last_month"]}
        if c["best_snr"] is not None:
            props["best_snr"] = c["best_snr"]
        features.append({"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [ring(q, r)]},
                         "properties": props})
    return {"type": "FeatureCollection", "generator": generator,
            "cell_size_mercator_m": CELL_SIZE, "features": features}


def dumps(doc):
    return json.dumps(doc, indent=1, sort_keys=True) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", nargs="+", metavar="FILE", help="validate these files only")
    ap.add_argument("--submissions", type=Path, default=SUBMISSIONS)
    ap.add_argument("--output", type=Path, default=OUTPUT)
    args = ap.parse_args(argv)

    files = [Path(p) for p in args.check] if args.check else sorted(args.submissions.glob("*.geojson"))
    good, bad = [], 0
    for path in files:
        try:
            cells = load(path)
        except (Invalid, OSError) as e:
            print(f"REJECTED {path}: {e}", file=sys.stderr)
            bad += 1
            continue
        print(f"ok       {path}: {len(cells)} hexagons")
        good.append(cells)
    if bad:
        print(f"{bad} file(s) rejected; nothing written.", file=sys.stderr)
        return 1
    if args.check:
        return 0
    merged = merge(good)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(dumps(to_geojson(merged)), encoding="utf-8")
    print(f"wrote    {args.output}: {len(merged)} hexagons from {len(good)} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
