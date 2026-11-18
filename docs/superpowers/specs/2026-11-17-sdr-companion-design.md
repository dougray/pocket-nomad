# SDR for Pocket Nomad: design

Add receive-only software-defined radio (an RTL-SDR USB dongle) to the Radio tab.
Android can drive the dongle directly over USB. iPhone cannot (iOS gives apps no raw
USB access to a generic dongle), so on iPhone, and on Android when you would rather,
the dongle lives on a small **SDR box** (a Raspberry Pi) that the phone pairs with.

This is the same shape as the WarWalker scanner: a Pi does the radio work, the phone is the screen.
The box reuses WarWalker's Bluetooth protocol and Pi software wholesale.

## Goals
- Receive-only. Never transmits.
- Useful with no internet: aircraft (ADS-B), APRS packets, NOAA Weather Radio alerts, a spectrum view.
- Results land where the user already looks: aircraft on the map, APRS in the Ham tab, alerts in Conditions.
- One protocol for iPhone and Android; Android direct USB is an extra, not a fork.
- Free and open: no accounts, no cloud, no paid services, no keys.

## Non-goals / anti-goals
- No transmitting, no TX-capable SDRs.
- No decoding of encrypted or private traffic (trunked-system voice, cellular, pagers). Public broadcast data only.
- Not a full SDR console. Tuning knobs are limited to what the use-cases need.
- Raw radio data is not streamed to the phone by default (it is large). The box decodes; the phone gets results.

## Reuse map (from WarWalker, `docs/superpowers/specs/2026-11-06-warwalker-ios-pi-design.md`)

