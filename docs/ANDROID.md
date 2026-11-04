# Pocket Nomad for Android

The Android phone app lives in `android/`. It is a port of the iPhone app (the
SwiftUI code in `App/Sources` and `Shared/` is the reference for behaviour and
wording), built with Kotlin, Jetpack Compose and Material 3. Like the iPhone app
it is free, open source (Apache 2.0), has no accounts, analytics or ads, and
needs no network to work.

The first Android version has **Survive**, **Map** and **Mesh**. **Library** and
**Ask** are phase 2; their tabs say so plainly instead of showing anything fake.

## Build, test, install

You need JDK 17 and the Android SDK (platform 37). Point `android/local.properties`
at the SDK (`sdk.dir=...`).

```sh
cd android
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # unit tests (JUnit + Robolectric)
./gradlew :mesh:testDebugUnitTest     # radio layer tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug build installs as `org.nomadpocket.app.debug`, beside a release build
(`org.nomadpocket.app`). Release builds are signed only when
`private/android-signing/keystore.properties` exists (git-ignored; `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`); otherwise they are left unsigned.
`./gradlew :app:assembleRelease` also copies the APK to
`android/app/build/outputs/pocket-nomad.apk`.

Debug builds open straight to a screen, which is handy for screenshots:

```sh
adb shell am start -n org.nomadpocket.app.debug/org.nomadpocket.app.MainActivity -e screen tool:water
# survive, ask, library, map, mesh, settings, coverage, tool:<compass|sos|morse|water|medicalID|checklists|sun>,
# guide:<id>, category:<id>; map:demo loads demo radios, coverage and known radios around Denver,
# map:shared shows the demo as "what sharing would publish", mesh:demo adds demo radios
```

## Architecture

```
android/
├── app/     :app   the phone app: Survive, Map, Library/Ask placeholders, Settings
└── mesh/    :mesh  the radio layer: Meshtastic + MeshCore over Bluetooth LE, messages,
                    SOS, coverage recording, drive mode, and the Mesh and Coverage screens
```

- **One source for the guides.** The 69 guides, `index.json` and `checklists.json`
  stay in `App/Resources/Guides`. A Gradle task in `app/build.gradle.kts`
  (`copy<Variant>Guides`) copies them into the APK's generated assets at build
  time, so nothing is duplicated in git and an edit to a guide reaches both apps.
- **`:app` talks to `:mesh` through a small API** (`org.nomadpocket.mesh`): `Mesh.init`,
  `Mesh.repository.nodes` and `.coverage` (StateFlows the map draws), `OptOut.optsOut`
  (the ⛔ 🛑 🚫 "don't map me" check), and the `MeshScreen` and `CoverageScreen`
  composables.
- **Packages in `:app`:** `guides` (index, search, the WebView reader and its
  stylesheet), `tools` (compass, signal light, Morse, water, Medical ID,
  checklists, daylight, and the pure maths they use), `map` (MapLibre screen,
  pins, offline regions, known radios), `location` (one shared `LocationManager`
  and rotation-vector compass, no Google Play Services), `settings`, `library`,
  `ui` (theme, navigation, shared components).
- **Maps:** MapLibre Native for Android with the OpenFreeMap dark style
  (`https://tiles.openfreemap.org/styles/dark`), the same one the public coverage
  map uses. Offline areas use MapLibre's `OfflineManager`.
- **Storage:** app-private files only (`filesDir`, and `noBackupFilesDir` for
  Medical ID). `allowBackup` is off and the data-extraction rules exclude
  everything, so nothing goes to Google's cloud backup or device transfer.
- **No Google Play Services, analytics or ads**, so the app can go on F-Droid.
  Location comes from Android's own `LocationManager`. The flashlight uses
  `CameraManager.setTorchMode`, which needs no camera permission.

### Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Compass position, the map's blue dot, sunrise and sunset, the boil time. Asked the first time one of those screens opens. No background-location permission. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Map tiles while online, saving an offline area, downloading known radios. |
| `BLUETOOTH_SCAN` (never for location), `BLUETOOTH_CONNECT`, older `BLUETOOTH`/`BLUETOOTH_ADMIN` | Declared by `:mesh` to pair and talk to your radio. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` | Declared by `:mesh` for drive mode: a foreground service with a visible notification that keeps the coverage scanner running with the screen off, and turns itself off after 4 hours. |

No camera permission: turning on the flash doesn't need one.

## Feature parity with iOS

| Feature | iOS | Android |
|---|---|---|
| Guides (69), categories, emergency tiles, "All emergencies" | ✓ | ✓ Same files, same reader look (callout, warn, danger, tip, tables) |
| Guide search | Full-text index of guides and books (FTS5) | ✓ Title, summary and keywords; urgent first. Full text in phase 2 |
| Checklists with ticks and your own items | ✓ | ✓ |
| Compass, true/magnetic, lock a bearing | ✓ | ✓ Rotation-vector sensor; true north from `GeomagneticField` |
| Position in decimal, DMS, MGRS; copy | ✓ | ✓ Same maths, same test vectors |
| SOS and Morse light: flash, white screen, sound | ✓ | ✓ |
| Water purifier (bleach drops, boil time by altitude) | ✓ | ✓ 3-minute boil above 5,000 ft (1,524 m) |
| Medical ID, stored only on the phone | ✓ | ✓ Excluded from backup |
| Daylight: sunrise, sunset, twilight, moon | ✓ | ✓ |
| Map: online vector map | Bundled world overview + region .pmtiles | ✓ OpenFreeMap online |
| Offline map areas | Region .pmtiles from the pack script or Library › Get Content | ✓ Save the area on screen (OfflineManager), with a size estimate and delete |
| Pins (water, shelter, rally, medical, supplies, danger, other), list, delete | ✓ | ✓ |
| Live mesh radios on the map | ✓ | ✓ From `:mesh` |
| Coverage layer: my recordings and pings | ✓ | ✓ |
| Coverage layer: "what sharing would publish" hexagons | ✓ | ✓ From `:mesh`'s privacy filter (`CoverageStore.sharedCells`) |
| Known radios from meshmap.net and map.meshcore.io | ✓ | ✓ Same parsing, radius, opt-out and wording |
| Mesh: chat, DMs, nodes, SOS, coverage scanner, drive mode | ✓ | `:mesh` (see its own docs) |
| Settings: always dark, privacy summary, licences | ✓ | ✓ |
| Library: Kiwix .zim books, PubMed pack, full-text search | ✓ | Phase 2 |
| Ask: on-device AI answering from sources | ✓ | Phase 2 |
| Drag files in from a computer (Finder) | ✓ | Phase 2 (open with / share sheet) |
| Apple Intelligence model | ✓ | Not possible (Apple only); llama.cpp instead |
| iPad split view | ✓ | Not yet; phones first. Tablets get the phone layout |
| Watch app | Apple Watch | Wear OS companion is phase 2 |
| Siri/Shortcuts, Health Medical ID hand-off | Partly | Not possible as-is; the Medical ID card points to Android's Safety/Emergency info |

## Phase 2 plan

### Library (Kiwix ZIM books)

**Licences first.** `libzim` (openZIM) is GPL-2.0-or-later and `libkiwix`,
`java-libkiwix` and the Kiwix Android app are GPL-3.0. Apache-2.0 code may be
combined into a GPLv3 work, but the result must then be distributed under
GPLv3: shipping either library inside the APK would make the Android app as a
whole GPLv3, which conflicts with keeping it Apache-2.0 (and with the reserved
name and icon terms in `NOTICE`). So, as on iOS, **don't link libzim or
libkiwix.** Check this again with the actual licence files before release; it
is the deciding fact for the approach.

**Proposed approach: port NomadKit to Kotlin.** The iOS app already has a ZIM
reader written from the openZIM spec (`NomadKit/Sources/ZIMKit`) and a SQLite
FTS5 passage index (`NomadKit/Sources/NomadSearch`), both Apache-2.0. A Kotlin
port, as an `android/library` module:

- ZIM header, MIME list, URL/title pointer lists, directory entries, redirects,
  clusters and blobs, read with `RandomAccessFile`/`FileChannel` (books are
  several GB; never load them whole).
- Cluster decompression: xz/LZMA2 with **XZ for Java** (`org.tukaani:xz`, public
  domain / 0BSD) and zstd with **aircompressor** (Apache-2.0, pure Java) or
  **zstd-jni** (BSD-2, native). Both licences are compatible with Apache-2.0.
  zlib via `java.util.zip.Inflater`.
- Reading pages in a `WebView` with `shouldInterceptRequest` serving
  `nomad://book/<id>/<path>` from the archive, like `NomadSchemeHandler` on iOS.
