# Antennas for Pocket Nomad radios

*Plain-language guide for the SDR box, a dongle in your phone, and your own radio. The numbers are standard antenna maths; none of it has been tested here with real hardware, so treat the setups as starting points and use the Spectrum screen to check them (see "Testing an antenna").*

The antenna matters more than the dongle. A $10 antenna in the right place beats a $200 radio indoors.

## The three rules

1. **Match the antenna to the band.** An antenna is cut for one frequency. A 2 m antenna hears 2 m well, other bands weakly or not at all. Dual-band antennas cover two bands, usually 144 and 430 MHz.
2. **Height and clear sky beat gain.** Outdoors and high is the biggest win. Indoors, near a window, is the second best.
3. **Short, good coax.** Every metre of thin cable (RG-174) loses signal above 100 MHz, and 1090 MHz loses the most. Keep the cable short, use RG-58 or LMR-195 or better, and put the dongle or an amplifier near the antenna if you can.

## Which antenna for which mode

| Mode (screen) | Frequency | Best antenna | Quarter-wave length |
|---|---|---|---|
| Aircraft (ADS-B) | 1090 MHz | Small vertical on a ground plane, or a 1090 collinear. Needs its own antenna. | 6.5 cm |
| APRS | 144.390 MHz (North America) | 2 m whip, or a dual-band 144/430 whip | 49 cm |
| Weather radio alerts | 162.400 to 162.550 MHz | 2 m or marine VHF whip (works on a 2 m antenna, a little weaker) | 44 cm |
| Ships (AIS) | 161.975 and 162.025 MHz | Marine VHF whip, as high as you can | 44 cm |
| Weather satellite pictures | 137.100, 137.620 and 137.9125 MHz | V-dipole (two 52 to 53 cm elements at about 120 degrees), horizontal, outdoors, clear sky | 52 cm each |
| Weather balloons (sondes) | 400 to 406 MHz | 70 cm whip or dual-band 144/430 whip | 18 cm |
| HF stations (FT8, WSPR, JS8) | 1.8 to 30 MHz | Long wire or a half-wave dipole (metres long); needs an HF-capable dongle such as the RTL-SDR Blog V4 | 142.65 / MHz metres per half-wave |
| Spectrum view | whatever you pick | A wide-band antenna (a discone covers about 25 to 1300 MHz) is the easiest way to see everything | n/a |

Quarter-wave length in cm is roughly 7130 / MHz; half-wave is roughly 14260 / MHz. Cut a bit long, then trim for the best signal.

## About a dual-band gooseneck antenna (for example the Dual Comm YCA)

We could not find a published spec sheet for the Dual Comm YCA, so check its bands on the box or the maker's page. The common dual-band pairing is 144 and 430 MHz. If that is what it covers:

- **Good for:** APRS (144.390), the 2 m and 70 cm spectrum presets, and weather balloons (400 to 406 MHz, near the edge of the 70 cm band).
- **Usable but weaker for:** weather radio and AIS at about 162 MHz (just above the 2 m band).
- **Not for:** weather satellites (137 MHz needs the V-dipole) or aircraft (1090 MHz needs its own antenna).

A compact omnidirectional gooseneck is a good everyday antenna for a pack or a vehicle dash. It is not a substitute for the V-dipole or the 1090 antenna.

## Setups

### In a car
- **Best:** a magnetic or NMO-mount whip in the middle of the roof. The roof is the ground plane that makes a quarter-wave whip work. Never mount a whip on glass or plastic with no metal under it.
- A 144/430 whip covers APRS, the 2 m and 70 cm views and weather balloons. Add a small 1090 MHz antenna on the roof for aircraft only if you want them.
- Run the coax through a door seal (the flat ribbon types exist for this), keep it away from power wires, and avoid coiling the extra.
- Magnetic mounts scratch paint: put a thin plastic wrap under the magnet.
- A dongle and a Pi or phone in the car pulls about 2 W. Use the car's USB power or a battery pack.

### In a pack
- **Best:** a flexible whip or a roll-up 2 m J-pole on the pack's top strap, held vertical. A dual-band gooseneck on a shoulder strap works well for APRS and the 2 m view while you walk.
- Keep the dongle and Pi in the pack's top pocket with the antenna out, and the cable short.
- The antenna sits against your body when you move. Body in the way costs signal; clip it as high as you can on the strap, away from your back.
- For a weather satellite pass, stop, take the V-dipole out, lay it flat on open ground or a tripod, point the legs north to south, and sit still for the 10 to 15 minutes of the pass. A pack cannot do this while moving.
- Fold-up antennas bend and break. Carry a spare adapter (SMA to BNC) and a short extension cable.

### At home
- **Outdoors, as high as safely possible**, with a clear view. A fence post, a deck rail, a chimney mount, or a mast. Keep well away from power lines.
- **Separate antennas for separate jobs** is the best set-up:
  - One discone or 2 m antenna for APRS, spectrum, weather radio and ships.
  - One 1090 MHz antenna for aircraft, with a short cable (or a small amplifier and filter close to the antenna).
  - One V-dipole outdoors for weather satellites.
  - A long wire or a dipole for HF if you have an HF-capable dongle.
- One dongle listens to one antenna at a time, so a second dongle (see "Several dongles" in the SDR guide) lets two antennas work at once.
- Splitters lose half the signal each time and add cost; only use one if you must share an antenna.
- Add a lightning arrestor and ground the mast on any permanent outdoor antenna.

## Connecting things safely

- **Never share an antenna between a transmitting radio and a dongle.** A transmitter can burn out a dongle in one second. The SDR box and Pocket Nomad only listen. If you ever transmit with your own radio (you need a licence for that), use a separate antenna for the dongle, or a proper antenna switch that disconnects the dongle while you transmit.
- **Keep a dongle away from your own transmit antenna** even when you use different antennas: a few watts nearby can overload it.
- **FM broadcast and pager towers** can swamp a dongle and show ghost signals everywhere. A broadcast-FM notch filter, or just a different antenna spot, usually fixes it.
- **Do not use a bias tee on a plain antenna.** Some dongles can feed power up the cable to an amplifier; turning it on with an antenna that is a direct short can damage it.
- **Connectors:** RTL-SDR Blog dongles use SMA female; many antennas use BNC or SO-239. Keep a few adapters, and use them at the antenna end, not in the middle.

## Testing an antenna

You can compare two antennas without any test equipment, using Pocket Nomad's Spectrum screen:

1. Choose a band your antenna should cover (for example 2 m amateur or weather radio).
2. Let the peak-hold line run for a minute or two.
3. Swap antennas, or move to a different spot, and watch the peak-hold line again.
4. The antenna or spot with higher peaks and a lower noise floor wins. Signals that appear only on one are a good sign.

The values are relative, not calibrated, so only compare antennas on the same dongle, the same gain setting and the same band.

## If you hear nothing

- Antenna is on the right connector and screwed on tight.
- A different dongle gain: automatic is the default; try a lower gain if the whole spectrum shows noise.
- Check from outdoors, away from computers, chargers and LED lights, which add noise at 100 to 150 MHz.
- Try the Spectrum screen first: if you see peaks, the hardware works and the problem is the decoder; if the line is flat everywhere, check the antenna, the dongle and the cable.

## In the app
The same advice is in the app under Survive > Radio and Comms: **Antennas for Your Radios** and **Solar Mesh Node** (a portable solar mesh node in a weatherproof box, including how to reuse a solar light's own battery and panel).
