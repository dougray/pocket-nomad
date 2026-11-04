# Pocket Nomad: App Store listing

Copy for App Store Connect. Every field is within Apple's limits. Screenshots are in
`docs/appstore/` at Apple's required sizes (iPhone 6.9": 1320 x 2868, iPad 13": 2064 x 2752).

## Details

| Field | Value |
|---|---|
| App name | Pocket Nomad (no app by that name on the US store, checked 2026-11-04) |
| Bundle ID | `org.nomadpocket.NomadPocket` (watch app: `org.nomadpocket.NomadPocket.watchkitapp`) |
| SKU | pocket-nomad-ios |
| Version | 1.0 (build 1) |
| Price | Free. No in-app purchases, no ads, no accounts |
| Category | Reference (secondary: Navigation) |
| Marketing URL | https://www.pocketnomad.us/ |
| Support URL | https://github.com/dougray/pocket-nomad/issues |
| Privacy policy URL | https://www.pocketnomad.us/PRIVACY.html |
| Copyright | 2026 The Pocket Nomad contributors |
| Availability | All countries; check any that restrict LoRa radio apps |

**Subtitle** (30 max):
> Survival kit with no signal

**Promotional text** (170 max):
> First aid, water, maps, an offline medical library and mesh radio texting, all on your phone before you need them. Works with no signal. Free and open source.

**Keywords** (100 max):
> offline,survival,first aid,emergency,prepper,meshtastic,meshcore,lora,mesh,sos,compass,hiking,water

**Description** (4000 max):
> Pocket Nomad is a survival kit that works with no signal. Everything is on your phone before you need it: no account, no internet, no tracking.
>
> SURVIVE
> • 69 step-by-step guides for emergencies: CPR, choking, severe bleeding, burns, stroke, heart attack, allergic reactions, hypothermia, heat stroke and more, written as short numbered steps
> • Tools: compass with your position (decimal, DMS and MGRS), SOS signal light, Morse code, water purifier calculator, medical ID, checklists and daylight times
>
> ASK
> • Ask a question in plain words. An AI that runs entirely on your phone answers from your offline guides and books and shows the source of every step
> • Uses Apple's on-device model on supported iPhones, or a free open model you download once
>
> LIBRARY
> • Download whole reference books for offline reading: WikiMed, NHS medicines A to Z, field medical manuals, water treatment, knots, post-disaster resources, and almost 10,000 PubMed summaries on emergency and wilderness medicine
>
> MAP
> • Offline street maps of your region that follow your GPS with no data connection
> • Save pins for water, shelter and rally points
>
> MESH
> • Text people nearby with no cell network through a Meshtastic or MeshCore LoRa radio (sold separately) paired over Bluetooth
> • Send an SOS with your coordinates, see nearby radios on the map
> • Optional coverage scanner shows where your mesh reaches, using your phone's GPS. Off by default; nothing is shared unless you export it, and private zones are always removed first
> • Drive mode keeps mapping with the phone locked in a car mount, and its ping test marks where your messages actually get through
> • Download the known Meshtastic and MeshCore radios near you from the community maps, and see repeaters on your offline map
>
> APPLE WATCH
> • SOS light, compass and position, the urgent first-aid steps and quick mesh replies on your wrist
>
> PRIVATE BY DESIGN
> No accounts, no analytics, no ads and no servers. Your data stays on your phone. The app only goes online when you ask it to download a book, a map or an AI model.
>
> FREE AND OPEN SOURCE
> Apache 2.0. The full source is at github.com/dougray/pocket-nomad.
>
> Pocket Nomad is not a medical device and doesn't replace professional care or first-aid training. In an emergency, call your local emergency number first if you can.

**What's New** (version 1.0): leave blank for the first release.

## App Privacy (nutrition label)

**Data Not Collected.** The app has no servers and no analytics. Location, the medical ID,
messages and coverage recordings stay on the device (on-device processing is not
"collection" under Apple's definition). Downloads go straight from the Kiwix library and
Hugging Face; the app sends them nothing about the user.

## Age rating answers

- Medical or treatment information: **Frequent/Intense** (first-aid guides, medicines A to Z)
- User-generated content / messaging: **Yes**: peer-to-peer radio messages from people nearby,
  with no server. Users can block a radio, which hides its messages and stops its notifications.
- Unrestricted web access: **No** (web links open in Safari, outside the app)
- Everything else (violence, sexual content, profanity, gambling, etc.): **None**

Apple then sets the age rating. Expect 13+ or higher because of the medical content and messaging.

## Export compliance

`ITSAppUsesNonExemptEncryption = NO`. The app uses only Apple's standard encryption (HTTPS).
Mesh channel encryption is done by the radio's own firmware, not by the app.

## App Review notes

See `store/REVIEW-NOTES.md`; paste it into App Review Information › Notes.
