# Situation layers: build plan

Seven features inspired by live "world dashboard" sites, reshaped for Pocket Nomad's rules:
open, free, keyless data; nothing kept on a server; useful with no signal.

**The shape that makes them fit:** every online feature is a *snapshot*. While the phone has
signal the user taps "Update" (or the app refreshes when opened, if they allow it), the data is
saved on the phone, and every screen shows its age ("updated 3 h ago"). With no signal the last
snapshot stays readable. Nothing here needs an account or a key.

## The features

| # | Feature | Source | Works offline? |
|---|---------|--------|----------------|
| 1 | Earthquakes near you | USGS GeoJSON feeds | Last snapshot |
| 2 | Weather alerts | US National Weather Service API | Last snapshot (US only) |
| 3 | Wind and rain forecast | Open-Meteo | Last snapshot (7 days ahead) |
| 4 | Space weather | NOAA SWPC JSON | Last snapshot |
| 5 | Weather radio companion (new) | NOAA Weather Radio station list | Fully, once saved |
| 6 | Draw and measure on the map | none | Fully |
| 7 | Satellite passes | CelesTrak orbit data | Fully for ~2 weeks per download |
| 8 | Radio line-of-sight | Open elevation tiles | Fully, for downloaded areas |

Feature 5 was added because many of the handheld ham radios that pair with the Ham tab also
receive NOAA Weather Radio: the app can tell the user which of the seven channels to tune.

## Shared groundwork (build first)

**Snapshot store** (`Conditions` module on both platforms: `App/Sources/Conditions`,
`android/conditions`):
- One small JSON file per source in app storage (no-backup on Android, excluded from backup on iOS).
- Each record: fetched-at time, the area it covers, the parsed items.
- `refresh(source)` with a per-source minimum interval so repeated taps don't hammer a free service.
- A descriptive User-Agent (app name + site), which the NWS API requires. No personal details.
- Failure never clears the old snapshot.

**Location privacy rule:** the app's promise is that location never leaves the phone unless the
user sends it. These features ask services about a place, so:
- USGS and SWPC: download the regional/global feed and filter on the phone. No location sent.
- NWS and Open-Meteo need a point. Send it rounded to 0.1 degree (about 11 km / 7 miles), never
  the exact fix, and say so in the Privacy summary screen and the store privacy text.
- Refresh only on a tap, or on app open if the user turns on "Update when I open the app" (off by default).

**Where it lives (five-tab limit):**
- A **Conditions** card at the top of the Survive tab: worst current alert, nearest recent quake,
  next 24 h wind/rain, Kp. Tapping it opens the Conditions screen (four sections + Update button).
- Map › Layers gets "Earthquakes" and "Weather alert areas".
- Satellites and the weather radio card live in the Ham side of the Radio tab.
- Draw/measure and line-of-sight are map tools.

## Phase 1: Conditions (features 1-5)

Smallest work, biggest everyday value. Do it as one release.

1. **Earthquakes.** `earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson`
   (M2.5+, past 7 days, ~100-400 KB) plus `significant_month.geojson`. Parse, keep those within a
   radius the user picks (default 500 km) and all "significant". List with magnitude, distance,
   depth, age; map layer with circles sized by magnitude. Tests: parser on a saved fixture,
   radius filter, age text.
2. **Weather alerts.** `api.weather.gov/alerts/active?point=<lat>,<lon>` (rounded). Keep event,
   severity, headline, description, instruction, onset/expires, and the polygon when present.
   Sort by severity; expired alerts greyed out, not hidden (offline the user must see they are
   stale). Outside the US: show "US only for now" instead of an error. Tests: fixture parse,
   expiry logic, severity order.
3. **Wind and rain.** `api.open-meteo.com/v1/forecast` hourly: temperature, precipitation,
   precipitation probability, wind speed/gusts/direction, weather code; 7 days. Show next 24 h as
   a strip and 7 days as rows. Units follow the phone's region. Attribution line (CC BY 4.0).
   **Check before release:** Open-Meteo's free tier is for non-commercial use; confirm the app
   (free, open source, no ads) qualifies, and keep the source behind one interface so it can be
   swapped for NWS gridpoint forecasts (US, public domain) if needed.
