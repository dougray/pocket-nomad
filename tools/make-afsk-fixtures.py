#!/usr/bin/env python3
"""Makes the AFSK 1200 baud APRS audio fixtures used by the iOS and Android decoder tests.

An independent reference modulator (Bell 202: mark 1200 Hz, space 2200 Hz, NRZI, HDLC bit stuffing, CRC-16/X.25),
written without sharing code with the decoders, so the tests check the decoders against something else.
Run from the repo root:  python3 tools/make-afsk-fixtures.py
Writes AppTests/Fixtures/Audio/ and android/ham/src/test/resources/audio/ (16-bit little-endian mono PCM + frames.json).
"""

import json
import math
import os
import random
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTS = [os.path.join(ROOT, "AppTests/Fixtures/Audio"), os.path.join(ROOT, "android/ham/src/test/resources/audio")]


def addr(call, last, ssid=0):
    call = call.upper().ljust(6)[:6]
    b = bytearray(ord(c) << 1 for c in call)
    b.append(0x60 | (ssid << 1) | (1 if last else 0))
    return bytes(b)


def ui_frame(source, dest, path, info):
    s, ssid = (source.split("-") + ["0"])[:2]
    parts = [addr(dest, False), addr(s, not path, int(ssid))]
    for i, p in enumerate(path):
        c, sd = (p.split("-") + ["0"])[:2]
        parts.append(addr(c, i == len(path) - 1, int(sd)))
    return b"".join(parts) + bytes([0x03, 0xF0]) + info


def crc16(data):
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8408 if crc & 1 else crc >> 1
    return crc ^ 0xFFFF


def bits_of(data):
    for b in data:
        for i in range(8):
            yield (b >> i) & 1


def hdlc_bits(frame, lead_flags=30, tail_flags=4):
    flag = [0, 1, 1, 1, 1, 1, 1, 0]
    out = flag * lead_flags
    body = frame + struct.pack("<H", crc16(frame))
    ones = 0
    for bit in bits_of(body):
        out.append(bit)
        if bit:
            ones += 1
            if ones == 5:
                out.append(0)
                ones = 0
        else:
            ones = 0
    return out + flag * tail_flags


def afsk(bits, rate, state):
    """Phase-continuous AFSK for NRZI-coded bits; state = [phase, tone_is_mark] carried between calls."""
    out = []
    spb = rate / 1200.0
    t = 0.0
    nxt = 0.0
    idx = 0
    n = int(len(bits) * spb)
    for i in range(n):
        if i >= nxt:
            bit = bits[idx]; idx += 1; nxt += spb
            if bit == 0:
                state[1] = not state[1]
        f = 1200.0 if state[1] else 2200.0
        state[0] += 2 * math.pi * f / rate
        out.append(math.sin(state[0]))
    return out


def render(frames, rate, amp, noise, space_gain, seed):
    rnd = random.Random(seed)
    state = [0.0, True]
    audio = [0.0] * int(rate * 0.15)
    for fr in frames:
        a = afsk(hdlc_bits(fr), rate, state)
        # a quieter 2200 Hz tone, like a receiver with de-emphasis: scale samples while the space tone is active
        audio += a
        audio += [0.0] * int(rate * 0.2)
    pcm = bytearray()
    for x in audio:
        pcm += struct.pack("<h", int(max(-1, min(1, amp * x + rnd.gauss(0, noise))) * 32767))
    if space_gain != 1.0:
        # re-render with the space tone scaled: do it per sample by detecting the tone from phase steps is awkward,
        # so apply a gentle tilt instead (a first-order high-cut that favours 1200 Hz over 2200 Hz)
        s = struct.unpack("<%dh" % (len(pcm) // 2), bytes(pcm))
        y, prev = [], 0.0
        k = 1 - math.exp(-2 * math.pi * 1500 / rate)
        for v in s:
            prev += k * (v - prev)
            y.append(int(prev * 1.25))
        pcm = bytearray(struct.pack("<%dh" % len(y), *[max(-32768, min(32767, v)) for v in y]))
    return bytes(pcm)


FRAMES = [
    ui_frame("W5SIM-9", "APRS", ["WIDE1-1"], b"!3016.20N/09744.40W>Simulated mobile"),
    ui_frame("K5TEST", "APRS", [], b":N0CALL-9 :Hello from the test{1"),
    ui_frame("N0DEMO-7", "APRS", ["WIDE2-2"], b"=3012.00N/09745.00W-Home station"),
]

SPECS = [
    # name, rate, amplitude, noise sigma, tilt (de-emphasis-like), seed
    ("afsk-48000-clean", 48000, 0.5, 0.0, 1.0, 1),
    ("afsk-44100-noisy-tilted", 44100, 0.4, 0.08, 0.5, 2),
]


# ---- NOAA Weather Radio SAME (EAS) headers: AFSK 520.83 baud, mark 2083.33 Hz, space 1562.5 Hz, no NRZI, ASCII LSB first ----
SAME_HEADERS = [
    "ZCZC-WXR-TOR-048453+0030-3201530-KEWX/NWS-",
    "ZCZC-WXR-SVR-048021-048055+0045-3201545-KAUS/NWS-",
]


def same_bits(text, preamble=16):
    out = []
    for byte in [0xAB] * preamble + list(text.encode("ascii")):
        for i in range(8):
            out.append((byte >> i) & 1)
    return out


def same_audio(messages, rate, amp, noise, seed):
    rnd = random.Random(seed)
    baud = 15625.0 / 30.0
    phase = 0.0
    out = [0.0] * int(rate * 0.3)
    for text in messages:
        for _rep in range(3):
            bits = same_bits(text)
            spb = rate / baud
            n = int(len(bits) * spb)
            for i in range(n):
                bit = bits[min(len(bits) - 1, int(i / spb))]
                phase += 2 * math.pi * (2083.3333 if bit else 1562.5) / rate
                out.append(math.sin(phase))
            out += [0.0] * int(rate * 0.4)
    pcm = bytearray()
    for x in out:
        pcm += struct.pack("<h", int(max(-1, min(1, amp * x + rnd.gauss(0, noise))) * 32767))
    return bytes(pcm)


def main_same():
    meta = []
    for name, rate, amp, noise, seed, header in (("same-48000-clean", 48000, 0.5, 0.0, 11, SAME_HEADERS[0]), ("same-44100-noisy", 44100, 0.4, 0.07, 12, SAME_HEADERS[1])):
        pcm = same_audio([header], rate, amp, noise, seed)
        meta.append({"file": name + ".pcm", "rate": rate, "headers": [header]})
        for out in OUTS:
            open(os.path.join(out, name + ".pcm"), "wb").write(pcm)
    for out in OUTS:
        json.dump(meta, open(os.path.join(out, "same.json"), "w"), indent=1)
    print("wrote", [m["file"] for m in meta])


def main():
    meta = []
    for name, rate, amp, noise, tilt, seed in SPECS:
        pcm = render(FRAMES, rate, amp, noise, tilt, seed)
        meta.append({"file": name + ".pcm", "rate": rate, "frames": [f.hex() for f in FRAMES]})
        for out in OUTS:
            os.makedirs(out, exist_ok=True)
            open(os.path.join(out, name + ".pcm"), "wb").write(pcm)
    for out in OUTS:
        json.dump(meta, open(os.path.join(out, "frames.json"), "w"), indent=1)
    print("wrote", [m["file"] for m in meta])


if __name__ == "__main__":
    main()
    main_same()
