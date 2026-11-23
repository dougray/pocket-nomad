# SDR with Pocket Nomad: what you need and how it works

*Early beta. The SDR screen is in the Radio tab (Mesh | Ham | SDR). It connects to an SDR box over Wi-Fi. It has been tested against a simulated box only: it has not been tried with a real dongle yet.*

An **SDR** (software-defined radio) is a small USB radio receiver. Pocket Nomad uses it to **listen only**:
it never transmits.

## What you will be able to see
- **Aircraft** near you, on the offline map (ADS-B, 1090 MHz). No internet needed.
- **APRS** ham radio packets, in the Ham tab, even without a TNC radio.
- **NOAA Weather Radio** alerts (the same ones weather radios give), read straight off the air, so they reach you when there is no signal.
- Later: a spectrum view ("what is active near me").

## What you need
| Part | Notes |
|---|---|
| An RTL-SDR USB dongle | About $30-40. The "RTL-SDR Blog V4" is a good one. |
| An antenna | The one in the kit works. A bigger or outdoor antenna hears much farther. |
| Android phone **or** an SDR box | See below. |

### Android
Plug the dongle into the phone with a USB-OTG adapter. Pocket Nomad asks permission to use it.
(*Direct USB is a later phase. Until then, Android uses the box too.*)

### iPhone
iPhones do not let apps talk to a USB radio dongle directly. So the dongle goes into a small **SDR box**:
a Raspberry Pi Zero 2 W (about $15-20), a microSD card and a battery or USB power.
The box does the radio work and sends the results to your phone over Bluetooth.
If you built a WarWalker scanner, the same Pi can do both: there is a combined installer, and a ready-made SD card image (see below).

The box needs no internet and no account.

## Setting up the box (about 15 minutes)
1. Put Raspberry Pi OS Lite on the microSD card and boot the Pi once (the WarWalker guide covers this).
2. Plug the dongle into the Pi with a USB-OTG adapter, attach the antenna.
3. Copy the `sdrbox` folder to the Pi and run `sudo ./install.sh`, then reboot.
4. In Pocket Nomad open **Radio > SDR**. Choose **PocketNomad-SDR-XXXX** from the list.
5. Pick what to listen to: Aircraft, APRS, Weather Radio, Ships, Weather balloons, HF stations (FT8) or Weather satellite pictures (each only if the box has the decoder installed).

