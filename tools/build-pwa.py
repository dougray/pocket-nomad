#!/usr/bin/env python3
"""Builds the generated parts of the web app in docs/app/.

    python3 tools/build-pwa.py

- copies the guides from App/Resources/Guides (one source for iOS, Android and web),
- makes the app icons from the iOS app icon,
- writes docs/app/sw.js with a versioned list of every file to keep offline,
  including MapLibre GL JS (BSD-3-Clause) from a pinned CDN URL. It isn't
  committed here: its minified identifiers trip the secret and denylist checks.

Run it after changing a guide or anything in docs/app, then commit the result.
Standard library only (plus macOS `sips` for the icons).
"""

import hashlib
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "docs" / "app"
GUIDES_SRC = ROOT / "App" / "Resources" / "Guides"
ICON_SRC = ROOT / "App" / "Assets.xcassets" / "AppIcon.appiconset" / "icon-1024.png"
MAPLIBRE = "4.7.1"  # same version as the coverage map; keep in step with js/map.js
CDN = [
    f"https://unpkg.com/maplibre-gl@{MAPLIBRE}/dist/maplibre-gl.js",
    f"https://unpkg.com/maplibre-gl@{MAPLIBRE}/dist/maplibre-gl.css",
]
# Never cached for offline use: test and demo files, and the worker itself.
SKIP = ("sw.js", "sw.template.js", "/tests/", "demo.html", "demo-radio.js", "package.json", ".DS_Store")


def copy_guides():
    dest = APP / "guides"
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(GUIDES_SRC, dest)


def icons():
    out = APP / "icons"
    out.mkdir(exist_ok=True)
    for name, size in [("icon-192.png", 192), ("icon-512.png", 512), ("apple-touch-icon.png", 180)]:
        subprocess.run(["sips", "-z", str(size), str(size), str(ICON_SRC), "--out", str(out / name)],
                       check=True, capture_output=True)


def service_worker():
    files = sorted(p.relative_to(APP).as_posix() for p in APP.rglob("*")
                   if p.is_file() and not any(s in "/" + p.relative_to(APP).as_posix() for s in SKIP))
    digest = hashlib.sha256()
    for f in files:
        digest.update(f.encode())
        digest.update((APP / f).read_bytes())
    template = (APP / "sw.template.js").read_text()
    digest.update("".join(CDN).encode())
    sw = template.replace("__VERSION__", digest.hexdigest()[:12]).replace("__FILES__", json.dumps(["./"] + files + CDN, indent=1))
    (APP / "sw.js").write_text(sw)
    return len(files)


if __name__ == "__main__":
    copy_guides()
    icons()
    n = service_worker()
    print(f"docs/app ready: {n} files cached for offline use")
