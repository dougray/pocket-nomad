# Pocket Nomad for Android

The Android phone app lives in `android/`. It is a port of the iPhone app (the
SwiftUI code in `App/Sources` and `Shared/` is the reference for behaviour and
wording), built with Kotlin, Jetpack Compose and Material 3. Like the iPhone app
it is free, open source (Apache 2.0), has no accounts, analytics or ads, and
needs no network to work.

The Android app has **Survive**, **Ask**, **Library**, **Map** and **Mesh**, and Ask
answers from the guides and from your downloaded books.

There is also a standalone **Wear OS** watch app in `android/wear` (SOS, compass
and position, first aid); see [Wear OS](#wear-os) below.

## Build, test, install

You need JDK 17, the Android SDK (platform 37), and for Ask's AI engine the NDK
and CMake at the versions `:llm` pins:

```sh
sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"
```

Point `android/local.properties` at the SDK (`sdk.dir=...`). The first native
build downloads the pinned llama.cpp source once (about 39 MB, SHA-256 checked;
see [Ask](#ask-on-device-ai)) and compiles it, which takes several minutes.
Without the NDK, `-Pnomad.skipNative` builds everything else; Ask then shows
passages only.

```sh
cd android
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # unit tests (JUnit + Robolectric)
./gradlew :mesh:testDebugUnitTest     # radio layer tests
./gradlew :library:testDebugUnitTest  # ZIM reader and full-text search tests (plain JVM)
./gradlew :llm:testDebugUnitTest      # AI engine's Kotlin side
./gradlew :core:testDebugUnitTest     # shared maths and first-aid extraction
./gradlew :wear:assembleDebug         # the Wear OS app (see Wear OS below)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Some `:library` tests read the small ZIM files NomadKit's tests use; fetch them once
with `NomadKit/Tests/Fixtures/fetch-fixtures.sh` (about 12 MB, git-ignored). Without
them those tests are skipped, not failed; the rest write their own ZIM files.

A debug build installs as `org.nomadpocket.app.debug`, beside a release build
(`org.nomadpocket.app`). Release builds are signed only when
`private/android-signing/keystore.properties` exists (git-ignored; `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`); otherwise they are left unsigned.
`./gradlew :app:assembleRelease` also copies the APK to
`android/app/build/outputs/pocket-nomad.apk`.

Debug builds open straight to a screen, which is handy for screenshots:

```sh
adb shell am start -n org.nomadpocket.app.debug/org.nomadpocket.app.MainActivity -e screen tool:water
# survive, ask, library, packs (Get Content), book (first book) or book:<text in its file name>,
# map, mesh, settings, coverage, tool:<compass|sos|morse|water|medicalID|checklists|sun>,
# guide:<id>, category:<id>; map:demo loads demo radios, coverage and known radios around Denver,
# map:shared shows the demo as "what sharing would publish", mesh:demo adds demo radios,
# "ask:How do I stop severe bleeding?" asks a question, ask:models opens AI models
```

Debug builds also have a model self-test (like `ModelSelfTest` on iOS): put a
model in the app's `files/models/` (download it in Ask › AI models), then

```sh
adb shell am start -n org.nomadpocket.app.debug/org.nomadpocket.app.MainActivity -e modelSelfTest Qwen3-1.7B-Q4_K_M.gguf
adb shell run-as org.nomadpocket.app.debug cat files/selftest.txt   # timings, free memory, the answer
```

## Architecture

```
android/
├── app/      :app      the phone app: Survive, Ask, Library, Map, Settings
├── core/     :core     pure Kotlin both apps share: CoordinateFormat (decimal, DMS, MGRS),
│                       Morse timing, FirstAidSteps (the watch's "Do this now" extraction)
├── library/  :library  offline books, no UI: a Kotlin port of NomadKit's ZIM reader (ZIMKit)
│                       and full-text search (NomadSearch), the book shelf and on-phone indexer
├── llm/      :llm      llama.cpp built with the NDK behind a small JNI wrapper: load a GGUF
│                       model, stream an answer as a Kotlin Flow, cancel, free
├── mesh/     :mesh     the radio layer: Meshtastic + MeshCore over Bluetooth LE, messages,
│                       SOS, coverage recording, drive mode, and the Mesh and Coverage screens
└── wear/     :wear     the standalone Wear OS app (depends only on :core)
```

- **One source for the guides.** The 69 guides, `index.json` and `checklists.json`
  stay in `App/Resources/Guides`. A Gradle task in `app/build.gradle.kts`
  (`copy<Variant>Guides`) copies them into the APK's generated assets at build
  time, so nothing is duplicated in git and an edit to a guide reaches both apps.
- **`:app` talks to `:mesh` through a small API** (`org.nomadpocket.mesh`): `Mesh.init`,
  `Mesh.repository.nodes` and `.coverage` (StateFlows the map draws), `OptOut.optsOut`
  (the ⛔ 🛑 🚫 "don't map me" check), and the `MeshScreen` and `CoverageScreen`
  composables.
- **`:library`** (`org.nomadpocket.library`): `zim.ZimArchive` (header, MIME list,
  path/title pointer lists, dirents, redirects, clusters; xz, zstd and zlib; positional
  `FileChannel` reads with small caches, never the whole file), `search.SearchIndex`
  (reads `.nomadidx`, the same FTS5 schema and query sanitiser as iOS), `search.IndexBuilder`
  and `HtmlText` (builds indexes on the phone, byte-compatible with the Mac `nomad-index`
  tool), `search.Ranking` (each index's bm25 scaled against its best hit × question
  coverage², from iOS `SearchService`), `BookShelf` (installed books, indexes, the
  indexing queue) and `BookSearch`, the interface for Ask:

  ```kotlin
  data class BookPassage(val bookId: String, val bookTitle: String, val articleTitle: String,
                         val path: String, val text: String, val score: Double)
  object BookSearch { fun passages(context: Context, question: String, limit: Int): List<BookPassage> }
  ```

  It returns the best passages across every installed book's index (empty when there
  are none) and does file I/O, so call it off the main thread. `NomadApp` connects it to
  Ask (`AskSources.books`), and a tapped book source opens the article in the reader.
- **`:app` talks to `:llm` through `org.nomadpocket.llm`:** `Llama.isAvailable`,
  `Llama.load(file)` → `LlamaModel.generate(system, user)` (a `Flow<String>` of the
  answer so far; cancelling the collector stops generation) and `close()`. `:llm`
  knows nothing about guides or books.
- **Packages in `:app`:** `ask` (retrieval, prompt and tidy-up, the Ask and AI models
  screens, model downloads), `guides` (index, search, the WebView reader and its
  stylesheet), `tools` (compass, signal light, Morse, water, Medical ID,
  checklists, daylight, and the pure maths they use; position formats and Morse
  timing live in `:core` so the watch shares them), `map` (MapLibre screen,
  pins, offline regions, known radios), `location` (one shared `LocationManager`
  and rotation-vector compass, no Google Play Services), `settings`, `library` (the Library tab, book reader, PDF viewer,
  Get Content, downloads, import, the guides' full-text index and the combined
  guide + book search), `ui` (theme, navigation, shared components).
- **Books** are read in a WebView. The app names a page `nomad://book/<book id>/<path>`
  (as on iOS); the WebView loads it as `https://nomad.invalid/book/<id>/<path>` and
  `shouldInterceptRequest` answers every request from the archive. Chromium only
  resolves relative links against standard schemes such as https, and `.invalid` can
  never reach a real server. Every other request from a book page is refused, so books
  stay offline; links to the web open in the browser when tapped. PDFs inside a book
  (the zimgit collections) open in an in-app viewer built on Android's `PdfRenderer`.
- **Search:** Survive's search lists title and keyword matches first, then full-text
  matches from the guides and every indexed book. The guides' index is built on the
  phone from the bundled guides once per app version (in the cache folder). Library's
  search covers the same sources.
- **Maps:** MapLibre Native for Android with the OpenFreeMap dark style
  (`https://tiles.openfreemap.org/styles/dark`), the same one the public coverage
  map uses. Offline areas use MapLibre's `OfflineManager`.
- **Storage:** app-private files only (`filesDir`, and `noBackupFilesDir` for
  Medical ID). Books live in app-specific external storage (`Android/data/<app>/files/Books`,
  no storage permission needed; removed with the app), indexes built on the phone in
  `noBackupFilesDir/Indexes`. `allowBackup` is off and the data-extraction rules exclude
  everything, so nothing goes to Google's cloud backup or device transfer.
- **No Google Play Services, analytics or ads**, so the app can go on F-Droid.
  Location comes from Android's own `LocationManager`. The flashlight uses
  `CameraManager.setTorchMode`, which needs no camera permission.

### Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Compass position, the map's blue dot, sunrise and sunset, the boil time. Asked the first time one of those screens opens. No background-location permission. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Map tiles while online, saving an offline area, downloading known radios, reading the Kiwix catalogue, downloading an AI model. Book downloads go through Android's DownloadManager, which needs no extra permission. |
| `BLUETOOTH_SCAN` (never for location), `BLUETOOTH_CONNECT`, older `BLUETOOTH`/`BLUETOOTH_ADMIN` | Declared by `:mesh` to pair and talk to your radio. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` | Declared by `:mesh` for drive mode: a foreground service with a visible notification that keeps the coverage scanner running with the screen off, and turns itself off after 4 hours. |

No camera permission: turning on the flash doesn't need one. No storage permission:
books are in app-specific storage, and files opened with or shared to Pocket Nomad
arrive with a one-time read grant.

The manifest also has intent filters for "Open with" and "Share" (`.zim` as
`application/x-zim` or `application/octet-stream`, `.nomadidx` as SQLite) and an
exported receiver for `DOWNLOAD_COMPLETE`, which only acts on downloads this app
started (checked against DownloadManager).

### Libraries and licences

Only Apache-2.0, MIT, BSD, 0BSD or public-domain code; no libzim, libkiwix or
java-libkiwix (GPL), no Google Play Services.

| Library | Used by | Licence |
|---|---|---|
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines | all | Apache-2.0 |
| MapLibre Native (`android-sdk-opengl`) | `:app` | BSD-2-Clause |
| `androidx.sqlite:sqlite-bundled` 2.6.2 (SQLite built with FTS5) | `:library` | Apache-2.0 (SQLite itself public domain) |
| XZ for Java (`org.tukaani:xz`) 1.12 | `:library` | 0BSD |
| zstd-jni (`com.github.luben:zstd-jni`) 1.5.7-6 (`.aar` with zstd for each ABI) | `:library` | BSD-2-Clause (zstd: BSD) |
| `org.json`, JUnit 4, Robolectric, AndroidX Test | tests only, not in the APK | public domain / EPL-1.0 / MIT / Apache-2.0 |

The `.aar` adds `libsqliteJni.so` (about 1.3 MB) and `libzstd-jni` (about 0.6 MB) per ABI.
The tests use the desktop builds of the same two (`sqlite-bundled-jvm`, the zstd-jni jar),
so the FTS5 checks run against the same SQLite sources and flags as the app.

## Feature parity with iOS

| Feature | iOS | Android |
|---|---|---|
| Guides (69), categories, emergency tiles, "All emergencies" | ✓ | ✓ Same files, same reader look (callout, warn, danger, tip, tables) |
| Guide search | Full-text index of guides and books (FTS5) | ✓ Title, summary and keywords (urgent first), then full text of the guides and books (FTS5, same ranking) |
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
| Library: Kiwix .zim books, reader, delete | ✓ | ✓ Same ZIM reader, ported (ZIM 5 and 6, old and new namespaces, xz/zstd/zlib) |
| Library: full-text search across books | ✓ | ✓ Prebuilt `.nomadidx` or built on the phone (books up to 200,000 articles automatically, bigger on request) |
| Library: title search inside a book | ✓ | ✓ |
| Library: PDFs inside books | ✓ | ✓ Shown with `PdfRenderer`; their text is not indexed (no PDF text extraction on Android without a new library), so PDF-only books get title search only |
| Get Content: Kiwix catalogue, same picks, search, download all | ✓ | ✓ DownloadManager: keeps going with the app closed, resumes after drops |
| PubMed survival-medicine pack | Built on a Mac, installed over USB | Built on a Mac; copy the `.zim` and `.nomadidx` and open them with Pocket Nomad |
| Ask: retrieval first, answers only from numbered sources, [1] [2] citations | ✓ | ✓ Same prompt, `pick`, `tidy` and score normalisation; guides and books |
| Ask: streaming answer, stop, tappable citations, sources list, source [1] word for word | ✓ | ✓ |
| Ask: bleach doses from the calculator, model skipped | ✓ | ✓ `WaterCalculator.dose` |
| Ask: passages only with no model | ✓ | ✓ Also when free memory is too low |
| Ask: guide full-text search | FTS5 index built on first launch | In-memory bm25 over guide passages (same weights, rough stemmer instead of Porter) |
| AI models: Qwen3 1.7B and 4B GGUF from Hugging Face | ✓ | ✓ Same files, pinned to a commit, SHA-256 checked, resumable, delete |
| AI models on the GPU | Metal | CPU only for now (no Vulkan/OpenCL backend built) |
| Memory check before loading | Partly (model notes) | ✓ 4B needs about 3.5 GB free, else 1.7B, else passages |
| Add files from elsewhere | Files app, Finder | ✓ Open with / Share to Pocket Nomad, Get Content › Import from Files (`.zim`, `.nomadidx`, `.gguf`; checked by their first bytes) |
| Apple Intelligence model | ✓ | Not possible (Apple only); llama.cpp instead |
| iPad split view | ✓ | Not yet; phones first. Tablets get the phone layout |
| Watch app: SOS light + haptics, compass + position, first-aid steps | Apple Watch | ✓ Standalone Wear OS app (`:wear`), no phone needed; SOS tile |
| Watch: mesh SOS and messages relayed from the phone | Apple Watch (WatchConnectivity) | Later: needs a phone link without Play Services (see [Wear OS](#wear-os)) |
| Siri/Shortcuts, Health Medical ID hand-off | Partly | Not possible as-is; the Medical ID card points to Android's Safety/Emergency info |

## Phase 2 (Library, Ask, release, Wear OS)

### Library (Kiwix ZIM books): done

Built as planned, in `:library` and `:app`'s `library` package (see Architecture).
**Licences:** `libzim` (openZIM) is GPL-2.0-or-later and `libkiwix`, `java-libkiwix`
and the Kiwix Android app are GPL-3.0; shipping either inside the APK would make the
app as a whole GPLv3, which conflicts with keeping it Apache-2.0 (and with the
reserved name and icon terms in `NOTICE`). So, as on iOS, nothing links them; the ZIM
reader is a port of NomadKit's, written from the openZIM spec. Check this again with
the actual licence files before release.

Tested: NomadKit's ZIM and search tests are ported as JVM unit tests (the openZIM
test files, a real 2017 Kiwix file, Wikipedia mini and maxi files, a prebuilt
`.nomadidx` made outside Android, and ZIM files written by the tests with each
compression). Smoke-tested on an Android 16 emulator (arm64): books indexed on the
phone (bundled SQLite with FTS5, zstd and xz natives), search across books and in
Survive, the reader (Wikipedia pages with images, old-layout books), a real Kiwix
download (Water Treatment Library) that dropped mid-way and resumed by itself, the
zimgit web-app book with its PDFs opening in the PDF viewer. Not yet tried on a real
phone. What is still open:

- Text inside PDFs is not indexed (iOS uses PDFKit). Options: a pure-Java PDF text
  extractor with a compatible licence, or prebuilt indexes from the Mac tool.
- Book pages can run JavaScript and are cut off from the network by
  `shouldInterceptRequest`; WebSockets are not intercepted by Android, so a page
  script could open one if it tried. Kiwix books don't, but a later version could
  turn JavaScript off for books that don't need it.
- Books written as service-worker web apps (some zimit captures) are untested.
- Downloads cancelled by the user start again from the beginning; drops resume.
  In the smoke test the finished file was moved into place when the app came back
  to the front; the background `DOWNLOAD_COMPLETE` receiver path is not confirmed.
- "Open with" and "Share" imports are untested on a device (the type check and copy
  code are unit-tested only in part: the magic-byte check).
- `.gguf` model files are left for Ask.

### Ask (on-device AI): done

Retrieval first, exactly as iOS: search the guides
(and books), `AskEngine.pick` passages within a 7,000-character budget (at most
five, two per article), tell the model to answer only from numbered sources and
cite [1], [2], `tidy` "couldn't find that" answers, show source [1] word for word
under the answer, and show the passages when there is no model. Bleach-dose
questions are answered by `WaterCalculator.dose` and the model is skipped.

- **Search.** `ask/GuideIndex` splits each guide into ~900–1,200-character passages
  (`PassageSplitter`, ported from NomadKit), with table rows written
  "Header: value" (`GuideText`), and scores them with FTS5's bm25 formula (title,
  including keywords, weighted 4x) in memory. `AskSearch` merges guides and books
  and, as on iOS, each index is scaled against its own best hit × question
  coverage² (guides × 1.3), because raw bm25 isn't comparable across indexes.
- **Books.** `:app` doesn't depend on `:library`. Ask reads books through
  `fun interface BookPassageSource` (`ask/AskSearch.kt`), set in one place:
  `NomadApp.onCreate` (`AskSources.books`, default `BookPassageSource.NONE`). At
  merge, set it to `BookSearch.passages(...)` mapped with `AskPassage.fromBook`
  (it is called off the main thread, and its scores are already normalised per
  book, so Ask uses them as they are and only normalises and boosts the guides),
  and set `AskSources.bookRoute` to open an article in the Library reader (until
  then a book source opens as a passage dialog).
- **Engine (`:llm`).** llama.cpp **b11976** (the same tag as the iOS app's
  `tools/fetch-vendor.sh`), MIT, compiled from source with NDK 29.0.14206865 and
  CMake 3.31.6 for `arm64-v8a` (phones, and emulators on Apple-silicon Macs; add
  `x86_64` in `llm/build.gradle.kts` for an Intel emulator), statically
  linked into `libnomadllm.so` with a 16 KB page size. CPU only, no OpenMP and no
  KleidiAI (KleidiAI would be a second build-time download; both are possible
  speed-ups). Context 4,096 tokens, up to 700 answer tokens, the iOS sampler
  chain (repeat penalty 1.1 over 64, top-k 40, top-p 0.9, temperature 0.3), the
  model's own chat template, Qwen3's `<think>` hidden and `/no_think` sent.
- **Source pinning.** `:llm`'s `fetchLlamaCpp` Gradle task downloads
  `github.com/ggml-org/llama.cpp/archive/refs/tags/b11976.tar.gz` once into
  Gradle's cache, refuses it unless its SHA-256 is
  `248756d1e3eac86a12b37a658ca545cf98bc0d8203977cd3450ee1499f60023f`, and
  hands the unpacked folder to CMake with `FETCHCONTENT_FULLY_DISCONNECTED`, so
  CMake fetches nothing. `CMakeLists.txt` declares the same URL + `URL_HASH` for
  a hand-run configure. GitHub's generated archives are normally stable but not
  guaranteed byte-for-byte; if the hash ever changes, check the new archive
  against the tag before updating it.
- **F-Droid.** F-Droid builds offline from source and doesn't allow build-time
  downloads, so its recipe should provide llama.cpp as a srclib (or a git
  submodule at tag b11976) and build with `-Pnomad.llamaSourceDir=<that folder>`,
  which skips the download. The NDK version is pinned in `llm/build.gradle.kts`
  (`ndkVersion`), which F-Droid's `ndk:` field should match. llama.cpp is MIT,
  so there is no licence problem; models are downloaded by the user, not
  shipped, and come from Hugging Face (a "NonFreeNet"-style anti-feature
  shouldn't apply to downloading Apache-2.0 files, but confirm with F-Droid).
- **Models.** `ask/ModelCatalog`: the iOS app's two files, Qwen3 1.7B Q4_K_M
  (1,107,409,472 bytes, SHA-256 `b139949c…181897`) and Qwen3 4B Instruct 2507
  Q4_K_M (2,497,281,120 bytes, `3605803b…c67e597`), both Apache-2.0, from
  `huggingface.co/unsloth/…` at a fixed commit. The iOS app doesn't check these;
  the hashes are Hugging Face's LFS object ids at those commits. `ModelStore`
  downloads into the app's private `files/models/`, resumes from a `.part` file
  (HTTP Range), verifies the SHA-256 before a file counts as a model, and
  deletes. Downloads run while the app process is alive (no foreground service
  yet); a cut-off download is offered as Resume next time. "Open with" or share
  a `.gguf` file to add it (a catalog file must match its checksum; any other
  file must start with the GGUF magic).
- **Memory.** Before each question, `ModelChooser` compares Android's free memory
  (`ActivityManager.MemoryInfo.availMem`) with what the model needs (4B about
  3.5 GB, 1.7B about 1.8 GB, other files about 1.3 × size + 0.6 GB): Automatic
  takes the biggest that fits, a chosen model falls back to a smaller one, and
  with nothing that fits Ask shows the passages and says why.

**Tested (2026-11-04):** the JVM tests (`AskEngineTest`, `AskRetrievalTest`
with the real guides, `:llm`'s `LlamaTextTest`); `libnomadllm.so` builds for
arm64-v8a (6.4 MB stripped, 16 KB pages) and loads. On an arm64 Android 16
emulator (4 GB RAM, CPU, no phone): the debug self-test loaded Qwen3 1.7B in
about 4–11 s and answered; Ask streamed a cited answer to "How do I stop severe
bleeding?" (about 2.5 minutes on the emulator, most of it reading the prompt);
tapping [1] opened the guide inside the Ask tab and Back kept the answer; a
bleach question showed the calculator's dose with the model skipped; the AI
models screen showed free memory and the 4B memory warning; a real 4B download
started, paused, resumed from where it stopped (HTTP Range) and was deleted.

**Not yet tested:** a whole download to the end and its SHA-256 check in the
app (the files' hashes were checked by hand against the iOS copies), the 4B
model, any real phone (speed, memory, thermal), "Open with" / share of a
`.gguf` from a file manager, a download surviving the app being closed, and
books (no Library on this branch).

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
3. **Google Play.** Ready to submit: `store/PLAY.md` has every Play Console answer
   (listing, data safety "no data collected", content rating, target audience, the
   foreground-service declarations for drive mode, signing with the existing release key
   so Play and GitHub builds update each other) and the release plan. The listing text,
   icon, feature graphic and screenshots are in `fastlane/metadata/android/en-US/`.
   `./gradlew :app:bundleRelease` builds the `.aab` Play needs.
4. **Wear OS.** `./gradlew :wear:assembleRelease` gives
   `android/wear/build/outputs/pocket-nomad-wear.apk` (shrunk with R8, about
   2.6 MB), signed with the same keystore as the phone app when
   `private/android-signing/keystore.properties` exists. On GitHub Releases and
   F-Droid it is a second APK beside the phone one. On Google Play it goes in the
   same app (same `applicationId`) on the Wear OS release track, with its own
   version code (10000 + the phone's), built as a bundle with
   `./gradlew :wear:bundleRelease`, screenshots from `docs/appstore/wear-*.png`
   (also in `fastlane/.../images/wearScreenshots/`), and the Wear OS opt-in in Play Console. Play reviews
   Wear apps against its Wear quality guidelines separately.

## Wear OS

`android/wear` is a **standalone** Wear OS app: everything works with no phone
nearby, paired or not (`com.google.android.wearable.standalone` is `true`). It is
a port of the Apple Watch app (`Watch/`) in Compose for Wear OS (Material 3).

| Screen | What it does |
|---|---|
| Home | Big red SOS button, Compass, First aid, and a line saying mesh SOS/messages need a phone link that comes later |
| SOS | Full-screen white flashes in the Morse SOS rhythm (150 ms unit, from `:core` `Morse.timeline`), a buzz on each flash, screen kept on at full brightness; tap or swipe right to stop |
| Compass | Turning dial and heading (true north once there is a fix, else magnetic, and it says which), then position in decimal, MGRS, DMS, altitude and accuracy from the watch's own `LocationManager`. With no fix it says so (no permission, no GPS chip, or still searching) and marks a fix older than 2 minutes |
| First aid | The urgent guides grouped by category, each with its "Do this now" steps and red callouts, extracted from the same `App/Resources/Guides` HTML as the Apple Watch (`:core` `FirstAidSteps`, a port of `Shared/FirstAidSteps.swift`). Lists scroll with the crown or bezel |
| SOS tile | One-tap SOS from the tile carousel (`SosTileService`, static layout) |

**Build, test, install on a watch:**

```sh
cd android
./gradlew :wear:assembleDebug :wear:testDebugUnitTest :core:testDebugUnitTest
# On the watch: Settings › System › About › tap Build number 7 times, then
# Settings › Developer options › ADB debugging and Wireless debugging on.
# Wireless debugging shows "Pair new device" with an IP:port and a pairing code.
adb pair <watch-ip>:<pairing-port>        # enter the code shown on the watch
adb connect <watch-ip>:<port>             # the port shown on the Wireless debugging screen
adb -s <watch-ip>:<port> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

A debug build installs as `org.nomadpocket.app.debug` beside a release one. It
opens straight to a screen for screenshots:

```sh
adb shell am start -n org.nomadpocket.app.debug/org.nomadpocket.wear.MainActivity -e screen sos
# sos, compass, position (compass scrolled to the coordinates), firstaid, guide:<id>
# SOS screenshots: -e sosFrame on|off holds the flash on or off (debug only)
```

On the emulator, give it a location with `adb emu geo fix <lon> <lat> <alt>`
(Denver for screenshots: `adb emu geo fix -104.99654 39.74956 1609`).

**No phone link yet.** The Wearable Data Layer (`com.google.android.gms:play-services-wearable`)
is part of Google Play Services, which this project doesn't use (F-Droid, no
Google dependency). So this version has no mesh SOS or messages relayed through
the phone. A later phase could add a phone link without Play Services, for example
a Bluetooth LE GATT service in `:app` that the watch connects to, or let the
watch talk to the radio directly over BLE with `:mesh`'s protocol code (the
radio only allows one central at a time, so this needs care). Either way the
standalone screens stay as they are.

The SOS tile opens the `.SosShortcut` activity alias, not `MainActivity` itself:
Android only brings a running app to the front (dropping the `screen` extra)
when a launch looks like the launcher's, and `MainActivity` is `singleTop` so
the running copy gets the extra in `onNewIntent`.

**Tested** on a Wear OS 5 emulator (API 34, large round 454×454): every screen,
the SOS flash rhythm, vibration (150 ms dots, 400 ms dashes, logged by the
vibrator service), keep-screen-on, the location permission prompt, a GPS fix
via `geo fix`, true heading from the emulated magnetometer, rotary scrolling
(`adb shell input rotaryencoder scroll`), the tile (added with the Wear debug
surface broadcast) opening SOS, and the R8-shrunk release build.
Screenshots: `docs/appstore/wear-*.png`.

**Not tested yet:** real watch hardware (how the vibration feels, the crown or
bezel on a device, GPS fix time on the wrist, compass accuracy on watches with
weak magnetometers, watches without GPS), and the tile on a real tile carousel.
