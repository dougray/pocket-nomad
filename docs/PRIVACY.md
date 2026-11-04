# Pocket Nomad privacy policy

Effective November 3, 2026.

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
| **Private zones** | Places never to share coverage from | Mesh › Coverage map |
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
- record coverage points, only if you turn the coverage scanner on.

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

## Sharing coverage (opt-in)

Sharing is off by default and cannot be turned on until you add at least one
private zone, such as your home. Even then, nothing is sent automatically: you
export a file and choose where it goes.

Before that file is created, the app removes, on your phone:

- every recording inside a private zone, and every map hexagon that touches one;
- exact positions: recordings are grouped into hexagons about 350 m across,
  keeping only the best signal, a count, the firmware and the months covered;
- all node names and IDs, and dates finer than a month.

You can preview exactly what a file would contain on the map (Layers ›
Coverage › What sharing would publish). If you contribute a file to a public
coverage map, it becomes public.

## Network connections

The app works with no connection. It only connects when you ask it to:

| When | Where | What they see |
|---|---|---|
| Browsing or downloading books (Library › Get Content) | The Kiwix library (library.kiwix.org and its download mirrors) | Your IP address and which files you request |
| Downloading an AI model | Hugging Face (huggingface.co) | Your IP address and which file you request |
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

Questions about privacy: use the support link on the project's page or its App
Store listing.
