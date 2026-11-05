# Pocket Nomad

A free, open-source iPhone app that keeps working when the network doesn't. It
puts first aid, water and sanitation, survival and radio guides, an offline
library, offline maps, an on-device AI that answers from that library, and a
Meshtastic and MeshCore mesh messenger in one place, on iPhone, iPad and Apple Watch.

Inspired by [Project NOMAD](https://www.projectnomad.us), which does this as a home
server. Pocket Nomad is an independent project and is not affiliated with it.

## Platforms

| | Where | Status |
|---|---|---|
| **iPhone, iPad, Apple Watch** | `App/`, `Watch/`, `Shared/` (SwiftUI) | Full app; App Store coming soon |
| **Android** | `android/` (Kotlin, Jetpack Compose; `:app` + `:mesh`) | Beta APK on [Releases](https://github.com/dougray/pocket-nomad/releases); see [docs/ANDROID.md](docs/ANDROID.md) |
| **Web app (PWA)** | `docs/app/` (plain JavaScript, no build step) | Live at [pocketnomad.us/app](https://www.pocketnomad.us/app/) |

All three read the same guides from `App/Resources/Guides`. The web app copies them in with
`python3 tools/build-pwa.py` (which also writes its offline service worker); Android copies
them at build time. The site's platform pages and feature comparison come from
`tools/build-site-pages.py`.

Web tests: `node --test docs/app/js/tests/ docs/app/js/mesh/tests/`. Android tests:
`cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mesh:testDebugUnitTest :app:testDebugUnitTest`.

## What's in it

| Tab | What it does | Needs a download? |
|---|---|---|
| **Survive** | 69 guides (first aid, water and sanitation, survival, radio and comms), 8 checklists, and tools: compass with decimal/DMS/MGRS position, SOS and Morse signal light (flash, screen, sound), bleach and boiling water calculator, Medical ID, and sunrise, sunset and moon. | No |
| **Ask** | Ask a question in plain words. The app finds the best passages in the guides and your books, and an on-device model answers **only from those passages**, citing them as [1], [2]. With no model it shows the passages. | Optional model |
| **Library** | Reads Kiwix `.zim` books (WikiMed, MedlinePlus, the Post-Disaster Library, iFixit, all of Wikipedia…) and a PubMed survival-medicine pack, with title and full-text search, including the text of PDFs. | Yes, books |
| **Map** | OpenStreetMap vector maps. A world overview (zoom 0–5) is built in; add regions for street-level detail. Drop pins for water, shelter and rally points. Mesh radios that share a position appear live (Meshtastic green, MeshCore blue, repeaters ringed), **known radios** from the public community maps can be downloaded for offline use, and a coverage layer shows where your radio has heard others and how drive-test pings went. | Regions optional |
| **Mesh** | Text over a LoRa radio paired by Bluetooth, running **Meshtastic or MeshCore** (detected automatically): channel chat, direct messages, node list, one-tap SOS with your coordinates, blocking, an optional **coverage scanner** that records where your radio hears others (private by default; see below), and **drive mode** with a ping test. | A Meshtastic or MeshCore radio |

Nothing is sent anywhere. The only network traffic is the downloads you start.
See the [privacy policy](docs/PRIVACY.md); the app's App Store privacy label is
"Data Not Collected", and `App/PrivacyInfo.xcprivacy` is its privacy manifest.

## Coverage scanner and privacy

Mesh › Coverage map. When recording is on (it's off by default), each time the
radio hears another node live, the app notes where the phone was and how strong
the signal was. Map › Layers › Coverage shows it as coloured dots. It stays on
the phone.

Sharing is a separate, opt-in switch, and it needs at least one private zone
(home, work) first. An export never contains raw points. On the phone, samples
are binned into ~350 m hexagons on a fixed global grid, keeping only the best
signal, a count, the firmware and the month range. Samples in private zones are
dropped, as are whole hexagons that touch one, and node names and IDs are
removed. The file goes only where you send it. Map › Layers › Coverage › "What
sharing would publish" previews it exactly.

## Drive mode and the ping test

Mesh › Coverage map › Drive mode keeps the coverage scanner running, from the
phone's own GPS, while the phone is locked in a car mount (iOS shows the
location indicator; it turns itself off after 4 hours). With the ping test on,
it sends one test every 300 m, at most every 45 s:

- **Meshtastic**: a tiny packet on a private app port that chat apps ignore.
  To "any radio that repeats it" it goes one hop only; to a chosen radio it asks
  for that radio's acknowledgement.
- **MeshCore**: a short "Pocket Nomad ping" direct message to a radio you choose
  (your own second radio or a friend's), confirmed by that radio.

Each spot is marked answered (green), repeated by another radio (yellow) or
nothing heard (red). Pings are stored with the coverage recordings, follow the
same private zones, and are exported per hexagon as `pings_reached`,
`pings_relayed` and `pings_none`; the public map's "Two-way pings" view shows them.

## Two radios at once

Connect one Meshtastic and one MeshCore radio at the same time (iPhone and Android). Both
feed the coverage scanner and drive mode together, so one drive maps both networks: every
recording and ping is tagged with its network, and Map › Layers › Coverage can show both,
or Meshtastic only, or MeshCore only (green and blue rings, with a legend). Each network
keeps its own channel and direct messages, replies go out on the conversation's network,
and an SOS goes out on every connected radio. Both radios are remembered and reconnect on
their own. Nothing heard on one network is ever repeated on the other. Not yet tried with
two real radios at once.

## Known radios

Map › Layers › Download known radios fetches the public lists from
[meshmap.net](https://meshmap.net) (Meshtastic radios heard by the official MQTT
server) and [map.meshcore.io](https://map.meshcore.io) (MeshCore adverts), keeps the
radios within 50–400 km of you, and shows them offline. Your location is not sent.
Radios with ⛔, 🛑 or 🚫 in their name, the community "don't map me" sign, are
never recorded, shared or shown.

## AI

- **Apple's on-device model** (iOS 26+, Apple Intelligence devices) works with no download.
- **Any GGUF model** through [llama.cpp](https://github.com/ggml-org/llama.cpp) on device builds. The app offers Qwen3 1.7B (1.1 GB) and Qwen3 4B (2.5 GB), both Apache-2.0.

Small models make things up when asked from memory, so Ask always retrieves
first and tells the model to answer only from the numbered sources.

## Getting content

On the phone, **Library › Get Content** downloads survival books from the free
[Kiwix library](https://library.kiwix.org) and AI models, in the background.

On a Mac, `tools/nomad-packs.sh` prepares everything (including map regions and
prebuilt search indexes) and copies it to the phone over USB:

```sh
tools/nomad-packs.sh essentials                                  # ~2 GB of survival books
tools/nomad-packs.sh pubmed                                      # PubMed: Survival Medicine, ~55 MB
tools/nomad-packs.sh map denver -105.3,39.5,-104.6,40.0         # a region (bbox), zoom 0–15
tools/nomad-packs.sh model small
tools/nomad-packs.sh install                                     # copy packs/ to the connected iPhone
```

### PubMed: Survival Medicine

PubMed has no offline edition, so `nomad-packs.sh pubmed` builds one: about 9,600
guideline and systematic-review abstracts on 35 topics that matter when help is
far away (bleeding, burns, fractures, bites, hypothermia, heat illness, waterborne
and insect-borne disease, poisoning, sanitation, disasters…). It uses NCBI's free
E-utilities (no key), skips withdrawn and retracted papers, and turns the result
into a ZIM book with `nomad-index zim`, NomadKit's own ZIM writer. The topic list
is in `tools/pubmed-pack.py`. Publishers may hold copyright in the abstracts, so
build the pack for your own use and don't redistribute it.

You can also drag `.zim`, `.pmtiles`, `.gguf` and `.nomadidx` files onto the app
in Finder (iPhone › Files), or open them from the Files app.

## Building

Requires Xcode 27 and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
cp Config/Local.xcconfig.example Config/Local.xcconfig   # set your DEVELOPMENT_TEAM
make project      # fetches llama.xcframework (checksum-verified), generates the Xcode project
make test         # app + mesh tests in the simulator
make kit-test     # ZIM reader and search tests (run NomadKit/Tests/Fixtures/fetch-fixtures.sh first)
```

llama.cpp ships without an iOS Simulator slice, so it is linked for device builds
only. In the simulator, Ask uses Apple's model or passages only.

Debug builds open straight to a screen with `-screen`, which is handy for
screenshots:

```sh
xcrun simctl launch booted org.nomadpocket.NomadPocket -screen tool:water
# survive, ask, library, book, packs, map, mesh, settings, tool:<compass|sos|morse|water|medicalID|checklists|sun>,
# guide:<file>, category:<id>, query:<text>, ask:<question>
```

`UITests/WalkthroughUITests.swift` taps through every tab and saves a screenshot
of each screen to the test results.

### App Store art

`docs/appstore` holds the App Store screenshots (6.9" iPhone 1320×2868, 13" iPad
2064×2752) and a 2:1 banner. To remake them, capture raw screens on an iPhone Pro Max
and a 13" iPad simulator with `-screen` (name them `iphone-<n>-<screen>.png`), then:

```sh
swift tools/store-art.swift <raw-folder> docs/appstore App/Assets.xcassets/Logo.imageset/logo@3x.png
```

Captions live at the top of the script. Brand colours come from the logo: amber
`#FC9712`, orange `#FC840C`, highlight `#FEBB1E` (see `Theme.swift`).

### Layout

- `App/Sources`: the SwiftUI app (`Guides`, `Ask`, `Library`, `Map`, `Tools`, `Packs`, `Mesh`, `Web`).
- `App/Resources/Guides`: the bundled guides (HTML fragments plus `index.json` and `checklists.json`).
- `App/Resources/Map`: world overview tiles, Protomaps style layers, fonts and sprites.
- `NomadKit`: a pure-Swift ZIM reader and small ZIM writer (`ZIMKit`), a SQLite FTS5 search index (`NomadSearch`) and the `nomad-index` CLI.
- `tools`: vendor fetch, the llama embed build phase, and the pack script.

The ZIM reader is written from the [openZIM spec](https://wiki.openzim.org/wiki/ZIM_file_format),
not from libzim, and the Meshtastic and MeshCore support are small hand-written
codecs (MeshCore's from its MIT-licensed companion protocol docs), so the whole
app can stay Apache-2.0. Planned work is in `docs/ROADMAP.md`.

## Safety

The guides follow published guidance (Red Cross/AHA, CDC, EPA, FEMA, WHO, US Army
field manuals, FCC) and list their sources, but they **do not replace training**.
Call emergency services whenever you can. AI answers can be wrong; check the
cited source.

## License

The code is Apache 2.0 (`LICENSE`). The **Pocket Nomad name, logo and app icon
are reserved** and not covered by that license; if you ship a fork, please give
it its own name and icon. `NOTICE` lists the reserved files and the bundled
third-party material (OpenStreetMap data under ODbL, Noto fonts under OFL).
