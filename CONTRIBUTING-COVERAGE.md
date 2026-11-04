# Contributing mesh coverage

The public coverage map at <https://www.pocketnomad.us/coverage/> shows where
Meshtastic and MeshCore radios have been heard. It is built from files that
Pocket Nomad users export and send in by hand. There is no server and nothing
is uploaded automatically.

**Contributions are public.** Your file, the pull request or issue, your GitHub
username and the hexagons on the map are visible to anyone, permanently (git
history keeps them even if the map is later rebuilt). Only send a file you are
happy to publish.

## 1. Export from the app

1. Turn on the coverage scanner and carry your radio around for a while.
2. Add at least one private zone (home, work). Sharing stays off until you do.
3. Check what would be published: **Map › Layers › Coverage › What sharing
   would publish**.
4. Export: **Mesh › Coverage map › Share**, and save the `.geojson` file.

The phone has already removed your private zones (and every hexagon touching
one), exact positions, node names and IDs, and dates finer than a month. Each
hexagon keeps only `count`, `best_snr`, `firmware`, `first_month`,
`last_month` and, if you used drive mode's ping test, `pings_reached`,
`pings_relayed` and `pings_none`. See [the privacy policy](docs/PRIVACY.md#sharing-coverage-opt-in).

## 2. Send it in, either way

**Pull request.** Fork the repository, add your file as
`docs/coverage/submissions/<anything>.geojson` (don't put your name or place in
the file name), and open a pull request. You can check it first:

```sh
python3 tools/coverage-merge.py --check your-file.geojson
```

**Issue.** No Git needed: open a
[coverage issue](https://github.com/dougray/pocket-nomad/issues/new?template=coverage.yml)
and attach the file. If GitHub won't accept `.geojson`, rename it to `.txt` or
zip it.

Please don't edit the file. The merge script rejects anything that isn't
exactly what the app exports.

## 3. For maintainers: merging

1. Save the file into `docs/coverage/submissions/` (from the PR, or from the
   issue attachment, renamed to `.geojson` with a neutral name such as
   `2026-11-issue-42.geojson`).
2. Run the merge. It validates every submission strictly and writes nothing
   if any file is rejected:

   ```sh
   python3 tools/coverage-merge.py
   ```

   Rejected: properties other than the five above, Point or other geometry,
   shapes that aren't hexagons on the app's 400 m grid, out-of-range counts or
   SNR, months that aren't `YYYY-MM` or are in the future, duplicate hexagons,
   extra top-level or feature fields (such as `id`).
3. Commit the submission and the updated `docs/coverage/data/coverage.geojson`
   together, and close the issue or merge the PR.

Same hexagons from different files combine: counts (including ping counts)
add, `best_snr` is the highest, firmware is the union, and the month range widens.

Tests: `python3 -m unittest discover -s tools/tests`
