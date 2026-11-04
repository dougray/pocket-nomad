# Pocket Nomad privacy policy

Effective November 4, 2026.

Pocket Nomad is built to work with no network at all, and it is built so that
nothing about you leaves your phone unless you send it yourself. There are no
accounts, no analytics, no advertising, no tracking and no servers run by the
project. We (the developers) never receive any of your data.

A short version is in the app: Settings › Privacy › How Pocket Nomad handles
your data.

## What stays on your phone

Everything below is stored only on your device, in the app's own storage. It is
included in your iPhone's backups (iCloud or computer) if you have backups
turned on, and it is deleted when you delete the app.

| Data | Why | You control |
|---|---|---|
| **Medical ID** (name, date of birth, blood type, allergies, medications, conditions, emergency contacts) | So you or a responder can read it in an emergency | Edit or clear the fields in Survive › Medical ID. Encrypted at rest by iOS. |
| **Mesh messages and the node list** | Your chat history and the radios you've heard | Settings › Privacy › Delete mesh messages and nodes |
| **Map pins** | Places you saved (water, shelter, rally points) | Swipe to delete in Map › Places |
| **Checklists** | Your ticks | "Uncheck everything" in each checklist |
| **Coverage recordings** (only if you turn recording on) | Where your phone was when your radio heard another node, the signal strength, hops and firmware | Off by default. Mesh › Coverage map: turn off, choose how long to keep it (7–365 days), or delete all. |
| **Drive-test pings** (only in drive mode with the ping test on) | Where your phone was when it sent a ping, and whether a radio answered, repeated it or nothing heard it | Kept and deleted with coverage recordings |
| **Private zones** | Places never to share coverage from | Mesh › Coverage map |
| **Known radios** (only if you download them) | Names, types and reported positions of radios listed on the public community maps near you | Map › Layers › Download known radios › Delete |
| **Books, maps and AI models you add** | Offline reading, maps and answers | Swipe to delete: books in Library, maps and models in Library › Get Content |
| **Book reading data** | Some books (such as the zimgit collections) keep their own document list in the app's built-in browser storage so they open quickly. Only offline book data, nothing about you. | Removed when you delete the app |
| **Settings** | Your choices | Settings |

**Ask** questions and answers are not saved. The AI runs entirely on your
phone, using Apple's on-device model or a model file you downloaded.

## Location

Pocket Nomad asks for location "while using the app". It uses it to:

- show where you are on the offline map and compass;
- add your coordinates to an SOS message, only when you send an SOS with
  location turned on;
- record coverage points, only if you turn the coverage scanner on;
- keep recording while the phone is locked, only while **drive mode** is on.
  iOS shows the location indicator the whole time, and drive mode turns
  itself off after 4 hours;
- choose which known radios to keep, when you download them. The download
  itself does not include your location.

Location is never sent anywhere by the app on its own.

## Bluetooth and mesh radios

Pocket Nomad talks to your own Meshtastic or MeshCore radio over Bluetooth.
What you send through the radio is, by design, transmitted over the air to
other people's radios:

- Messages you type, and SOS messages (with your coordinates if you include
  them), go out to everyone on that channel or to the person you message.
  Channels are encrypted, but the default public channels use a well-known key,
  so anyone nearby with a radio can read them. Use a private channel for
  anything sensitive.
- Your radio itself may broadcast its name and, if you've set it to, its
  position. That is set in the radio's own settings, not by Pocket Nomad.

Other people's messages, names and positions that your radio hears are stored
on your phone as described above.

The drive-mode ping test transmits a small test packet (Meshtastic) or a short
"Pocket Nomad ping" direct message (MeshCore) to the radio you choose, at most
once every 45 seconds. It does not include your location.

Radios whose name contains ⛔, 🛑 or 🚫 (a "don't map me" sign used by
community mesh maps) are never recorded, shared or shown from the downloaded
lists.

## Sharing coverage (opt-in)

Sharing is off by default and cannot be turned on until you add at least one
private zone, such as your home. Even then, nothing is sent automatically: you
export a file and choose where it goes.

Before that file is created, the app removes, on your phone:

- every recording inside a private zone, and every map hexagon that touches one;
- exact positions: recordings and pings are grouped into hexagons about 350 m
  across, keeping only the best signal, counts (including how many pings were
  answered, repeated or unanswered), the firmware and the months covered;
- all node names and IDs, and dates finer than a month.

You can preview exactly what a file would contain on the map (Layers ›
Coverage › What sharing would publish). If you contribute a file to a public
coverage map, it becomes public.

## Apple Watch

The Pocket Nomad watch app has no accounts, analytics or servers either.

- **Location on the watch.** The watch asks for location "while using the
  app" to show your heading and coordinates on its compass screen, and to add
  the watch's position to a mesh SOS, only when you send one. Location stays
  on the watch unless you send that SOS.
- **SOS light and first-aid steps** work on the watch alone. The first-aid
  steps come from the same guides the phone ships, built into the watch app;
  nothing is downloaded.
- **Talking to your iPhone.** The watch and phone talk directly over Apple's
  WatchConnectivity link between your own devices. The phone gives the watch
  your recent mesh messages and whether a radio is connected, so you can read
  them on your wrist; the watch keeps them only in memory.
- **Nothing leaves except what you send.** The watch never sends anything to
  the mesh on its own. A mesh SOS (after you confirm it) or a reply (when you
  tap a quick reply, or confirm dictated text) goes to your iPhone, which
  sends it through your radio exactly like a message typed on the phone, as
  described under Bluetooth and mesh radios above. Dictation uses Apple's
  speech recognition, under Apple's privacy policy.

## Network connections

The app works with no connection. It only connects when you ask it to:

| When | Where | What they see |
|---|---|---|
| Browsing or downloading books (Library › Get Content) | The Kiwix library (library.kiwix.org and its download mirrors) | Your IP address and which files you request |
| Downloading an AI model | Hugging Face (huggingface.co) | Your IP address and which file you request |
| Downloading known radios (Map › Layers) | meshmap.net and map.meshcore.io | Your IP address. The whole public list is downloaded and trimmed to your area on the phone, so your location is not sent. |
| Opening a web link in a guide, book or paper | That website, in Safari | Whatever that site normally collects |

Those services have their own privacy policies. Pocket Nomad sends them no
personal information.

## Children

Pocket Nomad doesn't collect personal information from anyone, including
children.

## Changes

If this policy changes, the new version will be in the app's source code and
on the project's page, with a new effective date.

## Contact

Questions about privacy: open an issue at
https://github.com/dougray/pocket-nomad/issues (the project's support page).
