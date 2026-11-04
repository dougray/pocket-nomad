"""Run from the repo root: python3 -m unittest discover -s tools/tests"""

import copy
import datetime
import importlib.util
import json
import math
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("coverage_merge", Path(__file__).resolve().parents[1] / "coverage-merge.py")
cm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cm)

TODAY = datetime.date(2026, 11, 4)
DENVER = cm.hex_of(39.80, -104.99)


def feature(q, r, **props):
    p = {"count": 2, "best_snr": 2.0, "firmware": ["meshcore", "meshtastic"],
         "first_month": "2026-09", "last_month": "2026-10"}
    p.update(props)
    p = {k: v for k, v in p.items() if v is not ...}
    return {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [cm.ring(q, r)]}, "properties": p}


def export(*features):
    """Shaped exactly like CoverageShare.geoJSON in the app."""
    return {"type": "FeatureCollection", "generator": cm.APP_GENERATOR, "cell_size_mercator_m": 400.0,
            "features": list(features)}


class GridTests(unittest.TestCase):
    def test_hex_round_trips_and_matches_the_app_cell_size(self):
        q, r = cm.hex_of(30.27, -97.74)
        lon, lat = sum(c[0] for c in cm.corners(q, r)) / 6, sum(c[1] for c in cm.corners(q, r)) / 6
        self.assertEqual(cm.hex_of(lat, lon), (q, r))
        (lon0, lat0), (lon3, lat3) = cm.corners(q, r)[0], cm.corners(q, r)[3]
        p1, p2 = math.radians(lat0), math.radians(lat3)
        a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon3 - lon0) / 2) ** 2
        corner_to_corner = 6_371_000 * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
        # 800 Mercator metres corner to corner is about 690 m on the ground in Texas.
        self.assertAlmostEqual(corner_to_corner, 690, delta=10)

    def test_nearby_points_share_a_cell(self):
        self.assertEqual(cm.hex_of(39.7392, -104.9903), cm.hex_of(39.73925, -104.99035))
        self.assertNotEqual(cm.hex_of(39.7392, -104.9903), cm.hex_of(39.76, -104.99))


