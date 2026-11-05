# Pocket Nomad: Google Play listing

Everything Play Console asks for, in the order it asks. The listing text and images are in
`fastlane/metadata/android/en-US/` (the layout both Fastlane `supply` and F-Droid read):

| File | Play field | Limit |
|---|---|---|
| `title.txt` | App name | 30 |
| `short_description.txt` | Short description | 80 |
| `full_description.txt` | Full description | 4000 |
| `changelogs/<versionCode>.txt` | Release notes | 500 |
| `images/icon.png` | App icon, 512 x 512 | |
| `images/featureGraphic.png` | Feature graphic, 1024 x 500 | |
| `images/phoneScreenshots/1-8.png` | Phone screenshots, 1200 x 2400 | 2-8 |
| `images/wearScreenshots/1-7.png` | Wear OS screenshots, 454 x 454 | 1-8 |

The screenshots are eight of `docs/appstore/android-*.png` (Survive, Ask, a guide, Library, water, the coverage map with both networks, two radios on the Mesh tab, compass) padded with the app's black background
from 1080 x 2400 to 1200 x 2400, because Play rejects screenshots more than twice as tall
as they are wide. The feature graphic is the store banner without the iPhone. All of these
show the logo, so they are reserved artwork (see `NOTICE`).

**Submit only once Library, Ask and the Wear OS app are in the release build:** the
description lists all three. Until then, cut those sections or use a closed test.

## Before the first upload

1. **Developer account.** Google charges a one-time US$25 registration fee (no
   subscription) and verifies your identity. Choose a *personal* account unless you have a
   registered organisation. Play shows the developer name and a contact email on the
   listing: use a project address, not a personal one.
2. **Closed test first.** Personal accounts created after November 2023 must run a closed
   test with at least 12 testers who stay opted in for 14 days in a row before Play allows
   a production release. The GitHub APK beta testers are the obvious group.
3. **Signing.** Play only takes an Android App Bundle and re-signs it with an *app signing
   key*. Choose **"Use a different key" → export and upload the existing release key**
   (`private/android-signing/pocket-nomad-release.jks`, with Google's PEPK tool, as the
   Console shows) rather than letting Google generate one. Then the Play build and the
   GitHub APK carry the same certificate (SHA-256 `23:5F:15:2E:...:40:AC`), and people who
   installed the APK get Play updates without uninstalling and losing their data. If Google
   generates the key instead, the two can never update each other.
   Then register an upload key (the same keystore is fine) and keep both backed up.
4. **Build the bundle:**

   ```sh
   cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:bundleRelease
   ```

   gives `app/build/outputs/bundle/release/app-release.aab`. Raise `versionCode` in
   `app/build.gradle.kts` for every upload (the GitHub beta used 1), and add
   `changelogs/<versionCode>.txt`.

## Store settings

| Field | Value |
|---|---|
| Package name | `org.nomadpocket.app` (permanent once uploaded) |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free (permanent: a free app can never become paid) |
| Category | Tools |
| Tags | Survival, First aid, Navigation, Maps, Emergency |
| Website | https://www.pocketnomad.us/ |
| Privacy policy | https://www.pocketnomad.us/PRIVACY.html |
| Contains ads | No |
| In-app purchases | None |
| Countries | All. LoRa radios are sold separately and their frequency rules are the radio owner's responsibility |

## App content (Policy › App content)

**App access:** all functionality is available without special access. No login.

**Ads:** no ads.

**Content rating (IARC questionnaire):** category *Reference, News, or Educational*.
- Violence, fear, sexuality, language, controlled substances, gambling: No.
- Medical information: the questionnaire may not ask; if it does, first-aid reference: Yes.
- *Users can interact or exchange information:* **Yes**: text messages over the user's own
  LoRa radio to people nearby, with no server. Users can block a radio.
- *Shares the user's current location with other users:* **Yes**: only when the user sends
  an SOS or a position over the mesh themselves.
- Digital purchases: No. Unrestricted web browsing: No (links open the browser).

**Target audience:** 13 and over (13-15, 16-17, 18+). Not designed for children, so the
Families policy does not apply. Not primarily child-directed.

**Data safety:** *No data collected, no data shared.*
- Location, the medical ID, messages, pins and coverage recordings are processed and stored
  only on the device. Play's definition of collection is data sent off the device, so none
  of it counts.
- Downloads (books from library.kiwix.org, AI models from Hugging Face, map tiles from
  OpenFreeMap, known radios from meshmap.net and map.meshcore.io) are plain HTTPS requests
  for public files; the app sends nothing about the user with them.
- Coverage sharing is a GeoJSON file the user exports and sends themselves; the app does
  not upload it.
- Encrypted in transit: Yes (HTTPS for every download). Account deletion: not applicable,
  no accounts.

**Health apps declaration** (if the Console asks): the app offers first-aid and wilderness
medicine *reference* information and calculators. It is not a medical device, gives no
diagnosis and connects to no health sensors or health records. The disclaimer is at the
end of the description and in every guide.

**News, government, financial features, COVID-19:** none.

### Permission declarations

| Permission | Declaration needed | What to enter |
|---|---|---|
| `FOREGROUND_SERVICE_LOCATION` | **Yes** (foreground service types form) | Drive mode: "Keeps recording mesh radio coverage with the phone's GPS while the screen is off, during a drive the user starts. A notification shows the whole time; it stops when the user taps Stop or after 4 hours." Google asks for a short video of the feature: record drive mode starting from the Mesh tab, the notification, and Stop. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | **Yes** (same form) | "Keeps the Bluetooth link to the user's LoRa radio open during drive mode, so pings and received messages are recorded." Same video. |
| `ACCESS_FINE_LOCATION` | No | Foreground only; there is no `ACCESS_BACKGROUND_LOCATION`, so no location declaration form. |
| `BLUETOOTH_SCAN` | No | Marked `neverForLocation`. |

## Wear OS

The watch app (`android/wear`) is part of the same Play listing: same package name, its
own version code (10000 + the phone's) and its own release track.

1. Play Console › Test and release › Advanced settings › Form factors: add **Wear OS**,
   then accept the Wear OS terms.
2. Build it with `./gradlew :wear:bundleRelease` and upload it to a Wear OS track (it has
   its own internal, closed and production tracks).
3. Store listing › Wear OS: upload `images/wearScreenshots/` (square 454 x 454; the
   description is shared with the phone). Note in the description that the watch app
   works on its own: SOS, compass, position and first aid, with no phone link yet.
4. Google reviews Wear apps against its Wear OS app quality guidelines separately. The
   app is standalone (`com.google.android.wearable.standalone` = true), has no
   INTERNET permission, and asks only for location (for position and true north).

## Release plan

1. **Internal testing** with the first `.aab`: install from Play on a real phone, check that
   the signing certificate matches the GitHub APK (`apksigner verify --print-certs`).
2. **Closed testing**, 12+ testers for 14 days (see above), with feedback through GitHub
   issues.
3. **Production**, then put the Play badge on `docs/android/` and the site (the Play badge
   artwork has its own usage guidelines; self-host it, no Google-hosted images).

F-Droid (free, no account fee) reads the same `fastlane/` folder; see `docs/ANDROID.md`
› Release.
