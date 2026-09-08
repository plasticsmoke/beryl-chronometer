#!/usr/bin/env python3
"""
Convert the iOS Willmann-Bell planet tables (esastro/.../ESWBPlanetsTable.h) into a binary
resource for the Kotlin engine, cross-checking every value against the web's planet-tables.ts.

We port from the iOS canonical table; the web is used ONLY to verify (it has diverged before).
Skips Uranus/Neptune (Miami/Firenze/Terra only need Sun..Saturn).

Output: engine/src/main/resources/planet-tables.bin  (big-endian float64, arrays concatenated
in the order listed in ARRAYS; the Kotlin loader knows the counts).
"""
import os, re, struct, sys

# Paths: the upstream EmeraldSequoia checkouts (esastro, chronometer-web) are expected as
# siblings of this repo; override with EMERALD_SRC=<dir containing them>.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.environ.get("EMERALD_SRC", os.path.dirname(ROOT))
IOS = os.path.join(SRC, "esastro/Willmann-Bell/Planets/ESWBPlanetsTable.h")
WEB = os.path.join(SRC, "chronometer-web/src/astronomy/planet-tables.ts")
OUT = os.path.join(ROOT, "engine/src/main/resources/planet-tables.bin")

# name -> (num_entries, doubles_per_entry).  Order here == order in the .bin file.
ARRAYS = [
    ("sunData", 50, 4),
    ("mercuryLongitudeData", 25, 3), ("mercuryLatitudeData", 18, 3), ("mercuryRadiusData", 14, 3),
    ("venusLongitudeData", 20, 3),   ("venusLatitudeData", 6, 3),    ("venusRadiusData", 5, 3),
    ("marsLongitudeData", 60, 3),    ("marsLatitudeData", 7, 3),     ("marsRadiusData", 29, 3),
    ("jupiterData", 1360, 21), ("jupiterJDRange", 1360, 2),
    ("saturnData", 1360, 21),  ("saturnJDRange", 1360, 2),
]

NUM = re.compile(r'[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?')

def extract_block(text, name, open_brace, close_seq):
    """Return the flat list of floats inside the first `name ... open_brace ... close_seq`."""
    i = text.index(name)
    j = text.index(open_brace, i)
    # find matching close: for C++ '{...};' and TS '[...];' we scan to the first '};' / '];'
    k = text.index(close_seq, j)
    body = text[j+1:k]
    return [float(x) for x in NUM.findall(body)]

def main():
    ios = open(IOS).read()
    web = open(WEB).read()
    out = bytearray()
    total = 0
    for name, n, per in ARRAYS:
        want = n * per
        ios_vals = extract_block(ios, "%s[" % name if name != "sunData" else "sunData[", "{", "};")
        # web arrays are `export const NAME ... = [ ... ];`
        web_vals = extract_block(web, "export const %s" % name, "[", "];")
        if len(ios_vals) != want:
            print("IOS %s: got %d floats, want %d" % (name, len(ios_vals), want)); sys.exit(1)
        if len(web_vals) != want:
            print("WEB %s: got %d floats, want %d" % (name, len(web_vals), want)); sys.exit(1)
        # cross-check iOS vs web
        mism = 0
        for a, b in zip(ios_vals, web_vals):
            if abs(a - b) > 1e-6 * max(1.0, abs(a)):
                mism += 1
                if mism <= 3:
                    print("  DIVERGE %s: ios=%r web=%r" % (name, a, b))
        print("%-22s n=%-5d per=%-2d floats=%-6d iOS-vs-web mismatches=%d" % (name, n, per, want, mism))
        for v in ios_vals:
            out += struct.pack(">d", v)
        total += want
    open(OUT, "wb").write(out)
    print("\nWrote %s : %d doubles, %d bytes" % (OUT, total, len(out)))

if __name__ == "__main__":
    main()
