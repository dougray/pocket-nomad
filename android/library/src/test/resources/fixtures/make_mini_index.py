#!/usr/bin/env python3
"""Builds mini.nomadidx, a tiny prebuilt index for the :library tests.

It is made outside Android (with Python's own SQLite) in exactly the schema that
NomadKit's IndexBuilder and the nomad-index Mac tool write, so the tests prove a
.nomadidx built elsewhere opens and searches with the SQLite bundled in the app.
Run from this folder: python3 make_mini_index.py
"""
import os
import sqlite3

OUT = "mini.nomadidx"
PASSAGES = [
    ("Water purification", "Boil water for one minute to kill pathogens. At altitudes above 5,000 feet, boil for three minutes.", "guides/water.html"),
    ("Water purification", "Unscented household bleach: add 8 drops of 6% bleach per gallon of clear water and wait 30 minutes.", "guides/water.html"),
    ("Starting a fire", "Use a ferro rod and dry tinder. Build a teepee of kindling over the tinder bundle.", "guides/fire.html"),
    ("Snake bites", "Keep the bitten limb still and below the heart. Do not cut the wound or try to suck out venom.", "guides/snake.html"),
]

if os.path.exists(OUT):
    os.remove(OUT)
db = sqlite3.connect(OUT)
db.executescript("""
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE VIRTUAL TABLE passages USING fts5(title, body, path UNINDEXED, tokenize='porter unicode61');
""")
db.executemany("INSERT INTO passages(title, body, path) VALUES (?, ?, ?)", PASSAGES)
meta = {
    "schema_version": "1", "articles": "3", "passages": str(len(PASSAGES)),
    "built_at": "2026-01-01T00:00:00Z", "zim_uuid": "00112233445566778899aabbccddeeff",
}
db.executemany("INSERT INTO meta(key, value) VALUES (?, ?)", sorted(meta.items()))
db.execute("INSERT INTO passages(passages) VALUES('optimize')")
db.execute("INSERT INTO meta(key, value) VALUES ('complete', '1')")
db.commit()
db.execute("VACUUM")
db.close()
print(f"wrote {OUT} ({os.path.getsize(OUT)} bytes)")
