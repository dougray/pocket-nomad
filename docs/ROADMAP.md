# Roadmap

What is planned next, roughly in order. Done work lives in the git history.

## In progress

- **Mesh map and coverage scanner (steps 1–3 below): built.** Nodes on the map,
  the scanner, and opt-in sharing with on-phone privacy filtering are in the
  app; needs real radios to test. Step 4 (public map site): built at
  `docs/coverage/` with `tools/coverage-merge.py` and CONTRIBUTING-COVERAGE.md;
  no contributions merged yet, so it shows an empty map (demo data is a toggle).

- **MeshCore radios.** The Mesh tab detects whether a paired radio runs Meshtastic
  or MeshCore and speaks the right protocol, so the same chats, node list and SOS
  work with either. Needs a hardware test on two Heltec V4 radios.

## Planned

### Mesh map and coverage scanner

1. **Nodes on the map:** nodes that share a position appear on the offline map,
   live, with name and last-heard time; tap one to message it.
2. **Coverage scanner:** each time the radio hears a node, log the phone's own
   position, time, signal (SNR/RSSI), hops and firmware, and colour it on the
   map. That shows where the mesh reaches, which is how to place repeaters.
3. **Privacy controls, private by default:**
   - Recording has its own on/off switch, separate from sharing.
   - Sharing is opt-in. With it off there is no export, and the data never
     leaves the phone.
   - Opting in asks for private zones (home, work) first. Hexagon binning
     (~200–500 m), zone blanking and removal of node names and IDs all happen
     on the phone, before any file exists.
   - Preview of exactly what will be shared, "delete all coverage data", and
     automatic deletion after N days.
4. **Public coverage map:** a free GitHub Pages site (MapLibre, OpenStreetMap
   tiles) showing the shared hexagons with best signal and count, filterable by
   Meshtastic/MeshCore and date. Contributors submit their filtered file by
   pull request or issue, by hand. No server, nothing automatic, nothing hosted
   on the NAS.
   **Status: built** (docs/coverage/, served at www.pocketnomad.us/coverage/).
   OpenFreeMap tiles, firmware and month filters, a made-up demo that is off
   by default. A maintainer validates and merges submissions with
   `tools/coverage-merge.py` (strict: only the five allowed properties on grid
   hexagons). Next: first real contributions once radios are tested.

### iPad design

Built on branch `ipad-design` (regular-width iPad only; iPhone and narrow iPad
windows keep the tab layout): a sidebar of sections and books with a list column
and a detail column per section, a Go menu (Cmd-1…5, Cmd-F search, Cmd-Shift-S SOS
light) and multiple windows. Screenshots: `docs/appstore/ipad-new-*.png`. The plan was:

- Sidebar navigation (Survive, Ask, Library, Map, Mesh) instead of a tab bar.
- Survive: guide list beside the open guide (split view).
- Library: book list, article list and reader in three columns.
- Ask: answer beside its sources, with the source open next to it.
- Map and Mesh side by side, so pins and node positions show while chatting.
- Keyboard shortcuts for search and SOS, and Stage Manager / multiple windows.

### Apple Watch app

**Built (branch watch-app):** SOS light, mesh SOS via the phone, compass and
position, urgent first-aid steps, messages with quick replies, SOS and compass
complications. Needs a test on a real watch with a real radio. Checklists are
still to do.

A companion for when the phone is in a pack or a pocket:

- SOS: one press shows the signal light / screen flash and, with the phone and a
  radio nearby, sends the mesh SOS with the watch's location.
- Compass and position (decimal, DMS, MGRS) using the watch's own GPS.
- The urgent first-aid guides (CPR, bleeding, choking…) as short step lists that
  work without the phone.
- Checklists, with ticks synced to the phone.
- Mesh messages: read and reply with quick replies, relayed through the phone.
- Complication: one tap into SOS or the compass.

### Other

- When the hardware build is done: a slide deck of the whole system, covering the
  app, radios, repeaters and the pole tower.
- Medical review of the guides (snakebite limb position, aspirin wording, shock
  positioning).
- A public GitHub page with screenshots and use cases (after a fresh history and a
  check for personal data).
