# Map navigation: build plan

Follows the situation-layers plan (Conditions, draw and measure and satellite passes are built).
Everything here keeps the app's rules: free, keyless, works offline where it can, and no location
leaves the phone.

## Scope (agreed 2026-11-17)

1. **Search by coordinates** (offline). One search box on the Map. It accepts decimal degrees,
   degrees-minutes-seconds, UTM, MGRS and Maidenhead grid squares, and pasted text like
   "39.7400, -104.9900". The map jumps there and offers to drop a pin. Place-name search is
   online-only (OpenStreetMap's Nominatim, 1 request a second, with a User-Agent) and is a later,
   optional add-on; offline it searches saved pins and favorites only.
2. **Favorites.** Star any place: a pin, a coordinate result, a drawing, a heard radio or a known
   repeater. A Favorites list on the Map, shown first in search and in Go-to. Named quick spots
   (Home, Camp, Rally point, Car) so "Go to Car" is one tap. Stored on the phone only; exported
   with drawings (GeoJSON/GPX). An optional star on existing pins, so nothing is migrated.
3. **Go to** (offline compass navigation). Pick a destination (favorite, pin, drawing waypoint,
   coordinate, radio). Shows an arrow, distance, heading and a walking-time estimate; steps through
   a drawn route waypoint by waypoint. Uses the phone's compass and location.
4. **Map style picker** in the Layers menu: Streets, Terrain, and later Terrain with contours.
   Plus a north-up / compass-up toggle on the recenter button.
5. **Topography.** Hillshade from open elevation tiles (AWS terrain tiles, "terrarium" encoding),
   downloaded for an area like the street maps. The same tiles feed Phase 4 (radio line-of-sight),
   so the download and storage are built once. Contour lines are a follow-up (needs a free vector
   source). Measure the tile count and size for a 50 km area before building.
6. **Road directions** (optional, last). Offline road routing is a separate large project; free
   public routing servers are demo-only. If wanted, add it as an online-only "when you have
   signal" feature and say so in the privacy text (it would send the route's end points).

## Not planned
Satellite photo layers (no good free offline source). Turn-by-turn voice guidance.

## Order
1. Coordinate search + favorites + Go to (small to medium, fully offline).
2. Map style picker + terrain/hillshade + shared elevation downloads.
3. Phase 4 line-of-sight on the same elevation data.
4. Optional road directions.

## Open decisions
- Default coordinate format shown: latitude/longitude or MGRS?
- Should a Favorites star also appear on the Mesh node and APRS station detail screens?
