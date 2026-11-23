#!/usr/bin/env python3
"""Makes the AIS ship-report test signal used by the Android decoder test: narrow-FM I/Q samples at 250 kS/s.

An independent reference modulator (pyais builds the messages; this script does HDLC framing, NRZI, Gaussian-filtered
FSK at 9600 baud, +-2.4 kHz deviation, then FM), written without sharing code with the decoder.
Needs pyais:  pip install pyais     Run from the repo root:  python3 tools/make-ais-fixture.py
Writes android/app/src/test/resources/ais/ais-250k.iq8 (unsigned 8-bit interleaved I/Q) and ais.json (the expected fields).
"""

import json
import math
import os
import random
import struct

from pyais.encode import encode_dict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "android/app/src/test/resources/ais")
FS = 250_000
BAUD = 9600


def sixbit_bits(payload, fill):
    bits = []
    for ch in payload:
        v = ord(ch) - 48
        if v > 40:
            v -= 8
        bits += [(v >> (5 - i)) & 1 for i in range(6)]
    return bits[:len(bits) - fill] if fill else bits


def crc16(data):
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8408 if crc & 1 else crc >> 1
    return crc ^ 0xFFFF


def frame_bits(msg_bits):
    assert len(msg_bits) % 8 == 0
    data = bytes(sum(msg_bits[i * 8 + j] << j for j in range(8)) for i in range(len(msg_bits) // 8))   # LSB-first packing
    fcs = struct.pack("<H", crc16(data))
    body = list(msg_bits) + [(fcs[i // 8] >> (i % 8)) & 1 for i in range(16)]
    stuffed, ones = [], 0
    for b in body:
        stuffed.append(b)
        ones = ones + 1 if b else 0
        if ones == 5:
            stuffed.append(0); ones = 0
    flag = [0, 1, 1, 1, 1, 1, 1, 0]
    training = [0, 1] * 12
    return training + flag + stuffed + flag + [0] * 8


def nrzi(bits):
    out, level = [], 1
    for b in bits:
        if b == 0:
            level ^= 1
        out.append(level)
    return out


def phi(x):
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def gmsk_iq(symbols, amp, noise, offset_hz, rnd):
    T = FS / BAUD
    sigma = math.sqrt(math.log(2)) / (2 * math.pi * 0.4) * T
    n = int((len(symbols) + 4) * T)
    out = bytearray()
    ph = 0.0
    for i in range(n):
        k0 = int(i / T)
        f = 0.0
        for k in range(k0 - 3, k0 + 4):
            if 0 <= k < len(symbols):
                a = 1.0 if symbols[k] else -1.0
                f += a * (phi((i - k * T + T / 2) / sigma) - phi((i - k * T - T / 2) / sigma))   # a rectangular bit, Gaussian-smoothed
        ph += 2 * math.pi * (2400.0 * f + offset_hz) / FS
        out.append(max(0, min(255, int(127.5 + amp * math.cos(ph) + rnd.gauss(0, noise)))))
        out.append(max(0, min(255, int(127.5 + amp * math.sin(ph) + rnd.gauss(0, noise)))))
    return bytes(out)


MESSAGES = [
    {"type": 1, "mmsi": 366999000, "lat": 30.2672, "lon": -97.7431, "speed": 12.3, "course": 90.0, "heading": 91, "accuracy": 1, "status": 0,
     "turn": 0, "maneuver": 0, "second": 10, "raim": False, "radio": 0},
    {"type": 5, "mmsi": 338087471, "shipname": "SIM FERRY", "callsign": "WDC1234", "shiptype": 60, "ais_version": 0, "imo": 0, "to_bow": 10,
     "to_stern": 20, "to_port": 3, "to_starboard": 3, "epfd": 1, "month": 11, "day": 23, "hour": 12, "minute": 0, "draught": 3.0,
     "destination": "GALVESTON", "dte": 0},
    {"type": 18, "mmsi": 367123456, "lat": 29.3013, "lon": -94.7977, "speed": 5.5, "course": 270.0, "heading": 270, "second": 20, "accuracy": 0,
     "radio": 0, "cs": 1, "display": 0, "dsc": 0, "band": 0, "msg22": 0, "assigned": 0, "raim": False},
]


def main():
    make("ais-250k", noise=1.5, offset=0.0, seed=21)                 # a clean signal
    make("ais-mistuned-noisy", noise=9.0, offset=1500.0, seed=22)    # receiver 1.5 kHz off tune, much more noise


def make(name, noise, offset, seed):
    rnd = random.Random(seed)
    audio = bytearray()
    silence = lambda s: bytes([127, 128]) * int(FS * s)
    audio += silence(0.05)
    expected = []
    for spec in MESSAGES:
        sentences = encode_dict(spec, talker_id="AI")
        parts = [s.split(",") for s in sentences]
        payload = "".join(p[5] for p in parts)
        fill = int(parts[-1][6].split("*")[0])
        bits = sixbit_bits(payload, fill)
        audio += gmsk_iq(nrzi(frame_bits(bits)), 90, noise, offset, rnd)
        audio += silence(0.04)
        expected.append({k: spec[k] for k in ("type", "mmsi") + (("lat", "lon", "speed", "course") if spec["type"] != 5 else ("shipname", "shiptype"))})
    os.makedirs(OUT, exist_ok=True)
    open(os.path.join(OUT, name + ".iq8"), "wb").write(bytes(audio))
    json.dump(expected, open(os.path.join(OUT, "ais.json"), "w"), indent=1)
    print("wrote", name, len(audio), "bytes;", [e["mmsi"] for e in expected])


if __name__ == "__main__":
    main()