### Weather satellite pictures
With a V-dipole antenna outdoors, the box records NOAA weather satellites as they pass over and turns the signal into a picture.
Tap **Tell the box my area** once (your area is rounded to about 7 miles and kept only on the box) so it knows when satellites pass.
Pictures come to the phone over Wi-Fi (join the box's network); over Bluetooth you see the pass times only.
NOAA 15, 18 and 19 are old and may already be switched off.

### Spectrum
Pick **Spectrum (signal strength)** and a band (2 m amateur, aircraft, FM, weather radio, marine, 70 cm): the app draws what the box hears across that band once a second,
with a peak-hold line and a drag readout. It shows where signals are, not what they say.

### JS8 messages (listen only)
With the JS8 decoder installed on the box, the **JS8 messages** list shows heartbeats, CQ calls and directed messages heard on 14.078 MHz (change it in the box settings).
Receive only: sending JS8 needs an amateur licence and an audio link to a radio, which Pocket Nomad does not have. The decoder is not packaged for Raspberry Pi OS and its output
format was not checked against a real install.

### A dongle plugged into your Android phone (spectrum only)
Install the free, open-source *Rtl-sdr driver* app from [F-Droid](https://f-droid.org/en/packages/marto.rtl_tcp_andro/) (GPL, so it stays a separate app), plug the dongle in with a USB-OTG adapter,
start the driver, then tap **Start spectrum**, **Start aircraft**, **Start APRS**, **Start weather radio** or **Start ships (AIS)** in Pocket Nomad. APRS packets go to the Ham tab, weather alerts to the weather radio list and ships to the Ships list (it switches between the two AIS channels every few seconds, so a report can take a little longer to appear). Balloons, HF and satellite pictures still need an SDR box.

### Listening to your radio's audio (no dongle needed)
In the **Ham** tab, **Listen to radio audio** decodes APRS packets and NOAA Weather Radio alerts from a radio's speaker or headphone output, through the phone's headset input.
You need a cable made for it (a 3.5 mm cable with the right plug for your phone, and often a small resistor or attenuator so the radio's loud output does not overload the input).
Turn the radio's volume up until the level bar is about half full. It is receive only: nothing is sent, no sound is recorded or kept, and no licence is needed to listen.
Packets show up in the Ham tab's monitor and station list; weather alerts show with the other weather radio alerts.
Tune the radio to 144.390 MHz (North America) for APRS, or a NOAA weather channel (162.400 to 162.550 MHz) for alerts.
It also decodes **FT8** (the weak-signal chat mode on HF, 14.074 MHz on 20 m for example) when the radio is tuned to an FT8 frequency in USB: a message list appears under the level bar, and CQ calls with a grid square become "Heard on HF" spots. FT8 runs on 15 second slots set by the clock, so the phone's time must be right (a phone on a network is). A VHF/UHF handheld hears almost no FT8; an HF radio or an HF-capable receiver is needed. A radio with a built-in TNC and Bluetooth does not need this: connect it as a TNC instead.
Written and tested against recorded and synthetic audio only; it has not been tried with a real radio yet.

### Several dongles
Plug more dongles into a powered hub. Each one listens to a different thing at the same time (for example aircraft on one, ships on another).
In the app pick **Dongle 1 / 2 / ...** and give each its mode. Give each dongle its own serial first (`rtl_eeprom -s ADSB0001`, one at a time),
or the box numbers them by USB order, which can change when you re-plug.

### One Pi for WarWalker and the SDR box
The `sdrbox` repo has `bringup/combined/` (installer, self-test, guide). There is also a built SD card image: flash it with Raspberry Pi Imager
(set user and Wi-Fi in its customisation screen), boot with internet, and it installs both on first boot. Not yet booted on a real Pi.

## Antennas
Which antenna for which mode, and how to set one up in a car, a pack or at home: see [ANTENNAS.md](ANTENNAS.md).

## Tips
- **Put the antenna by a window or outdoors.** Indoors, aircraft are heard 20-60 km; outdoors, 100+ km.
- **Aircraft only appear when the radio is on.** Ground-level or very distant aircraft will not show.
- **Weather alerts** need a US/Canada NOAA station in range. Pocket Nomad shows which of the 7 channels is strongest.
- **APRS** is on 144.390 MHz in North America. Other regions use other frequencies; the app lets you change it.
- **Battery:** a Pi Zero 2 W with a dongle draws about 1.5-2 W, roughly 6-8 hours from a 5000 mAh pack.
- **Heat:** the dongle gets warm. Keep it ventilated.

## Privacy and the law
- Your location is never sent anywhere, except one thing you choose: **Tell the box my area** (weather satellite pictures) sends your area, rounded to about 7 miles, to your own box. Nothing else leaves the phone.
- The box asks for a pairing token before it says anything, and it only receives: it never transmits.
- Listening to public broadcasts (aircraft, APRS, weather) is legal in most countries. A few restrict radio scanners; check your local rules.
- Pocket Nomad does not decode encrypted or private communications.

## Honest status
Built and tested against a simulated box: the SDR screen over Wi-Fi and Bluetooth, aircraft on the map, weather-radio alerts,
APRS into the Ham tab, ships, weather balloons, FT8/WSPR spots, weather satellite pictures and several dongles.
Not built: aircraft, ships and the other decoders directly from a dongle in the phone, and sending JS8. Not tested with a real dongle, antenna or Pi, and no on-air test yet. If something does not work,
the box's log (`journalctl -u sdrbox`) says what the dongle found.