| WarWalker piece | Reused as |
|---|---|
| BLE GATT service, JSON-lines events/commands, `seq` + `replay`, `hello`, `sim:true` | Same framing and rules. New service UUID base (`5d00xxxx-...`), same four characteristics (events, commands, info, hello). |
| `pi/warwalker_scanner` `gatt.py`, `ble.py`, `btadapter.py`, `protocol.py`, `session.py`, `config.py`, `clock.py`, `battery.py` | Re-implemented in the separate `sdrbox` project (GPL-3.0, its own repo, built as a clean framing layer; WarWalker's GATT code is reused there in phase S1). It is not inside this Apache-2.0 repo. |
| `install.sh` (idempotent, `--profile`, `WARWALKER_DRYRUN`), systemd unit, `bringup/` docs, `selftest.sh` | Same pattern: `sdrbox/install.sh`, `sdrbox/bringup/`. |
| Mac stand-in scanner and Simulate walk | A Mac/Linux simulator box that replays recorded aircraft/APRS so the apps are testable with no hardware. |
| Phone BLE client (iOS CoreBluetooth, Android) incl. reconnect, replay-from-first-gap | Pocket Nomad already has BLE clients for radios (Mesh, Ham TNC). The WarWalker replay logic is ported for the gap handling. |
| Pi e-ink pages, PiSugar, GPS, compass | Optional. The same Pi Zero 2 W and PiSugar can run both; the e-ink is not needed. |
| Shared protocol fixtures + `NOTES.md` golden files | Same: `docs/protocol/sdr-fixtures/` used by Swift, Kotlin and Python tests. |

Hardware overlap: a **Pi Zero 2 W** (and PiSugar battery) bought for WarWalker can also be the SDR box (one at a time, or two Pis).

## Pieces

1. **sdrbox** (Python, on the Pi): owns the dongle, runs decoders, emits events.
2. **Protocol v1** (below).
3. **Phone client** (iOS + Android): pairing screen, live list, map layer, ingest into Ham/Conditions.
4. **Android direct USB** (optional, later): the same decoders on the phone via USB host.
5. **Spectrum / waterfall** (optional, later): needs Wi-Fi, since it is high-rate.

## Decoders (run on the box; open-source tools used as separate processes)

| Use | Tool | Output into the app |
|---|---|---|
| Aircraft (1090 MHz ADS-B) | `readsb` or `dump1090` | `aircraft` events -> map layer |
| APRS (144.390 MHz US) | `rtl_fm` + `direwolf` | raw AX.25 frames -> **the existing Ham APRS decoder** (frames sent as KISS, so Ham shows them like TNC packets) |
| NOAA Weather Radio (162.400-162.550) | `rtl_fm` + `multimon-ng` (SAME/EAS) | `alert` events -> Conditions, plus which of the 7 channels is strongest |
| Spectrum sweep | `rtl_power` | `spectrum` events (coarse) |
| Quick listen (optional) | `rtl_fm` audio over Wi-Fi | later, behind a toggle |

These are GPL tools called as separate programs on the box, so the Apache-2.0 app is not affected.
Android direct USB needs an RTL-SDR driver library; its licence (librtlsdr is GPL-2.0) must be checked
before shipping inside the app. Open question: a separate GPL helper app, or a permissive driver.

## Protocol v1 additions (events, box -> phone; same framing as WarWalker)

```json
{"t":"hello","seq":0,"proto":1,"fw":"0.1.0","model":"pi-zero-2w","dongle":"RTL-SDR Blog V4","tuners":["R828D"],"modes":["adsb","aprs","nwr","spectrum"],"gps":false,"sim":true}
{"t":"status","seq":9,"ts":1793975401000,"mode":"adsb","gain":"auto","ppm":0,"temp":48.2,"bat":78,"dongle":true,"decoded":412}
{"t":"aircraft","seq":10,"ts":1793975400123,"icao":"A1B2C3","call":"SWA123","lat":30.2,"lon":-97.7,"alt":34000,"gs":451,"trk":182,"vr":0,"squawk":"1200","rssi":-18.4}
{"t":"aprs","seq":11,"ts":1793975400200,"kiss":"<base64 AX.25 frame>","rssi":-31.0}
{"t":"alert","seq":12,"ts":1793975400300,"src":"nwr","freq":162.55,"same":"ZCZC-WXR-TOR-048453+0030-3201530-KEWX/NWS-","text":"Tornado Warning"}
{"t":"nwr","seq":13,"ts":1793975400400,"channels":[{"mhz":162.4,"snr":4.1},{"mhz":162.55,"snr":19.8}]}
{"t":"spectrum","seq":14,"ts":1793975400500,"lo":144000000,"step":25000,"db":[-71,-70,-52]}
```

Commands (phone -> box): `start{mode}`, `stop`, `replay{from}`, `settings{gain, ppm, bands}`, `time{ts}`, `forget`.

Rules inherited unchanged: `\n`-terminated UTF-8 JSON, 64 KB line cap, `seq` + replay-from-first-gap, `sim:true` marks invented data
(never mixed into real logs or shared), box keeps an event log on its SD card.

## Phone behaviour
- **Pairing:** Radio tab > SDR > scan for "PocketNomad-SDR-XXXX" (BLE, same picker pattern as Ham). The first run shows the end-user setup notes inline.
- **Aircraft:** map layer with heading arrows, callsign, altitude; list sorted by distance; seen-ago; layer toggle. Positions come from the aircraft, not the user; the user's own location is never sent to the box.
- **APRS:** frames feed the Ham pipeline. Listen-only rules are unchanged, and the box never transmits.
- **Weather alerts:** SAME alerts appear in Conditions with the source "Weather radio (received)", so they work with no internet.
- **Snapshots:** like Conditions, the last aircraft/alerts stay readable with their age when the box is out of range.
- **Privacy summary + store text:** add one line: SDR data stays on the phone and the box; nothing is uploaded.

## Android direct USB (phase S3)
USB host + permission prompt for the dongle's vendor/product id; same decoders as a native/Kotlin implementation of ADS-B and APRS demod,
or a bundled helper. It is the largest piece and is only worth it if people use the box path and ask for it.

## Status (2026-11-17)
S0 and most of S1 done (the link is TCP over Wi-Fi for now; Bluetooth GATT on the box is not built): `sdrbox` repo (protocol, event builders, AX.25 helper, simulator, tests) and phone-side event parsing/state with recorded-stream tests on iOS and Android (branch `feat/sdr-s0`). The simulator's APRS frames decode with the existing Ham decoder. Not built: Bluetooth link, real decoders, UI.

## Phases
| Phase | What | Notes |
|---|---|---|
| S0 | Protocol, `sdrbox` transport (copied from WarWalker), simulator, fixtures, bring-up docs | no hardware needed |
| S1 | Box: ADS-B + APRS. Phone: pairing, aircraft layer, APRS into Ham | first real hardware test |
| S2 | NOAA weather radio + SAME alerts into Conditions | |
| S3 | Android direct USB | licence decision first |
| S4 | Spectrum/waterfall over Wi-Fi | |

## Risks
- **No hardware test yet.** Nothing here has run with a real dongle. S1 ships as beta with that said plainly, as Ham did.
- **BLE throughput.** Aircraft and packets are small (a busy sky is a few KB/s); the spectrum is not, hence Wi-Fi for S4.
- **Antenna reality.** A stock dongle antenna hears aircraft within ~50-100 km at best indoors; set expectations in the user notes.
- **Licensing** of any bundled driver (above).
- **Regional rules.** Receive-only is legal almost everywhere, but a few countries restrict scanners; the notes say to check local law.

## Left to the builder
Exact box OS image vs install script (WarWalker uses an install script on Raspberry Pi OS Lite; do the same), decoder
version pins, gain defaults per mode, and the BLE UUIDs.

## Cross-promotion with WarWalker (idea, not built)

A small "Works with" card in each app pointing at the other, with no tracking and no server.

- **Where:** Settings/About, and one contextual spot (never a popup, never on first run).
  - Pocket Nomad: on the SDR pairing screen ("Have a WarWalker scanner? The same Pi can be your SDR box") and in Settings.
  - WarWalker: on the Pocket Nomad Targets screen (it already uses Pocket Nomad's targets and hexagons) and in Settings.
- **Who sees it ("similar user subset"):** decided on the phone only, from what the person already uses.
  - Pocket Nomad shows the WarWalker card to people who use the Map, Ham or SDR screens.
  - WarWalker shows the Pocket Nomad card to people with a paired scanner or a saved run.
  - Nothing is reported anywhere; there is no "who saw it" count.
- **Behaviour:** one line, one link to the store page or project site, a "Not interested" button that hides it for good. Shown at most once per app version.
- **Rules:** no ads, no third-party code, no accounts. It is plain text and a link, in line with the "no paid services, no tracking" promise.
- **Real overlap to lead with:** the same Pi Zero 2 W runs either WarWalker or the SDR box; WarWalker already exchanges targets with Pocket Nomad.
