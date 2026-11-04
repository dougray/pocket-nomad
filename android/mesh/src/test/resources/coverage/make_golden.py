#!/usr/bin/env python3
"""Writes golden.json for CoverageGoldenTest from tools/coverage-merge.py itself.

    python3 android/mesh/src/test/resources/coverage/make_golden.py

Points are made up and spread over the globe (both hemispheres, both sides
of the antimeridian, past the 85 degree Mercator clamp). For each one the
script records hex_of(), the cell's ring() and its corners(); it also builds a
small coverage document with to_geojson() using the app's generator name, and
checks that validate() accepts it. Rerun after changing the grid in either
place; the Kotlin test must then still match.
"""

import datetime
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[5]
spec = importlib.util.spec_from_file_location("coverage_merge", ROOT / "tools" / "coverage-merge.py")
cm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cm)

POINTS = [
    (0.0, 0.0), (0.001, -0.001), (10.1392, -20.3903), (-33.5, 160.25), (51.0001, -30.1234),
    (-45.678, -130.456), (64.2, 179.999), (-64.2, -179.999), (84.9, 12.5), (89.0, 45.0),
    (-89.0, -45.0), (12.27, -30.74), (12.3, -30.7), (-0.00001, 0.00001), (35.123456, 150.654321),
    (-12.3456, 93.4567), (47.5, -40.5), (1.2345, 90.8765), (-22.9, -20.2), (60.0, -10.0),
]

points = []
for lat, lon in POINTS:
    q, r = cm.hex_of(lat, lon)
    points.append({"lat": lat, "lon": lon, "q": q, "r": r, "ring": cm.ring(q, r),
                   "corners": [[lon_, lat_] for lon_, lat_ in cm.corners(q, r)]})

# A document with every property shape: with and without best_snr, with and
# without pings, one or two firmwares, a month range.
cells = {}
specs = [
    (2, (10.1392, -20.3903), 5, 7.5, {"meshtastic"}, "2026-01", "2026-03", (0, 0, 0)),
    (3, (-33.5, 160.25), 1, None, {"meshcore"}, "2026-02", "2026-02", (0, 0, 0)),
    (4, (12.27, -30.74), 0, None, {"meshtastic", "meshcore"}, "2026-05", "2026-06", (2, 1, 3)),
    (5, (64.2, 179.999), 12, -14.5, {"meshcore"}, "2025-12", "2026-04", (1, 0, 0)),
]
doc_cells = []
for _, (lat, lon), count, snr, fw, first, last, (reached, relayed, none) in specs:
    q, r = cm.hex_of(lat, lon)
    cells[(q, r)] = {"count": count, "best_snr": snr, "firmware": set(fw), "first_month": first,
                     "last_month": last, "pings_reached": reached, "pings_relayed": relayed, "pings_none": none}
    doc_cells.append({"q": q, "r": r, "count": count, "best_snr": snr, "firmware": sorted(fw),
                      "first_month": first, "last_month": last, "pings": [reached, relayed, none]})

doc = cm.to_geojson(cells, generator=cm.APP_GENERATOR)
cm.validate(json.loads(cm.dumps(doc)), today=datetime.date(2026, 11, 1))

out = {"cell_size": cm.CELL_SIZE, "points": points, "cells": doc_cells, "geojson": doc}
(HERE / "golden.json").write_text(json.dumps(out, indent=1, sort_keys=True) + "\n", encoding="utf-8")
print(f"wrote {HERE / 'golden.json'}: {len(points)} points, {len(doc_cells)} cells")