class ValidateTests(unittest.TestCase):
    def ok(self, doc):
        return cm.validate(doc, TODAY)

    def bad(self, doc, needle):
        with self.assertRaises(cm.Invalid) as e:
            cm.validate(doc, TODAY)
        self.assertIn(needle, str(e.exception))

    def test_accepts_an_app_export(self):
        cells = self.ok(export(feature(*DENVER)))
        self.assertEqual(cells[DENVER]["count"], 2)
        self.assertEqual(cells[DENVER]["firmware"], {"meshcore", "meshtastic"})

    def test_accepts_missing_best_snr_like_the_app(self):
        cells = self.ok(export(feature(*DENVER, best_snr=...)))
        self.assertIsNone(cells[DENVER]["best_snr"])

    def test_accepts_empty_export(self):
        self.assertEqual(self.ok(export()), {})

    def test_rejects_extra_properties(self):
        for key in ("name", "node", "id", "long_name", "hops", "date"):
            with self.subTest(key=key):
                self.bad(export(feature(*DENVER, **{key: "x"})), "not allowed")

    def test_rejects_feature_level_extras(self):
        f = feature(*DENVER)
        f["id"] = "!a1b2c3d4"
        self.bad(export(f), "exactly type, geometry and properties")

    def test_rejects_top_level_extras(self):
        doc = export(feature(*DENVER))
        doc["contributor"] = "me"
        self.bad(doc, "top-level keys")

    def test_rejects_points_and_other_geometry(self):
        f = feature(*DENVER)
        f["geometry"] = {"type": "Point", "coordinates": [-104.99, 39.80]}
        self.bad(export(f), "Polygon")
        f["geometry"] = {"type": "MultiPolygon", "coordinates": [[cm.ring(*DENVER)]]}
        self.bad(export(f), "Polygon")

    def test_rejects_polygons_off_the_grid(self):
        f = feature(*DENVER)
        f["geometry"]["coordinates"][0] = [[x + 0.001, y] for x, y in f["geometry"]["coordinates"][0]]
        self.bad(export(f), "grid")

    def test_rejects_a_tiny_polygon_pinpointing_a_house(self):
        lon, lat = -104.99, 39.80
        tiny = [[lon + 0.0001 * dx, lat + 0.0001 * dy] for dx, dy in ((1, 0), (.5, 1), (-.5, 1), (-1, 0), (-.5, -1), (.5, -1))]
        f = feature(*DENVER)
        f["geometry"]["coordinates"] = [tiny + [tiny[0]]]
        self.bad(export(f), "grid")

    def test_rejects_open_or_short_rings_and_holes(self):
        f = feature(*DENVER)
        f["geometry"]["coordinates"][0][6] = f["geometry"]["coordinates"][0][1]
        self.bad(export(f), "not closed")
        f = feature(*DENVER)
        f["geometry"]["coordinates"][0] = f["geometry"]["coordinates"][0][:5]
        self.bad(export(f), "7 positions")
        f = feature(*DENVER)
        f["geometry"]["coordinates"].append(cm.ring(*DENVER))
        self.bad(export(f), "one ring")

    def test_rejects_extra_coordinate_dimensions(self):
        f = feature(*DENVER)
        f["geometry"]["coordinates"][0] = [p + [1609.0] for p in f["geometry"]["coordinates"][0]]
        self.bad(export(f), "[longitude, latitude]")

    def test_property_types_and_ranges(self):
        cases = [
            (dict(count=0), "count"), (dict(count=2.5), "count"), (dict(count=True), "count"), (dict(count="2"), "count"),
            (dict(best_snr=99), "best_snr"), (dict(best_snr="9"), "best_snr"), (dict(best_snr=True), "best_snr"),
            (dict(firmware=[]), "firmware"), (dict(firmware="meshtastic"), "firmware"),
            (dict(firmware=["meshtastic", "meshtastic"]), "firmware"), (dict(firmware=["lorawan"]), "firmware"),
            (dict(first_month="2026-9"), "YYYY-MM"), (dict(first_month="2026-13"), "month 13"),
            (dict(first_month="2026-09-14"), "YYYY-MM"), (dict(last_month=202610), "string"),
            (dict(first_month="2026-11", last_month="2026-10"), "after"),
            (dict(last_month="2027-06"), "outside"), (dict(first_month="1999-01"), "outside"),
            (dict(count=...), "missing"), (dict(firmware=...), "missing"),
        ]
        for props, needle in cases:
            with self.subTest(props=props):
                self.bad(export(feature(*DENVER, **props)), needle)

    def test_rejects_duplicate_cells_and_wrong_header(self):
        self.bad(export(feature(*DENVER), feature(*DENVER)), "twice")
        doc = export()
        doc["type"] = "Feature"
        self.bad(doc, "FeatureCollection")
        doc = export()
        doc["cell_size_mercator_m"] = 100
        self.bad(doc, "cell_size")
        doc = export()
        doc["generator"] = "Someone's tracker"
        self.bad(doc, "generator")
        self.bad([], "FeatureCollection")


class PingTests(unittest.TestCase):
    def test_accepts_ping_only_and_mixed_cells(self):
        cells = cm.validate(export(feature(*DENVER, count=0, best_snr=..., pings_reached=1, pings_relayed=0, pings_none=2)), TODAY)
        self.assertEqual((cells[DENVER]["count"], cells[DENVER]["pings_none"]), (0, 2))
        cells = cm.validate(export(feature(*DENVER, pings_reached=0, pings_relayed=3, pings_none=0)), TODAY)
        self.assertEqual(cells[DENVER]["pings_relayed"], 3)

    def test_rejects_bad_ping_fields(self):
        for props, needle in [
            (dict(pings_reached=1), "together"),
            (dict(pings_reached=-1, pings_relayed=0, pings_none=0), "ping counts"),
            (dict(pings_reached=1.5, pings_relayed=0, pings_none=0), "ping counts"),
            (dict(pings_reached=True, pings_relayed=0, pings_none=0), "ping counts"),
            (dict(count=0, pings_reached=0, pings_relayed=0, pings_none=0), "recording or a ping"),
        ]:
            with self.subTest(props=props):
                with self.assertRaisesRegex(cm.Invalid, needle):
                    cm.validate(export(feature(*DENVER, **props)), TODAY)

    def test_pings_add_up_and_only_appear_when_present(self):
        other = (DENVER[0] + 1, DENVER[1])
        a = cm.validate(export(feature(*DENVER, pings_reached=1, pings_relayed=1, pings_none=0)), TODAY)
        b = cm.validate(export(feature(*DENVER, count=0, best_snr=..., pings_reached=2, pings_relayed=0, pings_none=1),
                               feature(*other)), TODAY)
        out = cm.to_geojson(cm.merge([a, b]))
        props = {tuple(f["geometry"]["coordinates"][0][0]): f["properties"] for f in out["features"]}
        here = props[tuple(cm.ring(*DENVER)[0])]
        self.assertEqual((here["count"], here["pings_reached"], here["pings_relayed"], here["pings_none"]), (2, 3, 1, 1))
        self.assertNotIn("pings_reached", props[tuple(cm.ring(*other)[0])])
        cm.validate(json.loads(cm.dumps(out)), TODAY)


