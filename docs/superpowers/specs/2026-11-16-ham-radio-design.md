# Ham radio (KISS TNC over Bluetooth): design

A user asked for ham radio in Pocket Nomad: connect to a radio's KISS TNC over
Bluetooth and do real packet work: Winlink email, APRS messaging, and BBS / node
connections. The brief: go all out on capabilities.

## Licence model (maintainer's call)
- Settings hold the **callsign** (+ SSID) and **Winlink password**, with a notice
  that sending on amateur bands needs an amateur licence and Winlink needs its own
  account. The app does not verify the licence; the user is responsible.
- **No callsign = listen only.** Enforced in the link layer: nothing is keyed up.
  Listen-only still gets the packet monitor, APRS decoding (positions, messages,
  bulletins, weather, objects) and APRS stations on the Map.
- **Callsign set = everything:** Winlink (inbox/outbox/compose/connect via an RMS
  packet gateway), packet terminal for BBS/nodes (connected-mode AX.25, digipeater
  path), APRS messages with acks/retries, position beacons.
- The Winlink password is stored in the Keychain (iOS) / Android Keystore-encrypted
  prefs and never leaves the phone except as the Winlink secure-login answer.

## Hardware (my call)
- **BLE KISS** on both platforms: the `00000001-ba2a-46c9-ae49-01b0961f68bb`
  service (Mobilinkd TNC3/TNC4, Benshi-family radios: BTech UV-Pro, Vero VR-N76,
  Radioddity GA-5WB, ...). Write = KISS bytes to `...0002`, notify = `...0003`.
- **Classic Bluetooth SPP** (RFCOMM `00001101-...`) on **Android only** (Kenwood
  TH-D74/D75, older Mobilinkd, many hardware TNCs). iOS cannot reach these (MFi).

## Placement (my call)
iPhone and Android phones fit five tabs, so the Mesh tab becomes **Radio** with a
Mesh | Ham switch at the top. The iPad sidebar gets Ham as its own section.

## Approaches considered
1. **Native Swift + native Kotlin, same structure as the Mesh code (chosen).**
   Every other radio protocol in the app is written twice natively with tests;
   no new toolchain.
2. Embed Pat (Go) through gomobile: proven Winlink client, but Pat's packet
   transport relies on Linux AX.25 / AGWPE, not KISS on a phone, and gomobile adds
   a Go toolchain to both builds and CI.
3. Shared Rust/C core: one protocol implementation, but a new language and FFI on
   both platforms for ~2k lines of protocol.

## Protocol stack
- KISS framing (FEND/FESC), TNC parameters (TXDELAY, persistence, slot time).
- AX.25 v2.0 frames (addresses, SSIDs, digipeater path, I/S/U control, PID) and a
  connected-mode link (SABM/UA/DISC/DM, modulo-8 window, RR/RNR/REJ, T1 retries
  N2, T3 keep-alive). Optional accept of incoming connects into the terminal.
- APRS: uncompressed/compressed/timestamped positions, Mic-E, messages + acks,
  bulletins, status, objects, items, weather, third-party unwrap.
- Winlink B2F as a client (non-master): handshake with ;FW / SID / ;PR secure
  login (MD5 + paclink salt), FC proposals, FS answers, SOH/STX/EOT blocks with
  checksums, LZHUF (B2 variant with CRC16). Message format: Mid/Date/Type/From/
  To/Cc/Subject/Mbo/Body headers, CRLF, File attachments.
  Ported from wl2k-go (MIT, LA5NTA) and JNOS lzhuf ("use, distribute and modify
  freely"); credited in NOTICE.
- RMS gateway list: entered by the user (Winlink's gateway API needs a key, which
  breaks the keyless-data rule).

## Non-goals
VARA / ARDOP (closed or needs a sound modem), Winlink over the internet (telnet),
CAT radio control, sound-card AFSK in the phone.