- Search: reuse the prebuilt `.nomadidx` files (SQLite FTS5). Android's built-in
  SQLite doesn't promise FTS5, so bundle a SQLite build with FTS5 enabled (for
  example requery's `sqlite-android`, Apache-2.0; confirm FTS5 is on) and port
  `SearchIndex.swift`'s query builder. Then the same index powers guide full-text
  search.
- Downloads from library.kiwix.org with `DownloadManager` or WorkManager, into
  app-specific external storage, with resume. Also "Open with Pocket Nomad" for
  `.zim`, `.nomadidx` and `.gguf` files.
- Port the NomadKit fixture tests (`NomadKit/Tests`) as JVM unit tests.

### Ask (on-device AI)

- **llama.cpp** (MIT) built for Android with the NDK (`arm64-v8a` only), behind
  a small JNI wrapper in its own module, with streaming tokens to Compose.
  llama.cpp ships an Android example to start from.
- **Models** from Hugging Face, the same ones as iOS: Qwen3 1.7B (about 1.1 GB)
  and Qwen3 4B (about 2.5 GB), both Apache-2.0, GGUF Q4 files, checksum-verified,
  downloaded once.
- **Retrieval first**, exactly as iOS: search the guides and books, pick passages
  within a budget, tell the model to answer only from numbered sources, cite
  [1], [2], tidy "couldn't find that" answers, and show passages when there is
  no model. Port `AskEngine` (`pick`, `tidy`) and `ToolKind.related` (already
  ported) with their tests; `WaterCalculator.dose` is already ported for the
  exact bleach arithmetic.
- Check memory before loading (4B needs about 3.5 GB free) and fall back to 1.7B
  or passages only.

### Release

1. **Signed APK on GitHub Releases.** Keystore in `private/android-signing/`
   (never in git); `./gradlew :app:assembleRelease` gives `pocket-nomad.apk`.
   Publish the SHA-256 and the signing certificate fingerprint with each release.
2. **F-Droid.** Already F-Droid friendly: no Play Services, no analytics, no
   proprietary libraries. Needs a metadata merge request to `fdroiddata`, a
   reproducible build (pin the Gradle and AGP versions, no build-time network
   fetches beyond Maven), and Fastlane-style metadata
   (`fastlane/metadata/android/en-US/`: title, short and full description,
   screenshots from `docs/appstore/android-*.png`). Anti-features: probably none;
   "NonFreeNet" does not apply to OpenFreeMap or the community maps, but confirm.
3. **Google Play.** Reuse `store/LISTING.md` (name, short description from the
   subtitle and promotional text, full description, keywords as tags, privacy
   URL `https://www.pocketnomad.us/PRIVACY.html`). Data safety form: "No data
   collected, no data shared". Content rating questionnaire. Screenshots from
   `docs/appstore/android-*.png` (phone, 9:16). The Bluetooth and location
   permission declarations need short justifications.

### Wear OS companion

Mirror the Apple Watch app (`Watch/`): SOS screen with flash and vibration,
compass and position (decimal and MGRS), first-aid steps (`Shared/FirstAidSteps.swift`)
and mesh messages relayed from the phone. Use the Wearable Data Layer only if it
works without Play Services; otherwise a standalone Wear app with no phone link
first (SOS, compass, first aid work with no phone at all).
