#!/usr/bin/env python3
"""Regenerates the FT8 LDPC tables (Kotlin and Swift) from ft8_lib's ft8/constants.c.

ft8_lib: https://github.com/kgoba/ft8_lib (MIT licence, Copyright (c) 2018 Karlis Goba).
Usage: python3 tools/gen-ft8-constants.py /path/to/ft8_lib/ft8/constants.c
The tables were produced once with this logic and are checked in; only rerun it if ft8_lib's code tables change.
"""
import re
import sys

src = open(sys.argv[1]).read()


def arr(name):
    m = re.search(name + r"\s*(?:\[[^\]]*\])+\s*=\s*\{(.*?)\n\};", src, re.S)
    return [int(x) for x in re.findall(r"\b\d+\b", m.group(1))]


nm, mn, nr = arr("kFTX_LDPC_Nm"), arr("kFTX_LDPC_Mn"), arr("kFTX_LDPC_Num_rows")
assert len(nm) == 83 * 7 and len(mn) == 174 * 3 and len(nr) == 83
print("Nm", len(nm), "Mn", len(mn), "Num_rows", len(nr), "- paste into Ft8Constants.kt / Ft8Constants.swift")