4. **Space weather.** `services.swpc.noaa.gov/products/noaa-planetary-k-index.json` and
   `.../noaa-scales.json` (R/S/G scales). Show Kp now and the 3-day outlook with plain words:
   "HF radio: normal / degraded / blackout likely", "GPS: normal / less accurate". Tests: scale
   mapping.
5. **Weather radio companion.** Bundle the seven NWR channels (162.400-162.550 MHz) in the Ham
   reference page now (static, zero risk). Then: download NOAA's public transmitter list once,
   save it, and show the nearest transmitters with frequency, distance and call sign, plus the
   user's county SAME code for radios with alert programming. Works fully offline after the one
   download. **Verify first:** the exact URL/format of NOAA's station list (CSV/JSON) and its terms.

Done when: both platforms show the Conditions card and screen, the two map layers, snapshot ages,
and everything is readable in airplane mode after one update.

## Phase 2: Draw and measure (feature 6)

Fully offline, no data source.
- **Measure:** tap points on the map; running distance per leg and total, bearing of the last leg.
- **Draw:** lines and areas saved with a name and colour next to the existing pins; area in
  acres/hectares; list, rename, delete.
- **Share:** export as GeoJSON/GPX through the share sheet, and "send to mesh" as a short text
  (name + centre + size) since radios can't carry shapes.
- Storage alongside `MapPin` (iOS) / pins (Android). Tests: geodesic distance and polygon area
  against known values, GeoJSON round trip.

## Phase 3: Satellite passes (feature 7)

- Download `celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle` and `GROUP=stations`
  (a few KB). Orbit data is good for roughly two weeks; show its age and warn when stale.
- Port SGP4 (the standard orbit model; public-domain reference code) to Swift and Kotlin, tested
  against the published verification vectors.
- Pass prediction for the phone's location: rise time, max elevation, set time, direction to look.
  Next 48 h, filtered to passes above 10 degrees.
- Ham screen: "Next ISS pass" row (the space station's packet digipeater on 145.825 MHz, path
  ARISS, already in the reference page) and a list of amateur satellites. Optional local
  notification 5 minutes before a chosen pass.
- Biggest piece of pure maths in this plan; no network risk. Tests: SGP4 vectors, a known ISS
  pass, stale-data warning.

## Phase 4: Radio line-of-sight (feature 8)

- Elevation from open "terrarium" terrain tiles (AWS Open Data, `elevation-tiles-prod`), where
  each PNG pixel encodes height. Download for the same area the user downloads streets (zoom 11,
  about 30 m per pixel near the equator is too heavy; zoom 10-11 is the practical range) and
  store beside the offline maps. **Measure first:** tile count and size for a 50 km radius.
- Tool: pick two points (me, a pin, a heard radio, a known repeater). Draw the terrain profile
  with the straight radio path over a 4/3-earth curve, mark where it is blocked, and show the
  first Fresnel zone clearance for the chosen band (LoRa 915 MHz, 2 m, 70 cm).
- Antenna heights for each end (defaults 2 m and 10 m).
- Honest label: terrain only. Trees and buildings are not in the data, so "clear" means
  "possible", not "guaranteed".
- Tests: decode a tile fixture, profile sampling, curvature and Fresnel maths against worked examples.

## Order and size

| Phase | What | Rough size | Risk |
|-------|------|-----------|------|
| 0 | Snapshot store, privacy wording, Conditions card shell | small | low |
| 1 | Quakes, alerts, forecast, space weather, weather radio | medium | low (check Open-Meteo terms, NOAA list URL) |
| 2 | Draw and measure | small-medium | low |
| 3 | Satellite passes | medium-large | medium (SGP4 port) |
| 4 | Line-of-sight | large | medium (download size, map UI) |

Each phase ships on its own as a beta, iOS and Android together, with tests mirroring each other
as the Mesh and Ham modules do.

## Decisions needed before Phase 1

1. Auto-refresh on app open: off by default (recommended) or on?
2. Rounded location (0.1 degree) sent to NWS and Open-Meteo: acceptable under the "open, keyless"
   rule and the privacy promise, with the wording updated?
3. Open-Meteo vs NWS-only forecasts if the licence check is unclear.
4. Earthquake default radius (500 km suggested) and minimum magnitude (2.5 suggested).

## Deliberately left out

Ship tracking and NASA's wildfire feed (need keys), live aircraft (online only), cameras
(privacy and legal problems), cyber/breach tools and market tickers (off-mission).