class MergeTests(unittest.TestCase):
    def test_same_cells_combine(self):
        a = cm.validate(export(feature(*DENVER, count=3, best_snr=-4.5, firmware=["meshtastic"],
                                       first_month="2026-08", last_month="2026-09")), TODAY)
        b = cm.validate(export(feature(*DENVER, count=5, best_snr=6.25, firmware=["meshcore"],
                                       first_month="2026-09", last_month="2026-11")), TODAY)
        c = cm.validate(export(feature(*DENVER, count=1, best_snr=..., firmware=["meshtastic"])), TODAY)
        merged = cm.merge([a, b, c])
        cell = merged[DENVER]
        self.assertEqual(cell["count"], 9)
        self.assertEqual(cell["best_snr"], 6.2)  # rounded to 0.1 like the app
        self.assertEqual(cell["firmware"], {"meshcore", "meshtastic"})
        self.assertEqual((cell["first_month"], cell["last_month"]), ("2026-08", "2026-11"))
        self.assertEqual(a[DENVER]["count"], 3, "inputs are not modified")

    def test_different_cells_stay_apart_and_output_is_valid_and_stable(self):
        other = (DENVER[0] + 1, DENVER[1])
        merged = cm.merge([cm.validate(export(feature(*DENVER)), TODAY),
                           cm.validate(export(feature(*other, best_snr=...)), TODAY)])
        doc = cm.to_geojson(merged)
        self.assertEqual(len(doc["features"]), 2)
        self.assertEqual(cm.validate(json.loads(cm.dumps(doc)), TODAY).keys(), {DENVER, other})
        self.assertEqual(cm.dumps(doc), cm.dumps(cm.to_geojson(copy.deepcopy(merged))))
        for f in doc["features"]:
            self.assertLessEqual(set(f["properties"]), cm.ALLOWED_PROPS)


class CommandLineTests(unittest.TestCase):
    def test_writes_merged_file_and_refuses_when_any_file_is_bad(self):
        with tempfile.TemporaryDirectory() as tmp:
            subs, out = Path(tmp, "subs"), Path(tmp, "data", "coverage.geojson")
            subs.mkdir()
            Path(subs, "a.geojson").write_text(json.dumps(export(feature(*DENVER))))
            Path(subs, "b.geojson").write_text(json.dumps(export(feature(*DENVER, count=4))))
            Path(subs, "notes.txt").write_text("ignored")
            self.assertEqual(cm.main(["--submissions", str(subs), "--output", str(out)]), 0)
            doc = json.loads(out.read_text())
            self.assertEqual(doc["features"][0]["properties"]["count"], 6)

            before = out.read_text()
            Path(subs, "c.geojson").write_text(json.dumps(export(feature(*DENVER, name="Bob's base"))))
            self.assertEqual(cm.main(["--submissions", str(subs), "--output", str(out)]), 1)
            self.assertEqual(out.read_text(), before, "nothing written when a file is rejected")

    def test_rejects_non_json_nan_and_duplicate_keys(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in (("a", "not json"), ("b", '{"type": NaN}'),
                               ("c", '{"type": "FeatureCollection", "type": "x", "features": []}')):
                p = Path(tmp, name + ".geojson")
                p.write_text(text)
                with self.subTest(name=name), self.assertRaises(cm.Invalid):
                    cm.load(p, TODAY)

    def test_committed_map_and_demo_are_valid(self):
        root = Path(__file__).resolve().parents[2] / "docs" / "coverage" / "data"
        cm.load(root / "coverage.geojson")
        self.assertTrue(cm.load(root / "demo.geojson", allow_demo=True), "demo has hexagons")
        with self.assertRaises(cm.Invalid, msg="demo data can never be merged as a submission"):
            cm.load(root / "demo.geojson")


if __name__ == "__main__":
    unittest.main()
