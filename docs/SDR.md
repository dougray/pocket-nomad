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
If you built a WarWalker scanner, the same Pi works. Run one or the other.

The box needs no internet and no account.

## Setting up the box (about 15 minutes)
1. Put Raspberry Pi OS Lite on the microSD card and boot the Pi once (the WarWalker guide covers this).
2. Plug the dongle into the Pi with a USB-OTG adapter, attach the antenna.
3. Copy the `sdrbox` folder to the Pi and run `sudo ./install.sh`, then reboot.
4. In Pocket Nomad open **Radio > SDR**. Choose **PocketNomad-SDR-XXXX** from the list.
5. Pick what to listen to: Aircraft, APRS or Weather Radio.

## Tips
- **Put the antenna by a window or outdoors.** Indoors, aircraft are heard 20-60 km; outdoors, 100+ km.
- **Aircraft only appear when the radio is on.** Ground-level or very distant aircraft will not show.
- **Weather alerts** need a US/Canada NOAA station in range. Pocket Nomad shows which of the 7 channels is strongest.
- **APRS** is on 144.390 MHz in North America. Other regions use other frequencies; the app lets you change it.
- **Battery:** a Pi Zero 2 W with a dongle draws about 1.5-2 W, roughly 6-8 hours from a 5000 mAh pack.
- **Heat:** the dongle gets warm. Keep it ventilated.

## Privacy and the law
- Your location is never sent to the box or anywhere else. What the box hears stays on the box and your phone.
- Listening to public broadcasts (aircraft, APRS, weather) is legal in most countries. A few restrict radio scanners; check your local rules.
- Pocket Nomad does not decode encrypted or private communications.

## Honest status
Built and tested against a simulated box: the SDR screen, aircraft on the map, weather-radio alerts in Conditions,
and APRS packets flowing into the Ham tab. Not built yet: the Bluetooth link (the box is reached over Wi-Fi for now),
Android direct USB, and the spectrum view. Not tested with a real dongle, antenna or Pi. If something does not work,
the box's log (`journalctl -u sdrbox`) says what the dongle found.
